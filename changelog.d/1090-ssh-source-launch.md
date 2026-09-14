### Fixed (2026-09-14 — #1090: SSH-source bootstrap ran `:latest` with no role, ignored the runtime profile, and deployed every SSH source's hosts onto each other)
- **The SSH source's launch line was a hand-rolled copy of the cloud one, four defects behind it.**
  `BootstrapPhaseDeploy.startRuntimeViaSsh` pulled and ran `ghcr.io/pragmaticalabs/aether-node:latest`
  (never the version being bootstrapped), emitted no `aether-role` label and no `AETHER_ROLE` (so every
  SSH host joined unlabelled and classified itself CORE — workers included), ignored the role's runtime
  profile, and `deploySshSource` iterated every `ssh` node in the context regardless of source, composing
  each with `NodeRole.CORE`. Now: only this source's hosts (exact id attribution,
  `BootstrapPhaseProvision.belongsTo`), each with its own role, launched through the SAME builder the
  cloud re-launch uses (`buildRestartCommand` — role label, `AETHER_ROLE`, node id, identity allow-list,
  image) prefixed with `mkdir -p … && docker pull <image>`; the image is the role's runtime profile
  image, else the version-derived tag, never `:latest`. The config dir is created before the scp that
  needs it (it used to be created after). A `JVM`/`EMBER` runtime on an SSH source is **refused by
  name at deploy** rather than silently run as a container — installing a JVM unit over SSH is the
  cloud-init script's job and has no SSH equivalent yet; `bootstrap-config.md` says so.
  [verified: `aether/cli/src/test/java/org/pragmatica/aether/cli/cluster/BootstrapPhaseDeploySshSourceTest.java`
  — through `deploySshSource` with injected `ssh`/`scp` seams: resolved image and no `:latest`; a
  declared worker host gets `-l aether-role=worker` and `AETHER_ROLE="worker"`; a second SSH source's
  host is never touched; a JVM profile is refused with the source, node and runtime named]
- Stacked on #1085 (`belongsTo`, `nodeRole`, the role-bearing `buildRestartCommand`).
  [design intent — unverified: no SSH host was deployed; the launch line is asserted at the ssh seam]
