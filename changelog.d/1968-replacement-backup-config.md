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
- Not in this change: #1968 item 2 (the harness restarting the whole cluster onto fresh ids), nodes started by the CLI's first-start command mount no backup volume,
  and a Docker leader whose `[backup]` is in its TOML rather than the environment does not forward it.
