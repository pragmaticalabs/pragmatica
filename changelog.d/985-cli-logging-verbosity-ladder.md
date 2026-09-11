### Fixed (2026-09-10 — #985: the CLI ships with slf4j-NOP, so every diagnostic it writes is discarded)
- **The shipped CLI discarded every log statement it made.** `aether/cli/pom.xml` declared
  `slf4j-nop`, whose `NOPServiceProvider` was the only provider in the fat jar, so
  `HetznerComputeProvider.logCreateRequest` — the one record of the `serverType`, ssh-key count,
  firewall count and labels actually sent to Hetzner — was produced and thrown away.
  [mechanism: `META-INF/services/org.slf4j.spi.SLF4JServiceProvider` in `aether/cli/target/aether.jar`
  resolved to `org.slf4j.nop.NOPServiceProvider`, while `aether-node.jar` resolved to log4j2's
  provider and emitted — the node jar is the positive control that makes the CLI's silence a real
  absence rather than a broken probe]
- The CLI now binds the **same log4j2 stack as `aether-node`**, so there is one logging story across
  both shipped artifacts and no second configuration format to document.
  [verified: `aether/cli/src/test/java/org/pragmatica/aether/cli/VerbosityTest.java`]
- A top-level **`-v` / `-vv` / `-vvv`** ladder raises `org.pragmatica` to INFO / DEBUG / TRACE, and
  saturates beyond the last rung. The shipped default keeps it at WARN.
  [verified: `VerbosityTest.verbosity_mapsRungs_toTheDocumentedLevels`]
- `-v` is declared on the **top-level command only**, because four subcommands already use `-v` for
  `--version` and take a value there. `aether -vv cluster bootstrap …` works; `aether cluster
  bootstrap -vv` is still `--version`, unchanged.
  [mechanism: a picocli execution-strategy seam applies the rung after parse and before the
  subcommand, so no subcommand option is shadowed and none was edited]
- Console target is **`SYSTEM_ERR`**, deliberately diverging from the node's `SYSTEM_OUT`: the node's
  stdout is its log, whereas the CLI's stdout is data that operators pipe into `jq`. A diagnostic on
  stdout would corrupt those pipelines silently.
  [verified: `CliLoggingPinTest.consoleAppender_targetsStderr_notStdout`]
- Root stays **`OFF`**, so no shaded third-party library logs at any rung — including `-vvv`. The
  node needs seven explicit noise-suppression entries to stay readable; inverting the default avoids
  maintaining such a list against the whole shaded dependency set.
  [verified: `VerbosityTest`]
- **Default behaviour is unchanged on the channel logging uses:** 83 invocations across 36
  subcommands, run against a baseline worktree at `87c1f78e4`, produced **0 stderr difference
  lines**. The 5 stdout differences are the new `-v` help/completion entries, a build timestamp, and
  `cluster list` row order, which is non-deterministic at baseline too.
- [unverified: the scoped-WARN vs root-WARN vs root-ERROR **noise comparison is inconclusive**. The
  measured space is offline invocations — help, usage errors, connection-refused — and nothing in it
  logs at WARN or above under any setting, so all three candidates read 0. The instrument is also
  broken: `-Dlog4j2.configurationFile` supplies an alternative appender but demonstrably not
  alternative levels. The shipped default therefore rests on the byte-identical-stderr result above,
  not on a noise comparison, and no measurement supports switching to ERROR.]
- [unverified: `logCreateRequest` is pinned by unit test against the real `HetznerComputeProvider`,
  not by a jar-level run — reaching that line through the fat jar requires a real cloud provision.]
