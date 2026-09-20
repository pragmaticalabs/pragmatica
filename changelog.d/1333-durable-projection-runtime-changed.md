### Changed (2026-09-20 — #1333: durable projections wired to the runtime)
- **A dead-lettered event's cursor advance is committed at once** rather than on the 500 ms /
  1000-event cadence: one consensus round per dead letter, bounded by the poison count. Without it a
  rebuilding projection learned of the skip only from the NEXT delivery, whose write it refused
  meanwhile — one extra dead-letter entry per poison replay offset `[verified:
  DurableProjectionRebuildTest forcedCommitRemoved mutation → DLQ count 2]`.
- **Pre-GA storage layout changes, stated:** the node-local `CursorStore` block grows from 8 to 24
  bytes (offset + rewind epoch). An existing 8-byte ref reads as ABSENT, so each declarative or
  durable-topic group resumes from the earliest retained offset ONCE after upgrading a node with
  cursors on disk, then rewrites the ref in the new layout. The KV snapshot form of a stream cursor
  grows from 2 to 4 pipe-delimited fields; a pre-#1333 snapshot does not parse.
- `StreamConsumerManager` restarts a consumer whose committed rewind epoch is newer than the one it
  runs under — on the checkpoint's KV notification and on every reconcile pass.
