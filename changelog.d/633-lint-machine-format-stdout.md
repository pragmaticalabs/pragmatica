### Fixed (2026-09-14 — #633: `jbct lint --format json|sarif` printed the summary onto the document)
- **`LintCommand` wrote the human summary to stdout after the JSON array / SARIF document**, so
  `jbct lint --format json … > out.json` was not parseable (`JSONDecodeError: Extra data`) and every
  consumer had to truncate at the last `]`. A **clean run was worse: it emitted no document at all**
  (`printResults` returned early on zero diagnostics), so the same parser failed on empty input;
  `-v` progress lines and `No Java files found.` went to stdout too. For `json`/`sarif`, stdout now
  carries exactly one document — an empty array / a SARIF run with empty `results` when there is
  nothing to report, including the no-files case — and the summary, progress and no-files lines go
  to stderr, the split `FileCollector`'s diagnostics and `score --format json` already make. `text`
  is untouched: its summary stays on stdout, as `LintCommandTest` pins.
  [verified: `jbct/jbct-cli/src/test/java/org/pragmatica/jbct/cli/LintCommandMachineFormatTest.java`
  — stdout parsed with Jackson for findings, clean run, `-v`, no files, sarif; text control]
