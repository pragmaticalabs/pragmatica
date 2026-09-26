### Fixed (2026-09-26 — #1527: a new leader that had led less often than its predecessor minted a lower leader term, and the epoch fence refused its writes)
- **The leader term was a per-process count of this node's own leader gains**, never seeded from
  committed state. Every leader-authored `Epoch(rabiaTerm, counter)` is minted from it, and
  `KVStore.staleEpochWrite` refuses an `EpochBearing` write whose epoch is strictly older than the
  committed one. After a failover to a node that had led fewer times than the previous leader (or had
  restarted since), the new leader's consumer-assignment and stream-partition-ownership rewrites were
  refused. The assignment then stayed pinned to the dead node, and the assignment guard refused the live
  consumer's cursor checkpoints as well. A successor on its first tenure minted the SAME term as a
  predecessor also on its first tenure, so its epochs were never strictly after the prior leader's.
  [mechanism: `KVStore.staleEpochWrite` over writers minting `Epoch(leaderTerm, n)`; reproduced through
  the real applier in `aether/node/src/test/java/org/pragmatica/aether/node/LeaderTermTest.java`]
- The term is now the committed `LeaderValue.viewSequence` of the election that named this node. The
  applier accepts a `LeaderKey` write only when its sequence is strictly greater than the committed one,
  so a new leader orders strictly after every earlier committed leader, across nodes and across
  process restarts. [mechanism: `LeaderTerm.onLeaderGained`; `KVStore.staleLeaderWrite`]
- Across two real leader kills in a five-node in-JVM cluster, each successor's minted term equals its
  committed `viewSequence` and is strictly above its predecessor's. With the old counter the first
  successor minted term 1 against a committed sequence of 2.
  [verified: `aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/LeaderTermFailoverTest.java`,
  `@Tag("Heavy")` — runs in the heavy-forge workflow, not the default CI forge job]
- The same term gates the cluster-sync ping: `ClusterSyncCollector.acceptPingFencing` drops a ping whose
  term is below the highest one seen. So a lower-term leader's pings were dropped by followers, and their
  observed generation epoch did not advance. The fix reaches this path through the same supplier, but no
  test drives the ping across a failover. [design intent — unverified]
- Not covered: a whole-cluster cold start with an empty store restarts the sequence at 1. The planned
  cluster-incarnation epoch component addresses that case. [design intent — unverified]
- Unchanged on purpose: `LeaderReconciler`'s re-election pre-latch still reads this process's own
  leader-gain count (`LeaderTerm.localGainCount`), not the new term. Moving it to the cluster-wide
  sequence would pre-latch the first leader after a whole-cluster restart, because the durable consensus
  store's sequence is already above 1. [mechanism: `AetherNode` wires `leaderTerm::localGainCount` into
  `LeaderReconciler`]
