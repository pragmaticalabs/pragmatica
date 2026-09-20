### Changed (2026-09-20 — #1342: `publishBatch` reports per-event outcomes and the offsets that landed)
- **A batch is not atomic, and its result now says so per event.** `StreamPublisher.publishBatch` and
  `FrameworkStreamPublisher.publishBatch` return `Promise<List<PublishOutcome>>` — one outcome per event, in
  input order — instead of `Promise<Unit>` (pre-GA API change, no shim). In #1236's vocabulary:
  `Published(offset)` is durably in the log; `OutcomeUnknown(cause)` was refused or timed out — before or
  after the local append, the caller cannot tell which — so it MAY be in the log and retrying it can
  duplicate unless the event carries a stable key (#1237); `NotAttempted(cause)` never reached the write path, is NOT in the log and is safe to
  retry. The promise never fails on per-event grounds.
  [mechanism: `DefaultStreamPublisher.publishBatchEventual` places each partition group's outcomes back at
  their input indices; a group stops at its first failure and reports the rest `NotAttempted`
  (`StreamPublisherError.PrecedingEventFailed`); pinned by `BatchPublishOutcomeTest`]
- **Why.** The interface defaults folded per-event results with `Promise.allOf(...).mapToUnit()`, so a
  batch with a refused event was acknowledged as success (`SystemStreamPublisher` inherited that fold).
  #1263 made the slice publisher fail the batch instead, which is more honest and still hid the other half: a
  2-partition batch whose min-sync barrier refuses on one partition reports failure with BOTH events durably
  in the ring, and a caller retrying the batch duplicated the one that landed.
  [verified: `BatchPublishOutcomeTest.partialBatch_reportsTheOffsetThatLanded_andOutcomeUnknownForTheRefused_bothDurable`]
- **The STRONG batch fold is pinned** with a consensus path whose proposer refuses one event: that event is
  `OutcomeUnknown`, the others `Published` at their proposal's offset (rev1305 R10 left this fold unpinned
  because the up-front consensus-path guard returned first). With no consensus path every event is
  `NotAttempted(CONSENSUS_PATH_UNAVAILABLE)`.
  [verified: `BatchPublishOutcomeTest.strongBatch_withConsensusPath_reportsEachProposalsOwnOutcome`,
  `StrongConsistencyFailClosedTest.streamPublisherPublishBatch_refusesWithConsensusPathUnavailable_forStrongStream`]
- **`publishBatch` has no default any more.** A batch derived from `publish(T) -> Promise<Unit>` cannot report
  offsets, so both SPIs require an implementation; `StreamPublisher` is no longer a functional interface.
  `SystemStreamPublisher` delegates the batch to its transport; `TestSystemStreamPublisher` reports every
  event `Published` at its batch index (it has no log).
- **Management API `publish-batch` reports every item.** `PublishBatchResponse` is now
  `{address, published, notPublished, outcomes:[{index, status, offset?, cause?}]}` with status `PUBLISHED`
  / `OUTCOME_UNKNOWN` / `NOT_ATTEMPTED`, returned with 200 for every batch that ran — partial included.
  **Hazard: `200` means the batch RAN, not that every event landed. A client that reads only the status
  misreads a partial batch; read `notPublished`.** A typed non-2xx that carries structured offsets needs
  `ManagementRouter` to support typed success statuses — ticket to be filed by the CTO. The former `Result.allOf`
  fold returned only the first failure and discarded the offsets of the items that had landed. An item
  rejected before writing (stream unavailable, partition out of range) is `NOT_ATTEMPTED`; an item the
  write router refused is `OUTCOME_UNKNOWN` (#1236).
  [verified: `StreamApiRoutesPublishBatchTest`, 4 tests, including
  `partialBatch_overHttpDispatch_answers200_withNotPublishedAndTheLandedOffset` — the real route through
  `ManagementRouter`, asserting the written status 200 and the JSON body
  `{"published":1,"notPublished":1,"outcomes":[{"index":0,"status":"PUBLISHED","offset":0},…]}`; absent
  `Option` fields are omitted from the JSON]
