### Security (2026-10-04 — #828: cloud bootstrap put the cluster secret on the remote `docker run` command line)

- **The node reads its cluster secret from `AETHER_CLUSTER_SECRET_FILE`** (a path; surrounding line breaks stripped).
  The plain `AETHER_CLUSTER_SECRET` still works. Both set to *different* values is refused at start (exit 65) with a
  message naming the variables and no value; an unreadable or empty file is refused the same way.
  [verified: `ClusterSecretSourceTest`, `MainConfigGivenBootTest#aSecretFileAndADifferingSecretVariable_refuseToStart_withExit65_namingNoValue`, `MainClusterSecretStampTest#secretFromAFile_isStampedAndDerivesTheSameBytes_andConfiguredSecretStillWins`]
- **No launch path puts the secret on a process argv or in a container's environment.**
  - CLI bootstrap (cloud first start, container and JVM) and the SSH-source launch: the secret is scp'd as a `0600` staged
    file; the launch line installs it `0400` (container: owned by uid 1000, bind-mounted read-only; JVM: root, named in the
    unit's env file) only after the already-present guard passes, and removes the staged copy on every exit. The installed file is
    kept for the node's life.
  - Cloud-init: written by shell builtins under `umask 077`, same final paths and modes, `AETHER_CLUSTER_SECRET_FILE` in the
    container run / unit env file instead of the value.
  - Provider-minted replacement nodes (`DockerComputeProvider`): `docker create`, the file piped over stdin to `docker cp -`
    as a tar owned by uid 1000 mode 0400, `docker start`. The leader's `AETHER_CLUSTER_SECRET` is no longer forwarded as `-e`.
  [verified: sentinel tests per path — `BootstrapPhaseDeployCloudSshRestartTest`, `BootstrapPhaseDeploySshSourceTest`, `BootstrapLaunchOnceTest` (runs the real launch line under `sh`), `BootstrapPhaseProvisionUserDataTest`, `UserDataTemplate*Test`, `DockerComputeProviderTest$SecretFileTests`, `SingleFileTarTest` (extracts with system `tar`)]
- **Same class, same fix: `AETHER_API_KEY` / `AETHER_API_KEYS`** forwarded from the operator's host env no longer ride `-e` / printf
  operands; they ride a transient `0600` env file. A credential with a line break is refused by name.
- **Stated limits.** Cloud-init user-data still contains the secret in clear text: readable via the cloud provider's metadata/API by
  the account holder and, on the VM, via the metadata service and `/var/lib/cloud`. `AETHER_API_KEY(S)` have no `_FILE` form, so they
  remain environment variables (visible in `docker inspect`). Hand-written compose files and operator `docker run -e` keep the
  env-var form unless the operator adopts the file form. A removed bind-mount source makes a container restart fail loudly.
- [unverified: no real container was started — the local Docker daemon was not running; the real-path `docker inspect` check was not run]
