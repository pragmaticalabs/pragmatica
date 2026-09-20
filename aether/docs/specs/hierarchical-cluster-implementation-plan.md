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

The packages below describe implementation responsibilities, not independent merge boundaries.
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

## Reconciliation ledger

The following is an initial work ledger, not a completion claim. Update it with commit and executed
test evidence before requesting final review of the batch.

| Contract | Package | Initial state |
|---|---|---|
| H01/H02 core eligibility and ordered application | P1 | Implementation and focused tests in progress |
| H03 committed electorate | P2 | Design and implementation in progress |
| H04 exclusive committed governor | P3 | Implementation and integration tests in progress |
| H05 independent community placement | P5 | Configuration/provider foundations in progress |
| H06 idempotent runtime ownership | P3 | Assembly and lifecycle tests in progress |
| H07/H08 metrics authority and quality | P4 | Implementation and focused tests in progress |
| H09 resumable movement | P6 | Required; not yet complete |
| H10 bounded recovery and information flow | P7 | Required; not yet complete |

Final review requires no required MISSING/STUB/SHORTCUT/OMISSION/SIMPLIFICATION entries. Keep
unexecuted physical WAN/cloud benchmarks clearly separate from implemented correctness contracts.

## Validation ledger (2026-09-20)

This is a specification-only PR. The following are local checkpoints from the separate integrated
implementation worktree, not tests executed by this documentation change or a claim that the batch
is ready to merge. Later changes invalidate earlier evidence for affected paths; counts are not additive.

| Checkpoint | Executed result | Remaining scope |
|---|---|---|
| Extracted foundation | 75 canonical serialization/KV cases and 3 baseline codec pinning cases passed; baseline node/CLI reactor compiled. | Independent PR #1379 CI build/Forge failures are under investigation; no green-CI claim. |
| Configuration and metrics | Complete configuration suite: 430 passed. Complete metrics suite: 268 passed. | Cloud/WAN and 10K-producer throughput, encoded bytes and memory remain unmeasured. |
| Control | Complete control suite: 119 passed after the worker-directive fixture correction. | Runtime interaction with movement and scoped worker admission still requires Forge evidence. |
| Deployment | Complete deployment suite: 1298 passed before the final implicit-community retirement change; its subsequent selected integration tests passed. | Full repeat after that change is pending. Provider tests use fixtures, not paid cloud resources. |
| Integrated selected gate | 281 passed: consensus transport 66, metrics 20, deployment 137, node 58. | This checkpoint predates later runtime corrections and is not complete module coverage. |
| Node correction gate | 24 selected cases passed after role/scope fixtures and the consensus-WAL/encrypted-storage namespace correction. | Earlier full node run: 1565 cases, 6 failures, 3 errors, 1 skip. The corrected full node/Ember rerun has no final result yet. |
| Actual multi-node Forge | Worker formation/governor loss, durable all-core restart and governor-report-only loss each passed in targeted runs. | Loaded movement, held worker invocation/drain and multi-community metadata blackout recovery require passing final-tree runs. Earlier workload/movement failures uncovered implementation defects and are not waived. |
| JBCT | No verified full lint pass recorded here. | Build defaults skipped source discovery in the presumed lint gate; execute a gate that demonstrably discovers and checks all changed Java sources. |

Required runtime acceptance includes policy-aware make-before-break placement, full eligible replica
count before drain unload, provider retirement only after acknowledged quiescence and confirmed
absence, and recovery of scoped metadata without reopening admission from stale data. The four-client
metadata blackout test is modest local recovery evidence, not mass socket reconnect or a 10K benchmark.
Asymmetric governor-report loss exercises a specific authority-recovery path; it does not certify all
one-way network partitions or exclusive application execution.

Performance boundaries remain explicit. Operational metrics history is bounded, not a year-long
seasonal archive. Entry-count limits do not establish encoded-byte bounds. Provider fixtures do not
measure cloud quota, pagination latency or movement completion time. Uncertain effects deliberately
retain durable capacity reservations until reconciliation establishes the outcome.

Shared metadata scopes, cache limits, one in-flight client exchange and per-core byte budgets bound
particular resources; directory construction, per-client manifest work and mutation bursts still need
profiling. Oversized required scopes fail visibly and close worker admission. Relevant endpoint sets
can reach the full fleet. Worker DHT data connections may reach every verified core independently of
two control uplinks: O(NK) connections and up to N incoming worker connections per core. Storage and
IOPS remain core-hosted. No validated 10K-node application/storage performance envelope is claimed.
