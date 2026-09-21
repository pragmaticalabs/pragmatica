### Fixed (2026-09-21 — #1383: a replacement replica could not catch up past a ring-evicted prefix and the partition stayed SYNCING)

- **The replication catch-up read was ring-only.** A registered replica's `catchup` forward
  (`StreamForwardHandler.readAppended`) was answered from the owner's ring alone, so a replacement
  replica whose catch-up started below the ring tail got `CursorExpired` on every redrive: the backfill
  never promoted, the replica's row stayed at `-1`, the owner's next live batch was a gap on the empty
  replica and was never acked. With `min-sync 2` and the original peer gone, the partition's visible
  position never advanced again — while the evicted prefix sat sealed and readable in the owner's tier.
- **The catch-up read now falls through to the owner's tier for the evicted prefix, then continues
  into the ring for the rest of the page.** Per operation: a replacement replica's catch-up read is
  served from the owner's ring, or from the owner's tier for any prefix the ring has evicted but the
  tier retains, **bounded by the APPENDED head** — the replication-read class (#1235), never the
  consumer's visible bound (#1352, which is unchanged) — and only for a registered replica. The
  replica therefore holds every offset it acks, and `replicatedThrough(minAcks)` keeps its meaning for
  the sealed-but-retained prefix. An evicted offset whose seal has not landed yet is answered with the
  transient `SealInFlight` (the backfill redrives), never `CursorExpired`, which names an offset nobody
  holds. A ring failure after the sealed prefix returns the prefix alone (FER): the backfill applies it,
  stays SYNCING and redrives from the advanced watermark — failing the whole page would redrive from the
  same cursor into the same race under sustained eviction. Wired in `AetherNode` with the node's own
  tiered reader; the base handler without one behaves as before.
  [verified: `aether/aether-stream/src/test/java/org/pragmatica/aether/stream/forward/ReplicaCatchupTierFallbackTest.java`
  — production `PartitionBackfill` + `ForwardCatchupTransport` + `DefaultStreamForwardHandler` in-process
  over the real sealer and tier: the s1352 P3 scenario (cap 2, min-sync 2, silent peer, three publishes)
  backfills `[0, 2]`, the replacement peer's row reaches 2, `replicatedThrough(1) == 2`, the live batch
  from 3 acks with no gap and the owner's visible position moves from `-1` to 3; the appended-head bound;
  the in-flight seal; the prefix-alone page; and both `CursorExpired` controls]
  [limit: prefix reclaimed by retention → still stalls, #1407 — the tier answers `CursorExpired` for an
  offset at or below its sealed watermark that no segment holds, and the replica stays SYNCING]
  [unverified: in-process transport mirroring the wire, not a running cluster; the `AetherNode` wiring
  line is verified by reading, not by a node-level test]
