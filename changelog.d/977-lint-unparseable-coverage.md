### Fixed (2026-09-10 — #977: `jbct lint` counted unparseable files as checked, and called it a pass)

- **A file the linter could not parse was excluded from analysis and still counted in the summary,
  in both output branches.** Pointed at one clean file and one unparseable one, `jbct lint` printed
  `✓ All 2 file(s) passed JBCT compliance check.`; pointed at the unparseable file **alone** it
  printed `✓ All 1 file(s) passed JBCT compliance check.` — a perfect score over a set the
  instrument never read. `Parse errors: 1` sat two lines above, in a separate sentence, so anything
  keying on the summary line was told the opposite of the truth. This is the failure this repository
  keeps hitting in its purest form: a check that passes by not looking.
- **Every summary line now carries the denominator its counts were taken over**, via the new
  `AnalysisCoverage` record (`jbct-core`, `org.pragmatica.jbct.shared`) shared by all five affected
  entry points:
  - `jbct lint` → `✗ Checked 1 of 2 file(s), 1 UNPARSEABLE: 0 error(s), ...`
  - `jbct check` → `Check results (checked 1 of 2 file(s), 1 UNPARSEABLE): ...`
  - `jbct:lint` → `Lint results (checked 1 of 2 file(s), 1 UNPARSEABLE): ...`
  - `jbct:check` → `Check results (checked 1 of 2 file(s), 1 UNPARSEABLE): ...`
  - `jbct:process` → both its `Format (checked ...)` and `Lint (checked ...)` lines
  Plus one loud line naming the gap: `Parse errors: 1 — 1 file(s) could not be read or parsed and
  were NOT analysed by JBCT lint. This is a COVERAGE GAP, not a pass: this run is not evidence about
  those file(s).`
- **The `UNPARSEABLE` clause exists only when the gap does** — the count is rendered as the failure,
  not the success. A reader whose pattern over-matches therefore raises a false ALARM, which gets
  chased, rather than manufacturing an all-clear, which invites relief. With complete coverage the
  clause renders `2 file(s)`, **byte-identical to what these summaries printed before**, so every
  visible change lands in the case that was lying and nothing keying on a clean run needs updating.
- **`✓ All N file(s) passed JBCT compliance check.` is now unreachable while any file went
  unanalysed**, as is `jbct:check`'s `JBCT check passed.`.
- **BEHAVIOUR CHANGE — `jbct lint` exit codes now split, matching `jbct check`:** `1` for rule
  violations, `2` for a coverage gap. Both were `2`, which conflated "your code is wrong" with "I
  never read your code" — the ticket's second complaint. `0` is unchanged. Nothing in this repository
  keys on `jbct lint` returning `2` for violations; the IDE-plugin plan's `exitCode > 1` guard
  (`jbct/docs/ide-plugins-plan.md`) becomes correct under the new split rather than incorrect.
- **Maven failure messages name the reason that actually stopped the build.** `jbct:lint` and
  `jbct:process` threw `JBCT lint found 0 error(s)` when the cause was a parse error — true, and the
  exact opposite of an explanation. They now list every reason: `JBCT lint failed: 1 file(s) could
  not be read or parsed and were NOT analysed`.
- **`jbct:process` counted an unreadable file as a format error**, so it was absent from the
  coverage arithmetic entirely; unreadable files are now tallied separately and land in the coverage
  gap, where they belong — the file was neither formatted nor linted.
- **Three states that all render as silence now tell each other apart:** *the goal did not run*
  (`Skipping JBCT <goal>`), *the goal ran on zero files* (`examined NOTHING` / `No Java files
  found.`, #740), and *the goal ran and could not read what it was pointed at* (this fix). They call
  for different actions and none of them is a pass.
- **Not changed, and deliberately:** the pre-run announcements (`Processing N Java file(s)`,
  `Running JBCT check on N Java file(s)`, `Linting N Java file(s)`) still report what was
  **collected** — they are printed before any file is read and cannot know better. The summary lines
  beneath them carry what was **analysed**. A partial run always fails, so a green build's count
  line is unaffected.
- **Known limit, unchanged by this fix:** `jbct lint --format json` / `--format sarif` do not
  represent parse failures in their structured output, and the text summary is still written to the
  same stdout stream as the JSON. A machine consumer must read the exit code (`2`), not the payload.
  Pre-existing, out of scope here, not introduced by this change.
  [mechanism: `AnalysisCoverage.render()` / `gapReport()` — `AnalysisCoverageTest` (`jbct-core`),
  `LintCommandTest` (`jbct-cli`), `UnparseableCoverageReportingTest` (`jbct-maven-plugin`); each
  suite pins the FORBIDDEN value (`All 2 file(s)`, `Checked 2 file(s)`, `found 0 error(s)`) against a
  complete-coverage positive control that produces that very line, and asserts the parser's own
  `Parse failed:` diagnostic so a failing run is distinguishable from a fixture that never reached
  the parser]
