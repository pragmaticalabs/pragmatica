### Fixed (2026-09-14 — #725: `RouteAssembler` rendered a blank param value as two adjacent slashes)
- **An empty or whitespace-only param value passed `RouteAssembler.assemble`'s null check and
  rendered as `/api/v1/deploy/promote/` — a malformed URL `RouteMatcher` round-trips without
  complaint**, so a caller with an accidentally-empty value got a wrong request instead of an error
  at the assembly site. Blank now fails the same way null does: `ManagementRouteError.MissingParam`
  naming the route and the parameter.
  [verified: `aether/aether-management-api` `RouteAssemblerTest.assemble_failsOnBlankParam_ratherThanRenderingAdjacentSlashes`
  — `""`, `" "`, `"\t"`]
- **The ticket's second half — a `/` inside a param value renders as an extra segment — is NOT a
  defect and is deliberately left as it is.** The ticket states neither shape is exercised by any
  route; that is wrong for this one: `aether artifact …` builds `groupPath = group.replace('.', '/')`
  (`AetherCli`) and passes it as ONE value to `ARTIFACT_GET`/`PUT`/`DELETE`/`MAVEN_METADATA`,
  whose `groupPath` spans as many segments as the group has dots — the `%2F` un-escape in
  `encodeSegment` is what makes `/repository/org/example/hello/1.0.0/hello-1.0.0.jar` route
  (`ARTIFACT_INFO` is the one repository route the server does NOT honour this way — it
  positional-parses the dotted group; that is #1102, not this fix). Refusing `/` would break every
  other Maven repository command. The contract is now written next to the rule (`encodeSegment` doc)
  and pinned from the caller's side (`assemble_groupPathWithSlashes_spansSegments_becauseMavenRoutesNeedIt`
  on `ARTIFACT_GET`, beside the pre-existing `assemble_urlEncodesSegments`). A per-parameter "may span segments"
  declaration on `ManagementRoute`, which would let every OTHER param refuse `/`, is a follow-up
  with its own enum change, not this fix.
  [mechanism: `AetherCli` `parts[0].replace('.', '/')` → `ClusterHttpClient` → `ManagementRoute.assemble`]
