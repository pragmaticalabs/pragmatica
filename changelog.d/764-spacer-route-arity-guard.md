### Fixed (2026-09-13 — #764: a listed route could still 404 "Unknown request path" through the spacer-route holes in the dispatcher)
- The rc3 fix for #764 (arity guard in `RequestRouter.selectBestRoute`) closed the defect for
  plain-parameter routes and left two holes for **spacer-bearing** routes (`withPath(aLong(),
  spacer("edit"))`): the guard exempted them ("left to their own matching"), so a lone
  `/api/users/{id}/edit` route was handed `GET /api/users/42` by the single-candidate shortcut, and
  `findFallbackRoute` returned `candidates.getFirst()` unconditionally, so with only spacer siblings
  registered (`…/edit`, `…/profile`) a path naming neither was dispatched to whichever registered
  first. Either way the handler reached `pathParam(1)` on a one-segment path and the request died two
  layers deeper as `RequestContext.NOT_FOUND` — `404 "Unknown request path"` from a route that is
  registered and listed, indistinguishable to the caller from a client error and different text from
  the ordinary `"No route found for …"` miss.
  [mechanism: `Route.pathParamCount()` counts spacers, so arity applies to spacer routes exactly as
  to parameter routes; `RequestContext.pathParam(int)` is the sole producer of "Unknown request path"]
- `selectBestRoute` now holds every candidate to the arity guard and additionally requires a spacer
  route's spacers to be present in the path before it is viable, so neither the shortcut nor the
  fallback can return a spacer route the path does not name; `findFallbackRoute` returns only a
  spacer-free candidate and otherwise nothing, so an under-specified match is a routing miss and the
  server answers with the ordinary no-match.
  [verified: `integrations/http-routing/src/test/java/org/pragmatica/http/routing/SpacerRouteArityGuardTest.java`
  — unit level: `RequestRouter.findRoute` returns empty for the under-supplied, bare-prefix and
  spacer-mismatch shapes and still resolves the fully specified paths; the `findFallbackRoute` hunk
  is not independently pinnable once the viability filter holds (reverting it alone leaves all six
  green) and stands as defence for the sibling cases, which it catches when the filter's spacer
  clause is reverted]
- Unchanged, deliberately: an **over-supplied** path (more trailing segments than the route's arity)
  still matches by prefix, because static-file and other prefix-consuming routes rely on it; the
  route registry (`/api/v1/routes`) still advertises arity-erased prefixes, which is the remaining
  half the ticket thread names and a KV wire change (`HttpNodeRouteValue`), not this fix.
  [design intent — unverified]
