### Fixed (2026-10-04 — #1314: removing one NodeRoutesKey removed the node from unrelated artifacts' HTTP routes)
- **`LoadBalancerManager` reduced a `NodeRoutesKey` removal to its node id**, so unloading artifact A from a
  node also withdrew that node from every route of artifact B it still hosted. When the node was B's only
  host, the external load balancer stopped routing B entirely until the next leader-side reconcile rebuilt
  the table from KV.
- The manager now tracks each `NodeRoutesKey`'s contribution and derives route membership from them: a
  removal withdraws only that key's routes, a put **replaces** that key's contribution (a route the new
  value omits is withdrawn), and a route/node pair shared by two artifacts stays until its last
  contribution goes. Repeated puts and removes are idempotent.
  [mechanism: `LoadBalancerManager.Active.contributions`, keyed by `NodeRoutesKey`; pinned by
  `LoadBalancerManagerTest$ContributionScope`]
- **`HttpRouteRegistry` only ever added on a put**, so a route a node's republication dropped stayed
  registered for that node indefinitely. A put now replaces the (node, artifact) contribution, swapped per
  method in one step so a lookup never sees it half-replaced. Its removal was already scoped to the
  artifact since #1659. [mechanism: `HttpRouteRegistry.replaceContribution`; pinned by
  `HttpRouteRegistryContributionTest`]
- [unverified: no multi-node run; the change is pinned by unit tests against the handlers only]
