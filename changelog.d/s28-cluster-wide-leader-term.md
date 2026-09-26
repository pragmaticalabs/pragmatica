### Fixed (2026-09-26 — S28: a new leader that had led less often than its predecessor minted a lower leader term, and the epoch fence refused its writes)
- **The leader term was a per-process count of this node's own leader gains**, never seeded from
  committed state. Every leader-authored `Epoch(rabiaTerm, counter)` is minted from it, and
  `KVStore.staleEpochWrite` refuses an `EpochBearing` write whose epoch is strictly older than the
  committed one. After a failover to a node that had led fewer times than the previous leader (or had
  restarted since), the new leader's consumer-assignment and stream-partition-ownership rewrites were
  refused: the assignment stayed pinned to the dead node, so the live consumer's cursor checkpoints were
  refused by the assignment guard too. [verified: `aether/node/src/test/java/org/pragmatica/aether/node/LeaderTermTest.java`]
- The term is now the committed `LeaderValue.viewSequence` of the election that named this node. The
  applier accepts a `LeaderKey` write only when its sequence is strictly greater than the committed one,
  so every new leader orders strictly after every earlier committed leader, across nodes and across
  process restarts. [mechanism: `LeaderTerm.onLeaderGained`; `KVStore.staleLeaderWrite`]
- The same term fences the cluster-sync ping (`ClusterSyncCollector.acceptPingFencing` drops a ping
  whose term is below the highest seen), so a lower-term leader's pings were also dropped by followers
  until it had led enough times. The fix covers this path through the same supplier; no test pins it
  directly. [mechanism: `AetherNode` `leaderEpochSupplier`] [unverified: no test drives the ping path
  across a failover]
- Not covered: a whole-cluster cold start with an empty store restarts the sequence at 1.
  [unverified: cold-restart ordering is left to the planned cluster-incarnation epoch component]
