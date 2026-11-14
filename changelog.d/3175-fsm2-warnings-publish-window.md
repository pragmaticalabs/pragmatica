### Fixed (2026-11-13 — #3160: a committed-but-unpublished warning raise or close was lost on a leader change)
- **FSM2 warnings** hold a family raise or close until its WARNINGS row commits (#3085). A leader that died, or lost
  leadership, between the commit and the publish left the operator-visible set wrong for good: the successor's inherit saw a
  row with no pending recovery or opener. The row now carries the unpublished opener/recovery messages (`FsmWarningValue`
  gains `open`, `opener`, `recovery`; `open=false` is a tombstone for a closed key), and the successor's inherit replays them.
- **Delivery is at-least-once on a leader change**: the markers are cleared by a follow-up WARNINGS write after the publish
  (one extra write per published batch), so a leader dying in between makes the successor publish the marked rows again.
  Several raise/close cycles of one key inside one in-flight window keep only the latest opener and recovery message.
- **Late settle and late rows**: a deposed leader publishes nothing once it mirrors the successor's `LeaderKey` (the successor
  replays the committed markers), and a marked row the deposed leader commits after the successor's snapshot is replayed and
  adopted like an inherited one. A settlement processed before the deposed leader sees the new `LeaderKey` still publishes
  (term-stamped warnings to close it: #3203).
