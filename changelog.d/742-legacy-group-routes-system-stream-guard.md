### Fixed (2026-09-14 — #742: legacy consumer-group join/leave accepted a reserved system stream name)
- **`POST /api/v1/streams/groups/join` and `.../leave` (`StreamRoutes#joinGroup`/`#leaveGroup`) passed
  a body-carried `streamName` straight to `ConsumerGroupCoordinator` with no `SystemStreams` check.**
  `ManagementServer`'s pre-auth write-gate inspects method+path only (gate condition 1: no parallel
  body parser), so these two routes carried no check at all — and the coordinator's
  `joinGroup`/`leaveGroup` both `rebalance`, proposing real, replicated `KVCommand.Put` assignment
  records under that name. Both handlers now carry the same guard `createFreshStream` has, as the
  first statement before any coordinator call, refusing with `405 Cannot join or leave a consumer
  group on a reserved system stream` — the status the pre-auth gate answers with (a bare
  `Causes.cause` rendered as 500 through `ProblemResponses`, the review found; CREATE's refusal had
  the same defect and carries 405 now too). Same predicate, not a reimplementation, **with the same
  canonicalization in front of it**: the catalog spelling `system:cluster-events:1.0.0` reduces
  through `ResourceAddress` → `StreamManager.engineKey` to `cluster-events` before `SystemStreams`
  is asked, so both spellings are refused (the raw-string check alone let the catalog spelling
  through to the coordinator, which would have committed `ConsumerGroupKey(…,
  "system:cluster-events:1.0.0", i)` — exactly the record the versioned gate refuses). A missing
  or blank name is refused as `Missing stream name`, as CREATE already did. Post-auth, like CREATE,
  because body-carried identity leaves no earlier hook.
  [verified: `aether/node/src/test/java/org/pragmatica/aether/api/routes/StreamRoutesGroupSystemStreamTest.java`
  — with the real `noOp()` coordinator the outcomes are mutually exclusive by cause: the guard's
  refusal for `cluster-events` and for `system:cluster-events:1.0.0`, `NOT_LEADER` (coordinator
  reached) for `orders`; the refusal is `HttpStatusAware` with 405;
  `StreamRoutesCreateSystemStreamTest` pins the catalog spelling for CREATE]
- **`STREAMS_PUBLISH_BATCH` was not in the pre-auth gate at all** — neither in
  `STREAM_IDENTITY_WRITE_ROUTES` nor in `resolveEngineKey` — so a privileged
  `POST …/system/cluster-events/1.0.0/publish-batch` appended caller payloads to the framework's own
  ring while `…/publish` was refused (found by this fix's review, pre-existing on base). It is now in
  both, gated by the same predicate the single form is.
  [verified: `SystemStreamWriteGateTest.catalogForm_publishBatch_toSystemNamespace_isGated`, with an
  app-namespace control]
- The four surfaces that described this as "a known, currently open gap" now say what protects it:
  `ManagementServer`'s `rejectSystemStreamWrite` doc and the `STREAM_IDENTITY_WRITE_ROUTES` doc,
  `management-api.md` (System stream write gating), `management-api-versioning-spec.md` §3.3
  blast-radius row. The §3.3 catalog-form reshape of these routes remains #754's, unchanged and
  independent of this guard.
