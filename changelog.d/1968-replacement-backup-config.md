### Fixed (2026-10-07 — #1968 item 1, #1543 part A2: auto-heal replacements lost `[backup]`, and a leader without it silently stopped the backup)
- **A replacement now carries the cluster's `[backup]` and the directory or volume it needs, in container and JVM mode and on the Docker provider.** The cloud
  user data renders `[backup]` from the committed cluster configuration (`[source.<name>.node_config.backup]`, the same for seeds and replacements) and now also
  creates the repository path: a container node gets the host directory `/opt/aether/backups` (owned by uid 1000) bind-mounted at `[backup] path`, a JVM node gets
  `path` created before it starts. A Docker node has no TOML: `[backup]` is overridable per key by `AETHER_BACKUP_ENABLED/PATH/REMOTE/RESTORE`, the Docker
  provider forwards those from the leader's environment, and mounts a per-node volume for the repository.
  `[verified: ReplacementBackupRenderTest, ClusterTopologyManagerRenderUserDataTest, DockerComputeProviderTest, ConfigLoaderBackupEnvTest]`
- **A leader without `[backup]` over a committed backup is no longer silent.** It keeps the committed setting (never downgrades it to DISABLED) and raises the operator
  event `backup-config-missing` (CRITICAL, once per leadership term), with the INFO recovery `backup-config-restored` when it stops leading.
  `[verified: BackupRestoreCoordinatorTest.LeaderWithoutBackup, BackupWarningOperatorEventTest]`
- **Every way a node is started carries `[backup]`.** The CLI's first-start command (cloud container, cloud JVM and SSH) creates the repository directory and
  bind-mounts it; a Docker source's `[source.<name>.node_config.backup]` reaches the nodes the CLI provisions; a Docker leader forwards its EFFECTIVE `[backup]`
  (TOML or environment) to its replacements. `[verified: BootstrapPhaseDeployCloudSshRestartTest, BootstrapPhaseDeploySshSourceTest, ProviderResolverTest, MainEffectiveBackupTest]`
- **`backup-config-restored` is also raised when the lacking leader's node stops** (graceful stop, drain, self-fence), not only on a clean leadership loss; a crash cannot
  raise it (documented). `[verified: BackupRestoreCoordinatorTest.LeaderWithoutBackup]`
- **The environment overriding the TOML is logged** at startup (one WARN per differing key, key and sources only), and the precedence is documented.
  `[verified: ConfigLoaderBackupEnvTest]`
- **An unwritable container backup path is refused at load.** `[source.<name>.node_config.backup] path` must be absolute; for a `docker` source it must be under
  `/data` (a named volume is root-owned elsewhere). `[verified: BackupPathValidationTest]`
- **`backup-restore-blocked` now has a recovery event, `backup-restore-unblocked`** (INFO), raised when the restore decides, and when the blocked node stops leading or
  stops (a restart is a stop first). `[verified: BackupRestoreCoordinatorTest.Blocked, BackupWarningOperatorEventTest, ClusterEventAggregatorTest]`
- A dead Docker node's backup volume is kept on purpose (reattachment belongs to #1569); the runbook says how to reclaim it.
- `[unverified: that WaveExecutor and BootstrapPhaseProvision pass the source to ProviderResolver.resolveDockerCompute(source) (the call that forwards [backup] to the Docker nodes the CLI provisions): replacing either call with resolveDockerComputeWithoutBackup() leaves every test green; the teardown-only provider is named for what it omits, which is a structural guard, not a pin]`
- Not in this change: #1968 item 2 (the harness restarting the whole cluster onto fresh ids). `[unverified: a leader that CRASHES while lacking [backup] leaves its
  backup-config-missing event without a recovery]`
