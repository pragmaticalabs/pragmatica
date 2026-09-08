### Fixed (2026-09-08 — #926: cluster-failure events are leader-gated, so a cluster that cannot elect a leader emits nothing)
- **The observability path failed exactly when the cluster did.** `NodeFailed` was emitted only through
  `ClusterEventAggregator.emitAsLeader`, gated on `leaderCheck`. Detection worked fine — SWIM marked
  peers faulty — but nothing reached `CLUSTER_EVENTS`, so every consumer of that stream saw silence and
  read it as health. Measured on a five-node cluster over ten days, from one node's logs: 3,350,175 log
  lines, 8 SWIM "member faulty" detections, **0** `NodeFailed` lines, 5 cluster events of any kind (all
  `onNodeJoined`, in the first 30 seconds), and 1,297,717 leader-election lines with the leader ping
  silent for 104,382 consecutive intervals. Three nodes sat `unhealthy` in Docker for nine days and
  nobody noticed, because there was nothing to notice.
- **`NodeFailed` is now emitted un-gated**, on every node whose FSM confirms the death.
  [verified: `ClusterEventAggregatorTest#noLeaderAnywhere_stillEmitsNodeFailed_onEveryObserver` — three
  observers, every one with a constant never-leader gate, so "no leader" holds by construction for the
  whole window rather than at one sampled instant; owner-checks deliberately mixed so neither gate can
  be the one letting the event through]
- **Two further events had the same self-defeating shape and are fixed with it.** `LeaderLost` was
  emitted from the branch where the leader id is EMPTY, through `emitAsLeader` — **the event announcing
  that there is no leader required the emitter to be the leader**, which no node can satisfy. `QuorumLost`
  (CRITICAL, the most severe event this class emits) was likewise leader-gated, and a cluster that has
  gone PASSIVE cannot commit through consensus and so cannot sustain a leader lease. Both now emit
  un-gated. [verified: `ClusterEventAggregatorTest#noLeader_stillEmitsLeaderLost`,
  `#noLeader_stillEmitsQuorumLost`]
- **`QuorumEstablished` is un-gated too, deliberately.** Quorum forms *before* a leader is elected, so
  the gate dropped the recovery notice at the one moment it was guaranteed false. Un-gating the loss
  while leaving the recovery gated would be worse than fixing neither: an operator would watch the
  cluster enter "quorum lost" and never see it leave. A failure signal is only usable if its matching
  recovery signal is at least as reachable. [verified:
  `ClusterEventAggregatorTest#noLeader_stillEmitsQuorumEstablished`]
- **The guarantee, per operation.** `NodeFailed` / `LeaderLost` / `QuorumLost` / `QuorumEstablished`:
  **at-least-once per observing core member, per event, into that member's LOCAL partition-0 ring.**
  Explicitly **not** exactly-once and not deduplicated. Duplicates are bounded, not unbounded — the
  ceiling is one event per member that confirms it — and `details.observedBy` names the emitter so a
  consumer can collapse them or count distinct observers. Replication to other replicas stays
  best-effort, fire-and-forget after the local ack.
  [mechanism: `emitLocal` applies neither the leader nor the owner gate; `MembershipFsm.enteredDead` is
  a fresh-edge fan-out firing once per DEAD transition per member FSM; the local append needs no leader,
  no quorum and no consensus — the system-stream publisher is built with `EVENTUAL`,
  `consensusPath=none`, `minSyncReplicas=0`, and the epoch fence reads local applied state]
- **A dedup token was considered and rejected**, rather than not considered. Any token whose scope
  matches the counted unit must be visible to every emitter, which costs at least quorum — and the
  measured incident ran three of five nodes unhealthy, below quorum. It would have been silent in the
  very incident it exists to report, and would newly bind this path to a coordination outcome it does
  not otherwise need. Gating instead on "is there a leader" fails for a related reason: it makes the
  leadership view — the least trustworthy input in this incident — the guard on the failure path, so a
  stale leader id naming a dead node silences every survivor.
- **The replay gate is retained**, so snapshot/resync replay still does not re-publish historical
  departures. This is the one suppression that had to survive un-gating.
  [verified: `ClusterEventAggregatorTest#noLeader_stillSuppressesDepartureDuringReplay`]
- **`LeaderElected` and `NodeJoined` stay leader-gated** — the new leader is the authoritative emitter
  of its own election — so this change is not "un-gate everything".
  [verified: `ClusterEventAggregatorTest#nonLeader_stillSuppressesLeaderElected`]
- **A failed publish is no longer silently dropped.** `publishSafely` wrapped the call in `Result.lift`,
  which catches only a *synchronous* throw; `publish` returns a `Promise`, and an asynchronous failure
  (e.g. `PARTITION_NOT_LOCAL`) was discarded with no log and no counter. With this path now load-bearing
  for reporting cluster failure, that drop would have rebuilt the same fail-open shape one layer down.
  [mechanism: `onSuccess(promise -> promise.onFailure(...))` added; still fire-and-forget, logged and
  never propagated to the DEAD-edge caller]
- **The operator-visible surface this closes on is the local WARN log**, not `/api/events`. The log
  needs no leader, no quorum, no replica, no partition ownership and no network, so nothing this ticket
  is about can gate it; the confirmed-departure edge previously logged nothing at all. The ticket's own
  evidence was gathered from logs, which is direct proof that this surface stayed up through the
  incident that killed the event surface.
  **Known limitation, not fixed here:** `/api/events` read-back is remote-preferring — the
  `ANY_REPLICA` router forwards to a randomly chosen CAUGHT_UP peer and propagates the failure with no
  local fallback, so with dead peers still registered CAUGHT_UP the read can fail while the event sits
  in the local ring. That is in `aether-stream`, outside this fix's boundary, and is filed separately.
  [design intent — unverified: the end-to-end multi-node behaviour under a genuine sustained no-leader
  condition has NOT been reproduced on a cluster; the evidence above is the original incident's, and
  every claim tagged `verified` here is a single-JVM exercise of the real publisher, codec and
  partition manager — not a multi-node run with failure injection]
- All production hunks above were mutation-probed: each was reverted alone, its named test confirmed
  red, and the file restored.
