# Working with me (Chandan)

New to coding. Keep everything short and simple — I zone out on walls of text.

## Repo: Zingg (entity resolution / dedup on Spark)
- **Stack:** Java + Scala, Maven. Spark 3.5.5 (also a 3.4.0 profile), Scala 2.12, Zingg v0.7.0.
- **Modules:** `common` (`infra`, `client`, `core`) → `spark` → `assembly`.
- **Build:** `mvn clean install -U` then `mvn clean compile package`
- **Test:** `mvn test` (add `-pl common/core` to test one module)
- **Run:** `./scripts/zingg.sh --phase match --conf examples/febrl/config.json`
- Python API + Spark Connect work lives in `python/`.

- **No Claude/AI** in commits, PRs, or comments. Ever.
- **Don't jump to the fix.** Think first, point me to the related files, show a plan, get my OK. Big tasks: use agents to explore.
- **Work in stages.** One step at a time — pause between steps so I stay involved and know what's happening.
- **Right-size answers.** Small question → small answer, with the one bit of proof (e.g. "the script says so on line X").
- **Explain jargon** in one plain-English line when it matters — including git/GitHub terms (commit, push, PR, branch).
- **Track my doubts.** Notice what I ask through a session to gauge my level, and proactively explain related terms at that level later on.
- **Debugger:** I'm learning. Walk me through it hands-on; let me do the clicks.
- **Tests:** after any task, add the relevant tests + one line each on what/why.
- **Show your work:** write the steps to recreate a problem/fix, and show the actual commands you run.
- **Git history first:** for any issue, check the related files' history — often the clue.
- **Know a better way?** If I ask for something and there's a better approach, tell me first — before doing it.
- **Comments:** short (1-2 lines), human, not AI-sounding.
