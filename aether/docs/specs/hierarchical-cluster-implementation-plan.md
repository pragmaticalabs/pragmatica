# Hierarchical cluster implementation and review plan

Companion contract: [hierarchical-cluster-contract-spec.md](hierarchical-cluster-contract-spec.md).
Working baseline: `ccba0dba5` on `release-1.0.0-rc4`.

The owner authorizes breaking pre-GA APIs, wire formats and persisted schemas. Test clusters
start fresh: no compatibility constructors, mixed-version operation or upgrade migration is
required. Runtime movement of a community between locations remains in scope.

## Delivery rules

Use an isolated worktree and Maven repository. Do not change the owner's checkout or Claude
technology files. All PRs target `release-1.0.0-rc4`; none is self-approved or merged. Every PR
states prerequisite PRs, its incremental commits, tests actually executed and remaining limitations.
Dependent PRs are reviewed and merged in order. Rebase/retest after predecessor integration.

Implement and validate foundation changes before running dependent behavior. Pure model tests
do not substitute for wire registration, node assembly, committed effects and real runtime tests.
Do not count compilation as execution. No paid provider provisioning is needed for this batch;
provider adapters use recorded/native response fixtures and deterministic fake services.

## Reviewable PR sequence

The packages below attribute work to packages, not independent merge boundaries.
Deliver three PRs, each targeting `release-1.0.0-rc4`:

1. **Contracts and acceptance plan.** The end-to-end hierarchy, Rabia, metrics, scoped metadata,
   source routing and reconciled historical specifications. State implemented limits and attach
   actual validation evidence before review.
2. **Additive state and serialization primitives.** Canonical serialization and guarded KV
   mutations with correlated outcomes. Validate against the otherwise unchanged rc4 runtime;
   this layer must not depend on new hierarchy assembly or remove existing wire registrations.
3. **Integrated hierarchy runtime.** Consensus durability and electorate handoff, immutable role
   admission, worker metadata and execution, governor authority, metrics, source placement and
   movement, with node assembly and runtime tests. This PR includes the prerequisite primitive
   commits until they are merged; identify its incremental changes explicitly.

Consensus wire changes, passive worker state, sender checks and node assembly must ship together:
separating them by module would leave intermediate commits with incompatible message constructors,
missing persistence/admission wiring or rejected legacy relay traffic. No temporary compatibility
layer is required for the pre-GA release. The runtime PR is rebased and revalidated after its
prerequisites merge; all merges and approvals remain with the independent reviewer.

## Work packages

### P0 — contracts and evidence

Deliver the overall contract, Rabia instance/round contract and metrics distribution contract.
Reconcile historical worker membership and metrics prose, including one-source communities,
role promotion, full metrics fan-out, and timeout-only exclusive-serving claims.

Acceptance: every reviewed defect maps to an invariant, implementation owner and test; explicit
future disconnected autonomy is distinguished from required present behavior.

### P1 — consensus instances and eligibility

Keep log slot and binary retry round separate. Retain a weak-MVC proposal for the entire slot;
isolate round ballots; a coin selects a later-round state and never directly decides a batch.
Validate the proposal quorum before deciding V1. Remove unproved shortcuts. Retain explicit
round catch-up and bounded decided-history replay. An application progress boundary survives
phase-cache eviction; gaps synchronize before application.

Core eligibility is an explicit input, not inferred from transport activity. Workers can observe
state but cannot vote, advertise LIVE synchronization evidence, or activate as voters. Unknown
identities fail closed. Protocol broadcasts go to the electorate; observer state delivery is
a separate audience.

Acceptance: real engine instances under reordered, duplicated and delayed message schedules
maintain identical applied prefixes after each delivery, not just equal eventual sets. Include
3/5 voter configurations, retry rounds, observer boot and stale decisions after cache eviction.
Run the broad consensus suite and an actual Forge cluster before dependent reconfiguration.

### P2 — committed voter configuration

Represent immutable electorate epoch and complete member identities separately from desired
core capacity and observed health. Initial formation has an authoritative roster. A replacement
does not synthesize its electorate from whatever peers answer first.

Agree a configuration transition in an old-electorate log slot. Stop old-epoch advancement at
the handoff boundary; install the corresponding checkpoint and new configuration before new
voters participate. Bind protocol evidence and synchronization to epochs. Await the required
handoff acknowledgements before deprovisioning removed cores. Persist configuration/progress
together. Reject delayed old ballots and overlapping incompatible changes.

Acceptance: divergent local health views cannot yield divergent quorum denominators. Exercise
growth, shrink, replacement, leader loss during handoff, delayed old messages and restart.
Topology/CTM integration must use actual admitted IDs and must not call SetClusterSize merely
because desired coreCount changed.

Crash recovery also requires durable voting evidence, not only durable handoff checkpoints.
A local consensus WAL records each immutable proposal and round ballot before transmission,
and decisions before application. Recovery restores that evidence before participation.
Checkpoint publication records its covered journal boundary before compaction; corrupt or
incomplete journal evidence fails closed with a typed error. The production default is local
durable storage; Git backup is an optional off-path copy. In-JVM clusters receive isolated,
per-node paths that survive a restart within that cluster. Test crashes at each emission and
checkpoint/compaction boundary; report the measured cost of durable writes separately from
in-memory protocol tests.

WAL crash acceptance includes temporary-file write/force failures, checkpoint rename before parent-directory fsync, and WAL replacement rename before parent-directory fsync. A failed durability step must not acknowledge success or permit further voting writes. Reopen tests cover retained old/new complete-file combinations; physical power-loss behavior remains a filesystem assumption, not an in-process test claim.


### P3 — community authority and worker execution

Core-authorized conditional governor acquisition checks current committed leader, incumbent,
community lifecycle and candidate assignment atomically. Announcement updates preserve owner
identity at equal generation. A worker's local election is nomination only. Correlated responses
cannot revive a stopped/reassigned runtime; actual committed ownership gates effects.

Node roles and source identity are immutable after admission. Unknown role observations do not
emit a core join; late role information emits the correct join once. Replayed activation reuses
one runtime and one observation subscription. Community retirement revokes governor eligibility.

Use the existing NodeDeploymentManager lifecycle for both core and worker execution. Placement
selects one eligible audience and one total instance target; ALL does not duplicate the target
across two mechanisms. WORKERS_ONLY does not silently fall back to core. Remove dormant duplicate
worker mutation/deployment machinery once reference and runtime tests prove the replacement.

Close invocation admission atomically with in-flight accounting. Both local and remote calls use
the gate; pending application execution remains counted until its actual completion. Core absence
and commanded drain share that boundary. Unknown community liveness after leader takeover has
a finite grace, not permanent presence.

Acceptance: concurrent claims, stale refresh, reassignment-before-commit, loss of leadership during
claim, repeated activation, worker workload deployment/invocation and drain rejection through the
real node router. Check all new codec classes through NodeCodecs/SystemTags.

### P4 — metrics distribution and calculation

Preserve leader-independent ping/pong response. Gate authority-bearing metadata separately.
Only identified cores renew core reachability; partial metric batches never prune a full roster.
Producers carry incarnation, sequence and original observation time. Core caches reject terminal
producer identities, duplicate/old versions and invalid freshness without blocking pong replies.

Raw and typed source reports reach cores in bounded chunks. A source selects a core uplink;
follower uplinks forward once to the current leader, which distributes its cache to other cores.
Relay batches are never recursively forwarded. Preserve source identity across all hops. Sample
locally once per interval, not once per chunk. Bound operational history independently of the
current control view. Missing active producers block unsafe scale decisions.

Derive rates from coherent cumulative counter intervals, with explicit reset/appearance/removal
semantics and elapsed-time normalization. Error ratio is errors/calls. Interval-mean percentiles
are named as such and never exposed as request latency percentiles. Match API, dashboard, TTM
feature names and documentation to the actual statistic.

Acceptance: codec round trips, lower/higher-term observation exchange, origin freshness through
multiple relays, terminal producer removal, counter conservation, partial coverage and 10K logical
producer batching. Report bytes/cardinality separately from entry-count limits. No throughput
claim follows from a synthetic shape test.

### P5 — source and placement configuration

Add explicit stable community policies independently of sources: target capacity, eligible
source/location entries, minimum coverage and preferred weights. Parse and cross-check hard
constraints at entry; commit configuration through the existing apply path. Preserve single-zone
implicit placement when no explicit policy is declared.

Source-aware provisioning resolves the named account/provider/region, credentials and instance
profile. Unknown or unresolved source bindings fail visibly. Do not fall back to the leader's
provider or substitute another source's credentials. Existing owned instances retain their
original binding when configuration changes; unsafe binding edits are refused or separately
reconciled. Selection, inventory and termination use the same source identity.

Read physical location from native provider responses, not requested placement tags. Publish
observed placement through committed node facts; late inventory triggers assignment reconciliation.
Missing observed zone cannot satisfy an explicit zone constraint. Quota/target reservations must
be atomic with assignment/provisioning intent so simultaneous joins cannot overfill a target.

Acceptance: two sources using different fake providers/accounts, identical zone names in different
source regions, missing location, unavailable preferred location, invalid policy and concurrent
assignment. Exercise configuration -> provider -> inventory -> committed placement -> assignment.

### P6 — durable movement and shared capacity reconciliation

Use one guarded multi-key transaction primitive for read-set validation and all-or-none mutation.
Return a correlated accepted/refused result to the submitter. A consensus batch of independent
commands is not an atomic conditional transaction.

The durable movement operation reserves overlap, records a stable provisioning attempt before
effect submission, observes destination readiness and replica safety, shifts assignment, drains
the victim, then confirms retirement and releases reservations. An uncertain provider result keeps
its reservation and is reconciled before retry/fallback. Leadership change resumes committed
progress; old pending callbacks cannot begin fresh side effects.

The existing reactive reconciler consumes the same capacity ledger/reservations: it cannot destroy
temporary overlap as surplus or provision the same deficit independently. Community target/growth
allocation must account for reactive source-role capacity changes. Minimums and weights have real
consumers; they are not parsed-only configuration.

Acceptance: interruption after every durable transition and every provider effect, timeout followed
by late success, source quota contention, policy revision during movement, drain failure, destination
failure before/after shift, and recovery without duplicate resources or premature retirement.

### P7 — bounded hierarchy and final integration

Reconcile worker-scoped state delivery and membership knowledge with the 10K target. Snapshot and
subscription sequencing must close the subscribe/pull race and detect gaps. A reconnect burst
cannot allocate unbounded snapshots or queues. Separate control from bulk telemetry/repair.

Run full build/lint, relevant complete module suites, Forge smoke and targeted heavy hierarchy
probes. Exercise multi-community formation, worker execution, governor replacement, core replacement,
source movement, asymmetric partition and mass reconnect under load. Document the measured envelope
and remaining unmeasured physical-cluster limits honestly.


## Reconciliation and review plan

The runtime PR owns the implementation checklist, in
`aether/docs/specs/hierarchical-cluster-reconciliation.md` on [PR #1390](https://github.com/pragmaticalabs/pragmatica/pull/1390).
It enumerates H01–H10 and H-T01–H-T12 with DONE / MISSING / STUB / SHORTCUT / OMISSION /
SIMPLIFICATION, names the production mechanism and regression classes, and separates source
coverage from executed final-head evidence. This specification PR does not claim that merging
these documents implements the contract. Current-product documentation changes belong to #1390.

Review the runtime by these axes, using the relevant contract sections as the claim list:

| Axis | Packages | Contract and acceptance scope |
|---|---|---|
| Consensus | `integrations/consensus`, cluster/Rabia assembly | H01–H03; slots/rounds, ordered application, voter handoff, WAL format and crash matrix |
| Assembly and authority | `aether/node`, `integrations/cluster`, worker health/governor | H01/H03/H04/H06/H07; immutable roles, operator refusal, committed ownership, callback/task lifecycle |
| Deployment and placement | `aether/aether-deployment`, `aether/environment`, cloud integrations | H05/H09; observed placement, source binding, shared reservations, interruption/recovery matrix |
| Metrics and metadata | `aether/aether-metrics`, config, worker metadata/health | H07/H08/H10; durable producer epochs distinct from SWIM, freshness/overlap, bounded repair |

The runtime PR body supplies commit ranges and per-axis changed paths. Review dependencies first:
this pure specification, the corrected guarded-KV foundation (#1379), then the integrated runtime
(#1390). Under the owner's revised release decision the batch may merge into rc4 when ready; rc5
creation is not a gate. Refresh the target branch and validate the merge ref before approval.

Final review requires no required MISSING/STUB/SHORTCUT/OMISSION/SIMPLIFICATION rows. The bounded
local correctness probes do not replace physical WAN/cloud or 10K-node performance measurements.
[unverified: physical-scale] No physical WAN or 10K-node throughput envelope is established.

## Reproducible validation ledger

Historical aggregate counts from private scratch worktrees are withdrawn as merge evidence.
Each retained result identifies source, command and test classes. Subsequent changes require
revalidation of affected paths. A command run with skipped tests is compile evidence only.

| Source ref and worktree | Exact command | Named tests / result |
|---|---|---|
| Foundation `52daae511`, `/private/tmp/pragmatica-hierarchy-primitives` | `env -u HCLOUD_TOKEN mvn -T1 -pl integrations/cluster -am install -Dtest='KVStore*Test,CanonicalSliceCodecTest' -Dsurefire.failIfNoSpecifiedTests=false` | 84 cases in the `KVStore*Test` classes plus two in `CanonicalSliceCodecTest`, zero failures/errors. Classes: `KVStoreAuthorizedMutationTest`, `KVStoreCanonicalSnapshotTest`, `KVStoreEpochFenceTest`, `KVStoreInstallOverlayTest`, `KVStoreLeaderFenceTest`, `KVStoreLeaderTransactionTest`, `KVStoreNoopTest`, `KVStoreNotificationIsolationTest`, `KVStoreOwnerFenceTest`, `KVStorePutFenceTest`, `KVStoreRemoveFenceTest`, `KVStoreReplaySignalTest`, `KVStoreWatermarkFenceTest` (including their nested cases). |
| Foundation production source `52daae511`; reproducible script added at `c80358cda`, same worktree | `python3 tools/check-hierarchy-foundation-mutations.py` | Six assertion-red mutations: read guards, leader authority, equal-epoch deletion, snapshot canonical selection, notification reentrancy and coherent snapshot reads. Exact per-mutation selectors and commands are recorded by the script in `target/hierarchy-mutations/results.json`. |
| Foundation `52daae511`, same worktree, exact production bytes restored after mutations | `env -u HCLOUD_TOKEN mvn -T1 -pl integrations/cluster install -Dtest='KVStore*Test'` | All 84 KV cases passed after source restoration. |
| Foundation PR head `c80358cda`; tested merge `b6382c3670bf0c9eb439d88892fbb19beb644720`, `/home/runner/work/pragmatica/pragmatica` | `python3 tools/check-hierarchy-foundation-mutations.py`; exact per-mutation Maven commands below | [CI run 35537456683](https://github.com/pragmaticalabs/pragmatica/actions/runs/35537456683) passed. `foundation-mutation-evidence/target/hierarchy-mutations/results.json` records the tested merge SHA and all six assertion-red cases; restored-implementation JUnit reports accompany it. |


The six commands below ran on that exact CI merge checkout, each after applying its named
mutation. Exit status 1 and one assertion failure killed each mutation; these are deliberate
negative controls, not passing unmodified-production test results. The script restores source
between mutations. The final restored-source check was
`mvn test -B -pl integrations/cluster -Dtest='KVStore*Test'`; its uploaded JUnit reports contain
84 cases with zero failures, errors or skips.

| Mutation | Exact Maven invocation / test selector |
|---|---|
| `read-set` | `mvn -T1 -pl integrations/cluster test -Dtest=KVStoreLeaderTransactionTest` |
| `owner-remove` | `mvn -T1 -pl integrations/cluster test -Dtest=KVStoreOwnerFenceTest` |
| `canonical-snapshot` | `mvn -T1 -pl integrations/cluster test -Dtest=KVStoreCanonicalSnapshotTest` |
| `reentrant-dispatch` | `mvn -T1 -pl integrations/cluster test '-Dtest=KVStoreNotificationIsolationTest#reentrantNotificationSeesStoreAfterAllNestedApplies'` |
| `atomic-reader` | `mvn -T1 -pl integrations/cluster test '-Dtest=KVStoreNotificationIsolationTest#snapshotCannotObserveHalfAppliedBatch'` |
| `leader-authority` | `mvn -T1 -pl integrations/cluster test -Dtest=KVStoreAuthorizedMutationTest` |

Runtime review corrections are being validated on the current rc4-integrated branch. Its
**Hierarchy review acceptance / runtime-acceptance** workflow records both the tested merge SHA
and PR head SHA, then uploads JUnit and measured-envelope artifacts. The runtime reconciliation
must cite those exact results before claiming final-head completion; an older green run is not
substituted for that evidence. Full CI results remain inspectable in the PR's Checks tab.

[limit: metadata-resources] Scope/cache/client/byte limits bound particular resources; directory
construction, per-client manifest work and mutation bursts still require profiling. Oversized
required scopes fail visibly and close worker admission.
[limit: core-hosted-state] Worker DHT connections may reach each of K cores, O(NK) connections
for N workers and up to N incoming worker connections per core. Storage and IOPS remain core-hosted.
[limit: workload-catalog] Relevant endpoint sets may reach the full fleet. Community peer scoping
does not establish a constant bound for arbitrary application dependency graphs.
[limit: uncertain-capacity] Unknown provider effects retain capacity reservations until their
outcome is established; an empty inventory result is not proof that an unconfirmed create failed.
