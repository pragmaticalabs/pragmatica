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
  `#repairOwnersEqualPlacementOwners_overGeneratedRings` asserts it over GENERATED rings (4 node
  counts x 4 replication factors x 500 random keys = 8,000 checks, control asserts the count) and
  `#keysSharingAPartitionShareTheirOwners` asserts the structural half a delegation cannot make true
  by itself — two keys in one partition have one owner list, over 10,000+ same-partition pairs.
  Reverting `nodesFor(byte[], int)` to a per-key ring walk reddens 9 tests across 3 classes]
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
  node) is backfilled by the join-time round, and failing that the exchange is retried every
  interval until a round completes** — the earlier "within one interval of it" held only for a
  single refused send, and the sustained backpressure that refuses one can refuse the next. A
  refused send (`WriteOutcome` BackpressureRefused / ConnectionDead / NoPeerState, previously
  discarded silently by the fire-and-forget `send`) is logged at WARN naming the peer and the
  outcome, and its correlation entry is dropped rather than left in `pendingDigests` — without that
  the refusing condition added one entry per owned partition per peer per round with nothing to
  remove them; an accepted-but-unanswered digest is expired at the start of the round one interval
  later. Per the owner's 2026-07-18 arm-B ruling this is not a new
  migration mechanism; it is the existing one run when it is needed.
  [verified: `DHTChurnSurvivalTest#recoveredNode_pullsWhatItMissedWhilePruned` (red with the
  recovery trigger removed); `DHTAntiEntropySendOutcomeTest` — a BackpressureRefused transport
  yields one WARN per refused send naming `node-1`, the outcome and "next round"; an accepted send
  logs nothing (red with the fire-and-forget send restored);
  `#refusedDigestSend_leavesNoPendingCorrelation` and `#unansweredDigests_areExpiredByTheFollowingRound`]
  [verified: `DHTChurnSurvivalTest#joinerHoldsItsPartitions_beforeTheFirstPeriodicRound_
  soTwoCrashesInsideTheWindowKeepTheReplicaSetStocked` — after the join the responsible set holds
  3/3 copies and survives the crash of both pre-join holders that stayed in it; red at `2005ea7d2`,
  red with the trigger removed, and red with the trigger present but the old placement (the pull
  asked the wrong nodes); `#withoutTheJoinTimeRound_twoCrashesInsideTheWindowEmptyTheReplicaSet`
  pins the pre-fix shape (RF=3 claimed, 2 held)]
- **A joiner asked for the whole keyspace, because on a PARTIAL ring it is a replica of every
  partition.** `AetherNode` creates the DHT ring empty and `MembershipDeltaProjector.emitJoin` emits
  one `NodeJoined` per member as the joiner's own FSM promotes it, so the joiner's ring grows one
  node per event and every event fired a full round on a ring of 2, 3, … nodes — on which
  `nodesFor(partition, effectiveRF)` returns the joiner for all 1,024 partitions. Nothing in this
  module deletes an unowned copy, so everything such a round pulled stayed. **Ownership is now
  settled by the node that HOLDS the data:** `DHTNode.handleMigrationDataRequest` returns an empty
  response to a requester that is not a replica of that partition in the HOLDER's ring, and
  `DHTAntiEntropy.handleDigestComparison` re-checks the local view when the digest response lands
  rather than only when the request went out — so a replica is acquired only where the two views
  agree. On a partial ring the joiner cannot refuse anything itself, which is why the refusal lives
  at the holder. **What a joiner does when it cannot yet know whether it owns something: it asks,
  and it WAITS for a holder to agree** — it never acquires-then-reconciles, because the only
  reconciliation available would be deleting a copy against a possibly-partial ring, which can drop
  the last one. A holder whose own ring is stale refuses a legitimate owner; that costs a delay,
  never a copy, because the next round repeats the exchange. Rejected alternative: reconciling the
  ring to the decision's `topology()` snapshot — it lags the DEPARTING prune that
  `DHTTopologyListener.onNodeDeparting` deliberately runs AHEAD of `NodeRemoved` (seed-500 part 2),
  so a later join's snapshot would re-add a node already halting.
  [verified: `DHTChurnSurvivalTest#productionShapedJoin_pullsEveryPartitionItOwns_andNothingElse` —
  the joiner's ring built the way `AetherNode` builds it (empty, filled by the events), 400 keys on
  5 nodes RF=3, asserted at EVERY position the joiner's own promotion can take in its staircase
  (a single position cannot settle it — self announced LAST is the one benign order). Stranded keys
  by position before the fix: 174 / 178 / 165 / 75 / 32 / 0; after: 0 at every position, with every
  owned key still held. Red with the holder-side refusal reverted;
  `DHTAntiEntropyTest#onDigestResponse_doesNotRequestMigration_whenTheRingNoLongerMakesThisNodeAReplica`
  pins the response-time re-check, red with that guard alone removed]
- **The survivor-side rebalance's placement is now pinned.** `DHTRebalancer.rebalancePartition`
  reverting to `nodesFor("partition:" + partitionIndex, rf)` left the whole module green before
  this — the half of #1136 that actually ran in production was unpinned.
  [verified: `DHTChurnSurvivalTest#survivorRebalance_stocksTheNewlyResponsibleNonHolder` — a key
  whose post-crash owner set gains exactly one never-holder is stocked by the primary's push]
- **After a rolling upgrade** (not supported, but worth stating): keys written under the old
  placement are repaired into the new owner set only where an old holder is also a new owner. On 5
  nodes all 2,000 probe keys stayed resolvable but 2,372 copies sat on non-owners; on 7 nodes 239 of
  2,000 keys (12%) had disjoint old/new owner sets and were reachable only through the C2 fallback.
  Stranded copies are never reclaimed — they persist until restart.
  [mechanism: verify-1142 probe `#p4`, two full cycles]
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
