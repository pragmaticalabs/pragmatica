# Rabia instance and binary-round contract

Scope: normative target for runtime PR #1390; present-tense requirements do not assert that
`release-1.0.0-rc4` implements them. Baseline observations are explicitly labelled below.

Status: implementation contract for the rc4 hierarchy correction batch.

## Problem and scope

A replicated log slot and a binary-consensus iteration are different coordinates. The previous
engine used `Phase` for both: carry-forward advanced the state-machine frontier, new batches
inherited the previous slot's binary lock, and the all-question branch committed a coin result.
These transitions do not implement the checked-in `integrations/consensus/weak_mvc.ivy` model.
The model's `propose(N,V)` is fixed across its phases; its coin branch only selects the next vote.

This change keeps the Java `Phase` name for the **log slot** and adds a binary-round coordinate
to vote messages. It is a breaking protocol change for fresh clusters. There is no rolling
upgrade or persisted-state migration contract.

## Reference and refinement boundary

The checked-in Ivy model defines one weak-MVC instance. The outer log sequencing wrapper is
implemented separately. The [Rabia paper, Algorithms 1–3](https://ceres.cs.umd.edu/818/papers/rabiaRandomization.pdf)
distinguishes log position from binary iteration and requires proposal agreement before returning
a non-null value. The implementation must preserve these distinctions.

The former first-round majority shortcut is removed: no checked-in proof licenses deciding from
that evidence. Receiving a majority of first-round votes licenses a second-round vote, not a
commit. The normal two-round path remains.

## State and transitions

- `Phase` identifies a log slot. `currentPhase` is the next unapplied slot.
- Each slot has one immutable proposal per voter and a set of ballots indexed by binary round.
- A ballot is keyed by slot, binary round, voting stage, and voter. First ballot wins. Duplicate
  delivery cannot change an earlier vote. Different rounds never share a ballot map.
- Initial round-one vote derives from the fixed proposal set. A later round-one vote derives
  solely from the preceding round's carry-forward result.
- After a quorum of round-one votes, broadcast the common value if a quorum agrees, otherwise
  question. After a quorum of round-two votes, decide on f+1 matching non-question votes for n=2f+1 voters
  (the refinement threshold in `weak_mvc.ivy`); otherwise carry the observed non-question value, or the common coin.
- Carry-forward advances only the binary round. It cannot apply a command, mark the slot decided,
  change the snapshot frontier, discard proposals, or start a different slot.
- A V1 decision waits for a quorum-agreed proposal. A plurality or empty fallback is forbidden.
- A V0 decision completes a no-op slot. A V1 decision applies the selected batch once. Only then
  may the next log slot start; the next slot obtains a fresh binary initial state.
- A core Decision is trusted under the existing crash-fault model. This is not Byzantine consensus.
  Worker/unknown senders cannot contribute proposals, ballots, Decisions, or synchronization evidence.

## Delivery and recovery

Current-slot Decisions apply once. Past Decisions are ignored using the applied frontier, even
when the phase cache has been evicted. Future-slot Decisions are buffered and request snapshot
repair; they never mutate the state machine across an unresolved gap. During quorum loss, repair
waits until quorum returns. Observer activation drains through the same ordering rules.

Binary ballots may arrive before proposals or out of round order. They remain associated with
their original round. Earlier-round retransmission must let a lagging voter collect the evidence
needed to advance; receiving a later-round ballot alone is not authority to skip rounds.

Snapshots identify the next unapplied log slot, never the current binary round. A snapshot and its
frontier are captured on the serialized consensus executor. Observers do not contribute snapshots
to core response quorums, and require a full core response majority because self is not a voter.

## Electorate and limitations

Hierarchical topology supplies an explicit, fail-closed admitted CORE predicate. Immutable worker
roles cannot be promoted through consensus observation. Static bootstrap membership must be
available before the first consensus operation.

This correction does not license live voter reconfiguration by changing a capacity count. A
membership generation protocol and agreed activation boundary are specified in
[voter-configuration-handoff-spec.md](voter-configuration-handoff-spec.md); durable vote/state
retention is specified below. These are separate proof obligations. Reconfiguration must not silently reinterpret in-flight votes under a
new denominator. Existing in-memory persistence cannot establish crash-recovery safety after
loss of voting history. These limitations must remain explicit in deployment guarantees.

The deterministic common-coin schedule is reproducible. Safety does not rely on its randomness;
probabilistic termination against an adaptive adversarial scheduler is not claimed for a publicly
predictable schedule.

## Acceptance

1. A carry-forward preserves slot, proposal identity, applied state, and snapshot frontier.
2. All-question evidence starts another binary round and never directly commits.
3. An old-round ballot cannot affect a later round, including conflicting duplicate delivery.
4. Different fair message schedules yield identical log prefixes across replicas; test duplicate,
   reordered, delayed proposal/ballot/Decision delivery and carry-forward before another peer decides.
5. A V1 result cannot use a plurality proposal or manufacture an empty batch.
6. A new slot does not inherit the previous slot's binary vote.
7. Past and ahead Decisions cannot reorder application; observer replay uses identical rules.
8. Worker, unknown, and self-echo synchronization responses cannot complete a core quorum.
9. The full consensus suite and generated serialization compile after the vote wire change.

## Crash-recovery write-ahead contract

A durable local WAL is required for a production voter. Before emitting its immutable proposal,
round-one ballot, or round-two ballot, a node appends and fsyncs that promise. Retransmission of the
same promise requires no additional write; a conflicting promise for the same epoch/slot/round is a
fatal protocol error. A Decision is appended and fsynced before application or publication. Recovery
restores the checkpoint, replays the subsequent contiguous Decisions, and restores the open slot's
own proposal and ballots before responding to synchronization or voting. A restarted process cannot
choose a new proposal or ballot merely because its volatile PhaseData map was lost.

The checkpoint includes the application frontier, voter authority/retirement evidence, WAL sequence,
and retained promises for the still-open slot. The temporary checkpoint file is fsynced, atomically
renamed, and the containing directory is fsynced before the covered WAL prefix is replaced. A crash
after rename but before directory fsync may lose the rename; recovery must retain the old WAL prefix
until directory durability is established. A crash between those replacements leaves redundant old records,
which recovery validates and then ignores below the checkpoint sequence. An incomplete frame,
checksum mismatch, unknown schema, or sequence gap fails startup; this implementation does not
silently truncate or reinterpret corrupted durable history as a fresh node.

The append boundary is local storage, not git or a remote content store. Each new proposal, ballot,
and Decision adds one fsync in the baseline implementation. Batching application commands still
amortizes this cost across a batch; network rounds and fsync latency both contribute to commit latency.
Snapshot map/set encoding is canonical only on the checkpoint path, leaving ordinary wire encoding
unchanged. Group commit is a possible later optimization, provided no vote or successful result can
escape before the group is durable. Performance measurements must report the filesystem and storage
used; an in-memory protocol test is not a durable-throughput benchmark.

The adapter holds an exclusive OS file lock for the node's consensus directory until serialized
shutdown closes it. A second process cannot reuse that identity's WAL directory concurrently.
Production selects `cluster.consensus_path`; Ember uses a distinct per-cluster, per-node temporary
path that survives that node's in-process restart. In-memory persistence remains an explicit test
choice and does not imply crash durability. Passive workers do not replay the global consensus WAL.

[limit: durable-journal-envelope] Baseline bounds: records are at most 64 MiB; checkpoint payloads at most 512 MiB; WAL at most 256 MiB;
compaction is requested after 4,096 appended records or 32 MiB. At most 65,536 uncompacted/open-slot
promises are retained, including binary-round history. Exceeding a bound stops voting with a typed
failure; it does not discard an unresolved promise. Optional git backup receives only immutable
checkpoints on a separate worker, with one in-flight and one latest pending snapshot. Backup failure
is observable but cannot delay voting or make a durable write appear unsuccessful.

[unverified: production-durable-throughput] Local development measurements (not production storage): 64 append+fsync samples
had median 6.7–8.3 ms and p95 8.4–23.1 ms across three runs. A proposal, two ballots, and a Decision
therefore add material serial storage latency per batch. Production throughput and tail latency
must be measured with actual batch size, core count, disk, and inter-region delay. These figures are
not a claim that a 10K-node hierarchy can run its core protocol at in-memory test throughput.

Disk durability is a deployment assumption: retain the voter directory for an identity across process
restarts. If that directory is lost, replace the node with a fresh identity through a certified
configuration handoff. Deleting all local evidence and reasserting the same identity as newly created
is outside this crash-recovery contract; neither an empty directory nor a discovery seed list proves
that the identity never voted. Explicit configured genesis must agree with recovered genesis, while
ordinary discovery membership and capacity-count changes cannot override verified local authority.
