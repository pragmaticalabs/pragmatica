### Fixed (2026-09-13 — #296: the cloud re-launch labelled every node `aether-role=core`)
- **The finalized-PEERS re-launch (`BootstrapPhaseDeploy.buildRestartCommand` /
  `buildJvmRestartCommand`) hardcoded `-l aether-role=core` and passed `NodeRole.CORE` into the
  identity pass, so the same argv also emitted `AETHER_ROLE=core`** — and `AETHER_ROLE` is the SWIM
  role label, the only worker classifier, so a non-core node through this path would not merely have
  been mislabelled for operators filtering by tier, it would have been reclassified as a core. The role
  is now threaded per node, read from the id the provision phase minted (`<source>-<role>-<index>`,
  `BootstrapPhaseProvision.nodeRole`) — the same convention the cleanup ledger already relies on
  (`extractRole`). An id that encodes no role is refused with `DeploymentFailed` naming the node,
  never defaulted to `core`.
  [verified: `aether/cli` `BootstrapPhaseDeployCloudSshRestartTest.deployCloudSource_containerRestart_labelsAndEnvsEachNodeWithItsOwnRole`,
  `…jvmRestart_envsEachNodeWithItsOwnRole`, `…failsLoudly_whenANodeIdEncodesNoRole` — through
  `deployCloudSource` with a captured `sshExec`, one node per role]
- **Scope, stated honestly:** at this tip the cloud re-launch only ever sees cores —
  `BootstrapPhaseProvision.CLOUD_BOOTSTRAP_ROLES` is `[CORE]` (RFC-0017 stage 7; workers are minted by
  the cluster's reconciler through `NodeUserDataRenderer`, which already threads the intended role,
  and the Hetzner/docker providers stamp the label from the role at create). So no wrong label was
  observable on the cloud path when this landed; the ticket's other cited site (`UserDataTemplate`)
  had already been fixed by the W4 work. This removes the last literal in the chain before
  `CLOUD_BOOTSTRAP_ROLES` widens. The SSH-source deploy path is a separate, larger gap (no role reaches
  an SSH host at all — see the ticket filed from this fix) and is not changed here.
  [mechanism: `CLOUD_BOOTSTRAP_ROLES = List.of(NodeRole.CORE)`; `HetznerComputeProvider` `labels.put("aether-role", …)` from the create-time role]
