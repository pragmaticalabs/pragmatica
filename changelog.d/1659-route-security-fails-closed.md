### Security (2026-09-29 — #1659: route security failed OPEN on nodes that do not host the route)
- **An override added after a route first registered was not enforced by nodes that do not host that route.** A node
  authorizes a request for a route it does not serve from the cluster route registry. The registry kept the policy
  registered FIRST for as long as any node served the route, so the republish carrying the override never reached
  it. The non-hosting node inherited the global policy, accepted any valid key and forwarded the request: 200 where
  the host answered 403. Any slice with fewer instances than nodes was exposed.
- **Registry.** Each route keeps one entry per (node, artifact) that serves it. A republish replaces that entry, the
  removal of the artifact's route key drops only that artifact's entries, and a departed node's entries are all
  dropped. The route reports the STRONGEST policy across its entries, in a total order: equal strengths are broken by
  the policy's canonical string, so every node resolves a route the same way. While entries disagree (a republish in
  flight or failed), the route is as strict as its strictest entry.
- **Ingress.** A route entry carries the declared policy beside the enforced one. A node that does not host a route
  applies its own COMMITTED overrides to that declared policy, by the rule the host uses. It falls back to the
  replicated (strongest) policy when no override matches. So an override takes effect at every ingress, and a
  relaxed one relaxes there, without waiting for peers to republish.
- **Nested prefixes.** A request is judged by the LONGEST matching route prefix at the ingress, the route the host
  serves it by. The first match in ascending order had let a PUBLIC `/api/` admit requests to a protected `/api/admin/`.
- **Overlapping overrides** resolve to the MOST SPECIFIC matching pattern, whatever order they are listed in. The
  first-listed match used to win, so `GET /api/*` = `public` listed ahead of `GET /api/admin/*` = `role:admin` left the
  admin route admitting any valid key.
- **Host re-authorization.** The host re-authorizes every forwarded request against the route it serves it by: its
  own longest local match, with its own overrides. The ingress authorizes against its own view, and while routes or
  overrides propagate that view can lack a narrower, stricter route the host already serves. A rollout forward from a
  local PUBLIC parent could otherwise reach a protected child unauthenticated. A request is served only when both ends
  admit it. A refusal returns the same 401/403 response the ingress would send.
- **Wire.** `NodeRoutesValue.RouteEntry` gained `declaredSecurity`, which changes its byte layout. Nodes on the previous
  build cannot decode the new entries, and a route snapshot taken before the upgrade is not readable by the new build.
  This is accepted pre-GA, with no migration path.
  [verified at `4979555b5`: each of the above has a named unit test that goes red under its mutation. See the table in
  PR #1670. Ember `BlueprintSecurityOverrideClusterWideTest`, one instance on three nodes: every node answers 403 after
  the override and 200 after it is withdrawn.]
  [unverified: Ember with nested prefixes or a live propagation window; no fixture slice declares nested prefixes.]
