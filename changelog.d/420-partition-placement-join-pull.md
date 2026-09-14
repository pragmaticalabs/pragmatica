### Fixed (2026-09-14 — #420: DHT-backed artifacts lost under topology churn — the repair machinery repaired the wrong replica sets, and a joiner counted toward RF while holding nothing)
- **`ConsistentHashRing` placed a key at its own hash position while `DHTAntiEntropy` and
  `DHTRebalancer.rebalancePartition` placed the key's PARTITION at `hash("partition:<p>")` — a
  different position.** Measured on a 5-node ring over 2,000 keys, RF=3: the two owner sets agreed
  for 196 keys (9.8%, chance level for three-of-five sets). The periodic anti-entropy cycle and the
  survivor-side rebalance therefore digested, pulled and pushed among nodes that owned the partition
  but not the key — they never restored the replica set that reads and writes use, and a joining
  node was never backfilled into it. Only the departure push (#427, C1) and the resolve-time fallback
  (#428, C2), both key-placed, were real. [mechanism: `ConsistentHashRing.positionOf(Partition)`;
  probe recorded in the fix report]
- The ring now has ONE placement: a key's nodes are its partition's nodes —
  `nodesFor(byte[] key, …)` and `primaryFor(byte[] key)` resolve through the new
  `nodesFor(Partition, …)`/`primaryFor(Partition)`, and the five `"partition:<p>"` string sites
  (`DHTAntiEntropy`, `DHTRebalancer`, `LocalPartitionMap` ×3) use the typed lookup, so both ends of
  every repair exchange name the same nodes. Every key-placed caller (`DistributedDHTClient` incl.
  the C2 fallback and read-repair, `DHTNode`, `ReplicationPolicyImpl`, the C1 departure push,
  `DhtRoutes`) moves at once through the one function. **Replica placement changes cluster-wide:**
  pre-GA, the DHT engine is in-memory and its contents carry no data promise (the same basis as
  #281's key change); a mixed-version cluster is not supported across this change.
  [verified: `integrations/dht/src/test/java/org/pragmatica/dht/PartitionPlacementTest.java` —
  2,000/2,000 keys have the ordered owner list, the filtered list and the primary of their partition;
  reverting either `nodesFor` overload or `primaryFor` to the key's own hash reddens it]
- **A joining node counted toward the replication factor of every partition it gained while holding
  none of them until the next 30 s cycle** — the ticket's "no join migration". `DHTTopologyListener.
  onNodeJoined` now runs one anti-entropy round right after the ring update (`DHTAntiEntropy.
  synchronizeNow`, the existing pull, idempotent with the periodic one; no new state, no new wire
  message — `SystemCodecPinningTest` unchanged and green). Per the owner's 2026-07-18 arm-B ruling
  this is not a new migration mechanism; it is the existing one run when it is needed.
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
  max/min 1.410 (was 1.161); pinned ≤ 1.5. The periodic cycle still sends 1024×(RF−1) digest requests
  per node every 30 s — pre-existing, filed separately.]`
