### Changed (2026-09-20 — #1333: durable projections wired to the runtime)
- **A dead-lettered event's cursor advance requests a checkpoint at once**, independent of the
  500 ms / 1000-event cadence (one coalesced consensus round per dead letter, bounded by the poison
  count). The committed cursor is the signal a rebuilding projection skips a dead-lettered replay
  offset on. For a durable group the retry budget (≥1.5 s over 5 attempts) already exceeds the
  interval, so the cadence check at the dead-letter advance was committing the skip anyway; the
  forced request makes that independent of the strategy and budget (SKIP, or a budget shorter than the
  interval, would otherwise wait for the next delivery) and schedules the follow-up checkpoint that
  lands the acks after the dead letter on a partition that then goes quiet `[verified:
  aether/node DurableProjectionRebuildTest rebuild_replaysInOrder_skipsTheDeadLetteredOffsetOnTheCommittedCursor_andGoesLive
  — with the request removed, the committed cursor stays at the dead-letter offset until the next event]`.
- **Pre-GA storage layout changes, stated (composed with #1271's):** a fenced commit's node-local
  `CursorStore` block is now 40 bytes — offset, the assignment epoch (#1271) and the rewind epoch
  (#1333). #1271's 8-byte unfenced block (the pull API's) and its 24-byte fenced block stay readable
  as they were: an 8-byte block is a cursor for an unfenced fetch and belongs to no tenure, a 24-byte
  block is that tenure's cursor at rewind epoch `0/0`; the next fenced commit rewrites either in the
  40-byte layout. Any other length reads as absent. The KV snapshot form of a stream cursor grows from
  #1271's 5 to 8 pipe-delimited fields (rewind epoch and the rewind-record flag after the assignment
  token); a pre-#1333 snapshot does not parse.
- **The rewind record is a write to the `AssignmentGuarded` checkpoint key (#1271)**, so it carries the
  partition's COMMITTED assignment token, read at rewind time: a rebuild on a node the record does not
  name, or a partition with no committed assignment, is refused before or by the applier and reported
  as a failed rebuild, never as Success. A checkpoint lands only when both applier arms admit it — the
  committed assignee's token AND a rewind epoch not older than the committed one `[verified:
  aether/node ClusterCursorStoreTest$RewindAndAssignmentFencesCompose, both directions]`.
- `StreamConsumerManager` restarts a consumer whose committed rewind epoch is newer than the one it
  runs under — on the checkpoint's KV notification and on every reconcile pass.
