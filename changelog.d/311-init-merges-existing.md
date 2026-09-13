### Fixed (2026-09-14 — #311: `cluster init` was overwrite-or-abort against an existing config)
- **Re-running `cluster init` against an existing output aborted a batch run (`OutputExists`) and,
  with `--force`, replaced the file wholesale** — a hand-added section (`[app-http.api-keys.*]`, a
  tuned timeout) was either a blocker or a casualty, so the generator could not be re-run to change
  one answer. Without `--force` the generated document is now **merged into** the existing one via
  `TomlDocumentMerger`: every key init generates follows the new answers, every key it does not
  generate survives, and the survivors are printed (`Merged into <path>: kept N key(s) init does not
  generate — …`) because a merge cannot tell a hand-added key from one init used to generate and no
  longer does — listing them is what keeps the second kind from going silently stale. Interactive
  mode asks before merging (default yes); `--force` keeps its meaning and overwrites.
  [verified: `aether/cli/src/test/java/org/pragmatica/aether/cli/cluster/ClusterInitCommandRerunTest.java`
  — same answers converge to the same key set; new answers apply while a hand-added section survives
  and is named; `--force` still drops it]
- **An existing file that does not parse is refused (`OutputUnreadable`, naming the file and
  `--force`), never clobbered** — the operator's edits are what the merge exists to keep.
  [verified: `ClusterInitCommandRerunTest.rerun_refusesAnUnparseableExistingFile_ratherThanClobberingIt`]
- Limits, stated: comments in the existing file are not preserved (the TOML writer emits none, so a
  merged file loses init's commented "advanced" templates); table arrays (`[[…]]`) init generates
  replace the existing ones wholesale rather than element-merging. Merge-not-abort in batch mode is a
  behaviour change for any script that relied on the abort — none in this repository does (grep of
  `aether/tests` for `cluster init`: no re-run against an existing file). The generator itself emits no
  random values (`--secret auto` defers to bootstrap), so a same-answers re-run was already
  byte-identical under `--force`; what was missing was convergence without destruction.
  [design intent — unverified]
