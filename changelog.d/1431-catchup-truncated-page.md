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
- **Same class, sharper consequence: the owner promotion gate's peer probe (`OwnerPeerReads.appendedWatermark`,
  and the backfill's `replicaWatermark`) also read a byte-capped page as the peer's head.** An understated peer
  head is the input the gate uses to decide whether a candidate holds everything its peers hold, so a cut page
  could let a candidate promote below records a peer holds `[design intent — unverified: the gate-level
  consequence was traced through the code, not reproduced]`. Both probes now page past a truncated page, and a
  cut page with no event fails the probe or the overlap range read, so the gate fails closed
  `[verified: aether/aether-stream/src/test/java/org/pragmatica/aether/stream/OwnerPeerReadsTest.java]`.
- **The forward handler now always admits the FIRST event of a page** (`StreamForwardHandler.applyCap`), even when
  it alone exceeds `maxReadResponseBytes`. This is the replication manager's rule for an oversized single event. A
  page cut before its first event could never advance any reader past that event: valid with an unbounded
  `max-event-size` and any event over about 28 MiB. Before this, the owner gate reported the peer, which had
  answered, as "did not answer the watermark probe" (a false CRITICAL), and the backfill's bounded escape could
  promote at the local watermark below it. Now an oversized event is read like any other, one per page, up to the
  transport frame limit
  `[verified: aether/aether-stream/src/test/java/org/pragmatica/aether/stream/forward/ByteCappedPeerGateTest.java]`.
  This also removes a consumer read's stall at such an event `[mechanism: the same applyCap serves consumer
  reads]`.
- **Backstop for a peer still cutting before the first event** (an older handler): the cause is now
  `OwnerPeerReads.EventExceedsReadCap(offset)`. The owner gate refuses with its own block, `PeerEventExceedsReadCap`,
  never `HoldersUnreachable`, raised once per distinct block as the CRITICAL operator warning
  `stream-event-exceeds-read-cap` naming the partition, the peer and the offset. The event's size is not known to
  the reader, because the cut page carries no event. The backfill's promoted-owner catch-up refuses on it and never
  takes the bounded escape `[verified: ByteCappedPeerGateTest, PartitionBackfillTest]`.
- Follow-ups, not changed: `[unverified]` a live publisher faster than a catch-up pull is chased indefinitely, with
  every page held in memory (a pre-existing class, widened because large-event backlogs are no longer cut at one
  page); `ForwardingReadRouter` drops `truncated`, so `StreamApiRoutes` `hasMore` ends management-API paging early;
  the replica-path backfill and the cold-start contest log the backstop cause but raise no operator event of their
  own.
