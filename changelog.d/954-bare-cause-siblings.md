### Fixed (2026-10-04 — #954: the remaining management routes that answered 500 for a refusal that is not a server fault)
- **Same mechanism as the first #954 change:** `ProblemResponses.resolveStatus` resolves a cause that is not `HttpStatusAware`
  to 500, and these routes raised bare `Causes.cause(...)` constants. They are now typed with `ManagementServerError`.
- **404:** `GET /api/v1/ab-tests/{id}` for an unknown test; `GET /api/v1/blueprints/{id}` and `/blueprints/status/{id}` for an
  unknown blueprint; `GET /api/v1/slices/config/{id}` for a slice that is not loaded.
- **409:** `POST /api/v1/scale` for a slice that belongs to no active blueprint; `POST /api/v1/cluster/config` when no core
  leader is committed (it is the same "the cluster is not in a state to accept this" refusal the other leader-bound routes
  report as 409).
- **400:** a missing stream name on `POST /api/v1/streams/groups/join` and `/leave`; an unknown `layer` on
  `GET /api/v1/cluster/journal`; a missing or malformed `epoch`, or a malformed `timeout`, on
  `POST /api/v1/cluster/await-quiesced`. The last was already documented as 400 and answered 500.
- New `ManagementServerError.NotFound` (404) and `Conflict` (409), alongside the `InvalidRequest` (400) of the first change.
- Pinned at the route-handler boundary by `ManagementClientErrorSiblingsStatusTest` (11 tests, each red when the production
  hunk is reverted).
- **Not changed, and why:** `ClusterJournalRoutes.NON_POSITIVE_LIMIT` is swallowed by `.or(DEFAULT_LIMIT)` and never reaches the
  wire; `ClusterTopologyRoutes.CTM_UNAVAILABLE` (a server-side "not ready", 503 rather than a caller error) and
  `StreamApiRoutes.tailDeferred` (a deferred feature, 501) are server statuses and need a ruling; path-id parse failures
  (`BlueprintId`, `Artifact`, `NodeId`, `Version` at about fifteen sites) surface the domain parser's untyped cause and need one shared
  helper rather than fifteen edits.
- [unverified: a real multi-node cluster; the tests drive the real `Route` handlers and `ProblemResponses.writeProblem`, not a bound listener]
