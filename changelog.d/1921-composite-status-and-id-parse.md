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
- Pinned by `ManagementCompositeAndIdParseStatusTest`: with the production hunks reverted, 7 of its 10 tests answer 500 (the other
  three are the stays-500 controls).
- **Honest scope of the unwrap:** of the three funnel sites the ticket named, `ClusterAwaitQuiescedRoute` maps its composite to a
  typed `InvalidRequest` already, and `StreamApiRoutes` group delete funnels `CoordinatorError`, which is itself untyped, so no
  production route turns the unwrap from 500 to a typed status today; it fixes the class at the funnel, ahead of the next typed
  member. [unverified: `TopicRoutes` group details, whose members were not read]
- [unverified: the four `NodeLifecycleRoutes` sites, which route through the same helper but were not driven; `StatusRoutes` parses a
  node id with `.option()` and is not an error path]
