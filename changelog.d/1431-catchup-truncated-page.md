### Fixed (2026-10-04 — #1431: a byte-capped catch-up page promoted the replica CAUGHT_UP below the owner's head)
- **`ForwardCatchupTransport` read a page shorter than `batchSize` as "at the head" even when the owner had cut
  it at `maxReadResponseBytes` and marked it `truncated`.** The pull ended there, `PartitionBackfill` promoted
  the replica CAUGHT_UP at the cut page's last offset, and the row was read-gate eligible and a source candidate
  while holding a short log. The replica held what it acked, so no data was lost; the status was false. It was
  reachable whenever a page's payload exceeded the cap (default 28 MiB) before `batchSize` events.
- The transport now keeps paging while a page is truncated; only an uncut short page ends the pull.
  `[verified: aether/aether-stream/src/test/java/org/pragmatica/aether/stream/forward/ByteCappedCatchupPageTest.java]`
  pulls through the real handler, client, transport and backfill with a cap of one event per page and asserts
  CAUGHT_UP at the owner's head (in-process, not a running cluster).
- A truncated page that carries no event (one event alone exceeds the cap) now fails the catch-up with
  `EVENT_EXCEEDS_READ_CAP` instead of being read as the head or re-read forever
  `[verified: aether/aether-stream/src/test/java/org/pragmatica/aether/stream/replication/ForwardCatchupTransportTest.java]`.
  The replica stays SYNCING `[mechanism: a failed catch-up promise is never promoted by PartitionBackfill]`.
  Recovery: raise `maxReadResponseBytes` on the owner above the largest event.
