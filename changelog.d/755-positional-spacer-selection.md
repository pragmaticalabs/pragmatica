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
- **Open remainder, stated rather than papered over (#1678): the gateway still matches by membership.** A node that does not
  host a route selects over REPLICATED shapes (`pathArity`, `spacers`), which carry no positions, so those fall back to the old
  match. Closing it carries positions through `AetherValue.HttpRoute`, `HttpRouteDefinition`, `HttpRoutePublisher`,
  `HttpRouteRegistry` and `RouteSource`: a wire change across about ten modules, not done here. An ENABLED tripwire,
  `HttpRouteRegistryGatewaySpacerPositionTest`, asserts today's wrong gateway behaviour and reddens when it is fixed; the real
  assertion sits `@Disabled` beside it.
- Pinned by `PositionalSpacerSelectionTest` (both tickets' repros, a swapped spacer/parameter pair, a parameter whose value equals the
  spacer literal, leading and trailing spacers, fallthrough to a spacer-free sibling).
- [unverified: a real gateway-to-host request for the #1103 shape; the gateway half is pinned at `RouteInfo.matchingShape`, not through a bound listener]
