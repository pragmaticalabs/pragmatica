### Fixed (2026-10-02 — #1818: DHT key lost from every replica after two nodes depart concurrently)
- **A copy of an existing key was refused by the owner-epoch fence.** Anti-entropy repair, migration and the departure
  push wrote each copy through the fenced `putVersioned` path. That path rejects any epoch older than the node's single
  `"core"` ownership high-water, which advances on every `DhtPartitionOwnershipKey("core")` rewrite. After such a rewrite,
  every key written before it could never be copied to a node that lacked it, and the key died with its last holder. Copies
  now go through `StorageEngine.putReplica`. It keeps the per-key epoch and HLC ordering but skips the high-water check, so a
  copy still never overwrites a newer entry. Fresh writes are fenced exactly as before.
  [verified: `integrations/dht/src/test/java/org/pragmatica/dht/DHTMigrationEpochFenceTest.java`]
- **A departure push ignored nodes draining at the same time.** It excluded only the departing node itself. A push could
  land on a co-drainer that then halted with the copy, and two holders leaving together each counted the other as a
  survivor. The drainer now reads the leader ping's global `drainNodes` set
  (`ClusterSyncCollector.commandedDrainNodes()`). It excludes every node in that set both as a push target and as a
  surviving replica.
  [verified: `DHTChurnSurvivalTest.concurrentDeparture_*`, `DrainCommandPlumbingTest$Receive`]
  [unverified: the `AetherNode` supplier that passes the set is pinned by no test, and a co-drain commanded in a later ping
  than the drainer's own is not seen. No multi-node or cloud run.]
