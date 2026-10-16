### Fixed (2026-10-16 — fresh `aether cluster bootstrap` of a live cluster's name overwrote its ledger, secret and registry entry)
- **Re-running `aether cluster bootstrap` with the name of an existing cluster ran over it.** A fresh run minted a new
  secret, overwrote the live cluster's `bootstrap-state.json` and registry entry, and on Hetzner adopted its firewall, so a
  later `destroy <name>` swept the live cluster's VMs by label. `ClusterAlreadyExists` was declared and never raised.
- A fresh bootstrap (not `--resume`) now refuses before `BootstrapPhaseValidate` mints a secret and before any state is
  written when (1) the registry holds the name, (2) a local bootstrap state exists for it (an unreadable one counts), or
  (3) a cloud source's provider lists an instance labelled `aether-cluster=<name>` (first page of the label-filtered
  listing; one hit is enough). The message names the check and the way forward: `--resume`, or `destroy` first. A
  check that cannot be made (unreadable registry, provider call failing or timing out after 30 s) refuses with
  `ExistingClusterCheckFailed` instead of proceeding. There is no `--force`: an override is how the incident recurs.
  `ClusterAlreadyExists` gained a `matched` component.
  [verified: `aether/cli/src/test/java/org/pragmatica/aether/cli/cluster/BootstrapExistingClusterGuardTest.java`]
- The guard sits outside `freshBootstrap`'s failure path on purpose: that path reaps the ledger recorded under the cluster
  name, which for a refused run is the live cluster's. [verified: `BootstrapExistingClusterGuardTest.OrchestratorEntryPoint`]
- Not changed: `--resume`, rollback, the ledger and the label sweep. Cloud sources only are asked; ssh, docker and forge
  sources have no provider listing, so for them checks 1 and 2 apply. [mechanism: `BootstrapExistingClusterGuard.cloudSources`]
