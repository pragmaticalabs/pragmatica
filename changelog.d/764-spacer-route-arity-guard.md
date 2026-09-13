### Fixed (2026-09-13 — #764: a listed route could still 404 "Unknown request path" through the spacer-route holes in the dispatcher)
- The rc3 fix for #764 (arity guard in `RequestRouter.selectBestRoute`) closed the defect for
  plain-parameter routes and left two holes for **spacer-bearing** routes (`withPath(aLong(),
  spacer("edit"))`): the guard exempted them ("left to their own matching"), so a lone
  `/api/users/{id}/edit` route was handed `GET /api/users/42` (or `GET /api/users/edit`) by the
  single-candidate shortcut; and `findFallbackRoute` returned `candidates.getFirst()` unconditionally,
  so with only spacer siblings registered (`…/edit`, `…/profile`) a path naming neither was dispatched
  to whichever registered first. What the misdispatch died as depended on the path: one segment short
  (`/api/users/42`) reached `pathParam(1)` and surfaced as `RequestContext.NOT_FOUND` — `404 "Unknown
  request path"`, the ticket's text, from a route that is registered and listed; arity met but the
  spacer absent (`/api/users/42/delete`) died at the spacer parse — `PathMismatch`, which
  `ErrorMapper.defaultMapper` renders as **500** `"Path segment mismatch: expected 'edit', got
  'delete'"`. Both now answer with the ordinary `404 "No route found for …"` miss — for the second
  shape that is a 500 → 404 status change.
  [mechanism: `Route.pathParamCount()` counts spacers, so arity applies to spacer routes exactly as
  to parameter routes; `RequestContext.pathParam(int)` is the sole producer of "Unknown request path";
  `SliceRouter.notFound` is the ordinary miss]
- `selectBestRoute` now holds every candidate to the arity guard and additionally requires a spacer
  route's spacers to be present in the path before it is viable, so neither the shortcut nor the
  fallback can return a spacer route the path does not name. `findFallbackRoute`'s
  `candidates.getFirst()` branch is removed: once the viability filter holds it is unreachable (every
  viable spacer route already matched and was preferred above), so this is dead-branch removal, not
  defence in depth, and no test can red it while the filter stands.
  [verified: `integrations/http-routing/src/test/java/org/pragmatica/http/routing/SpacerRouteArityGuardTest.java`
  — unit level: `RequestRouter.findRoute` returns empty for the under-supplied, bare-prefix,
  spacer-present-but-under-supplied (`/api/users/edit`, pins the arity clause alone) and
  spacer-mismatch shapes, and still resolves the fully specified paths]
- Unchanged, deliberately: an **over-supplied** path (more trailing segments than the route's arity)
  still matches by prefix, because static-file and other prefix-consuming routes rely on it; a spacer
  present in the WRONG slot (`/api/users/edit/42`) still dispatches and dies as a 500 — `routeMatchesPath`
  checks presence, not position — pre-existing, same family, its own follow-up; the route registry
  (`/api/v1/routes`) still advertises arity-erased prefixes, which is the remaining half the ticket
  thread names and a KV wire change (`HttpNodeRouteValue`), not this fix.
  [design intent — unverified]
