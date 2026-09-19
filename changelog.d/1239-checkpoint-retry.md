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
- **Monotonicity is enforced in the consumer runtime, not the store (CTO ruling, 2026-09-19).** The
  ticket asked for a store-level guard. That premise was wrong: `CursorStore` is also the public pull
  API's writer (`StreamAccessFactory` takes it as an SPI extension), and committing a lower offset there
  is a legitimate rewind. The runtime's in-memory cursor only moves forward, and every commit reads it
  when issued, so the runtime never issues a commit below one that already succeeded. [mechanism:
  `ConsumerState.advanceCursor` uses `accumulateAndGet(max)`; pinned by
  `StreamConsumerRuntimeTest$CursorCommitObservability.runtime_neverIssuesACommitBelowItsLastSuccessfulOne`]
- [unverified: the detach-time final commit is issued independently of an in-flight periodic commit, so
  a periodic commit landing after it can step the stored cursor back by at most one checkpoint interval.
  The consequence is redelivery on the next attach, not loss.]
- [unverified: two nodes briefly delivering one (group, partition) during reassignment can interleave
  checkpoint commits; consequence is bounded redelivery, not loss]
