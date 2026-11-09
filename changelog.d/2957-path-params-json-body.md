### Fixed (2026-11-09 — #2957: routes with path params and a JSON body required the path-bound components in the body)
- **A route that merges path or query parameters with a JSON body answered 400 "Missing required field"** for the
  components it fills from the path (`POST /articles/{slug}/comments`, `PUT /articles/{slug}`): the routes holder's
  generated codec decoded the whole request record and required every component.
- The body codec of such a record no longer requires its path- and query-bound components, and skips a body member of
  the same name unread: the path value wins. A record with a validating factory is built from the merged value only,
  so the factory never judges the body alone. `[verified: jbct/slice-processor-tests/src/test/java/com/example/pathbody/GeneratedPathBodyRouteTest.java]`
- The relaxation applies only where every decode of the record agrees: a record that another route of the slice
  decodes whole, that a body nests, or that a response record nests as a component keeps every component required (one
  codec serves clients and inter-slice decoding too), and its merged route still needs the member in the body. A record
  that is only a response root stays relaxed. `[verified: GeneratedPathBodyRouteTest.StrictWhereAnotherDecodeNeedsTheComponent]`
- The build now emits a WARNING naming the route, the record, the component and the reason whenever a bound component
  stays required. `[verified: SliceProcessorTest.mergedRoute_*]` Under `[json] naming = "snake_case"` the bound set is
  matched on the Java component name and the body member on its wire name, and a skipped member is not an unknown one
  under `fail_on_unknown`. `[verified: GeneratedPathBodySnakeRouteTest]` See `jbct/slice-processor/docs/HTTP-ROUTE-GENERATION.md`.
