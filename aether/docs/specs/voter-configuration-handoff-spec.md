# Voter configuration and checkpoint handoff

Status: implementation contract, rc4 hierarchy batch.

## Authority

Desired core capacity is a provisioning target. A voter configuration is an immutable pair of
monotonic epoch and complete CORE node-ID roster. Its majority and fault thresholds derive from
that roster, never local reachability or a desired count. Unknown IDs and workers cannot vote.

Genesis epoch zero requires an identical authoritative roster at all initial voters. Discovery
seeds and a desired count do not establish an electorate. New CORE instances enter as observing
candidates anchored to the genesis roster supplied by provisioning; this is a participation
state, not a change of immutable node role.

## Reconfiguration process

1. A controller asks the current engine to reconfigure to concrete, admitted CORE identities.
2. The request becomes a special consensus proposal for epoch E+1 in an ordinary log slot under E.
   Competing requests participate in the same agreement protocol as application proposals. A
   proposal is immutable within its slot; no out-of-band setter changes the electorate.
3. A V1 configuration Decision at slot R establishes the barrier R+1. Every old voter that applies
   it stops voting in E. No old-epoch slot R+1 can be committed: sequential execution requires each
   voter to apply the agreed configuration command before entering that slot.
4. Each old voter captures the state-machine checkpoint at R+1 and the selected successor roster.
   It persists the barrier before advertising handoff. Handoff is sent to the union of old and new
   voters, independently of ordinary consensus broadcast audiences.
5. A recipient installs only after an old-roster majority reports the same successor epoch,
   membership, boundary, and snapshot contents. Pending application requests are carried as
   uncommitted work; differing pending sets do not change the checkpoint identity.
6. The installed checkpoint and successor authority are persisted before participation in E+1.
   A new voter acknowledges installation. Removed voters retain checkpoint-service capability but
   cannot vote. The controller's reconfiguration promise completes only on a successor majority
   of installation acknowledgments.
7. Removed instances may be deprovisioned only after successful completion. Failure or uncertainty
   preserves the old instances and pending operation for retry. Repeating the same operation is
   idempotent; a contradictory request is a distinct consensus proposal.

The [Rabia paper's reconfiguration discussion](https://ceres.cs.umd.edu/818/papers/rabiaRandomization.pdf)
uses agreed membership commands that take effect at the following log slot. The checkpoint and
acknowledgment steps above make the joining and deprovisioning boundaries explicit for Aether.

## Message and restart rules

All proposals, binary ballots, Decisions, and round-repair requests identify their voter epoch.
Old-epoch messages cannot affect a newer instance. Worker observations and application request
forwarding are not quorum evidence. Proposals and ballots target the fixed current voter set;
Decision delivery to existing observers remains until scoped state distribution replaces it.

Synchronization carries authority with snapshot state. A checkpoint frontier is the next unapplied
slot, not a binary-round number. An installed epoch cannot regress on restart or snapshot adoption.
A persisted handoff is re-advertised after restart. A recipient that has not acquired sufficient
handoff evidence remains inactive; a timeout does not authorize activation.

Persistence failure before an authority transition leaves the engine unable to vote across that
transition. Tests must inject failures at both old-barrier persistence and new-checkpoint persistence.
Loss of all durable consensus history is not equivalent to a fresh node; bootstrap and join must
remain distinct operations.

## Integration contracts

- Initial node assembly supplies the complete genesis roster before engine start; discovery seeds are separate.
- The engine publishes installed voter configurations to topology/quorum consumers.
- ClusterConfig coreCount drives provisioning only. It must not call a quorum-size setter.
- CTM selects concrete admitted CORE IDs, stages new nodes as observers, and waits for the
  reconfiguration promise before removing old capacity.
- Local health controls transport/recovery decisions; it cannot rewrite voting membership.
- Operator-visible operation state distinguishes requested, agreed barrier, checkpoint collection,
  installed quorum, complete, and uncertain/failed. No cleanup is implied by an acknowledgment timeout.

## Acceptance

Test grow, shrink, and identity replacement; distinct local health views; old messages after handoff;
competing reconfiguration proposals; old and new quorum intersection at the barrier; absent joining
nodes; checkpoint content disagreement; worker/self spoofed responses; duplicate requests; and restart
at each persisted boundary. Assert application prefixes continuously, not only after convergence.

## Installation proof and retirement

Local installation and resource retirement are separate states. Each voter persists its new
checkpoint before sending `ConfigurationInstalled`. A receiver accumulates distinct acknowledgments
from the **new** roster, persists those identities with `VoterAuthority`, and only then exposes
`retirementSafeVoters()` and completes the pending reconfiguration promise. An installed epoch with
no durable new-majority proof is not retirement-safe, including after restart. Repeating
`reconfigure(currentRoster)` solicits missing acknowledgments instead of treating local installation
as completion. ACK solicitations carry the exact epoch, roster, and checkpoint boundary; only members
of the old/new handoff union can solicit them, and only installed new voters can supply evidence.

Genesis voter IDs are a trust anchor, distinct from discovery seeds. Provisioned replacements carry
that anchor explicitly even when all original voters have since been replaced. Every installed
transition retains a chain of old-majority certificates. A returning node verifies the chain from
its known authority before considering a newer snapshot quorum. Local persisted authority is loaded
and validated before applying bootstrap defaults; a restarted node does not revert to epoch zero.

Installed-voter changes also update leader eligibility. Expected election members, transport-derived
candidate pools, and committed/KV leader adoption are restricted to the installed roster. Health
can suppress reachability or trigger recovery, but cannot substitute a new electorate.

## Passive worker routing after a handoff

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
receive neither proposals/ballots nor global batches, Decisions, snapshots, or installation ACK
broadcasts. A full-state `SyncRequest` is served only to admitted immutable CORE identities or explicitly trusted
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
request fanout and service use the extra seed trust. A response must still contain authority anchored
in the configured genesis, a valid handoff chain, and a responder belonging to that authority's
configuration. Adoption requires that configuration's response quorum. An arbitrary seed roster or
local health view never becomes an electorate. Replacement bootstrap must provide enough reachable
current CORE seeds to obtain a response quorum after original genesis voters have retired.

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

### Bounded checkpoint admission

The current transport carries whole checkpoints, not chunks. Its frame ceiling is 32 MiB;
consensus reserves 64 KiB and checks the serialized envelope before transmission. A
configuration proposal is admitted only after the exact application prefix can encode both
the handoff transfer and the subsequent synchronization response, including the retained
handoff snapshot, complete certificate history, and conservative witness sets. The barrier
contains no application commands. Its prepared checkpoint is cached at that slot, so later
pending requests cannot enlarge an already admitted barrier.

Pending batches are uncommitted recovery hints. If their inclusion exceeds the bound, the
transfer omits them; the originating node retains its pending requests and promises, and
normal stop/failure or caller retry semantics still apply. Omission never acknowledges a
request. If committed checkpoint state itself does not fit, reconfiguration fails visibly
with `STATE_TRANSFER_TOO_LARGE` before proposing the barrier. A receiver refuses an
unprepared oversized barrier rather than reopening a decided old epoch.

Oversized synchronization emits `SyncRejected` and a typed local diagnostic. A rejection
from one eligible source does not cancel synchronization with other sources; stale epochs
are ignored and successful recovery clears the diagnostic. Operators must reduce retained
state or await a future chunked-transfer implementation when no source can produce a
bounded checkpoint. This implementation does not claim unbounded core state transfer.
