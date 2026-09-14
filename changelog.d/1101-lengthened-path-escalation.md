### Security (2026-09-14 — #1101: an OPERATOR key executed the ADMIN-only `CONFIG_NODE_DELETE` by appending a path segment)
- **Two halves, neither an escalation alone.** `ManagementServerImpl.resolvePermission` looked up the
  exact route table and, with no exact match, fell back to the prefix registry —
  `DELETE /api/v1/config/nodes/<id>/<key>/junk` has no exact match, its prefix `/api/v1/config` is
  OPERATOR, while the exact `CONFIG_NODE_DELETE` it extends is ADMIN. `RequestRouter.selectBestRoute`
  then admitted the over-length path (parameter count ≤ trailing segments) and dispatched the first
  two segments to `ConfigRoutes.handleDeleteNodeConfig`. Net: an OPERATOR key deleted node config.
  Both are fixed. **Authorisation:** an unmatched request now resolves to the STRICTEST of the prefix
  rule and every same-method exact route in its resource family (the path up to the first segment
  after `/api/v1`, or `/repository`) — deny-by-default wherever a stricter exact route lives. The
  matrix walk found the same weakening on `SCHEDULED_TASK_INJECT`, `BACKUP_RESTORE` and
  `BLUEPRINT_VALIDATE` (exact ADMIN, prefix OPERATOR/VIEWER); all resolve ADMIN now. **Dispatch:** a
  spacer-free route that declares parameters consumes exactly that many trailing segments — an
  over-length path is a routing miss (`404 No route found`), never a dispatch of the first N. Arity-0
  routes keep their prefix tolerance: `StaticFileRouteSource` registers `route(GET, urlPrefix, …)` and
  consumes the remainder itself, and an arity-0 handler binds nothing positional; with the
  authorisation half, a lengthened arity-0 mutation is authorised no weaker than the route it reaches.
  [verified: `aether/node/src/test/java/org/pragmatica/aether/api/LengthenedPathPermissionMatrixTest.java`
  — every mutating `ManagementRoute`, lengthened with a trailing junk segment and with junk inserted
  before the last segment, must not resolve weaker than its own permission (6 cells red before, 0
  after), plus the ticket's `CONFIG_NODE_DELETE …/anything` cell by name;
  `integrations/http-routing/src/test/java/org/pragmatica/http/routing/OverLengthPathTest.java` — a
  two-param route with three segments is a miss, an arity-0 route still matches `/static/css/site.css`]
- Consequence to state: `ARTIFACT_INFO` with a dotted group (`/repository/info/org/example/hello/1.0.0`,
  4 segments for 3 params) was dispatched with mis-split params (#1102); it is now a routing miss —
  a 404 instead of a wrong answer, and #1102 remains the fix. Stacked on #1076 (the spacer-route
  viability filter this rule extends).
  [design intent — unverified: no request was sent over a socket; both halves are pinned at the
  `resolvePermission` and `findRoute` seams the ticket's own reproduction used]
