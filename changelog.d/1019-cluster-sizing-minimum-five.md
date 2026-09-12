### Changed (2026-09-12 — #1019: cluster sizing is minimum 5, default 7, maximum 9 — BREAKING)

- **Why 3 is no longer enough, which is the part worth checking rather than taking on trust.** A
  3-node cluster tolerates ZERO failures *during maintenance*. A rolling restart takes one node down,
  leaving 2 of 3, and any further fault at that moment loses quorum. Maintenance is planned and
  routine, so a 3-node cluster spends a predictable fraction of its life with no fault budget at all.
  **5 is the smallest size where a planned operation still leaves margin**; 7 buys a second concurrent
  fault during maintenance and is now the production default. Note the claim is about the *maintenance*
  window specifically — a 3-node cluster at rest still tolerates one fault, which is exactly why the
  old floor looked adequate.
- **`aether cluster init --nodes N` silently produced a smaller consensus cluster than asked for.**
  `TopologyDeriver.derive` split `N` into core+worker with the core capped at 5, mapping `--nodes 5`
  to a THREE-member core and `--nodes 7`/`--nodes 9` to a five-member one. Cloud bootstrap provisions
  the CORE tier only — by design, RFC-0017 stage 7, with workers added later by the stage-5 reconciler
  — so the derived core IS the consensus cluster the operator receives. `--nodes 5` therefore reported
  success while describing a 3-node consensus cluster, and **no value of `--nodes` could express a 7-
  or 9-member one**, which is why the new default of 7 was previously unreachable. The rule is now
  "largest supported consensus tier that fits, remainder to workers": 5→5+0, 6→5+1, 7→7+0, 8→7+1,
  9→9+0, N>9→9+(N−9). `BootstrapPhaseProvision.CLOUD_BOOTSTRAP_ROLES` is unchanged; it was not the
  defect.
- **The cap bounds the CONSENSUS tier, not the fleet.** `[cluster] nodes` is the quorum basis
  (`TopologyConfig.clusterSize`) and every consensus round is broadcast across it, so it is bounded at
  9. Fleet size is bounded separately by `ClusterConfig.maxNodes`, which #298 deliberately leaves
  UNBOUNDED, so capacity beyond 9 is added as workers rather than refused. The old message said
  "Maximum recommended node count is 7" while hard-rejecting; it now states a limit because it
  enforces one, and names workers as the remedy.
- **BREAKING — existing 3-node clusters will not start, and the upgrade order matters.**
  `ClusterSizeGate` runs at node BOOT, so this is not only a config-validation change: the first node
  of a rolling upgrade on a 3-node cluster exits instead of rejoining. **Scale to 5 before upgrading.**
  Rejected rather than clamped: silently rounding 3 up to 5 would provision two nodes nobody budgeted,
  and warn-then-continue would leave a cluster running in exactly the state this change exists to
  forbid, with the warning scrolled off the console. Every refusal names the configured size, the
  minimum, the fault-budget reason and the remedy.
- **Thirteen encodings of one policy moved together, and one of them was already dead.**
  `ConfigValidator` (minimum/maximum/odd), `ClusterSizeGate` (boot floor),
  `ClusterTopologyManagerRecord` (runtime CORE scale-down floor), `BootstrapModule`
  (`SEED_CORE_MIN` and the seed guard), `TopologyDeriver`, `Environment` (LOCAL 3→5, DOCKER and
  KUBERNETES 5→7), `ClusterInitError`, `ClusterInitCommand`'s `--nodes` help, the docker compose quick
  start and four operator docs. `ConfigValidator.VALID_NODE_COUNTS = Set.of(3, 5, 7)` had exactly one
  occurrence in the tree — its own declaration — while the live rule was a literal chain in
  `nodeCountErrors`; editing that set would have changed no behaviour, so it is deleted rather than
  updated. **A policy with two encodings has one that can drift, and this one already had.**
- **LOCAL stays below the production default on purpose.** `Environment.LOCAL` is 5, not 7:
  it exists for developer ergonomics, where availability is not a goal and seven JVMs on a laptop is a
  real cost. `StartupConfig.DEFAULT_CLUSTER_SIZE` and `EmberConfig.DEFAULT_NODES` track LOCAL for the
  same reason and are commented so they are not "corrected" to 7 later. Forge's `--help` now renders
  the constant instead of repeating the literal.
- **The docker quick start is five containers.** `aether/docker/docker-compose.yml` gains `node-4` and
  `node-5` (management 8083/8084, cluster 8093/8094) and its Forge `CLUSTER_SIZE` moves 3→5. This
  supersedes the 2026-09-03 "single machine = three containers" guidance: with the boot gate at 5, a
  three-service compose file does not start, so leaving it would have shipped a quick start that
  cannot run.
