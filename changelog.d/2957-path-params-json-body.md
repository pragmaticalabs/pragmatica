### Fixed (2026-11-09 — #2957: routes with path params and a JSON body required the path-bound components in the body)
- **A route that merges path or query parameters with a JSON body answered 400 "Missing required field"** for the
  components it fills from the path (`POST /articles/{slug}/comments`, `PUT /articles/{slug}`): the routes holder's
  generated codec decoded the whole request record and required every component.
- The body codec of such a record no longer requires its path- and query-bound components, and skips a body member of
  the same name unread: the path value wins. A record with a validating factory is built from the merged value only,
  so the factory never judges the body alone. `[verified: jbct/slice-processor-tests/src/test/java/com/example/pathbody/GeneratedPathBodyRouteTest.java]`
- The relaxation applies only where every decode of the record agrees: a record that another route of the slice
  decodes whole, or that a body nests, keeps every component required, and its merged route still needs the member
  in the body. `[verified: GeneratedPathBodyRouteTest.StrictWhereAnotherDecodeNeedsTheComponent]`
