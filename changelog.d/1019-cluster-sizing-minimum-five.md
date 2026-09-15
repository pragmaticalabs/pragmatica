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
- **The MINIMUM is enforced where configs are CREATED, and nowhere else.** `CoreWorkerSplit` is the
  single gate, reachable only from `init` and `scaffold`. `ConfigValidator` and `ClusterSizeGate` keep
  a **structural** floor of 3 — below three no majority quorum exists at all. **Existing 3-node
  clusters keep booting; new ones cannot be created below 5.**
  `[verified: CoreWorkerSplitTest$CoreBelowMinimum, ClusterBootstrapConfigValidatorTest$ConsensusTierMaximum.validate_succeeds_atTheStructuralFloorOfThree]`
- **The two boot-path gates refuse differently, and an earlier draft of this fragment said they did
  not.** Both run on every node boot, so neither is an authoring gate — but only one of them stops a
  boot. `ClusterSizeGate` genuinely refuses: `Main#enforceMinimumClusterSize` pipes its failure into
  `Main#abortBoot`, so raising ITS floor to 5 really would stop the first node of a rolling upgrade
  from restarting and strand clusters running today. `ConfigValidator` does **not** refuse:
  `Main#loadConfigFile` is `ConfigLoader.load(path).onFailure(log::error).option()`, so its failure is
  logged and **discarded** and the node boots with no config at all — losing its TLS, port and secret
  settings, reporting `configuredClusterNodes` as 0, and (on a cloud node whose discovery arm then
  goes inert) aborting later in `ClusterSizeGate.enforce(0)` with a diagnostic that names neither the
  file nor the floor. Round 1 gave the first mechanism as the reason for both. The conclusion — keep
  both at 3 — is unchanged; the reason for half of it was wrong, and a rationale naming the wrong
  enforcer is the kind that never gets corrected.
  `[verified: ConfigLoaderTest.load_validationFailure_becomesAnEmptyOptionRatherThanAnAbort pins the discard's near half — a rejected config yields an EMPTY Option through the composition Main uses]`
  `[unverified: that Main#loadConfigFile itself continues past the empty Option — read at Main.java#loadConfigFile, not executed; no test in this PR boots a node]`
- **The MAXIMUM is on the CONSENSUS tier, not the fleet — and it now holds on every path that can
  set one, not only at `init`.** `[cluster] nodes` feeds `TopologyConfig.clusterSize`, the quorum
  basis every consensus round is broadcast across, and the bound is 9 (was 7). Fleet size is bounded
  separately by `ClusterConfig.maxNodes`, which #298 deliberately leaves UNBOUNDED, so capacity beyond
  9 is added as workers rather than refused. The old message said "Maximum **recommended** node count
  is 7" while hard-rejecting; a message that says *recommended* while enforcing is a false diagnostic,
  and it now states a limit and names workers as the remedy.
- **Round 1 enforced that maximum at `aether cluster init` alone, which made it an authoring
  convention rather than a bound.** Two paths went past it, and the ticket itself points at the first:
  it records that "writing the config directly … **does** provision five core nodes", so hand-authoring
  is a supported route, and `ClusterBootstrapConfigValidator` accepted a derived core count of **11**
  and a `core_topology.max` of **15**. The second is `ClusterTopologyManager#setDesiredCount`, which
  had a quorum floor and **no ceiling**, so a scale on a live cluster could grow the consensus tier
  without limit. The figure now lives in one place, `ConsensusTierBounds.MAXIMUM_CORE_NODES`, and is
  enforced at both — CL-04 and REQ-3.3.3 in the bootstrap validator, and the CORE branch of
  `setDesiredCount`. `[verified: ClusterBootstrapConfigValidatorTest$ConsensusTierMaximum (5 tests), ClusterTopologyManagerActuatorTest.setDesiredSize_aboveConsensusMaximum_rejectedWithoutAtomWrite, .setDesiredSize_nine_acceptedAtTheConsensusMaximum, .setDesiredSize_workerRoleAboveConsensusMaximum_isAccepted]`
- **No NEW enforcement is added on the boot path** (CTO ruling, session 19). `ConfigValidator`'s
  `[cluster] nodes` bound is pre-existing — it refused above 7 before — and is only RAISED to 9, never
  introduced; per the note above, a failure there does not refuse a boot in any case. The structural
  floor of 3 stays at `ClusterSizeGate`, `ConfigValidator` and `ClusterBootstrapConfigValidator`.
- **Five shipped harness TOMLs carried `core_topology.max = 15` and move to 9**
  (`aether/tests/integration/cluster-config.toml`, `env/docker.toml`, `env/docker-b.toml`,
  `env/remote.toml`, `env/remote-b.toml`). Their derived core count is 5, so the ceiling they actually
  exercise is unchanged — but left at 15 they would have failed the new REQ-3.3.3 check. Three live
  specs carried the same value in `[cluster.core]` examples and are corrected
  (`cluster-bootstrap-spec.md` ×2, `integration-test-overhaul-v2-spec.md`); the archived spec and the
  dated progress note keep theirs, being history. `[verified: root reactor green with the moved values]`
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

### Round 2 (2026-09-14) — what the adversarial review of round 1 found, and what pins it now

- **The fix was unpinned end to end, and that was the blocking finding.** The round-1 review
  re-introduced #1019 verbatim one layer below the CLI — `count = Math.min(5, core)` in
  `ClusterConfigGenerator.appendRoles` — and every one of `aether/cli`'s 821 tests stayed green. The
  unit tests covered `CoreWorkerSplit`'s arithmetic; nothing asserted that the arithmetic REACHED the
  file. `ClusterInitSizingRoundTripTest` now drives the real command through picocli, parses the
  output with the same `ClusterBootstrapConfigParser` that `aether cluster bootstrap` uses, and
  asserts `derivedCoreCount`, `[source.X.core] count`, `[cluster.core] min`/`max` and the worker
  sub-table over core ∈ {5,7,9} × worker ∈ {absent, 0, 2, 40}.
  `[verified: 12 tests; the Math.min(5, core) mutation reddens 4 of them, including "core=7 … expected: 7 but was: 5"]`
- **`--core-nodes` is now required for an `ssh` target too, which is a behaviour change.** Before
  #1019 an `ssh` batch run needed no count — the whole of `--hosts` became the cluster and the split
  was derived from its length. `cli.md` still described the old rule ("Required for non-SSH targets")
  and is corrected. `[verified: ClusterInitSizingRoundTripTest$Refusals.ssh_withoutCoreNodes_isRefusedNamingTheFlag]`
- **`TooFewCoreNodes` ended with a remedy that re-triggers the same error.** It said "For local
  single-process dev/test, use `--target forge`" — but `ClusterInitCommand#buildAnswersForTarget`
  routes `case DOCKER, FORGE` through the same `requestedSplit()` and the same minimum, so
  `init --target forge --core-nodes 3` exits 1 quoting that very sentence. There is no supported sub-5
  creation-time topology to point at, so the sentence is removed rather than replaced.
  `[verified: ClusterInitSizingRoundTripTest$Refusals.tooFewCoreNodes_offersNoRemedyThatReTriggersTheSameError — asserts the ABSENCE, with the presence of the surrounding message as its control]`
- **The `cluster-init-wizard-spec.md` still specified the deleted behaviour.** Status *Implemented*,
  and §1/§3 still described `TopologyDeriver`, the full derivation table including N=1, and a
  "Proposed: 3 core + 4 worker" transcript. §3 is rewritten as the two-tier model with a table of
  where each bound is enforced; the file-manifest row now names `CoreWorkerSplit.java`. The only
  remaining mentions of `TopologyDeriver` anywhere in the tree (space: 5,036 tracked files) are four
  notes recording that it was deleted. `[verified: git grep TopologyDeriver, all hits are deletion notes]`
- **Three behaviours the round-1 review mutated with no test reddening now have one each:** the ssh
  refusal of `--worker-nodes` (M2), the ssh host-count guard's MESSAGE — which shares its inputs with
  the `worker >= 0` rule, so only the text distinguishes them (M3/N3) — and the `scaffold --nodes`
  floor (M6). `[verified: ssh_workerNodesFlag_isRefusedRatherThanIgnored, ssh_coreExceedingHostCount_isRefusedNamingBothNumbers, ClusterScaffoldCommandTest.call_nodesBelowTheSupportedMinimum_isRefused + .call_nodesAtTheSupportedMinimum_isAccepted]`
- **The wizard's own topology answers were unpinned too.** Every pre-existing fixture answered the
  worker question with `0`, which is also what a wizard that IGNORED the answer would produce, so the
  suite could not tell them apart. The wizard also asked for workers BEFORE validating the core, so
  typing `3` cost the operator both prompts again; the core is now checked first.
  `[verified: ClusterConfigWizardTest$Topology (3 tests); replacing the worker answer with 0 reddens two of them, and reverting the ordering reddens the other two by input desync]`
- `[unverified: no live cloud bootstrap — that `--core-nodes 7` yields a 7-member consensus cluster on
  hardware is traced through `BootstrapPhaseProvision.provisionCloudWithCompute` and the overlay
  generator, and asserted as far as the generated file, not run against a provider]`
- `[unverified: whether a provisioned worker ever hosts a slice — the question `know: b654b83e8` gates
  this merge on, answered by verify-1150, not here]`
- `[unverified: the wizard's ssh branch (`promptedSshCore`) — still no test drives it]`
