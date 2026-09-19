### Fixed (2026-09-19 — #1239: periodic consumer cursor checkpoints were fire-and-forget)
- **A failed periodic cursor commit was never retried, and the trigger counters reset anyway**, so on
  a quiet partition the persisted cursor stayed stale until the next event arrived. Commits for one
  consumer were not serialized, so two in flight could land out of order.
- `ConsumerRuntimeState` now keeps at most one periodic commit in flight per consumer. A request made
  meanwhile coalesces into one pending commit of the cursor as it stands when the commit is issued. A
  failure keeps the slot and retries with capped exponential backoff until the commit persists or the
  consumer detaches. The trigger counters reset only on success. Delivery still never waits on a
  commit. [mechanism: `requestCheckpoint`/`issueCheckpoint`/`retryCheckpoint` in
  `ConsumerRuntimeState`; pinned by
  `StreamConsumerRuntimeTest$CursorCommitObservability.checkpointIfNeeded_retriesUntilPersisted_whenFirstCommitFails_onAQuietPartition`
  and `...checkpointIfNeeded_keepsOneCommitInFlight_andCoalescesToTheLatestCursor`]
- Each failed retry is still counted in `cursorCommitFailureCount` and surfaced through
  `lastCursorCommitFailure`, so a store that stays down shows as a rising count rather than silence.
- Corrected the stale "30 seconds" checkpoint cadence in `ClusterCursorStore`, `StreamConsumerManager`
  and feature-catalog row 488. The time bound is the group's checkpoint interval: 1s for declarative
  consumers, 500ms for durable-topic groups.
- [unverified: the detach-time final commit is still issued independently of an in-flight periodic
  commit. If the periodic one lands after it, the stored cursor can step back by at most one checkpoint
  interval, which means redelivery on the next attach, never loss. No store-level monotonic guard is
  added pending a ruling, because `CursorStore` is also the pull API's commit path, where a lower
  commit is a legitimate rewind.]
