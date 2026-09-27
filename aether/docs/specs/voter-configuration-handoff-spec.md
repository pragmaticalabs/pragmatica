# Voter configuration and Rabia §4 reconfiguration

Scope: normative contract for voter reconfiguration and genesis (#1526). Present-tense requirements
describe the implementation on the `fix/s28-rabia-section4-reconfiguration` line. The tests named
below run real engines over a simulated, reordering network in one JVM; none is a live multi-node run,
so no claim here carries a `[verified:]` tag: on a live cluster these behaviours are
[design intent — unverified].

Status: supersedes #1390's certified checkpoint handoff (barrier freeze, checkpoint transfer,
old-majority certificates and installation acknowledgements), which the owner dropped in session 28
because it wedges once cores run in-memory Rabia: a handoff that needed a transfer from a voter lost
after the barrier could never complete (#1526). This file keeps its name so existing
links resolve.

## Authority

Desired core capacity is a provisioning target. A voter configuration is an immutable pair of a
monotonic epoch and a complete CORE node-ID roster. Its majority and fault thresholds derive from
that roster, never from local reachability or a desired count. Unknown IDs and workers cannot vote.
Ballots (proposals, round votes, round repair requests) count only from installed voters at the
current epoch.

## Genesis

Genesis epoch zero is the complete configured roster. `cluster.genesis_voters` is optional:

- When present it is authoritative. A malformed value (a blank id) fails boot loudly
  (`MALFORMED_GENESIS_VOTERS`). (pinned in-JVM by `VoterGenesisResolutionTest.java`)
- When absent, a voter configuration persisted with a `[backup]` snapshot is used; otherwise the
  roster is the known core membership, complete only when it names exactly the configured core count.
- An incomplete roster never aborts assembly. The engine defers genesis: it neither votes nor adopts
  state, holds early ballots (bounded), and the node re-reads its discovered core membership every
  two seconds until the roster is complete, then installs it once. The wait is logged at INFO with
  the discovered and required counts. (pinned in-JVM by `VoterGenesisResolutionTest.java`)
  (pinned in-JVM by `RabiaReorderedDeliveryTest.java#genesisWaitsForTheLateCoreAndFormsTheClusterWhenItArrives`)
- Agreement is enforced at adoption: a sync response carrying a different roster at the same epoch
  is refused and reported at WARN, so a node never adopts state from a second electorate.
  (pinned in-JVM by `RabiaVoterReconfigurationTest.java#syncResponse_withADifferentRosterAtTheSameEpoch_isRefused`)

[limit: discovery-genesis] Without `cluster.genesis_voters`, two nodes that discover different sets
of exactly the configured count would each resolve a genesis roster. The same-epoch adoption refusal
keeps each from adopting the other's state, but two disjoint majorities are not excluded by this
mechanism. Configure `cluster.genesis_voters` wherever more cores than the configured count can be
discovered at first boot. [design intent — unverified]

Replacements are provisioned with `cluster.genesis_voters` rendered from the leader's bootstrap
roster; they boot as observers of it and learn later epochs from the log.

## Reconfiguration (Rabia §4)

The Rabia paper treats add-replica and remove-replica as special commands: Weak-MVC agrees the slot
for the command, every replica eventually learns it, and in the next slot the new configuration
joins or leaves the protocol. Aether implements exactly that.

1. A controller calls `reconfigure(target)` on an active voter with a complete roster of admitted
   CORE identities. The request becomes a `ReconfigurationCommand(baseEpoch, target)` carried
   out-of-batch on the voter's proposals (the `reconfiguration` field of `Propose` and `Decision`).
   `baseEpoch` is the epoch the target was computed from.
2. A single add, a single remove, and a one-slot replacement are all expressible. A change is admitted
   only while the voters it retains from the current roster are a majority of the target
   (`INSUFFICIENT_RETAINED_VOTERS` otherwise); those retained voters carry the applied prefix and keep
   the new configuration deciding before any added member has caught up.
3. Weak-MVC agrees slot R carrying the command. Applying the V1 Decision at R installs
   `VoterConfiguration(baseEpoch + 1, target)` in memory, notifies configuration listeners, and moves
   the replica to slot R+1, the first slot the new roster governs. Slots are strictly sequential — a
   replica votes only in its current slot — so no replica casts a ballot for R+1 before applying R,
   and every replica, including a lagging one, an observer, and a removed voter, switches through the
   same apply path. [mechanism: sequential slots; a ballot is cast only for the replica's current phase]
   (pinned in-JVM by `RabiaReorderedDeliveryTest.java#agreedChangeGovernsFromTheNextSlotOnEveryReplicaIncludingALateOne`)
4. A decided command whose base epoch is not the current epoch applies as a deterministic no-op and
   still consumes its slot. Stale and competing commands can therefore never apply on top of a roster
   they did not see. Competing commands of one base epoch converge on a deterministic preference, so
   voters propose identical values. (pinned in-JVM by `RabiaVoterReconfigurationTest.java#staleCommand_decidedWithOutdatedBaseEpoch_appliesAsNoOp`)
5. The common coin is seeded by slot plus epoch (§4); at epoch 0 the seed is unchanged.
6. The caller's promise completes when its replica applies the agreed command, and fails with
   `SUPERSEDED` once a different change is applied first.

Why a one-slot replacement rather than remove-then-add: at n=3, `{A,B,C} → {A,B,D}` agreed in one
slot never leaves a 2-voter configuration, while remove-then-add passes through `{A,B}`, where losing
either voter before the add is agreed wedges the cluster. Add-then-remove passes through `{A,B,C,D}`
with C dead, which tolerates no further failure. The single-slot swap keeps the roster at three at
every slot boundary.

## Slot boundary and message rules

- A ballot of an OLDER epoch for a slot at or after R+1 is refused; so is any ballot from a removed
  voter. (pinned in-JVM by `RabiaVoterReconfigurationTest.java#appliedChange_governsFromNextSlot_andRefusesOldEpochBallots`)
  (pinned in-JVM by `RabiaVoterReconfigurationTest.java#removedVoter_ballotAtCurrentEpoch_isNotCounted`)
- A ballot of a NEWER epoch belongs to a slot after a change this replica has not applied. It is held
  (bounded, oldest dropped), re-delivered once the epoch advances, and the sender is asked to repair
  this replica's current slot. (pinned in-JVM by `RabiaVoterReconfigurationTest.java#newerEpochBallot_isHeldUntilTheChangeIsApplied_andRepairIsRequested`)
- A ballot for a slot the receiver already completed is answered with that slot's Decision (or a
  snapshot when its data is gone) for any installed voter or admitted consensus member, whatever its
  epoch — this is how a lagging or removed voter learns R.
- A Decision for the current slot must carry the epoch that governs the slot; any other is refused.
- A removed-but-alive voter's later ballots carry the old epoch and are dropped; it learns its removal
  from the Decision at R and continues as an observer.

## Catch-up of added voters

A fresh-identity replacement boots observing, adopts a snapshot (with its voter configuration) from
core peers, applies relayed Decisions, and starts voting once it has applied R — or adopted a snapshot
at R+1 or later — with itself in the roster, through the same #1212 participation gate as any
activation.

Adoption of a NEWER epoch from synchronization needs one LIVE responder that belongs to the
configuration it claims; COLD responders never authorize it. [mechanism: under crash faults a live
responder's claimed configuration was applied from the log and its state is a prefix of the one log;
a replica still at an older epoch has not applied R, so it has cast no ballot in any slot at or after
R+1, and it joins every later slot as a fresh participant, which Weak-MVC tolerates.] Requiring a
response quorum here would wedge exactly the #1526 case: a replacement whose only live peer in the new
roster is the voter that decided R. Same-epoch adoption keeps its existing quorum rules.

## Retirement

An applied change is not permission to terminate removed or dead instances by itself. The engine
reports the installed roster as retirement-safe (`retirementSafeVoters`) only when no reconfiguration
is requested or awaiting its slot and every member added by the last applied change has been observed
voting past R. The same value gates `CoreVoterReconciler`: it requests a new target only while the
installed roster is settled. Catch-up evidence arrives with traffic, so a quiet cluster defers the next
change until the added member votes. (pinned in-JVM by `RabiaVoterReconfigurationTest.java#reconfigure_completesWhenApplied_andRetirementWaitsForAddedMemberCatchUp`)

## Operator surface

The node status `voterReconfiguration` field reports the stage (`UNAVAILABLE`, `GENESIS_PENDING`,
`STABLE`, `REQUESTED`, `CATCHING_UP`), the installed epoch and voters, the requested target, the
effective slot R+1 of the last change this node applied, the added members not yet observed voting
past R, and a failure description. Recovery action for `CATCHING_UP` that does not clear: confirm the
added core is reachable and synchronizing; the stage clears on its first ballot past R.

## Bounded state transfer

[limit: core-snapshot-frame] Added voters catch up through synchronization, whose response carries the
whole checkpoint. The frame ceiling is 32 MiB; consensus reserves 64 KiB and checks the serialized
envelope before transmission. `reconfigure` refuses a change that adds members with
`STATE_TRANSFER_TOO_LARGE` when the current state cannot be encoded in one sync response. An oversized
response emits `SyncRejected` and a typed local diagnostic; a rejection from one source does not cancel
synchronization with other sources. Chunked core snapshot transfer is future work.

Pending batches are uncommitted recovery hints. Adoption of a snapshot that advances the frontier
retains only batches explicitly pending in the adopted state; other pre-adoption pending entries fail
with `SnapshotOutcomeUnknown`, which means the outcome is ambiguous, not that execution failed.

## Integration contracts

- Node assembly supplies the complete genesis roster before the engine votes, or defers genesis until
  it is complete; discovery seeds are separate.
- The engine publishes installed voter configurations to topology/quorum consumers.
- ClusterConfig coreCount drives provisioning only. It must not call a quorum-size setter.
- The CTM selects concrete admitted CORE IDs and stages new nodes as observers; the leader's
  `CoreVoterReconciler` requests the change and retires old capacity only after the roster settles.
- Local health controls transport/recovery decisions; it cannot rewrite voting membership.

## Leadership and cleanup authority

Installed-voter changes also update leader eligibility. Expected election members, transport-derived
candidate pools, and committed/KV leader adoption are restricted to the installed roster. Health
can suppress reachability or trigger recovery, but cannot substitute a new electorate.

## Excluded core leadership and cleanup authority

A verified installed configuration that excludes the local CORE identity moves its leader FSM into
passive observation of that configuration's members. Rabia's observer state and leader observation
are separate: exclusion must not leave the leader FSM in `QuorumLost`, where committed leader replay
would be rejected. Subsequent local quorum notifications cannot invalidate passive observation or
start an election. The excluded core may therefore authenticate cleanup instructions from its
observed committed leader while awaiting retirement; admission as CORE alone is not cleanup authority.

Committed leader adoption requires an installed-member identity and a positive, non-regressing
sequence. Quorum loss may clear the current leader before configuration installation. Retain the last
adopted identity solely to accept replay of that same identity at the same sequence; a different
identity at that sequence remains rejected. This retained identity does not independently authorize
cleanup: the leader must be adopted again and belong to the installed electorate.

Snapshot notification replay is a delta and can omit an unchanged `LeaderValue`. After a verified
restore has installed the checkpoint and its voter configuration, updated engine participation and
replayed the KV delta, the production state-restored listener explicitly reads the committed
`LeaderValue` and refreshes leader observation with its actual stored sequence. It neither invents a
sequence nor relaxes installed-member or sequence fences. This refresh applies to excluded observers
and surviving voters; an absent value supplies no leadership authority. `CommittedLeaderRefreshTest`
covers an unchanged second replay and an absent committed leader.

If a later verified configuration re-admits the local identity, passive observation transitions
through `QuorumWaiting`. Configuration installation precedes Rabia becoming active, so membership
alone must not begin an election. The existing consensus-readiness gate and subsequent committed-KV
synchronization gate remain in force. Shutdown remains terminal.

Regression coverage in `LeaderAdoptionSequenceGateTest` verifies exclusion after quorum loss,
committed replay, rejection of unauthorized and conflicting equal-sequence leaders, and readmission
without premature election.

## Passive worker routing after a reconfiguration

A worker's configured bootstrap electorate is not its current routing directory. A verified scoped
metadata DIRECTORY update invokes `RabiaNode.installPassiveCoreDirectory` before replaying the
projected committed `LeaderValue`. The API is available only in passive-client mode, rejects an empty
or duplicate directory and rejects the worker's own identity. It changes leader-discovery eligibility
only: it cannot update Rabia voter authority, quorum size, or historical voter identities.

The leader FSM has an explicit passive observation state. It accepts eligible committed leaders with
positive, non-regressing commit sequences, ignores local quorum/election triggers, and owns no
election or leader-lease timer. This allows a fresh worker to discover a current leader even after
all original bootstrap voters have retired. An old core-quorum `Dormant` state must not suppress a
verified projected leader on a worker. Shutdown remains terminal.

Consensus payload fanout targets installed voters and connected admitted CORE candidates. Workers
receive neither proposals/ballots nor global batches, Decisions, or snapshots. A full-state `SyncRequest` is served only to admitted immutable CORE identities or explicitly trusted
CORE bootstrap transfer peers, including staged replacement candidates. Worker bootstrap and repair use the scoped metadata channel.


## Connection identity and bootstrap trust

The QUIC inbound boundary binds every `ProtocolMessage.sender()` to the authenticated connection
peer before routing. The only protocol relay exception is Rabia proposal evidence: an installed
voter may repeat another voter's proposal. The engine still validates its original sender, epoch,
slot, and immutable proposal rules. Worker transport peers cannot exercise this exception. The
higher-layer inbound policy adds Aether-specific authority checks and cannot bypass this binding.
Messages without the protocol sender interface require explicit application-level origin checks.
This is a crash-fault trust boundary; certificate-to-role cryptographic binding remains a separate
security property and is not claimed by this mechanism.

Administratively configured CORE discovery seeds may supply state-transfer evidence without gaining
candidate admission or votes. `isStateTransferPeer` is separate from `isConsensusMember`; only sync
request fanout and service use the extra seed trust. A response must still carry a voter configuration whose
roster contains the responder: the node's own configuration, or one of a newer epoch. A different
roster at the same epoch is refused (genesis disagreement). An arbitrary seed roster or local health
view never becomes an electorate. Replacement bootstrap must reach at least one live CORE of the
current roster after original genesis voters have retired.

Participation mode is sealed synchronously when `start()` is called or the first protocol task is
submitted. `configurePassiveClient` returns a typed failure after that boundary, including before a
queued startup task actually runs. It cannot turn an active CORE into a worker. Fresh worker
assembly must compose successful passive configuration before transport startup.

Directed hierarchy connections retain a single deterministic initiator. On CORE↔WORKER/SPOT edges,
the worker initiates regardless of NodeId ordering; same-tier edges retain the NodeId tie-break.
Both endpoints use immutable role information, independently of health. The transport's configurable
initiator policy applies to explicit connections and both reconcilers, while the existing delayed
recovery override and duplicate-connection protections remain in force. A worker must not wait for
an unassigned community's core to dial it before requesting its bootstrap directory.
