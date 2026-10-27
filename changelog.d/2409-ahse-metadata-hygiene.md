### Fixed (2026-10-26 — #2409: storage metadata hygiene, a block's record and read load)
- **A block's record is created, or made visible, only after its write is acknowledged.** The record of a block asserts "durably held
  per the ack point", but a put used to create it before writing any byte. A metadata snapshot taken in that window, restored later
  (a failed write followed by a clean stop is enough), made a later put of the same content deduplicate to a success with no tier
  holding the block, and `exists()` answered true while `get()` found nothing. Now the claim a put makes before its write is held in
  the metadata store as unacknowledged: no snapshot captures it, `exists()` does not read it (the tiers are asked, as `get()` does),
  and the claim is acknowledged once the required tiers hold the block. A put that loses to an already acknowledged record marks nothing, so it never hides that record from a snapshot. Concurrent puts of one block still write once. A failed write
  leaves no record. [verified: `StorageInstanceUnacknowledgedClaimTest`, `InMemoryMetadataStoreTest`]
- **Reads no longer force metadata snapshots.** Every successful `get` advanced the snapshot trigger twice (the access stamp, then the
  tier presence the read found), so with the default threshold of 1000, 500 reads within one 10 s tick took a full snapshot (124 B per
  block); under steady read load the cadence moved from every 60 s to every 10 s. Reads, and the cache-only markers they create and
  sweep, no longer advance the trigger. What reads did to a block (when it was last read, how often) now lives in a separate in-memory
  counter that is never snapshotted and that the block record does not carry, so a snapshot's bytes depend only on writes. After a
  restart every block's access is unknown, and for a warm-up window (15 minutes, measured on a monotonic clock, so a wall-clock step
  cannot end or stretch it) demotion by LRU, LFU or size pressure takes unknown blocks before the ones read since boot, oldest-created
  first, because an unknown block is colder evidence than one proven read; a tier above its high watermark demotes them, so a node
  that restarts in a loop cannot pin a full tier. After the window each strategy's own order applies. Demotion by age is unaffected
  (oldest-created first). Size pressure behaves as LRU until a block carries a size (#2443); before this change it took the most
  recently read block first. The counter is approximate and is lost
  with the process. The block record's own last-access time and access count are frozen at creation and deprecated (a later
  snapshot-format change removes them); neither promotion nor demotion reads them. [verified: `StorageInstanceReadsAreNotMutationsTest`, `StorageInstanceAccessTrackingTest`]
- Not changed: a put of a block that is already recorded still writes nothing (a later phase adds explicit touch and re-seed), reads
  that promote a block into a faster tier still record the promotion as before, and a restored record is not reconciled against the
  tiers at boot.
