### Security (2026-10-04 — #828: cloud bootstrap put the cluster secret on the remote `docker run` command line)

- **The finalized-PEERS re-launch and the SSH-source launch interpolated `AETHER_CLUSTER_SECRET` into the
  command string run over SSH** (`docker run -e AETHER_CLUSTER_SECRET="…"`; for JVM nodes a `printf` operand).
  On the remote host that string is visible in `ps` while it runs and in the bootstrap user's shell history.
- **The secret now travels as a `0600` file pushed with scp** (`/opt/aether/config/cluster-secret.env` for
  containers, `/etc/aether/cluster-secret.env` for JVM nodes). The launch command reads it — `docker run
  --env-file` or `cat` into the systemd env file — and removes it in the same command, preserving the
  launch's exit status. The CLI-host temp copy is created owner-only and deleted after the push.
  [verified: `BootstrapPhaseDeployCloudSshRestartTest#deployCloudSource_container_neverPutsTheSecretInAnySshCommand_andPushesItAsA0600File`, `…_jvm_…`, `BootstrapPhaseDeploySshSourceTest#sshSource_launchLineCarriesNoSecretMaterial_itReadsAnEnvFileInstead`]
- **Same class, same fix: `AETHER_API_KEY` / `AETHER_API_KEYS` forwarded from the operator's host env** rode `-e`
  on the same command line. They are credentials and now ride the pushed file too.
- A credential containing a line break cannot be carried in an env file and is refused by name rather than truncated.
- [unverified: `docker inspect` of the running container still lists the environment, as it does for any
  container env var; this change removes the argv, `ps` and shell-history exposure only. Neither the file
  push nor the launch was run against a real host.]
- [unverified: cloud-init user-data, which renders the secret at server creation, is a separate channel and is not changed here.]
