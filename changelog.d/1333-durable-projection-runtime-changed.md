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
- **Pre-GA storage layout changes, stated:** the node-local `CursorStore` block grows from 8 to 24
  bytes (offset + rewind epoch). An existing 8-byte ref reads as ABSENT, so each declarative or
  durable-topic group resumes from the earliest retained offset ONCE after upgrading a node with
  cursors on disk, then rewrites the ref in the new layout. The KV snapshot form of a stream cursor
  grows from 2 to 5 pipe-delimited fields (rewind epoch and the rewind-record flag); a pre-#1333 snapshot does not parse.
- `StreamConsumerManager` restarts a consumer whose committed rewind epoch is newer than the one it
  runs under — on the checkpoint's KV notification and on every reconcile pass.
