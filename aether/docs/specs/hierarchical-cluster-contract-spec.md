# Hierarchical cluster contract

Scope: normative target for runtime PR #1390; present-tense requirements do not assert that
`release-1.0.0-rc4` implements them. Baseline observations are explicitly labelled below.

Status: implementation in progress; no scale certification implied.
Baseline: `release-1.0.0-rc4`, `ccba0dba5` (2026-09-19).

## 1. Purpose and authority

This specification consolidates the hierarchy correctness review and the owner's subsequent
design decisions. It governs the complete path from configured capacity through admission,
community placement, governor authority, observation, recovery and shutdown. It supersedes
worker-membership-spec D2's one-source restriction and historical assumptions that any worker
may become a core. The companion metrics-distribution-spec governs observation exchange.

Core and worker are immutable instance roles. A governor is an operation a worker performs, not a
third instance role. Core sizes 5/7/9/11, roughly 100 workers per community and roughly 100
communities are operating targets, not protocol constants or measured production limits.

The specified operation remains dependent on core authority. Disconnected autonomous provisioning,
delegated recovery budgets and regional consensus cells are explicitly outside this batch.
Authorization boundaries must permit their later addition without redefining node identity,
operation identity or resource fencing. Existing seasonal predictive supervision remains the
separate cluster-supervision-spec; this batch supplies its topology and observation substrate.

## 2. Invariants

H01. Workers and unknown peers contribute zero votes or synchronization quorum evidence.
Workers may obtain state but never become voters through activation or a capacity deficit.

H02. Applied consensus history is ordered and never applied twice, including after in-memory
phase history eviction. Carry-forward phases do not imply missing application commands.
An unexplained forward gap requires synchronization, not speculative application.

H03. Desired core capacity, admitted core identity, live reachability and active voter
configuration are different facts. Local health cannot independently change a committed
quorum denominator. Voter changes require an agreed activation boundary and intersecting
old/new quorums; changing an integer alone is not such a protocol.
Provisioning and disruption guards intersect counted live membership with the installed voter roster;
nonvoting CORE candidates cannot supply quorum evidence. Desired capacity remains the scaling
target, including changes larger than one pair of cores. Missing installed authority fails closed.
Operator drain and shutdown share the voter disruption budget, and concurrent operator requests
must reserve that budget atomically. Worker removal does not consume the core voter budget.

H04. A community has one committed authority owner and monotonically advancing generation.
Local election proposes a candidate; committed acquisition authorizes effects. Stale owners
cannot write through an equal-generation overwrite or act merely because their claim was
submitted. Rejections must be visible to the submitting operation.

H05. Node role, source and physical location are distinct from community assignment. A stable
community can span sources and zones. Replica anti-affinity requires distinct eligible node
identities; community zone coverage is a separate placement property. Single-zone configurations
remain supported. No universal zone-count limit is introduced.

[limit: replica-zone-diversity] Distinct replica nodes do not guarantee distinct physical zones.
Zone diversity requires an explicit workload placement policy; community zone coverage alone
does not provide that guarantee.

H06. Replayed activation does not allocate duplicate schedulers, listeners or worker runtimes.
Reassignment stops the previous runtime before installing the replacement. Shutdown cancels
all owned subscriptions and tasks. Initial membership is sampled; activation must not wait
for a later unrelated SWIM edge.

H07. Observation is not authorization. Any authenticated cluster member can exchange ping/pong
observations independently of leadership. Authority-bearing piggybacks have their own sender,
term and scope checks. Worker traffic cannot renew core reachability.

H08. Missing, partial or old measurements are not zero load or fresh evidence. Receiving a
forwarded sample cannot renew its original freshness. Raw samples and overlapping summaries
are never summed twice. Only core nodes need the complete control metrics view.

H09. Recovery and migration have durable identities, guarded transitions and bounded attempts.
Timeouts do not prove provider failure or exclusive ownership. Destination readiness precedes
source retirement; replica repair completes before retiring required copies.

H10. Correctness traffic has bounded queues and priority over bulk telemetry and repair.
Steady-state operation and failure recovery must both fit the supported resource envelope.

## 3. Facts and owners

| Fact | Owner | Readers and enforcement |
|---|---|---|
| Instance role and identity | Provision/admission boundary | Consensus eligibility, lifecycle, placement |
| Source capability, location eligibility and quota | Cluster configuration | Capacity admission and provisioning |
| Desired community placement | Core-authorized configuration operation | Community reconciler and management |
| Actual node placement | Provisioning result plus authenticated admission | Reconciliation and diagnostics |
| Community assignment | Core committed state | Worker runtime, governor membership, scoped projection |
| Governor owner and generation | Committed guarded acquisition | Every governor-authorized effect |
| Raw metric observation | Original producer | Core collectors; intermediaries preserve identity |
| Summary | Named aggregation scope and interval | Consumers validate coverage and overlap |
| Migration progress and reservations | One committed workflow | Failover successor and provider reconciliation |

Each durable field must have a real producer and consumer. A model-only record with no live
configuration/runtime path does not satisfy this specification.

## 4. Core admission, synchronization and application

Consensus eligibility is a named predicate supplied by the hierarchy adapter, checked on
inbound protocol messages and synchronization quorum construction. An unknown sender fails
closed. Snapshot availability and voting evidence are separate concepts. Observers cannot
advertise themselves as LIVE voting responders. Broadcast audiences must distinguish voter
protocol traffic from committed state delivery to observers.

Consensus application keeps an applied progress boundary independent of garbage-collected
phase objects. Delayed decisions below progress are ignored. [limit: ahead-decision-buffer]
Ahead decisions are buffered up to 256 entries per engine; overflow evicts the oldest buffered
entry and requests authoritative synchronization rather than applying across the gap. Replay uses
the same checks. Snapshot installation
establishes the new boundary before replay. Observer activation follows the same installation
barrier as voting activation. Paused/synchronizing modes must not lose accepted buffered work.

Changing target core count must not promote a worker, demote a core, or independently change
quorum rules according to each node's locally healthy count. Any existing dynamic membership
path must be reconciled against H03 before being described as supported.

## 5. Community placement and sources

Community identity is stable and independent of source/zone names. A placement policy names
eligible source/location combinations, required minimum coverage, preferred distribution and
target capacity. Observed allocations are separate. Preferences may yield to unavailable
capacity only within explicit hard constraints; residency and quota are never implicit soft
preferences. Empty optional topology means the existing single-zone deployment, not an error.

Several communities may use one source; one community may use several sources. Source-level
quotas are shared reservations, not multiplied by community count. Source fallback resolves
prior ambiguous provisioning attempts before issuing replacement requests elsewhere.

Configuration must travel through parse/validation, committed desired state, source selection,
provision request, returned physical placement, assignment and operator-visible reconciliation.
Unknown source/zone combinations fail at configuration entry. Actual provider placement must
be recorded; requested location must not be reported as observed location without evidence.

## 6. Migration process

Trigger: committed placement revision differs from actual allocation.
Input: revision, stable community ID, current assignments, eligible sources, reservations,
replica requirements and observed ready capacity.
Output: converged allocation with old resources retired, or a named blocked/failure state
that preserves the last safe allocation and remaining commitments.

States: Planned -> CapacityReserved -> Provisioning -> CatchingUp -> Shifting -> Draining ->
Complete. WaitingForCapacity and ReconcilingUncertainEffect retain the operation and reservations.
Every effect uses a stable operation/attempt identity. Leadership change resumes from committed
progress and reconciles provider observations before issuing effects. A placement revision
superseding an in-flight migration reconciles existing effects rather than forgetting them.

No fixed maximum number of zones is imposed. Admission instead checks placement constraints,
temporary capacity, disruption allowance and repair feasibility. Cross-region movement must
respect residency and make the WAN/repair costs observable. Rollback is permitted only while
the source remains safe; after destructive retirement use forward recovery.

Source-count reductions in the implicit single-source mode use the same durable retirement
workflow. They may not delete surplus workers directly or treat a timeout as quiescence. A
retirement requires a committed worker assignment, source facts and unchanged configuration intent;
it excludes the victim from new placement, evacuates its workloads and waits for its matching
drain acknowledgement before source-bound termination. Missing authority or assignment refuses
the action and emits a bounded system event. Operations continue reconciling even when no explicit
placement policy exists; only one retirement per community is admitted at a time.

## 7. Governor authority and lifecycle

Read committed incumbent; derive eligible local candidates; request a guarded successor claim;
activate only on accepted committed ownership. Two equal-generation different-owner claims
cannot both succeed. A new process reads the existing generation rather than restarting at 1.
An incumbent is sticky only while still authoritative and eligible, not merely locally alive.

Announcement refreshes are owner-fenced updates, not fresh acquisitions. Reordered completions
cannot regress local knowledge. Lost authority stops owned mutations and clears local governor
activation. Every affected resource enforces community identity, owner and generation at its
write boundary; a metadata-only fence is insufficient.

Activation owns one runtime handle containing all subscriptions and scheduled tasks. The handle
is reused for an identical directive and replaced with orderly cancellation for reassignment.
Committed authority is consulted for effects, while observations can continue without authority.

## 8. Isolation and reconnection

Current policy retains core-dependent mutation. No autonomous scale-up, replacement or new
ownership is inferred from a cached allocation. Document the exact existing work/drain behavior
at execution and storage boundaries; distinguish receiving requests from completing in-flight
work and from shared durable writes.

Core absence uses identified-core evidence. Missing history after leader change enters a bounded
unknown grace period; it does not make a worker permanently present. Fresh observations may
restore availability but cannot undo terminal fencing or revive an evicted instance identity.
On reconnection reconcile current authority and assignment before accepting new work.

The inequality core_absence < community_absence is an operational margin, not an exclusive
ownership proof. Asymmetric loss, scheduling pauses and delayed packets must be covered by
effect fencing and tested explicitly.

## 9. Metrics and bounded information flow

Ping/pong remains leader independent. Separate observation acceptance from authority-bearing
piggyback processing. All cores receive sufficient control information for leader failover;
workers need their local/community information, not a full global history. A partial ping must
not erase a collector's entire peer roster. Higher sender terms alone confer no authority.

The companion specification defines producer identity, sequence, intervals, quality, summary
coverage, counter reset, histogram aggregation, core recipients and payload bounds. Long-term
forecast history uses durable rollups rather than expanding the hot in-memory window.

Scoped metadata delivery must have snapshot/subscribe ordering, sequence progress and gap repair.
Core failover and worker reconnection must not cause an unbounded full-snapshot herd. Worker membership and control connections must be bounded by community/control needs, not
global worker count. Application dependency endpoints are a separate catalog: a dependency
with instances throughout the cluster can require a larger catalog and more connections.
Enforce explicit metadata byte limits and report an unavailable projection when exceeded;
do not claim a constant total connection bound for arbitrary application graphs.

## 10. Verification and scale claims

The acceptance matrix must drive the live production path, not only invoke consumer methods:

| ID | Scenario | Required observation |
|---|---|---|
| H-T01 | Workers outnumber cores during cold synchronization | No worker quorum contribution |
| H-T02 | Duplicate decision after phase eviction; reordered decisions and carry-forward gap | No duplicate/out-of-order application; convergence |
| H-T03 | Worker joins below core target; core joins above target | Roles unchanged |
| H-T04 | Two governor candidates, restart, delayed refresh, partition/heal | One accepted authority; stale effects rejected |
| H-T05 | Repeated activation and community reassignment | One active runtime; old listeners/tasks stopped |
| H-T06 | New leader has no pong history for an isolated community | Bounded unknown grace then unavailable |
| H-T07 | Worker and follower pings with lower/higher terms | Pong returned; no unauthorized control effects |
| H-T08 | Repeated/forwarded metrics and overlapping summaries | No freshness renewal or double counting |
| H-T09 | Multi-source, multi-zone policy and unavailable preferred capacity | Hard constraints preserved; deterministic fallback |
| H-T10 | Migration interrupted at every state | Resume without duplicate provisioning or premature retirement |
| H-T11 | One-region outage, asymmetric loss, mass reconnect | Bounded queues, fenced effects, continuing control responsiveness |
| H-T12 | 10K logical nodes plus real increasing-size clusters | Report CPU, memory, bytes, convergence and recovery bounds |

Synthetic topology tests establish algorithmic bounds only. In-JVM tests establish wiring and
failure behavior, not WAN throughput. Real cluster measurements state node counts, workload
cardinality, hardware, network, warm-up, failure injection and percentile durations. Never
convert a 100x100 layout into a benchmark claim. Existing #591 results are not active-worker
coordination evidence. No paid cloud resources are required or authorized by this specification.

## 11. PR sequence and reconciliation

All PRs target `release-1.0.0-rc4`, remain unmerged for independent review, and declare dependencies.
Dependent branches contain their prerequisites and identify the incremental commits explicitly.

1. Contract/specification and acceptance plan.
2. Additive canonical serialization and guarded KV mutation primitives, independently validated
   against the otherwise unchanged rc4 node and CLI callers.
3. Integrated hierarchy runtime: consensus and voter handoff, role admission, community authority,
   worker execution, metrics, scoped metadata and source placement/movement, with runtime tests.

The integrated runtime changes have mutual wire, persistence and assembly dependencies. They are
reviewed as one cohesive layer so intermediate commits do not run new protocol messages against
old node wiring. The [implementation plan](hierarchical-cluster-implementation-plan.md) explains
these boundaries and records incremental validation. Each code layer needs JBCT review and relevant
module tests; the integrated batch additionally needs the repository build gate, Forge smoke and
targeted hierarchy/recovery probes. Use isolated Maven repositories.

Reconciliation records each requirement as DONE, MISSING, STUB, SHORTCUT, OMISSION or
SIMPLIFICATION with implementation and executed evidence. Implementation is complete only when
no required behavior remains in the latter five categories. Explicit future disconnected
autonomy is outside scope, not an implementation shortcut. This document's initial status is
not a claim that any requirement is already verified.

## Additional integration requirements

- `cluster.genesis_voters` carries the original voter identities independently of discovery
  addresses, current membership health and desired capacity. Replacements inherit the verified
  genesis roster; certificate history establishes later electorates.
- `cluster.consensus_path` identifies the local durable consensus journal/checkpoint directory.
  It is unique per node. In-process test clusters provide isolated paths and retain them for
  node restarts. Full loss of this evidence is not a normal same-identity restart.
- An installed successor roster is not permission to terminate its predecessors. Retirement
  additionally requires a persisted certificate of successor-quorum installation.
- Node READY proves node readiness, not workload replacement. Planned retirement first removes
  the old node from allocation eligibility, keeps its instances until the required replacements
  are ACTIVE, and waits for its workload entries to be removed before closing node admission.
  Drain completion is acknowledged only after quiescence and successful departure transfer,
  then committed by the current leader before the worker receives `DrainAccepted`.
  HTTP and QUIC execution accounting outlives caller timeout or cancellation. A terminal reply
  must be enqueued before its admission is released; timeout replies do not release unfinished
  execution. This ordering does not promise network delivery after transport or peer failure.
- With explicit communities configured, community target sizes are the worker capacity intent.
  Legacy per-source worker count reconciliation must not recreate a location that a community
  has left. Core count remains a separate aggregate capacity intent; the existing reactive
  slice-instance controller continues operating.


## Boundary and implementation contracts

Domain APIs and retained durations use Core `TimeSpan`; injectable monotonic clocks use Core
`TimeSource`. Monotonic clock readings and wire
representations may use numeric units at their adapters; convert immediately when representing
an age, timeout, freshness interval or duration. Renaming a raw value to remove `Ms` or `Nanos`
does not satisfy this rule. Use names describing purpose and domain, without `DTO` suffixes.
New fallible behavior uses `Result` or `Promise`; throwing libraries are accessed through their
`lift` boundaries. Infallible mutations return `Unit` unless a framework callback requires void.

Transport authentication and payload identity are separate checks. Authority-bearing direct
messages must bind their claimed sender to the authenticated transport peer before routing.
Any relay exception must name the permitted message category, trusted relay audience and
original-evidence validation; it must not allow a worker to impersonate a core by changing a
payload field. This is a crash-fault consensus design, not Byzantine consensus. It does not
establish a general certificate infrastructure binding provisioned roles to identities.

A configured core discovery seed can provide state-transfer history without acquiring voting
authority. Voter admission requires verified electorate history or trusted committed provisioning
intent. Inventory labels and a peer's claimed CORE role alone cannot create that intent.
Separating transfer trust from voter eligibility permits replacement after the original genesis
members have retired without promoting arbitrary discovery peers into the electorate.
A fresh worker uses configured CORE discovery seeds for its initial routing directory, then
replaces that directory with the verified projection. These bootstrap identities never become
voters by this mechanism. A disconnected worker cannot discover an entirely disjoint replacement
core roster from its obsolete directory alone: recovery requires reachable known cores or updated
administrative/provider discovery seeds and a restart. Seedless recovery across complete roster
replacement is not provided by this batch.

A worker installs a verified, complete metadata projection atomically. Its membership directory
contains relevant core and community peers; application endpoints do not enlarge this directory.
Readiness and new invocation admission require a fresh projection, even when core ping/pong
continues. Expiry prevents new work while preserving accounting for already admitted calls.
A later fresh projection cannot reopen a drain-closed invocation gate.

Workers forward global management reads to a core. Local membership and ownership diagnostics
include `completeClusterView=false` so that their scoped data cannot be mistaken for the full
cluster. A forwarded global read received by another worker fails explicitly instead of returning
partial results or entering a forwarding loop.

## Resource envelope to measure

Let `N` be workers, `K` cores, `G` communities, `M` the largest community, and `B` the
encoded bytes per producer observation. With two selected core uplinks and a possible additional
leader connection, worker control connections are at most approximately `3N` cluster-wide, plus
core mesh and governor/community links. This is a cluster-wide bound, not three connections per
core: at 10,000 workers, each core can still terminate thousands of connections. Changing the
leader can temporarily increase reconnect traffic. Application endpoint connections are additional.
[limit: core-dht-connections] After #1390, worker DHT clients also connect to every verified core replica peer: these data connections
can require `N * K` links and `N` worker connections per core. The two-uplink policy bounds control
probe audiences; it does not cap total worker-to-core connections or core-hosted storage demand.

Core SWIM membership is approximately `K + G`; worker membership is approximately `K + M`.
A governor processes its own community's direct health observations and answers fenced core
challenges. Ordinary workers do not relay global peer metrics. These bounds reduce observation
fan-out without substituting silence for proof of an individual worker's death.

Complete metrics on every core still require at least order `K * N * B` delivered observation
bytes per collection interval, regardless of batching. Batch limits bound individual messages,
not total throughput. History retention adds its configured point count times producer and metric
cardinality. Measure encoded payload bytes, allocation rate and collection/forwarding CPU alongside
message counts; a fixed sample count alone is not a memory limit.

[limit: metadata-and-endpoint-cardinality] Metadata caches, per-worker projection size, manifest lifetime and serving bandwidth have explicit
limits. Large shared catalogs and endpoint fan-out can exhaust those limits even when membership
is small. Reconnect tests must measure cache churn, rebuild cost, time until a fresh projection and
consensus latency while workers recover. Oversize projection rejection is a visible availability
failure, not permission to fall back to an unbounded full snapshot.

[unverified: 10k-wan-throughput] The 100-by-100 hierarchy is a deployment target. It is not evidence that one core group can sustain
all workloads at that size, or that a wide-area core quorum has local-area latency. Core consensus
latency, durable journal writes, governor turnover, metric cardinality, endpoint cardinality and
cross-region repair bandwidth each impose independent limits. Physical multi-region measurements
remain necessary before publishing a supported production envelope.

[limit: durable-checkpoint-pauses] The #1390 durable Rabia path forces proposal, first-round vote, second-round vote and decision
records for a normal decided batch. Retried rounds add evidence writes. The journal checkpoints
after 4,096 appended records or 32 MiB, encoding a canonical full-state image on the consensus
actor. A checkpoint temporary file is forced, atomically renamed, and its containing directory
is forced before the covered WAL prefix is replaced; atomic rename alone is not power-loss durability.
Large control-state images can therefore introduce checkpoint pauses. Local small-state
write measurements do not establish the latency or throughput of a 10K-worker deployment;
measure checkpoint size, serialization time, force latency and proposal queue delay under the
intended state cardinality and storage hardware.

[limit: core-snapshot-frame] After #1390, core catch-up and voter handoff transfer whole encoded messages under a 32 MiB
transport frame limit. The usable application snapshot is smaller: framing, certified authority
history and a retained handoff snapshot consume the same envelope. Reconfiguration must validate
the exact barrier prefix and both handoff and post-install catch-up envelopes before proposing or
voting for the barrier. Oversized transfer is a visible refusal, not permission to resume the old
electorate after an agreed barrier. Uncommitted pending requests may be omitted from recovery
hints without being acknowledged; callers retain their ordinary retry obligations. Chunked core
snapshot transfer is future work. Bounded worker metadata chunks do not remove this core limit.

[limit: handoff-write-unavailability] Once barrier R commits, old epoch E cannot resume writes.
Clients can observe delayed completion or their ordinary timeout/refusal until a successor majority
durably installs E+1. Operators restore connectivity/storage and restart the same durable participants
to retry the certified handoff; they must not roll back to E or manufacture a new electorate.
