### Fixed (2026-09-14 — #742: legacy consumer-group join/leave accepted a reserved system stream name)
- **`POST /api/v1/streams/groups/join` and `.../leave` (`StreamRoutes#joinGroup`/`#leaveGroup`) passed
  a body-carried `streamName` straight to `ConsumerGroupCoordinator` with no `SystemStreams` check.**
  `ManagementServer`'s pre-auth write-gate inspects method+path only (gate condition 1: no parallel
  body parser), so these two routes were the one remaining write surface where a caller could name
  `cluster-events` — and the coordinator's `joinGroup`/`leaveGroup` both `rebalance`, proposing real,
  replicated `KVCommand.Put` assignment records under that name. Both handlers now carry the same
  guard `createFreshStream` has: `SystemStreams.isForbiddenEngineKey(name)` as the first statement,
  before any coordinator call, refusing with `Cannot join or leave a consumer group on a reserved
  system stream`. Same predicate, not a reimplementation; post-auth, like CREATE, because
  body-carried identity leaves no earlier hook.
  [verified: `aether/node/src/test/java/org/pragmatica/aether/api/routes/StreamRoutesGroupSystemStreamTest.java`
  — with the real `noOp()` coordinator the outcomes are mutually exclusive by cause: the guard's
  refusal for `cluster-events`, `NOT_LEADER` (coordinator reached) for `orders`]
- The three surfaces that described this as "a known, currently open gap" now say what protects it:
  `ManagementServer`'s `rejectSystemStreamWrite` doc, `management-api.md` (System stream write
  gating), `management-api-versioning-spec.md` §3.3 blast-radius row. The §3.3 catalog-form reshape
  of these routes remains #754's, unchanged and independent of this guard.
