### Fixed (2026-10-05 — #1921: a composite cause and a malformed id both answered 500 on management routes)
- **`ProblemResponses.resolveStatus` now looks inside a composite cause.** `Result.all` / `Result.allOf` hand back a composite when
  several failures funnel together; it did not implement `HttpStatusAware`, so even when every member carried a client-error status
  the response was 500. It now answers the one status every member agrees on. Members that disagree, an empty composite and an
  untyped member stay 500, pinned by their own tests so the unwrap cannot silently widen.
- **One shared parse helper for caller-supplied ids**, `RequestParse.asRequest`: the domain parsers (`BlueprintId`, `Artifact`,
  `Version`, `NodeId`) fail with an untyped cause, so a malformed id answered 500. It becomes a 400 that keeps the parser's message
  (a cause that already carries a status is left alone). Applied at `GET/DELETE /blueprints/{id}`, `GET /blueprints/status/{id}`,
  `GET /slices/config/{id}`, the version of `POST /deploy`, the node id of `/config/nodes/{id}/...` and the four node-lifecycle
  routes.
- **The stream and topic routes' `namespace/name/version` addresses go through the same helper** (16 sites in `StreamApiRoutes`,
  `StreamNamespacesRoutes` and `TopicRoutes`): a malformed address is 400, not 500. `STREAM_NOT_FOUND` and `GROUP_NOT_FOUND` are
  typed 404 (`ManagementServerError.NotFound`, declared identically in #1924 so the two merge cleanly in either order). Still
  untyped and listed rather than changed: the `StreamRegistryError.General.NOT_FOUND` the metadata route funnels.
- **The consumer-group coordinator's not-leader refusal is typed.** `CoordinatorError.NOT_LEADER` (the coordinator is dormant on this node) was an
  untyped cause, so group create, join and leave answered 500 for what every other leader-bound route answers as 409. A small
  `CoordinatorRefusal.typed` maps it to `ManagementServerError.NotLeader` at the four call sites. Its message is now the standard
  not-leader one, so `StreamRoutesGroupSystemStreamTest` reads that text instead of the old enum text.
- Pinned by `ManagementCompositeAndIdParseStatusTest`: with the production hunks reverted, 16 of its 19 tests answer 500 (the other
  three are the stays-500 controls).
- **Honest scope of the unwrap:** of the three funnel sites the ticket named, `ClusterAwaitQuiescedRoute` maps its composite to a
  typed `InvalidRequest` already, and `StreamApiRoutes` group delete funnels `CoordinatorError`, which is itself untyped, so no
  production route turns the unwrap from 500 to a typed status today; it fixes the class at the funnel, ahead of the next typed
  member. [unverified: `TopicRoutes` group details, whose members were not read]
- [unverified: the four `NodeLifecycleRoutes` sites, which route through the same helper but were not driven; `StatusRoutes` parses a
  node id with `.option()` and is not an error path]
