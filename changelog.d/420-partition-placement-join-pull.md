### Fixed (2026-09-14 — #1136, #420: DHT anti-entropy and rebalance repaired partition-placed replica sets that ~90% of keys are not placed in; a joiner counted toward RF while holding nothing)
- **`ConsistentHashRing` placed a key at its own hash position while `DHTAntiEntropy` and
  `DHTRebalancer.rebalancePartition` placed the key's PARTITION at `hash("partition:<p>")` — a
  different position.** Measured on a 5-node ring over 2,000 keys, RF=3: the two owner sets agreed
  for 196 keys (9.8%, chance level for three-of-five sets). The survivor-side rebalance therefore
  pushed among nodes that owned the partition but not the key, and the anti-entropy exchange —
  had it run — would have done the same. **It never ran: `DHTAntiEntropy.start()` had no caller**
  (space: every `*.java` in the tree), so the "periodic 30 s repair" every earlier analysis of #420
  credited did not exist in production, and `[timeouts.dht] anti_entropy_interval` was parsed by
  `ConfigLoader` and read by nothing. Only the departure push (#427, C1) and the resolve-time
  fallback (#428, C2), both key-placed, were real. [mechanism: `ConsistentHashRing.positionOf(Partition)`;
  probe recorded in the fix report]
- The ring now has ONE placement: a key's nodes are its partition's nodes —
  `nodesFor(byte[] key, …)` and `primaryFor(byte[] key)` resolve through the new
  `nodesFor(Partition, …)`/`primaryFor(Partition)`, and the five `"partition:<p>"` string sites
  (`DHTAntiEntropy`, `DHTRebalancer`, `LocalPartitionMap` ×3) use the typed lookup, so both ends of
  every repair exchange name the same nodes. Every key-placed caller moves at once through the one
  function — space: `grep -rn 'nodesFor(\|primaryFor(' integrations/dht/src/main aether/*/src/main`
  excluding the ring and the partition-map classes = 11 hits: 9 key-typed, all through the ring's
  `byte[]` overloads (`DHTNode` ×2, `DistributedDHTClient` incl. the C2 fallback and read-repair,
  `ReplicationPolicyImpl` ×3, `DHTRebalancer.departureTargets` ×2 (C1), `DhtRoutes`) and 2
  already `Partition`-typed (`DHTAntiEntropy`, `DHTRebalancer.rebalancePartition`); control:
  `grep -rn '"partition:'` over the same space finds only `ConsistentHashRing.positionOf`, so no
  caller computes a partition position on its own. **Replica placement changes cluster-wide:**
  pre-GA, the DHT engine is in-memory and its contents carry no data promise (the same basis as
  #281's key change); a mixed-version cluster is not supported across this change.
  [verified: `integrations/dht/src/test/java/org/pragmatica/dht/PartitionPlacementTest.java` —
  2,000/2,000 keys have the ordered owner list, the filtered list and the primary of their partition;
  reverting either `nodesFor` overload or `primaryFor` to the key's own hash reddens it]
- **The anti-entropy cycle now runs.** `AetherNode` arms it with the node's other periodic work
  (#644: after cluster formation, cancelled first on `stop()`, never a zombie) on the configured
  `[timeouts.dht] anti_entropy_interval` (default 30 s). [verified:
  `aether/node/src/test/java/org/pragmatica/aether/node/AetherNodeAntiEntropyCycleBootTest.java` — a
  real self-forming node with the interval at 1 s logs two rounds within seconds of `start()` and
  none after `stop()`; red with the arming removed]
- **A joining node counted toward the replication factor of every partition it gained while holding
  none of them** — the ticket's "no join migration". `DHTTopologyListener.onNodeJoined` and the
  DEPARTING→MEMBER `onNodeRecovered` re-add now run one anti-entropy round right after the ring
  update (`DHTAntiEntropy.synchronizeNow`, the same pull the cycle runs; no new state, no new wire
  message — `SystemCodecPinningTest` unchanged and green). Guarantee: **a joiner (or a recovered
  node) is backfilled at join time or within one interval of it** — a refused join-time send
  (`WriteOutcome` BackpressureRefused / ConnectionDead / NoPeerState, previously discarded silently
  by the fire-and-forget `send`) is logged at WARN naming the peer and the outcome, and the next
  scheduled round repeats the exchange. Per the owner's 2026-07-18 arm-B ruling this is not a new
  migration mechanism; it is the existing one run when it is needed.
  [verified: `DHTChurnSurvivalTest#recoveredNode_pullsWhatItMissedWhilePruned` (red with the
  recovery trigger removed); `DHTAntiEntropySendOutcomeTest` — a BackpressureRefused transport
  yields one WARN per refused send naming `node-1`, the outcome and "next round"; an accepted send
  logs nothing (red with the fire-and-forget send restored)]
  [verified: `DHTChurnSurvivalTest#joinerHoldsItsPartitions_beforeTheFirstPeriodicRound_
  soTwoCrashesInsideTheWindowKeepTheReplicaSetStocked` — after the join the responsible set holds
  3/3 copies and survives the crash of both pre-join holders that stayed in it; red at `2005ea7d2`,
  red with the trigger removed, and red with the trigger present but the old placement (the pull
  asked the wrong nodes); `#withoutTheJoinTimeRound_twoCrashesInsideTheWindowEmptyTheReplicaSet`
  pins the pre-fix shape (RF=3 claimed, 2 held)]
- A drain that races the joiner's pull still excludes the joiner from the departure push (the push
  excludes current MEMBERS, not holders): the key survives on the push's newcomer target and the
  joiner's round then pulls it from there, so membership stays the exclusion criterion — a per-key
  holding query would need a new message. [verified: `DHTChurnSurvivalTest#drainRacingTheJoinersPull_
  keepsTheKeyOnTheNewcomer_andTheLateRoundCompletesTheJoiner`]
- Loss classes: L1 graceful scale-down closed by C1 (#427); L2 stranded copies by C2 (#428); L3 join
  divergence by this change; **L4 full-cluster restart remains** — `MemoryStorageEngine` is the only
  DHT engine and the artifact metadata key is DHT-only — the durable-tier feature under #463.
  [unverified: L4; the cloud 5→7→5 gate run — in-JVM evidence only]
- `[known: placement by 1,024 partition positions spreads load more coarsely than per-key positions
  — measured over the same 2,000 keys on 5 nodes: replica-count max/min 1.267 (was 1.188), primary
  max/min 1.410 (was 1.161), pinned ≤ 1.5; verify-1142 measured replica max/min ≤ 1.32 at 3/5/7/20
  nodes and primary max/min 2.06 at 20 nodes — 1024-point quantisation over random positions, not a
  defect of this change. The cycle sends 1024×(RF−1) digest requests per node per interval, each
  answered by a full local scan — filed separately. `DHTAntiEntropy.start()/stop()` (its own timer)
  remain uncalled now that the node drives the cadence — wire-or-delete residue, filed separately.]`
