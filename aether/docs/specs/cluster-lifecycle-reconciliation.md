# Cluster lifecycle reconciliation

Implementation contract for #1404, on the hierarchy foundation (#1386, #1379, #1390).
Breaking wire changes are permitted before GA; no migration is provided. The one new enum phase
(`PlacementOperationPhase.READINESS_DELAYED`) precedes the required final UNKNOWN constant; deploy
matching artifacts across the cluster.

Three owner rulings of 2026-10-18 bound this contract: the core capacity accounting below (D1), the
retirement section (D2) and the worker section (D3). What they leave out is stated where it would
otherwise be assumed.

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
core allocations which have not entered the installed electorate. Use a set union, not a sum.
Already installed but departed voters must not mask a replacement deficit.

A committed allocation counts on these terms (D1):

- A DISPATCHED allocation (the create may have happened) ALWAYS counts. The local replacement
  deadline may expire the local observation cache, but it cannot release a committed uncertain
  allocation, so no second create is dispatched while a create is uncertain.
- An OBSERVED allocation (the provider listed the instance) counts only while its node is within the
  join grace (#1783). Past the grace an unjoined node is no longer capacity and the deficit reappears,
  as it does for a confirmed in-flight entry. The grace runs from the id's mint time when the id
  carries one, so a new leader neither restarts nor shortens it; otherwise from the leader's first
  sight of the allocation. An allocation the in-flight map still tracks is governed by that entry alone.

The provisioning decision reports AWAITING_DURABLE_CAPACITY when durable allocations cover an
otherwise observed deficit. Metrics gossip remains useful for prompt observations and still seeds a new
leader's in-flight map; it is no longer the only recovery source.

DISPATCHED means the create might have happened. An empty list, timeout, or deletion request
cannot release it. A definitive provider capacity refusal first records RELEASED as durable no-create evidence in a
leader-guarded reservation-only transaction. This does not decrement the fleet counter. An active
movement consumes that evidence into its availability/COMPLETE transaction before periodic
inventory releases the reservation and decrements the ledger. A conflicting counter release
retains the evidence for subsequent passes and leader restart; it cannot overwrite the original
provider refusal with an ambiguous-create diagnosis. An identity with a retained RELEASED reservation cannot be reused.
A refusal before this evidence reaches consensus remains conservatively uncertain if authority
is lost: no process can promise durable delivery of an uncommitted provider response.
A matching provider observation advances it to OBSERVED. Unknown create outcomes without matching observations
remain reserved; this change does not invent provider evidence or retry them under new identities.

## Retirement

There is no RETIRING reservation phase and no periodic retry of a failed provider deletion (D2).
A failed or unconfirmed deletion is the confirmed-reap chain's: bounded retries, then an operator
event and an `UnconfirmedTermination` mark that a later leader inherits and re-checks at a low
bounded rate. Deletion acknowledgement does not release capacity; see the next section.

Automatic departed-core deletion respects disabled auto-heal (#1038): `terminateDeparted` consults
`isAutoHealEnabled()`, and both automatic deleters (the departed reap and the orphan sweep) pass through
it. Operator-requested retirement through the community reconciler is not gated, and this is not a
cluster-wide freeze API.

The lifecycle sweep is the leader's periodic provider contact, and it is rate-bounded (ruling 317923624:
never unbounded per-orphan calls, a low bounded re-check rate). Activation replays orphans at once, as before, and starts the interval; thereafter a sweep runs at most
once per five provisioning windows (five minutes at the default), the interval the mark re-check uses. A sweep
reconciles source inventory (one listing per managed source) and runs the orphan sweep: two inventory reads
a provisioning window apart, and only an instance unprotected at both is terminated. The number of calls
per sweep does not depend on the number of orphans, and a node already marked unconfirmed is left to the
mark re-check instead of starting another confirmed-reap chain. Only one orphan sweep runs per activation;
a prior activation cannot suppress its successor or clear its successor's guard. The poll that drives the
sweep ticks every ten seconds; the sweep is not run per tick.

Provider deletion acknowledgement does not release capacity. Source-bound absence confirmation
releases an OBSERVED allocation atomically with the ledger decrement and removal of its stale
NodePlacement. Membership departure alone does not establish provider absence. A DISPATCHED allocation
is not released by an empty deletion confirmation.

## Continuing inventory

Reconcile configured managed sources periodically. Adopt existing resources, and for allocations
missing from the complete source listing obtain a node-specific source-bound observation.
Previously observed absent resources can then release their allocations. DISPATCHED allocations
remain reserved even if both observations are empty. Failed or partial listings establish no absence. Confirm at most 32 missing observed allocations per source per pass, rotating the starting position so failures cannot starve later entries.

Initial admission still requires complete configured-source inventory, kept per source: a process
lists every source of the committed configuration it has not listed yet before an admission, and
forgets a source that leaves the configuration. A termination needs only a ledger inventoried at
all, never the per-source inventory. Creates defer during this inventory pause (bounded by the default
replacement ceiling of ten minutes). Reservation commits witness the configuration the admission
inventoried under, so a configuration committed while the inventory was being taken refuses the admission.
Out-of-band cloud administration remains outside transactional fleet-cap enforcement.

## Worker policies and scheduling

Worker lifecycle ownership is unchanged by this contract (D3). Configurations without explicit community
policies are not turned into `<source>-w-0` communities, and the worker actuator keeps the worker
growth, shrink and retirement it has for them. Who owns those for implicit topologies is decided in
the deployment-FSM phase 3 against #2042, C9 and C11. For the same reason a DISSOLVED community stays
terminal: it is the fact an operator waits for before removing a placement policy, and raising its
target does not reopen it.

Independent community, worker-entry and source-inventory work runs in four lanes. An item has a bounded
observation deadline, reports failure, and leaves its durable state for the next pass; one failure
cannot short-circuit the remaining identities. A stalled item still delays later identities in
its lane by up to the thirty-second observation deadline. Each item reads fresh state. Create admission is
limited to four active source bindings and one call per binding in a process. Existing global
committed capacity admission protects allocations across leadership change. These process-local
limits do not claim cancellation of an already-issued provider request or global provider rate quotas.

Typed pre-dispatch refusals (source admission, incomplete inventory, unavailable authority or seed
peers, and invalid preparation) leave the community identity reserved for retry and do not claim
an uncertain provider create. A core replacement that is refused this way is deferred to the
reconciler and is still counted as a provisioning failure (logged, last failure recorded, circuit
after three consecutive), so a permanent refusal does not retry invisibly. Once a create might
have been dispatched, its durable uncertainty cannot be downgraded to a pre-dispatch refusal.
A create whose reservation committed but whose dispatch was abandoned before the provider call
(leadership lost in between) provably did not happen: its DISPATCHED reservation is recorded as
no-create evidence and released the next time the instance that abandoned it leads. If a different
instance leads instead, it cannot know the create did not happen and keeps counting it.

READINESS_DELAYED distinguishes missed readiness deadlines from changed identity/policy
preconditions. It does not depend on how a community came to be, so it applies to explicit policies. It preserves identity and allocation, emits the existing operation escalation, and
resumes when the same target becomes ready and evacuation is safe. The previous worker remains
excluded from allocation throughout this phase. A ready target with unsafe evacuation also
escalates into READINESS_DELAYED after the drain deadline; later safety evidence resumes it.
A source binding outage preserves the exact interrupted phase and reports its blocking reason;
restoring the recorded binding resumes that phase without duplicating create or retirement.
BLOCKED operations can recover through a matching target observation, still subject to readiness,
assignment, evacuation and drain-acknowledgement guards. CREATE_UNCERTAIN can recover through
matching inventory or committed RELEASED evidence; DRAIN_UNCERTAIN requires its matching ack.
[limit: operator-resolution] There is no force-clear/cancel API for genuinely ambiguous creates
or missing drain acknowledgements. Retain the resource/operation and investigate source inventory
or worker drain delivery; neither policy edits nor elapsed time authorize destructive resolution.
[limit: provider-mapping] Definitive cloud-capacity fallback is mapped for AWS and Hetzner.
GCP and Azure generic provision failures remain uncertain; Azure allocation failure may leave a
resource, so code must not infer absence from a generic error. Expanding mappings requires provider-
specific no-create evidence and tests.

## Verification and limits

Required focused cases: lost dispatch gossip with committed capacity; elapsed local deadlines;
failed deletion and controller restart; continuing inventory with observed absence and uncertain
create; busy-source refusal before reservation; failed/stalled reconciliation lanes; late readiness;
the sweep cadence; activation changes during orphan sweep; disabled auto-heal holding automatic deletion. Keep existing stale-authority,
voter retirement, drain acknowledgement and source-binding tests.

Run deployment/slice/node suites and live Forge formation, core recovery, worker formation,
capacity fallback and movement tests. Record actual results in the PR. Small in-JVM tests do not
establish 10K-node, WAN, cloud throughput, provider quota, or region-failure recovery bounds.
The implementation still takes KV snapshots for planning; indexing and physical-scale validation
remain necessary before claiming a 10K-node operating envelope. Related scale gates: #367, #368,
#599. Predictive supervision (#1251), provider batch fan-out (#1328), role authentication (#747),
and gossip-key admission (#1200) are separate work and are not closed by this lifecycle change.
