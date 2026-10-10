### Fixed (2026-10-04 — #755, #1103: a route's spacers are matched by position, not by presence)
- **`RouteShapeSelector.allSpacersPresent` was a set-membership check**, so `GET /api/users/edit/42` was selected for
  `withPath(aLong(), spacer("edit"))`: `edit` is in the path, just not where the route declares it. The handler's
  `pathParam()` then failed with `expected 'edit', got '42'`, surfacing as a 5xx instead of a routing miss. The #764 arity guard
  checks how many trailing segments there are, not where the literals sit. #1103 was closed as the same defect.
- **A route now carries `spacerSlots()`**, the index of each spacer among its trailing segments, computed where `withPath(...)`
  already collects the spacers. `RouteShapeSelector` matches positionally whenever a shape carries one slot per spacer; the
  request is then refused, and a spacer-free sibling of the same arity can serve it. `RouteShape.spacerSlots()` defaults to empty,
  meaning "not carried".
- **Production callers of this path:** `ManagementRouter` (the management API dispatch), `SliceRouter` (a slice's own HTTP
  router) and Forge's API router all dispatch through `RequestRouter.findRoute`.
- **Every node-local path is positional.** `HttpRoutePublisher.wrapHandler` rebuilds each published route for observability and
  used to drop the slots, so the router a node actually served stayed on membership; it now passes `route.spacerSlots()`.
  `HttpRouteDefinition` (local to the serving node, not the replicated wire record) carries the slots too, so the host's own
  resolution (`resolveServed`) picks the slice that declared the position, and `sameShape` distinguishes shapes that differ
  only by slot, so a PUBLIC sibling no longer inherits its admin sibling's policy on its own host. With ONE node hosting both
  slices, each path now reaches the slice that declared that position; before, one of them answered 404 or 500.
- **Residual in a split deployment (node A hosts `edit/{id}`, node B hosts `{id}/edit`):** A's local shape keys collide with B's
  slot-less replicated key, so A drops B's entry and answers 404 for B's path. That was a 5xx before this change, so it is not a
  regression, and it belongs to the replicated-entry follow-up below. [unverified: not driven]
- **Open remainder, stated rather than papered over (#1678): the gateway still matches by membership.** A node that does not
  host a route selects over the REPLICATED entry (`AetherValue.HttpRoute`: `pathArity`, `spacers`), which carries no positions.
  Closing it carries positions through `AetherValue.HttpRoute`, `HttpRoutePublisher`, `HttpRouteRegistry` and `RouteSource`: a
  wire change. An ENABLED tripwire, `HttpRouteRegistryGatewaySpacerPositionTest`, asserts today's wrong gateway behaviour through
  a fixture built by the real publisher and registry, so it reddens when positions are carried; the real assertion sits `@Disabled`
  beside it.
  A non-hosting node therefore authorizes the PUBLIC sibling as the admin sibling when their shapes are slot-only twins; that fails
  closed, and the host re-authorizes positionally. [unverified: not driven]
- Pinned by `PositionalSpacerSelectionTest` (both tickets' repros, a swapped spacer/parameter pair, a parameter whose value equals the
  spacer literal, leading and trailing spacers, fallthrough to a spacer-free sibling) and, for the published router and the host's
  resolution, `SpacerPositionHostResolutionTest`.
- [unverified: a real gateway-to-host request for the #1103 shape; the gateway half is pinned at `RouteInfo.matchingShape`, not through a bound listener]
