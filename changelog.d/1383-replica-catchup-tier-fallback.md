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
  holds. A ring refusal for the offset right after the sealed prefix **fails the page** (as `SealInFlight`
  when the sealer still holds that offset, else as the ring's own cause) rather than shortening it: the
  pull ends on a short page and the backfill promotes at the page's own last offset, so a prefix-alone
  page would promote the replica CAUGHT_UP below the owner's head — a successful page is at the appended
  head, as it always was for the ring-only read. Wired in `AetherNode` with the node's own tiered reader;
  the base handler without one behaves as before.
  [verified: `aether/aether-stream/src/test/java/org/pragmatica/aether/stream/forward/ReplicaCatchupTierFallbackTest.java`
  — production `PartitionBackfill` + `ForwardCatchupTransport` + `DefaultStreamForwardHandler` in-process
  over the real sealer and tier: the s1352 P3 scenario (cap 2, min-sync 2, silent peer, three publishes)
  with the production-shaped backfill (HRW owner known, self watermark from the replica's ring, acks
  delivered to the owner): backfills `[0, 2]`, acks 2 over the wire, the replacement peer's row is
  CAUGHT_UP at 2, `replicatedThrough(1) == 2`, the owner's visible position moves from `-1` to 2 and to 3
  on the live batch from 3, which lands with no gap; the appended-head bound; the in-flight seal; a ring
  refusal after the prefix fails the page and the backfill stays SYNCING at `-1` until the seal lands, then
  catches up whole; and both `CursorExpired` controls]
  [limit: prefix reclaimed by retention → still stalls, #1407 — the tier answers `CursorExpired` for an
  offset at or below its sealed watermark that no segment holds, and the replica stays SYNCING]
  [unverified: in-process transport mirroring the wire, not a running cluster; the `AetherNode` wiring
  line is verified by reading, not by a node-level test]
  [limit: the appended-head bound is load-bearing — the `SegmentIndex` is never purged on stream removal,
  so a stream re-created under the same name reads a tier contiguous past its new head; pre-existing on
  the consumer path too, follow-on to file]
