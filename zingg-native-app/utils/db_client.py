"""
utils/db_client.py

Shared Databricks SDK client and config helpers for all app pages.
"""

import io
import os
import posixpath
import streamlit as st
from databricks.sdk import WorkspaceClient
from databricks.sdk.core import Config


def get_workspace_client() -> WorkspaceClient:
    """Returns a WorkspaceClient authenticated as the APP'S SERVICE PRINCIPAL
    (app authorization) -- required for cluster management, library
    installation, and writing notebooks into the Workspace"""
    return WorkspaceClient()


def get_current_user_email() -> str:
    """Returns the current user's email from the forwarded header, for
    display purposes."""
    return st.context.headers.get("X-Forwarded-Email", "")


_LEVELS = {"CAN_ATTACH_TO": 1, "CAN_RESTART": 2, "CAN_MANAGE": 3}


def get_current_user(w: WorkspaceClient) -> tuple[str, set]:
    """Returns the signed-in user's Databricks username and group names.
    Calls the Current User API with the user's own token (user authorization),
    so Databricks itself says who the user is. Returns ("", set()) when there
    is no user token, for example in a local run."""
    token = st.context.headers.get("X-Forwarded-Access-Token", "")
    if not token:
        return "", set()
    user_w = WorkspaceClient(host=w.config.host, token=token, auth_type="pat")
    me = user_w.current_user.me(attributes="userName,groups")
    groups = {g.display for g in (me.groups or []) if g.display}
    groups.add("users")  # every workspace user is in the built-in "users" group
    return me.user_name or "", groups


def _user_level(w: WorkspaceClient, cluster_id: str, user_name: str, groups: set) -> int:
    """Returns the user's highest permission level on the cluster, from the
    cluster's permission list: given to the user directly or to one of the
    user's groups. Returns 0 if there is none or the list cannot be read."""
    if not user_name:
        return 0
    try:
        acl = w.clusters.get_permissions(cluster_id).access_control_list or []
    except Exception:
        return 0
    best = 0
    for entry in acl:
        if (entry.user_name or "").lower() == user_name.lower() or entry.group_name in groups:
            for p in entry.all_permissions or []:
                level = p.permission_level.value if p.permission_level else ""
                best = max(best, _LEVELS.get(level, 0))
    return best


def assert_user_can(w: WorkspaceClient, cluster_id: str, user_name: str, groups: set,
                    needed: str) -> None:
    if _user_level(w, cluster_id, user_name, groups) < _LEVELS[needed]:
        raise PermissionError(f"Not allowed on cluster {cluster_id}")


def list_clusters(w: WorkspaceClient, user_name: str, groups: set):
    """Returns a list of (cluster_id, cluster_name, state) for clusters the
    current user can see, for the cluster-picker dropdown on the Get Started page."""
    clusters = []
    for c in w.clusters.list():
        if _user_level(w, c.cluster_id, user_name, groups) < _LEVELS["CAN_ATTACH_TO"]:
            continue
        clusters.append({
            "cluster_id": c.cluster_id,
            "name": c.cluster_name,
            "state": str(c.state) if c.state else "UNKNOWN",
            "spark_version": c.spark_version,
            "access_mode": str(getattr(c, "data_security_mode", "UNKNOWN")),
        })
    return clusters


def check_jar_installed(w: WorkspaceClient, cluster_id: str, jar_volume_path: str) -> bool:
    """Checks whether the Zingg OSS JAR is already installed on the given
    cluster. Returns True/False."""
    for lib_status in w.libraries.cluster_status(cluster_id):
        lib = lib_status.library
        if lib and lib.jar == jar_volume_path:
            return True
    return False


def install_jar(w: WorkspaceClient, cluster_id: str, jar_volume_path: str,
                user_name: str, groups: set, bundled_jar_local_path: str = None) -> None:
    """Installs the Zingg OSS JAR on the given cluster via the Libraries API.
    """
    from databricks.sdk.service.compute import Library

    assert_user_can(w, cluster_id, user_name, groups, "CAN_MANAGE")

    if bundled_jar_local_path:
        with open(bundled_jar_local_path, "rb") as f:
            content = f.read()
        w.files.upload(jar_volume_path, io.BytesIO(content), overwrite=True)

    w.libraries.install(cluster_id=cluster_id, libraries=[Library(jar=jar_volume_path)])


def restart_cluster(w: WorkspaceClient, cluster_id: str, user_name: str, groups: set) -> str:
    """Starts or restarts the given cluster, whichever is correct for its
    current state. Uses the SDK's own ensure_cluster_is_running() helper,
    which handles the PENDING/RUNNING/TERMINATED state distinction internally
    """
    assert_user_can(w, cluster_id, user_name, groups, "CAN_RESTART")
    w.clusters.ensure_cluster_is_running(cluster_id)
    return "started (or confirmed already running)"


def restart_running_cluster(w: WorkspaceClient, cluster_id: str, user_name: str,
                            groups: set) -> str:
    """Explicitly restarts a cluster that is currently running (e.g. to pick
    up a newly installed library). Only call this when the cluster is known
    to be running -- restart() is invalid on a terminated cluster."""
    assert_user_can(w, cluster_id, user_name, groups, "CAN_RESTART")
    w.clusters.restart(cluster_id)
    return "restarted"


def deploy_notebook_template(w: WorkspaceClient, template_local_path: str,
                             target_folder: str, target_filename: str,
                             user_name: str) -> tuple[str, bool]:
    """Deploys one bundled template notebook (a plain .ipynb file, tracked
    in this app's own Git repo alongside app.yaml, the JAR, etc.) to
    target_folder/target_filename in the Workspace.

    Never overwrites an existing file. If that exact path already exists,
    does nothing and returns (path, False). Returns (path, True) if newly
    written.

    template_local_path: a LOCAL filesystem path (the running app's own
    checked-out files, same as how install_jar reads the bundled JAR).
    """
    from databricks.sdk.service.workspace import ImportFormat

    target_folder = posixpath.normpath(target_folder)

    if not target_folder.startswith("/Workspace/"):
        raise ValueError(
            f"target_folder must be a Workspace path starting with /Workspace/ "
            f"(got: {target_folder}). Notebooks cannot be stored under /Volumes/."
        )

    home = f"/Workspace/Users/{user_name}".lower()
    if not user_name or not (target_folder.lower() + "/").startswith(home + "/"):
        raise PermissionError(f"Not allowed on folder {target_folder}")

    target_path = f"{target_folder.rstrip('/')}/{target_filename}"

    try:
        w.workspace.get_status(target_path)
        return target_path, False  # already exists, untouched
    except Exception:
        pass  # doesn't exist yet -- safe to write

    w.workspace.mkdirs(target_folder)

    with open(template_local_path, "rb") as f:
        content = f.read()

    try:
        w.workspace.upload(target_path, content, format=ImportFormat.JUPYTER, overwrite=False)
    except Exception as e:
        if "does not exist" in str(e):
            raise RuntimeError(
                f"Could not write to {target_folder}. This folder exists, "
                f"but the app's service principal likely lacks write "
                f"permission on it. Grant the app's service principal "
                f"'Can Manage' or 'Can Edit' permission on this Workspace "
                f"folder, then try again. Original error: {e}"
            ) from e
        raise

    return target_path, True


def deploy_notebook_set(w: WorkspaceClient, app_local_dir: str,
                        notebooks: list, target_folder: str, user_name: str) -> list:
    """Deploys a linked SET of modular notebooks into the SAME target_folder,
    preserving each one's original filename exactly.

    app_local_dir: the app's own local root directory (e.g.
    os.path.dirname(os.path.dirname(__file__)) from the calling page).
    """
    results = []
    for nb in notebooks:
        local_path = os.path.join(app_local_dir, nb["relative_path"])
        try:
            path, was_new = deploy_notebook_template(w, local_path, target_folder, nb["filename"], user_name)
            results.append({"display_name": nb["display_name"], "path": path, "was_new": was_new, "error": None})
        except Exception as e:
            results.append({"display_name": nb["display_name"], "path": None, "was_new": False, "error": str(e)})
    return results