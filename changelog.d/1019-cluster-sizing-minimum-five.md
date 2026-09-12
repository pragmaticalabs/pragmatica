### Changed (2026-09-12 — #1019: `aether cluster init` takes `--core-nodes` and `--worker-nodes`; supported minimum is 5 core, default 7, maximum 9)

- **Why 5, which is the part worth checking rather than taking on trust.** A 3-node core tolerates
  ZERO failures *during maintenance*. A rolling restart takes one node down, leaving 2 of 3, and any
  further fault at that moment loses quorum. Maintenance is planned and routine, so a 3-node cluster
  spends a predictable fraction of its life with no fault budget at all. **5 is the smallest core
  where a planned operation still leaves margin**; 7 buys a second concurrent fault and is the
  recommended default. The claim is about the maintenance window specifically — a 3-node cluster at
  rest still tolerates one fault, which is why the old floor looked adequate.
- **`--nodes` is replaced by `--core-nodes` and `--worker-nodes` on `aether cluster init`.** The
  config models the two tiers as independent quantities (`[cluster.core]`, `[source.X.core]`,
  `[source.X.worker]`); the CLI took one number and inferred the split, so the round trip lost
  information. `--nodes 5` produced a **three**-member consensus tier and reported success, and no
  value of `--nodes` could express a 7- or 9-member one — which is why a default of 7 was
  unreachable. Cloud bootstrap provisions the CORE tier only, by design (RFC-0017 stage 7; workers
  arrive later from the stage-5 reconciler), so the derived core IS the consensus cluster the
  operator receives.
- **`TopologyDeriver` is deleted rather than corrected.** Its table was not the defect but the place
  a modelling mismatch surfaced; with both tiers stated there is nothing to derive, and an absent
  `--worker-nodes` means zero workers rather than an invented split. `BootstrapPhaseProvision`'s
  `CLOUD_BOOTSTRAP_ROLES` is unchanged — the ticket named it as a cause and it is deliberate.
- **The interactive wizard asks for both tiers too.** A single "total node count" question was the
  interactive face of the same mismatch. For an `ssh` target only the core size is asked, since the
  fleet is the host list and the worker tier is its remainder; `--worker-nodes` is *refused* there
  rather than ignored, because a flag that cannot take effect only looks like it worked.
- **The minimum is enforced where configs are CREATED, and nowhere else.** `CoreWorkerSplit` is the
  single gate, reachable only from `init` and `scaffold`. `ConfigValidator` and `ClusterSizeGate`
  keep a **structural** floor of 3 — below three no majority quorum exists at all. They look like
  authoring gates and are not: `ConfigLoader.load` calls `ConfigValidator` at three sites and the
  node loads through `ConfigLoader`, so both run on **every node boot**. Putting the policy figure
  there would make the first node of a rolling upgrade refuse to start and fail to rejoin —
  forbidding, in the name of maintenance safety, the exact maintenance operation the rule protects —
  and would retroactively refuse to boot clusters running today. **Existing 3-node clusters keep
  booting; new ones cannot be created below 5.**
- **The maximum is on the CONSENSUS tier, not the fleet.** `[cluster] nodes` feeds
  `TopologyConfig.clusterSize`, the quorum basis every consensus round is broadcast across, and is
  now capped at 9 (was 7). Fleet size is bounded separately by `ClusterConfig.maxNodes`, which #298
  deliberately leaves UNBOUNDED, so capacity beyond 9 is added as workers rather than refused. The
  old message said "Maximum **recommended** node count is 7" while hard-rejecting; a message that
  says *recommended* while enforcing is a false diagnostic, and it now states a limit and names
  workers as the remedy.
- **A dead constant is deleted rather than updated.** `ConfigValidator.VALID_NODE_COUNTS =
  Set.of(3, 5, 7)` had exactly one occurrence in the tree — its own declaration — while the live rule
  was a literal chain in `nodeCountErrors`. Editing it would have changed no behaviour *and* left a
  plausible-looking constant for the next reader to believe, which is worse than removing it.
- **Defaults: `Environment.LOCAL` 3 → 5, `DOCKER` and `KUBERNETES` 5 → 7.** LOCAL stays below the
  production default on purpose — it is developer ergonomics, where availability is not a goal and
  seven JVMs on a laptop is a real cost. `StartupConfig.DEFAULT_CLUSTER_SIZE` and
  `EmberConfig.DEFAULT_NODES` track LOCAL for the same reason and now say so in a comment, because
  "inconsistent with the production default" is exactly the reasoning that would otherwise push them
  to 7. Forge's `--help` renders the constant instead of repeating the literal.
- The shipped three-container docker quick start is **unchanged**: with the boot path keeping the
  structural floor, it still starts.
