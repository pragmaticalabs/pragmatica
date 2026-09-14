### Fixed (2026-09-14 — #1090: SSH-source bootstrap ran `:latest` with no role, ignored the runtime profile, and deployed every SSH source's hosts onto each other)
- **The SSH source's launch line was a hand-rolled copy of the cloud one, four defects behind it.**
  `BootstrapPhaseDeploy.startRuntimeViaSsh` pulled and ran `ghcr.io/pragmaticalabs/aether-node:latest`
  (never the version being bootstrapped), emitted no `aether-role` label and no `AETHER_ROLE` (so every
  SSH host joined unlabelled and classified itself CORE — workers included), ignored the role's runtime
  profile, and `deploySshSource` iterated every `ssh` node in the context regardless of source, composing
  each with `NodeRole.CORE`. Now: only this source's hosts (exact id attribution,
  `BootstrapPhaseProvision.belongsTo`), each with its own role, launched through the SAME builder the
  cloud re-launch uses (`buildRestartCommand` — role label, `AETHER_ROLE`, node id, image, and the
  `ClusterIdentityEnv.IDENTITY_VARS` env names forwarded from the operator's host env — `AETHER_API_KEYS`,
  `AETHER_SOURCE`, `AETHER_ZONE`, …; nothing outside that list) prefixed with `mkdir -p … && docker pull
  <image>`; the image is the role's runtime profile image, else the version-derived
  `aether-node:<cluster version>` tag, never `:latest`. The config dir is created by its own ssh call
  before the scp that needs it (it used to be created after). A `JVM`/`EMBER` runtime on an SSH source is
  **refused at config load** (`PF-22` now admits only `CONTAINER` — it used to admit all three and the
  deploy phase refused after every other source had provisioned), and refused by name again at deploy
  as a backstop — installing a JVM unit over SSH is the cloud-init script's job and has no SSH
  equivalent yet. A host declared by two SSH sources used to be launched twice (the second `docker run`
  replacing the first); `PF-27` refuses it at config load, naming both sources.
  [verified: `aether/cli/src/test/java/org/pragmatica/aether/cli/cluster/BootstrapPhaseDeploySshSourceTest.java`
  — through `deploySshSource` with injected `ssh`/`scp` seams: resolved image and no `:latest`, with and
  without a runtime profile; a declared worker host gets `-l aether-role=worker` and `AETHER_ROLE="worker"`;
  a second SSH source's host is never touched; `mkdir` → `scp` → launch per host in that order;
  `AETHER_API_KEYS` from the host env reaches the line and an unlisted name does not; a JVM profile is
  refused with the source, node and runtime named.
  `aether/aether-config/src/test/java/org/pragmatica/aether/config/cluster/ClusterBootstrapConfigValidatorTest.java`
  — JVM and EMBER on an SSH source fail `PF-22`, CONTAINER passes; the same host in two SSH sources fails
  `PF-27` naming both, distinct hosts pass]
- Stacked on #1085 (`belongsTo`, `nodeRole`, the role-bearing `buildRestartCommand`).
  [design intent — unverified: no SSH host was deployed; the launch line is asserted at the ssh seam]
