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
- Each periodic commit is bounded at 5s. A commit that never settles used to hold the single-flight
  slot forever and absorb every later checkpoint. Past the bound it counts as a failed commit and the
  retry commits the latest cursor. A timed-out commit that lands late can step the stored cursor back
  by the progress made since: redelivery, not loss. [mechanism: `PERIODIC_COMMIT_BOUND`; pinned by
  `StreamConsumerRuntimeTest$CursorCommitObservability.periodicCommitNeverSettles_isBounded_andALaterCheckpointLands`]
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
- **A failed cluster checkpoint was never retried either.** `ClusterCursorStore` recovered a
  consensus-publish failure into a successful commit and parked the cause in a per-key side map
  (`lastRecoveredFailure`). Because the commit counted as a success, the retry never fired. Two overlapping
  commits for one key could also read each other's cause: one commit was reported with another's
  failure, or the failure was lost when the other commit cleared it. `ConsumerCursorStore.commit` now
  returns a typed `CommitOutcome` (`Persisted` or `LocalOnly(cause)`) on the commit's own promise. The
  side map is gone, and the periodic checkpoint retries a `LocalOnly` outcome until the cluster
  checkpoint lands. [mechanism: `ConsumerRuntimeState.checkpointSettled`; pinned by
  `ClusterCursorStoreTest$CommitFanout.overlappingCommits_forOneKey_eachReportOnlyTheirOwnOutcome` and
  `StreamConsumerRuntimeClusterCursorTest.periodicCheckpoint_retriesALocalOnlyOutcome_untilTheClusterCheckpointLands`]
- The detach-time final commit is now chained behind any periodic commit still in flight, so the two
  never overlap for one key. The consumer is cancelled first, so no periodic retry follows it.
  [mechanism: `flushCursorForKey` chains on `ConsumerState.periodicCommit`; pinned by
  `StreamConsumerRuntimeClusterCursorTest.detachFlush_waitsForTheInFlightPeriodicCommit_andEachCommitReportsOnlyItsOwnOutcome`]
- [unverified: two nodes briefly delivering one (group, partition) during reassignment can interleave
  checkpoint commits; consequence is bounded redelivery, not loss] Consumer assignment is unfenced.
  This is tracked as #1271.
