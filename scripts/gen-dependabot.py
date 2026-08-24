#!/usr/bin/env python3
"""Generate .github/dependabot.yml from the project's poms.

Zingg compiles against Spark/Scala/Jackson-core which are `provided` at runtime
(the cluster supplies them) and therefore not shipped in our jar. Dependabot
version-bump PRs for those are pure noise. Rather than hand-maintaining the
config, we derive it from the single source of truth — the dependency scopes
declared across the poms:

  * allow  (include) <- `compile`-scope deps we actually ship  -> watch these
  * ignore (exclude) <- `provided`-scope deps the runtime supplies -> silence

Usage:
  python3 scripts/gen-dependabot.py            # rewrite .github/dependabot.yml
  python3 scripts/gen-dependabot.py --check     # exit 1 if the file is stale

--------------------------------------------------------------------------
HOW THIS FILE IS ORGANIZED (read top to bottom, it tells a story):

  STEP 1 — Imports & configuration     : the tools we use, and constants
  STEP 2 — Small helpers               : tiny reusable pieces of logic
  STEP 3 — Scan the poms               : read every pom.xml, sort deps
  STEP 4 — Render the YAML             : turn the sorted deps into text
  STEP 5 — main(): tie it all together : the actual program entry point
--------------------------------------------------------------------------
"""

# =============================================================================
# STEP 1 — Imports & configuration
# =============================================================================

# glob: lets us search the filesystem for files matching a pattern (here, "**/pom.xml")
import glob
# re: Python's regular expression module, used to find/replace text patterns
import re
# sys: gives us access to command-line arguments and the ability to exit with a status code
import sys
# ElementTree: a built-in library for reading and parsing XML files (pom.xml is XML)
import xml.etree.ElementTree as ET

# Maven pom.xml files declare a "namespace" — this tells ElementTree how to find
# tags like <dependency> even though the file's real tag name is prefixed internally.
NS = {"m": "http://maven.apache.org/POM/4.0.0"}

# Path to the file this script writes/checks.
DEPENDABOT_YML = ".github/dependabot.yml"


# =============================================================================
# STEP 2 — Small helpers
# =============================================================================
# These two functions don't do anything by themselves — they're small building
# blocks used later by collect_by_scope() in STEP 3.

def _text(el):
    """Safely get the text of an XML element, or None if it's missing/empty."""
    # Small helper: given an XML element, safely return its text content
    # (trimmed of whitespace), or None if the element doesn't exist or is empty.
    # This avoids repeating "if el is not None" checks everywhere else.
    return el.text.strip() if el is not None and el.text else None


def _pattern(group, artifact):
    """`group:artifact`, with any Maven property in the artifactId (e.g.
    `${scala.binary.version}`) turned into a `*` wildcard so the pattern matches
    whatever the active build profile resolves it to."""
    # Maven poms sometimes use placeholders like ${scala.binary.version} instead
    # of a literal version number. Replace anything inside ${...} with a "*"
    # wildcard, since Dependabot patterns support wildcards but not variables.
    artifact = re.sub(r"\$\{[^}]+\}", "*", artifact)
    # Dependabot dependency-name patterns look like "groupId:artifactId".
    return f"{group}:{artifact}"


# =============================================================================
# STEP 3 — Scan the poms
# =============================================================================

def collect_by_scope():
    """Scan every pom and bucket external (non-zingg) deps by Maven scope.
    Returns (provided, shipped) as sorted pattern lists, where:
      provided = scope 'provided' — supplied by the runtime, not in our jar
      shipped  = scope 'compile'  — actually packaged and shipped
    A dependency that appears as `provided` in ANY module is treated as provided
    (it drops out of `shipped`), because Dependabot's `ignore` wins over `allow`,
    so listing it in both would just get it ignored anyway."""

    # --- 3a. Set up empty buckets -------------------------------------------
    # Use sets (not lists) so duplicate dependencies across multiple poms are
    # automatically collapsed into one entry.
    provided, compile_ = set(), set()

    # --- 3b. Find every pom.xml in the project ------------------------------
    for pom in glob.glob("**/pom.xml", recursive=True):
        # Skip poms inside build output folders (e.g. target/classes/...),
        # we only care about the real source poms.
        if "/target/" in pom:
            continue
        try:
            # Parse the XML file into a tree we can search through.
            root = ET.parse(pom).getroot()
        except ET.ParseError as ex:
            # If a pom is malformed, warn but keep going instead of crashing.
            print(f"WARN: could not parse {pom}: {ex}", file=sys.stderr)
            continue

        # --- 3c. Look at every <dependency> tag in this pom -----------------
        for dep in root.iter("{http://maven.apache.org/POM/4.0.0}dependency"):
            group = _text(dep.find("m:groupId", NS))
            artifact = _text(dep.find("m:artifactId", NS))
            # Skip incomplete entries, and skip Zingg's own internal modules
            # (we only want *external* third-party dependencies).
            if not group or not artifact or group.startswith("zingg"):
                continue
            # If no <scope> tag is present, Maven's default scope is "compile".
            scope = _text(dep.find("m:scope", NS)) or "compile"
            pat = _pattern(group, artifact)
            if scope == "provided":
                provided.add(pat)
            elif scope == "compile":
                compile_.add(pat)

    # --- 3d. Resolve conflicts: "provided" always wins ----------------------
    # If a dependency is "provided" in even one module, treat it as provided
    # everywhere, removing it from the "shipped" set. This mirrors how
    # Dependabot itself behaves: its ignore list wins over its allow list.
    shipped = compile_ - provided  # provided-anywhere wins

    # --- 3e. Return sorted, stable results -----------------------------------
    # Return both lists sorted, so the generated YAML file has a stable,
    # predictable order (important so re-running the script doesn't create
    # noisy diffs when nothing actually changed).
    return sorted(provided), sorted(shipped)


# =============================================================================
# STEP 4 — Render the YAML
# =============================================================================

def render(provided, shipped):
    """Turn the (provided, shipped) dependency lists into dependabot.yml text."""

    # --- 4a. Fixed header + boilerplate config -------------------------------
    # Build the dependabot.yml file line by line as a list of strings, then
    # join them together at the end. This is a common Python pattern for
    # generating text files.
    lines = [
        "# ---------------------------------------------------------------------------",
        "# AUTO-GENERATED by scripts/gen-dependabot.py from the poms' dependency scopes.",
        "# Do not edit by hand — run the script and commit the result.",
        "#   allow  (include) = `compile`-scope deps we actually ship  -> watch these",
        "#   ignore (exclude) = `provided`-scope deps supplied by the runtime -> noise",
        "# A dep that is `provided` in any module is treated as provided (ignore wins).",
        "# ---------------------------------------------------------------------------",
        "version: 2",
        "updates:",
        '  - package-ecosystem: "maven"',
        "    directories:",
        '      - "/**" # root + all Maven submodules',
        "    schedule:",
        '      interval: "weekly"',
        "    open-pull-requests-limit: 10",
        "    allow:",
    ]

    # --- 4b. One "allow" line per shipped dependency -------------------------
    # These are the deps we actually ship, so Dependabot should watch them.
    for p in shipped:
        lines.append(f'      - dependency-name: "{p}"')

    # --- 4c. One "ignore" line per provided dependency -----------------------
    # These are supplied by the runtime, so version-bump PRs for them are noise.
    lines.append("    ignore:")
    for p in provided:
        lines.append(f'      - dependency-name: "{p}"')

    # --- 4d. Combine everything into one final string ------------------------
    # Join every line with a newline, plus one trailing newline at the end
    # of the file (standard convention for text files).
    return "\n".join(lines) + "\n"


# =============================================================================
# STEP 5 — main(): tie it all together
# =============================================================================

def main():
    # --- 5a. Figure out which mode we're running in --------------------------
    # If the script was run as "gen-dependabot.py --check", we only verify
    # the file is up to date instead of overwriting it.
    check = "--check" in sys.argv

    # --- 5b. Do the real work: scan poms, then build the YAML text -----------
    provided, shipped = collect_by_scope()
    content = render(provided, shipped)

    # --- 5c. Read whatever is currently on disk (for comparison) -------------
    # Try to read whatever is currently on disk, so we can compare it to the
    # freshly generated content. If the file doesn't exist yet, treat it as
    # "nothing to compare" (current = None) instead of crashing.
    try:
        with open(DEPENDABOT_YML) as fh:
            current = fh.read()
    except FileNotFoundError:
        current = None

    # --- 5d. --check mode: report only, never write --------------------------
    if check:
        # --check mode: don't write anything. Just report whether the
        # committed file matches what the script would generate right now.
        if current != content:
            print(f"{DEPENDABOT_YML} is stale. Run: python3 scripts/gen-dependabot.py")
            sys.exit(1)  # non-zero exit code = failure, useful for CI
        print(f"{DEPENDABOT_YML} is up to date.")
        return

    # --- 5e. Normal mode: actually write the file -----------------------------
    with open(DEPENDABOT_YML, "w") as fh:
        fh.write(content)
    print(f"Wrote {DEPENDABOT_YML}: {len(shipped)} allowed, {len(provided)} ignored.")


# This is the standard Python idiom for "only run main() if this file was
# executed directly (e.g. `python3 gen-dependabot.py`), not if it were
# imported as a module from another script."
if __name__ == "__main__":
    main()
