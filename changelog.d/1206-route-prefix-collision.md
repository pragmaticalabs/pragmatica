### Fixed (2026-10-07 — #1206: two slices serving the same HTTP route were both admitted; the loser reported healthy and served nothing)
- **Blueprint admission refuses an identical-route collision.** Two slices of DIFFERENT artifacts that declare the same route (same method, same
  path template, path-parameter names and trailing slashes ignored) are refused at publish with a typed refusal naming both slices and the route: a collision between two slices of the SAME blueprint is a malformed request (400, `BlueprintRejected` wrapping
  `ExpanderError.RoutePrefixCollisions`); one with an ALREADY-STORED blueprint conflicts with current state (409, `BlueprintConflict` wrapping
  `ExpanderError.RoutePrefixConflictsWithStored`, naming the stored blueprint and its slice). The check covers the slices of the blueprint being published AND of every
  other stored blueprint, so the second of two blueprints published at different times is refused at its own publish; a blueprint's earlier version is
  excluded (a republish replaces it), two versions of one artifact never collide (rolling redeploy), and a collision already present between two
  stored blueprints does not block an unrelated publish. Overlapping but different routes (`/orders` and `/orders/export`, `/orders/{id}` and
  `/orders/{id}/items`) are admitted; the longest prefix keeps winning for the paths it covers. The runtime tie-break (`HttpRoutePublisher`: longest prefix, then
  the lexically smaller artifact coordinate) is unchanged and stays pinned (`HttpRoutePublisherRouteAgreementTest`).
- **What admission cannot see is announced.** A slice jar unavailable at admission, racing publishes or a slice deployed another way can still produce a
  collision. Every node derives it from the committed route table (an artifact base claims a route while at least one node publishes an ACTIVE entry of it)
  and the cluster-events owner publishes the WARNING `ROUTE_PREFIX_COLLISION` (method, prefix, claiming artifacts) when two bases claim one route and the INFO
  `ROUTE_PREFIX_COLLISION_CLEARED` when only one does; ids are the route, the claimants and the newest `registeredAt`, so a collision that returns is a new event.
  [mechanism: `RoutePrefixCollisionValidatorTest`, `BlueprintRouteCollisionTest`, `RouteCollisionAnnouncerTest`, `ClusterEventAggregatorTest`, `StreamFailoverAnnouncerWiringTest`.]
