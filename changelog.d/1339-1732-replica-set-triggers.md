### Fixed (2026-10-03 — #1732, #1339: the stream replica-set reconcile was pushed on an incomplete trigger set, so a replacement core never entered a replica set and a leaderless removal left a partition write-refused)

- **The reconcile is a pure function of current state and ran only on membership decisions, quorum edges and stream-config puts.**
  Placement members are the installed voters narrowed to the FSM's counted core (#1550), the owner follows the committed
  ownership record, and the record is written only by the leader's writer. Four input changes announced themselves to nothing:
  - **#1732 — voter install.** A replacement core is counted by the FSM at join, so the join decision's pass ran, but it
    becomes a placement member only when the voter configuration installs it, later. Nothing reconciled then, so the
    replacement never entered an existing replica set and RF stayed degraded (CF == RF streams stayed write-refused).
  - **#1339 — leadership.** The ownership writer is leader-only. A removal pass that ran while no live leader existed wrote
    nothing, and nothing ran the pass again when a leader appeared. The ticket's own mechanism (reconcile before
    `pruneDeparted`) does not apply since #1390/#1550: with voters installed, `TopologyObserver.coreNodes()` and `clusterSize()`
    ignore the pruned set, so the projector's emit/prune order is not what placement reads.
  - **Committed ownership record put.** Placement follows the committed owner, so a landed record changes every node's replica set.
  - **FSM counted-set change without a decision.** MEMBER → DEPARTING (drain) and DEPARTING → MEMBER (withdrawn drain) move a
    member out of and back into placement with no membership decision.
- **Fix:** `AetherNode.wireReplicaSetInputTriggers` (voter install, `LeaderChange`, `StreamPartitionOwnershipKey` put) and
  `reconcileReplicaSetOnCountedBoundary` (FSM transition hook). `ReplicaSetController.reconcile()` now coalesces triggers with a
  pending flag cleared BEFORE the pass reads its inputs, so a burst (one ownership record per moved partition) costs one pass and
  a trigger arriving during a pass always schedules one more.
  `[verified: aether/node/src/test/java/org/pragmatica/aether/node/AetherNodeReplicaSetTriggersTest.java]` isolates each input
  with the real FSM, controller and ownership writer and asserts the registry or commit has NOT moved before the trigger;
  `[verified: aether/aether-stream/src/test/java/org/pragmatica/aether/stream/replication/ReplicaSetControllerTest.java]` pins the
  coalescing and the clear-before-read ordering. In-JVM unit tests.
- **Ember evidence (bigboy, 3-node and 5-node in-JVM clusters).**
  `[verified: aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/StreamConfirmationEqualsFactorAvailabilityTest.java]`:
  the #1732 acceptance (replacement placed, caught up, publishes succeed) is green with the fix and RED at the base (`b55a359e1`:
  `ConditionTimeout` waiting for the replacement to enter the replica set). `StreamOwnerKillWritesResumeTest` asserts a WRITE
  (not the owner field) reaches every partition after the leader-and-owner dies silently; `[unverified: it is NOT red at the
  base]` — on Ember a new leader already exists when the removal decision arrives, so the cloud's 35 s refusal window is not
  reproduced and may be failure-detection latency rather than a wedge. The leaderless-removal mechanism is pinned in-JVM only.
- **A failed ownership batch write re-arms the reconcile** (`StreamOwnershipRetry`): the failure changes no placement input, so no
  trigger would re-run the pass and, with a steady leader and stable membership, the partition stayed write-refused. The delay
  doubles from 500 ms to a 30 s cap and resets on the next success, so a persistently failing write costs at most a few dozen
  attempts in ten minutes. `[verified: AetherNodeStreamOwnershipRetryTest]`. This closes the stable-cluster indefinite refusal;
  `[unverified: the cloud's ~35 s window is not reproduced]`.
