### Fixed (2026-10-08 — #2052: a given node config file that fails to load or validate no longer boots the node on defaults)
- **`--config=<file>` is now fail-closed.** `Main#loadConfigFile` used to turn a given file that failed to load or validate into an empty config plus one ERROR line,
  and the node booted on defaults, silently dropping its TLS, port, peers and secret settings; a path that did not exist was ignored the same way.
  Now a given file that is missing, not a regular file, unparseable or invalid REFUSES the boot: exit code **65** (`EX_DATAERR`; `78` stays the refused-identity
  halt), and a `FATAL: refusing to start: config file '<path>' (--config=) …` line on stderr and in the log, naming the file and the cause. With no `--config=`
  the node still boots on defaults. Every shipped launcher passes `--config=` (image entrypoint `/app/aether.toml`, cloud-init `/opt/aether/config/aether.toml`,
  `build-and-push.sh` `${CONFIG_PATH:-/app/aether.toml}`), so a node whose baked or rendered config is broken now stops instead of running unconfigured.
  Supervisors should not restart on 65 with the same configuration (`node-operations.md#exit-codes`).
  `[verified: MainConfigGivenBootTest (child JVM; 4 refusal tests go red when the refusal is replaced by the old log-and-continue)]`
- Comments that described the old discard (`ClusterSizeGate`, `ConfigValidator`, `CoreWorkerSplit`) and the test that pinned it are corrected.
