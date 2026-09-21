# Cluster lifecycle reconciliation

Implementation contract for #1404, on the hierarchy foundation (#1386, #1379, #1390).
Breaking wire changes are permitted before GA; no migration is provided. The two new enum phases
precede the required final UNKNOWN constant; deploy matching artifacts across the cluster.

## Authorities and progress

Committed topology is capacity intent. Membership is an incarnation-scoped health observation.
The installed voter configuration alone defines voting authority. Provider inventory establishes
resource existence, not workload readiness. A committed capacity reservation establishes an
outstanding allocation even when the caller or its metrics dispatch announcement was lost.

Events wake reconcilers. Core deficit/surplus follow-ups and the leader's periodic lifecycle
poll supply progress without another external edge. Leadership change is not a retry mechanism.
A pass either finds convergence, preserves pending durable work for a subsequent check, or
preserves a blocked operation whose documented evidence must change before it may proceed.
A successful `Promise<Unit>` means the pass finished; it does not assert convergence. Durable phases
and the provisioning decision describe pending or blocked work.

## Core creation

Effective core capacity includes current core members, local requests in progress, and committed
non-retiring core allocations which have not entered the installed electorate. Use a set union,
not a sum. Already installed but departed voters must not mask a replacement deficit.

The local replacement deadline may expire the local observation cache, but cannot release a
committed uncertain allocation. The provisioning decision reports AWAITING_DURABLE_CAPACITY
when durable pending allocations cover an otherwise observed deficit. Metrics gossip remains
useful for prompt observations; it is no longer the only recovery source.

DISPATCHED means the create might have happened. An empty list, timeout, or deletion request
cannot release it. A definitive provider capacity refusal can release it; a matching provider
observation advances it to OBSERVED. Unknown create outcomes without matching observations
remain reserved; this change does not invent provider evidence or retry them under new identities.

## Retirement

Before provider deletion, change an OBSERVED reservation to RETIRING using the same guarded
ledger transaction. A failed delete retains that phase. A later leader can discover it without
retaining the original callback. Source and provider/account binding remain unchanged.

RETIRING also vetoes core candidate admission, including historical voter and trusted local admission paths.

Core retries must re-evaluate the existing installed-voter, workload and local authority guards.
Auto-heal disable also holds automatic departed-core deletion to preserve diagnostic evidence.
This is not a new cluster-wide freeze API or a change to operator-issued placement policies.

The core orphan sweep runs periodically, takes two observations separated by the existing grace,
and requires current authority before acting. Only one sweep runs per activation; a prior
activation cannot suppress its successor or clear its successor's guard. Both pending retirement
and orphan rediscovery survive transient inventory/deletion failure under a stable leader.

Provider deletion acknowledgement does not release capacity. Source-bound absence confirmation
releases an OBSERVED or RETIRING allocation atomically with the ledger decrement. An unconfirmed
create cannot be converted to retirement merely to circumvent uncertainty retention.

## Continuing inventory

Reconcile configured managed sources periodically. Adopt existing resources, and for allocations
missing from the complete source listing obtain a node-specific source-bound observation.
Previously observed absent resources can then release their allocations. DISPATCHED allocations
remain reserved even if both observations are empty. Failed or partial listings establish no absence. Confirm at most 32 missing observed allocations per source per pass, rotating the starting position so failures cannot starve later entries.

Initial admission still requires complete configured-source inventory. Each process inventories
again before its first admission, and changes to committed configuration invalidate its cached
inventory completion. Reservation commits witness the configuration used for admission.
Out-of-band cloud administration remains outside transactional fleet-cap enforcement.

## Worker policies and scheduling

Configurations without explicit community policies normalize committed worker topology to the
established <source>-w-0 identities. Both admission and reconciliation use this interpretation.
Targets come from committed desiredTopology, including reactive scaling updates, not stale TOML
source counts. Explicit policies retain their configured targets and locations. This normalization
requires no persisted migration and does not silently reassign existing community identities.

Production worker growth uses the community operation protocol instead of provider-headcount
subtraction: reserve identity, request create, observe placement and readiness, then finish. Scale
reduction uses the existing drain acknowledgement and absence protocol. Compatibility actuator
helpers remain available to isolated test fixtures; production installs the community reconciler.

Independent community and source-inventory work runs in four lanes. An item has a bounded
observation deadline, reports failure, and leaves its durable state for the next pass; one failure
cannot short-circuit the remaining identities. Each item reads fresh state. Create admission is
limited to four active source bindings and one call per binding in a process. Existing global
committed capacity admission protects allocations across leadership change. These process-local
limits do not claim cancellation of an already-issued provider request or global provider rate quotas.

Typed pre-dispatch refusals (source admission, incomplete inventory, unavailable authority or seed
peers, and invalid preparation) leave the community identity reserved for retry. They do not claim
an uncertain provider create or increment the core provider-failure circuit. Once a create might
have been dispatched, its durable uncertainty cannot be downgraded to a pre-dispatch refusal.

READINESS_DELAYED distinguishes missed readiness deadlines from changed identity/policy
preconditions. It preserves identity and allocation, emits the existing operation escalation, and
resumes when the same target becomes ready. BLOCKED binding/precondition conflicts and
CREATE_UNCERTAIN/DRAIN_UNCERTAIN retain the hierarchy specification's evidence requirements.

## Verification and limits

Required focused cases: lost dispatch gossip with committed capacity; elapsed local deadlines;
failed deletion and controller restart; continuing inventory with observed absence and uncertain
create; busy-source refusal before reservation; failed/stalled reconciliation lanes; late readiness;
implicit target updates; activation changes during orphan sweep. Keep existing stale-authority,
voter retirement, drain acknowledgement and source-binding tests.

Run deployment/slice/node suites and live Forge formation, core recovery, worker formation,
capacity fallback and movement tests. Record actual results in the PR. Small in-JVM tests do not
establish 10K-node, WAN, cloud throughput, provider quota, or region-failure recovery bounds.
The implementation still takes KV snapshots for planning; indexing and physical-scale validation
remain necessary before claiming a 10K-node operating envelope. Related scale gates: #367, #368,
#599. Predictive supervision (#1251), provider batch fan-out (#1328), role authentication (#747),
and gossip-key admission (#1200) are separate work and are not closed by this lifecycle change.
