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
- **The claimed surface no longer latches red either.** This change nominates the local log as the
  surface that survives a leaderless cluster, and on that surface the pair was incomplete: `QUORUM_LOST`
  logged a WARN and `QUORUM_ESTABLISHED` logged nothing, so an operator reading logs saw "Quorum lost"
  and never saw it restored. That is the same latched-red failure the un-gating of `QuorumEstablished`
  exists to prevent, reached on a different surface — and it violated this entry's own stated principle
  that a failure signal is only usable if its recovery signal is at least as reachable. A matching
  `LOG.info` on the ACTIVE branch closes it.
  [mechanism: `ClusterEventAggregator.onQuorumStateChange`, ACTIVE branch]
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
  [verified: multi-node, on a real 3-node cluster with failure injection and a reverted control —
  see the A/B below. This claim was tagged `design intent — unverified` until that run; it is upgraded
  on evidence, not on confidence]
- **Verified on a real cluster, against a reverted control.** Three nodes on the internal test host;
  the leader and one other node killed with `docker kill`, leaving a survivor that was **never** the
  leader and, at 1-of-3, cannot become one. The no-leader condition is the system's own report, not an
  inference: the survivor logs `SWIM member faulty: NodeId[id=...] (currentLeader=None(), ...)` at the
  moment it confirms each death, and its leader-bound management routes answer
  `No leader elected for leader-bound management route` for the whole window.

  | measured on the survivor's own `/api/v1/events` | fixed | reverted control |
  |---|---|---|
  | `NODE_FAILED` | **2** | **0** |
  | `LEADER_LOST` | **1** | **0** |
  | `QUORUM_LOST` | **1** | **0** |
  | events stamped `observedBy` = survivor | **5** | **0** |
  | cluster-events partition watermark | **10** | **2** |
  | successful stream reads during the window | 26/30 | **30/30** |
  | `WARN` log lines from the same hooks | 2 / 1 / 1 | **2 / 1 / 1** |

  The control reverts **only** the four emit calls, leaving the log statements in place — so the WARN
  counts are identical in both arms, proving the hooks executed identically and the sole difference is
  the gate. The control's **30/30 successful reads** rule out the alternative explanation that the
  events were present but unreadable: the endpoint answered every time and the events were simply not
  there. The partition watermark (10 vs 2) corroborates that independently of HTTP.
- **The recovery half, measured the same way.** On a healthy quorate cluster the fixed build carries
  **three** `QUORUM_ESTABLISHED` events — one from each node, `observedBy` = n926-1, n926-2, n926-3 —
  while the reverted control carries **zero**, *including from the leader*. That confirms from
  measurement what the code reading predicted: quorum forms before a leader is elected, so the gate was
  false on every node at that instant and the cluster could never report quorum recovery at all.
  `LEADER_ELECTED` is present in both arms, as it should be — it stays leader-gated.
- **A limit of this scenario, stated because it bounds the evidence:** a sub-quorum survivor
  deliberately self-fences (`QUORUM_LOSS drain INTENT ... initiating self-drain (split-brain
  self-fence)`) about 18 seconds after quorum loss, so the window in which its HTTP surface can be read
  at all is short, and a first attempt that polled later than that saw nothing because the node had
  exited — not because the events were missing. The measurements above were taken inside the window.
  A cluster that keeps quorum but cannot elect a stable leader — the ten-day incident's actual shape —
  was not reproduced; that state cannot be induced by killing nodes.

- All production hunks above were mutation-probed: each was reverted alone, its named test confirmed
  red, and the file restored.
