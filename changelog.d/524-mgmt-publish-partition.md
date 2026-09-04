### Added (2026-09-04 — #524: explicit `partition` on Management-API publish)

- Added an optional `partition` field to `StreamApiRoutes.PublishRequest` (REST body and CLI
  `aether streams publish --partition N`). Management-API publish writes untyped bytes with no
  event class to extract an `@PartitionKey` from, so unlike an app publish (#507) an explicit
  partition here is the operator naming a target directly — never key-based routing. Omitted,
  it defaults to partition 0, the unchanged pre-#524 behavior; both single (`publish`) and batch
  (`publish-batch`) publish inherit this via the shared `PublishRequest` DTO.
  [verified: `aether/node/src/test/java/org/pragmatica/aether/api/routes/StreamApiRoutesPublishPartitionTest.java#publish_partitionOmitted_targetsPartitionZero_unchangedBehavior`,
  `#publish_explicitInRangePartition_targetsThatPartitionOnly`]
- Added `ManagementServerError.InvalidPartition`: a `partition` outside `[0, partitionCount)` for
  the target stream now fails `400 Bad Request` naming the valid range, resolved via the existing
  `HttpStatusAware`/`ProblemResponses` dispatch — never a silent write to partition 0, never `500`.
  [verified: `aether/node/src/test/java/org/pragmatica/aether/api/routes/StreamApiRoutesPublishPartitionTest.java#publish_outOfRangePartition_fails400NamingValidRange_neverPartitionZeroNever500`]
- Documented the no-key-based-routing rationale and the 4xx validation behavior in
  `aether/docs/reference/management-api.md` (Publish section) and
  `aether/docs/reference/cli.md` (`aether streams publish`).
  [mechanism: docs updated alongside the route/CLI code in the same change]
- Dashboard quad-rule check: `aether/dashboard/src/main/resources/dashboard/index.html` has no
  publish action of any kind (grepped for "publish" — only an unrelated "No desired topology
  published" table cell matched), so no dashboard change applies here.
  [mechanism: grep of the dashboard HTML found no publish UI surface to update]

### Fixed (PR #836 review round 1)

- `validatePartition` no longer guesses a partition count when the stream's declared count is
  unknown at the route. The prior `.or(DEFAULT_PARTITIONS)` fallback fired whenever
  `ensureStreamExists`'s `.recover` had swallowed a genuine (never transient) materialization
  failure, validating against a hardcoded default instead — a stream with more partitions got
  valid requests wrongly refused, and one with fewer got an invalid partition wrongly accepted and
  forwarded, failing later with a different status than the one promised. `ensureStreamExists` now
  surfaces that failure as `ManagementServerError.StreamUnavailable` (`409 Conflict`, naming the
  stream and the underlying cause) instead of swallowing it, so `validatePartition`'s own
  empty-`streamInfo()` branch is a defensive backstop, not the primary guard — and it, too, answers
  `StreamUnavailable` rather than a guessed count.
  [verified: `aether/node/src/test/java/org/pragmatica/aether/api/routes/StreamApiRoutesPublishPartitionTest.java#publish_streamMaterializationFails_reportsStreamUnavailable_neverGuessesPartitionCount`]
- Documented that `publish-batch` is not atomic: items before the first invalid partition are
  already durably written when the batch call fails. Pre-existing property of the concurrent
  per-item write, newly reachable now that per-item partition validation can fail.
  [mechanism: `publishMany` fires every item concurrently via `Promise.allOf` with no
  short-circuit, then `Result.allOf` surfaces only the first `Result` failure — documented in
  `aether/docs/reference/management-api.md` (Publish section)]

