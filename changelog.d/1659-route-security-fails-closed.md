### Security (2026-09-29 — #1659: a security override failed OPEN on nodes that do not host the route)
- **An override added after a route first registered was not enforced by nodes that do not host that route.**
  A node authorizes a request for a route it does not serve from the cluster route registry, and the registry kept
  the policy registered FIRST for as long as any node served the route. So the republish carrying the override
  never reached it: a non-hosting node saw the pre-override policy, inherited the global policy, accepted any valid
  key and forwarded the request, answering 200 where the hosting nodes answered 403. It surfaced in CI after a quorum
  flap left one node without its local route, but it needed no flap: any slice with fewer instances than nodes was
  exposed.
- The registry now keeps each node's published policy, replaces it on every republish, and drops it when the node's
  entry is removed or the node departs. It reports the STRONGEST policy among the nodes serving the route, so while
  nodes disagree (a republish in flight or failed) the route is as strict as its strictest node.
- An ingress applies its own COMMITTED security overrides to a route it does not host, through the same rule the
  hosting node uses, against the route's declared policy (now carried in the route entry beside the enforced one).
  An override therefore takes effect, and a relaxed or removed one relaxes, at every ingress without waiting for
  peers to republish. With no matching override the replicated (strongest) policy applies, and it relaxes once every
  serving node has republished.
  [verified: `aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/BlueprintSecurityOverrideClusterWideTest.java`
  — `overrideAddedAfterTheRouteRegistered_isEnforcedOnNodesThatDoNotHostTheRoute`, one instance on three nodes: every
  node answers 403 after the override and 200 again after it is withdrawn; with both layers reverted the two
  non-hosting nodes answer 200]
  [unverified: the Ember test goes red only with BOTH layers reverted; each layer alone keeps it green, so each layer
  is pinned by unit tests only (`HttpRouteRegistrySecurityRefreshTest`, `AppHttpServerRouteSecurityPolicyTest`)]
- **A non-hosting ingress judges a request by the LONGEST matching route prefix, the one the host serves it by.**
  Remote routes are matched most-specific first, the rule the hosting node applies to its own routes (#884).
  Previously the ingress took the first match in the registry's ascending order, which is the SHORTEST prefix. So a
  PUBLIC `/api/` let a request through to a protected `/api/admin/` subtree, and the host, which serves a forwarded
  request by its longest prefix, does not re-authorize it.
  [verified: `AppHttpServerRouteSecurityPolicyTest` — `remoteRoute_nestedPrefixes_theInnerRouteGovernsItsSubtree`,
  `remoteRoute_nestedPrefixes_committedOverrideOnTheInnerRoute_isEnforced`]
  [unverified: Ember nested-prefix, because no Ember fixture slice declares nested prefixes]
