### Fixed (2026-10-03 — #1777 track 3: a removed DHT entry could come back)
- **A DHT remove was a plain local delete on each replica**, with no stamp and no fence. A replica that missed the
  remove kept the value, and anti-entropy, migration or the departure hand-off copied it back. The #428 fallback
  read also re-homed a stray copy with a FRESH stamp, which beat every older entry.
- A remove now stores a **tombstone** stamped like a put (owner epoch, then HLC version) and fenced like one; a
  quorum lost to fences is `WriteIndeterminate`. Reads and `exists` keep the newest stamp, so a stale value loses to
  a newer tombstone, and a tombstone answer skips the fallback probe. The fallback re-homes a copy with its original
  stamp [verified: integrations/dht/src/test/java/org/pragmatica/dht/DHTDurableDeleteTest.java, in-JVM multi-node].
- Tombstones are collected after the committed `[replication] tombstone_retention` (default 1 h, floor 6m30s), and
  only once every co-replica agrees and no holder left the replica set within the retention. A node drops its stray
  copies of a partition it stopped replicating after the retention less 90 s, and a node removed from the cluster
  while running drops its store, so it rejoins empty. New gauges: `aether.dht.tombstones`,
  `aether.dht.tombstones.collected`, `aether.dht.strays.purged.partitions`, `aether.dht.gc.unagreed.partitions`.
- While a `[replication]` change is unsettled (#1777 R1b), a node keeps its stray copies past that horizon. A stray may
  hold the only copy of a write that a slower node acknowledged at the old quorum, and the catch-up that runs after the
  writers switch pulls from it. Tombstone collection waits the same way, until the change has been settled for two
  anti-entropy periods plus an operation timeout, so a stray is still dropped before any tombstone it could outlive
  [verified: DHTDurableDeleteTest `unsettledChange_keepsStrays_andTombstonesWait_untilItSettles`, in-JVM 4-node]
  [mechanism: assumes every node observes the committed settle within that margin, the same view-lag assumption
  the stray horizon already makes].
- [limit: the deposed-writer residual from #1820 stays open; tombstones do not close it (owner ruling Q8).]
