### Fixed (2026-10-04 — #833, #954: management POSTs answered 500 for the caller's own mistakes)
- **`ProblemResponses.resolveStatus` resolves a cause that is not `HttpStatusAware` to 500, and these routes raised bare
  `Causes.cause(...)` values**, so a request missing a required field, and a leader-bound write that reached a non-leader,
  were indistinguishable on the wire from a cluster fault. `POST /api/v1/deploy` already answered 400; the routes below did not.
- **400 for a missing or unparseable field, naming it:** `POST /api/v1/scale` (missing `artifact`/`instances`, malformed
  coordinates), `POST /api/v1/config` (missing key or value), `POST /api/v1/logging/levels` (missing or unknown level),
  `POST /api/v1/cluster/keys` (missing `keyId`/`keyHash`; it previously reached the consensus Put), `POST /api/v1/ab-tests/create`
  (missing `artifactBase`/`variants`, malformed artifact base or variant version), and the shared `artifact` check of
  `POST /api/v1/blueprints/deploy` and `/publish`. The scale route's two-field check used `Result.all`, whose composite cause
  is not `HttpStatusAware`, so a request missing both fields would still have answered 500; it now fails on the first.
- **409 for a leader-bound A/B write on a non-leader** (`ab-tests/create`, `ab-tests/conclude/{id}`), via
  `ManagementServerError.NotLeader` (which had no constructor before this change), matching `SchemaNotLeader`. It fires when
  task-group owner resolution itself fails, which is when an operator most needs a typed answer. The manager-level
  `AbTestDeploymentError.NotLeader` (a node that is leader but not yet activated), `TestNotFound`, `VariantNotFound` and
  `TestAlreadyExists` are typed too (409 / 404 / 400 / 409). Not every not-leader refusal in the codebase answers 409:
  `DeploymentError.NOT_ASSIGNED` answers 503.
- **`POST /api/v1/cluster/keys/revoke/{id}`:** an unknown key is now 404 and a key declared in node configuration is 409.
- `POST /api/v1/cluster/config` and `/cluster/scale` already answered typed 4xx at this base (`ClusterConfigError`); not changed.
- New `ManagementServerError.InvalidRequest` carries a route's own diagnosis unchanged with status 400.
- `management-api.md` states the 400/409 contract. Pinned at the route-handler boundary by `ManagementPostClientErrorStatusTest`
  (one or more tests per route, each red when the production hunk is reverted).
- [unverified: a real multi-node cluster; the tests drive the real `Route` handlers and `ProblemResponses.writeProblem`, not a bound listener]
- [unverified: a malformed JSON body that fails to parse at all is answered by the routing layer (#772) and is not covered here]
