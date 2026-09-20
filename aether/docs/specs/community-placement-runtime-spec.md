# Community placement, movement and capacity admission

Scope: normative target for runtime PR #1390; present-tense requirements do not assert that
`release-1.0.0-rc4` implements them. Baseline observations are explicitly labelled below.

This specification complements `hierarchical-cluster-contract-spec.md`. It defines the
core-authoritative implementation boundary; it does not delegate new actuation authority
to disconnected communities. Node roles remain immutable.

## Desired placement

A community is a stable coordination identity, independent of provider, account, region or
zone. A source selects a configured provider/account/region; a location additionally selects
an optional provider-observed zone. There is no limit on the number of zones.

```toml
[community.eu-shopping]
target_size = 20

[community.eu-shopping.placement.primary]
source = "eu-west"
zone = "west-a"
minimum = 5
weight = 2

[community.eu-shopping.placement.secondary]
source = "eu-central"
zone = "central-b"
minimum = 3
weight = 1
```

Targets and weights must be positive; minimums must be nonnegative and sum to no more than
the target. Sources must declare workers. Explicit zones must belong to the source's
configured zone set. Duplicate locations, and unzoned locations overlapping a zoned
location of the same source, are rejected.

Minimums reserve the first slots. Remaining slots use weighted largest remainders with
source/zone lexical tie breaking. The result sums exactly to the community target.

When explicit communities exist, their targets are the worker-capacity intent. Legacy
source worker counts do not independently create or destroy workers, including sources
no longer present in a community's desired placement. Core and spot capacity retain their
own role-specific control. Reactive application scaling continues to update slice demand;
this specification does not introduce a competing predictive controller.

## Facts and assignment

`NodePlacementKey(node)` stores source, provider instance identity and observed zone. The
zone comes from the provider's instance description/readiness result, never copied from
requested placement or caller labels. Unknown observation does not satisfy a zoned policy.

A provider observation is not membership admission. Only a worker admitted through the
membership protocol may receive activation. A pending placement reservation identifies the
community for its particular replacement; it takes precedence over ordinary load-balanced
assignment. Existing workers keep their community while desired locations change.

The source binding used for movement is resolved by `NodeLifecycleManager.sourceBinding`.
Its digest includes the actual protected provider/account credentials and region/zone
configuration. Raw credentials must never enter events, failure messages or operation
records. A changed binding blocks the operation; source name alone is insufficient proof
that a resumed effect still addresses the same account.

## Atomic authority primitive

`LeaderTransaction` carries a correlation ID, the expected committed `LeaderValue`, read
witnesses and mutations with exact previous values. The KV applier verifies the complete
read/write set and all existing epoch/version fences before changing anything. Duplicate
mutation keys and attempts to mutate `LeaderKey` are rejected. No notification is emitted
on refusal. Accepted transactions install every value before routing notifications.

The result is `TransactionResult(transactionId, accepted)`. Submitters select their own ID
from a merged batch response. They must not infer success from an unqualified successful
consensus promise. A single write also uses a caller-correlated `LeaderTransaction` containing one mutation.

Ordinary Put/Remove cannot modify leader-authorized records. An authorized transaction may
remove a non-owner-fenced reservation with an exact witness; owner-fenced authority uses
retirement tombstones to retain its epoch high-water mark.

## Movement protocol

There is at most one active operation per community. Its committed record contains:

- A unique operation ID and stable replacement node ID.
- Target source, optional zone and resolved source binding.
- Optional previous worker and its original source.
- Phase, issuer leader, start time, phase-change time and explanation.

The normal sequence is:

1. `RESERVED`: commit intent, bounded overlap and the read witnesses used for selection.
2. `CREATE_REQUESTED`: commit before calling the provider once.
3. `AWAITING_READY`: observe the actual destination, admit it to the intended community,
   and require current readiness. Exclude the old worker from new workload placement while
   existing work continues; make replacement workload instances active before unloading old
   instances.
4. `DRAIN_REQUESTED`: require the retirement-safety predicate, then commit and send a typed
   drain request with operation ID. The worker validates current core leader, issuer,
   previous-node identity and committed phase.
5. `DRAINED`: after admission closes, in-flight work quiesces and required departure transfer
   succeeds, the worker sends `DrainCompleted`. The core commits completion and replies
   `DrainAccepted`. Successful graceful shutdown waits for that acknowledgement.
6. `TERMINATING`: commit before idempotent provider termination in the previous source.
7. `COMPLETE`: require provider-confirmed absence, release capacity and remove obsolete
   activation/placement facts. A termination request acknowledgement alone is insufficient.

Growth has no previous worker and completes after destination readiness. Reduction retains
a ready worker and uses the same retirement/drain/absence protocol for the surplus worker.

A new leader first adopts the operation through a guarded transaction. Adoption preserves
the phase deadline. An old leader callback cannot mint a new phase or initiate the next
effect after losing committed authority.

`CREATE_UNCERTAIN` retains the stable identity and capacity reservation. An absent response,
or an empty inventory result for an unconfirmed create, does not authorize another create.
A subsequently observed matching destination can resume readiness checking.

`DRAIN_UNCERTAIN` retains the operation when acknowledgement expires. Membership removal is
not a drain acknowledgement. A late authenticated completion can still be committed;
otherwise operator intervention is required. `BLOCKED` covers changed bindings and invalid
preconditions. Unknown phase values never actuate. Escalations go to the cluster event
stream with community, operation, target and phase identifiers.

## Shared capacity admission

Every production `NodeLifecycleManager.provisionNode` passes through
`CapacityControlledLifecycle`, including core replacement, legacy scaling and placement.
A small versioned ledger counts admitted managed-instance allocations; per-node records
retain source and dispatch/observation state. One guarded transaction changes the ledger
and reservation together. A stable node ID is required before the provider boundary.

Before initial admission, inventory all configured managed sources and adopt existing
instances in bounded batches. An incomplete or failed inventory is not zero capacity.
Externally managed SSH capacity is not a provider allocation in this ledger.

Commit `DISPATCHED` before create. Ambiguous failures retain capacity across leader changes.
Explicit provider capacity rejection, which proves no create occurred, can release that
attempt for a bounded zone fallback. Reusing an existing unresolved identity is refused.
A successful provider observation changes the reservation to `OBSERVED`.

Release an observed allocation only after source-bound node inventory confirms absence.
Do not release an unconfirmed create merely because a list is empty. Released records can
be removed atomically with the count decrement, preventing unbounded terminal-record growth.
The ledger does not claim to prevent out-of-band cloud administration; provider identity and
inventory correctness remain boundary assumptions.

## Persistence and validation

Operation and capacity records belong to consensus snapshots and durable consensus state.
They are runtime resource bindings, excluded from the human configuration export alongside
other runtime provisioning records. A configuration export is not a replacement for the
consensus recovery checkpoint.

Required validation includes real KV application, not only reducer tests:

- A conflict on the final mutation leaves every earlier mutation untouched.
- Competing reservations cannot both consume the last shared slot.
- An ambiguous create survives leader replacement without another provider request.
- Old-leader acknowledgements and wrong-node drain completions cannot advance movement.
- Destination readiness alone cannot retire a worker with live workload or failed data transfer.
- Drain timeout does not imply completion, and deletion acknowledgement does not release
  capacity until absence is confirmed.
- A changed source binding cannot redirect an old operation into another account.
- Full node codec registration and strict system-tag checks cover every new wire value.

The implementation must be exercised under leader loss at each effect boundary and under
multi-source provider failures. Passing unit tests alone is not evidence of a 10,000-node
operational capacity limit.

## Bounded community health observations

Core health probing targets admitted core nodes and current committed governors. A governor probes its own community; ordinary workers retain community SWIM and bounded core uplinks. A core never uses a persisted governor roster as a heartbeat.

Each core challenges each governor using a random process-incarnation challenge token and monotonic request sequence. The token is
matched for equality, never ordered; restart invalidates outstanding challenges. A response must match the outstanding challenge, authenticated sender, committed governor term, governor assignment and every reported member assignment. Duplicate members, excessive report size, stale terms and expired challenges are rejected. One pending challenge per community bounds request state. A new core process cannot accept an old response.

Governor observations come from direct worker pongs, with durable producer-process incarnation/sequence
and timestamp freshness checks as specified in metrics-distribution-spec. This replay identity is
separate from the pong’s SWIM membership incarnation carried in MemberHealth and membership evidence. Relayed metrics and cached SWIM ALIVE labels cannot refresh this evidence. Reported age includes the producer observation age and elapsed governor-local time. The core adds the challenge round trip and its own elapsed receipt time, using monotonic clocks for elapsed durations. Readiness requires both fresh positive reachability and READY lifecycle evidence. A governor records its own lifecycle locally.

Accepted evidence retains provenance `(community, governor, governor term, member incarnation)` when supplied to the membership integration. Missing, stale or incomplete reports make workers unavailable for placement; they do not declare worker death, authorize instance termination or release capacity reservations. A governor change immediately invalidates prior positive evidence. Initial governor nomination requires a fresh direct candidate observation and committed worker assignment, avoiding a circular dependency on a report from a governor that has not yet been appointed.

### Bootstrap and governor reporting-path recovery

A trusted provisioning reservation must record immutable intended role before provider dispatch. For local Ember operations, the controller records distinct core and worker admissions before creating the instance. A peer-supplied role is not admission authority. An admitted, unassigned worker is probed directly with a bounded admission queue; a fresh advancing SYNCING or READY pong supplies explicitly named `WorkerAdmissionHealthy` evidence. This promotes the worker through the normal worker join channel and permits a committed activation assignment without retaining the worker in every core's SWIM probe set. READY is not required at this stage because readiness depends on activation. The pending/accepted evidence budget is bounded, uses a rotating cursor, rejects replay and releases entries after assignment or loss of admission authority.

After assignment, metadata projection may make a worker READY while its community is still FORMING and has no governor. The active core leader can therefore obtain direct READY candidate proof and grant initial governor authority without a circular dependency on governor reports.

Local worker election remains a nomination mechanism. It is not responsible for recovering an incumbent that remains alive inside the community while losing its core uplink. The core leader independently tracks current-term report freshness. After one full configured community-absence window without a validated report (including a fresh grace period after leader or term change), it probes alternative assigned candidates in a bounded rotation. Only fresh direct READY proof and a known network address permit a core-initiated grant. The existing atomic leader/prior-authority/community/directive guards commit a strictly newer term. Rejected or ambiguous grants are retried by rereading committed state. No worker request is synthesized, and no worker is declared dead or released from its capacity reservation by this process.

Community viability counts explicit assigned identities with fresh positive liveness evidence. Persisted member counts, incomplete rosters and old-term reports cannot keep an unreachable community ACTIVE. Report expiry changes availability, not ownership or instance existence.
