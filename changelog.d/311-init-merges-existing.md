### Fixed (2026-09-14 — #311: `cluster init` was overwrite-or-abort against an existing config)
- **Re-running `cluster init` against an existing output aborted a batch run (`OutputExists`, now
  removed — no path returns it) and, with `--force`, replaced the file wholesale** — a hand-added section or a tuned value was either
  a blocker or a casualty, so the generator could not be re-run to change one answer. Without
  `--force` the generated config is now **merged into the existing file in place**
  (`InPlaceTomlMerge`): the parsers only LOCATE and COMPARE, and the text written is the operator's
  text with exactly these edits — a generated key whose parsed value differs is rewritten on its own
  line (value only; key spelling and trailing comment kept), a generated key or section the file
  lacks is appended into place, and every other line — comments, blank lines, section order, value
  spelling, hand-added keys, sections and `[[…]]` tables — is preserved byte-for-byte. A
  same-answers re-run is byte-identical.
  [verified: `ClusterInitCommandRerunTest.rerun_sameAnswers_isByteIdentical_keepingEveryCommentAndSectionInOrder`
  (three re-runs, 35 comment lines, section order) and
  `rerun_newAnswers_appendMissingSectionInPlace_keepingHandAddedSection` (insert-only diff,
  `[source.primary.worker]` lands after `[source.primary.core]`)]
- **A generated key whose value the operator changed is never reverted silently.** It is reported as
  `section.key: <old> → <new>`; batch mode refuses (`OutputDiffers`, non-zero, file untouched) unless
  the new `--merge` flag consents, interactive mode lists the diffs and asks with default keep.
  [verified: `rerun_batch_refusesToRevertAHandTunedValue_namingOldAndNew`,
  `rerun_batch_withMerge_appliesTheNewAnswer_andRewritesOnlyThatLine` (one-line `diff`, the
  operator's comment survives), `rerun_interactive_defaultKeepsTheHandTunedValue`,
  `rerun_interactive_yesAppliesTheNewAnswer`,
  `rerun_withMerge_appliesAChangeAndAnAdditionTogether_eachAtItsOwnLine` (a rewrite and an
  insertion in one run, each on its own line); mutation: ignoring consent so the existing value
  always wins reddens the three "applies" tests]
- **An operator-added `[[source.primary.firewall.allow_ingress]]` rule survives a cloud re-run and is
  listed as kept.** Generated elements are matched by their scalar keys but `description`; missing
  ones are appended after the file's last element; nothing is removed, so a changed `--admin-cidr`
  adds the new admin rules and keeps the old ones listed.
  [verified: `rerun_cloud_keepsAnOperatorAddedIngressRule_andListsIt` — file byte-identical, rule
  count +1, the kept listing names port 9100]
- **The report line is true in both directions**: `Merged into <path>: updated N key(s) — …; added N
  key(s) — …; kept N key(s) init does not generate — …` names every rewritten, appended and kept key,
  table-array elements included, or says `already matches the answers, nothing changed`.
  [verified: `rerun_newAnswers_appendMissingSectionInPlace_keepingHandAddedSection` (kept and added
  keys named), `InPlaceTomlMergeTest` (kept lists `rules[port=1]`)]
- **The interactive wizard and the merge question share one stdin reader** (`ClusterInitCommand.prompt`,
  passed to `ClusterConfigWizard`). Each used to wrap `System.in` in its own buffered reader, so on a
  piped stdin the wizard's reader drained the input and the merge question always read EOF and took
  its default. [verified: `rerun_interactive_yesAppliesTheNewAnswer` drives both through one
  `System.in`; mutation: a fresh `Prompt` at the question reddens it]
- **A file the merge cannot read is refused (`OutputUnreadable`, naming the reason, the file and
  `--force`), never clobbered** — including valid TOML on a feature the reader does not support, which
  the message now attributes to the reader rather than calling the file unparseable. A merged text
  that would not parse is refused the same way (`MergeError`) before anything is written.
  [verified: `rerun_refusesAnUnparseableExistingFile_ratherThanClobberingIt`,
  `rerun_refusesADuplicateKey_namingTheLine`,
  `rerun_refusesAFeatureTheMergeCannotRead_namingTheReason`]
- Assumes #1037 (#1019): the topology is given per tier (`--core-nodes` / `--worker-nodes`); the
  re-run pins add a worker tier with `--worker-nodes 2` against a core-only file.
- The line index mirrors `TomlParser`'s line rules (multi-line `"""`/`'''`/`[` values, quoted and
  dotted keys, `[[…]]` sub-tables) so a rewrite lands on the right line past them.
  [verified: `InPlaceTomlMergeTest`, four cases]
- Limits, stated: a multi-line value that is rewritten (with consent) collapses to init's single
  line, losing its trailing comment; `# Topology: …` header comments are comments and are not
  rewritten; line endings other than `\n` are preserved on existing lines but not used on inserted
  ones. Batch mode no longer exits non-zero merely because the file exists — it does so only when a
  generated value differs and `--merge` is absent. [design intent — unverified]
