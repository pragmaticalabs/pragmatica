### Fixed (2026-09-29 — #1683: a replica that missed a whole slot stayed stale in a quiet cluster)
- **The ticket's framing — a replica 1..100 phases behind never starts catch-up — does not hold.** The
  proposal path was never the repair path: a replica that missed slot P resyncs as soon as ANY later
  Decision reaches it (`handleDecision` buffers a Decision past a gap and calls `triggerResync`).
  [verified: `integrations/consensus/src/test/java/org/pragmatica/consensus/rabia/RabiaReorderedDeliveryTest.java`
  `replicaThatMissedASlotCatchesUpFromTheNextDecision` — in-JVM engines, reordered delivery]
- **The real, narrower gap:** a replica that missed ALL traffic for slot P while the cluster then went
  quiet learned of it from nothing — it was `Idle` (no stall detector), no later Decision existed, and
  peers repair only on an inbound ballot — so it served slot-P-stale state indefinitely while
  `isPendingCatchUp()` reported `false`.
- An `Idle` voter now asks the other voters about its current slot every `syncRetryInterval` (default
  5 s). A peer past that slot replays the slot's Decision and, since this change, its own latest
  Decision too, so a replica several slots behind (or behind a slot the peer already cleaned) resyncs
  from one answer instead of one slot per request. The stale window is bounded by one probe interval,
  not closed: until the probe is answered the replica still cannot know it is behind, and
  `isPendingCatchUp()` still reports `false` during that window.
  [verified: `RabiaReorderedDeliveryTest` `quietClusterRepairsAReplicaThatMissedAWholeSlot`,
  `pastSlotRepairAlsoReplaysTheFrontierDecision` — in-JVM engines, not a multi-node run]
- Cost: in a quiet cluster, one small `RoundRequest` per voter pair per `syncRetryInterval`.
  [mechanism: `RabiaEngine.probeQuietSlot`, sent only in `Idle`]
