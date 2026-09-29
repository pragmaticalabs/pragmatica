### Security (2026-09-29 — #1678: sibling routes of one slice were authorized by one sibling's policy)
- **Sibling routes that share a base path were authorized by whichever sibling was listed first, on every node.**
  The route generator emits `GET /orders/{id}` and `GET /orders/{id}/admin` under the same base, `/orders/`. The
  hosting node picked a route by that prefix alone, and the cluster registry kept one entry per prefix. If the PUBLIC
  sibling was listed first, a request the `role:admin` handler served was authorized as PUBLIC, and a non-hosting
  ingress could do the same in the other declaration order.
- A route is now identified by its **shape**: method, base path, arity and spacers. This is exactly what the slice's
  router uses to tell siblings apart. `RouteShapeSelector` is the router's own sibling rule, extracted and shared:
  - the router dispatches through it;
  - the hosting node resolves the served (artifact, route) through it, ONE resolution for both authorization and
    dispatch, even when two slices share a base;
  - a non-hosting ingress picks the sibling through it from the replicated entries, which now carry the shape;
  - forwarding, retries included, targets only the nodes serving that sibling.
- When no sibling matches (the host would answer 404), the strongest policy across the base's siblings applies.
- **Limitation (#1681):** security overrides still match on the base path, so an override covers every sibling. An operator
  cannot relax or tighten one sibling alone. No route is left open by this.
- **Wire:** `NodeRoutesValue.RouteEntry` gained `pathArity` and `spacers`. This is pre-GA; no migration path.
