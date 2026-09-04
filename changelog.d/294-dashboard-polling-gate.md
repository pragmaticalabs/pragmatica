### Fixed (2026-09-05 — #294: polling is not gated to health)

- Every dashboard poll timer (primary status/events/alerts, the secondary fallback timer, and
  the requests store's own timer) now skips its tick while a client-side `degraded` flag is
  set, instead of continuing to poll a backend the operator's own health check has already
  marked unhealthy.
  [verified: `DashboardPollingGateContractTest.appJs_pollingTimers_skipWhenClusterDegraded`,
  `DashboardPollingGateContractTest.requestsJs_pollingTimer_skipsWhenClusterDegraded`]
- The gate is driven by a new, ungated health probe (`GET /health`, run on every primary-timer
  tick so the dashboard can detect recovery, not only degradation) keyed semantically on
  `status !== 'healthy'` — there is no literal `"degraded"` wire value in either health shape
  this dashboard talks to. `degraded` defaults to `false` and is left at its last known value
  on a missing/failed probe response; it is never fabricated from a failed fetch.
  [verified: `DashboardPollingGateContractTest.restClient_probesHealthEndpoint_semanticallyNotByLiteralDegradedString`,
  `DashboardPollingGateContractTest.clusterJs_declaresDegradedFlag_defaultingFalse`]
- **Scope boundary, stated plainly:** the probe uses bare `/health`, not `/api/health` or
  `/api/v1/health`. This is the path Forge's `StatusRoutes` actually serves and what this
  dashboard is demonstrably run against today; the real node's Management API has no bare
  `/health` route at all (only `/api/v1/health`, `/health/live`, `/health/ready`), so against a
  real node this probe 404s like every other dashboard call does — see the versioning gap
  below. Forge's own `HealthResponse` is hardcoded to always report `"healthy"` and can never
  signal degradation, an honest limit on this gate's real-world triggerability against Forge.
  [mechanism: `StatusRoutes.java` bare `/health` route vs. `ManagementServer`'s absence of one;
  Forge's `HealthResponse` construction is `new HealthResponse("healthy", ...)` unconditionally]
- A repeat 404 from an endpoint the target server has no route for is now logged once per
  method+path (`console.warn`) instead of toasting on every poll tick; every other failure
  status (5xx, network error) still toasts on every occurrence — a narrow carve-out for the
  specific, expected case of polling an unimplemented endpoint, not general failure
  suppression.
  [verified: `DashboardPollingGateContractTest.restClientJs_suppressesRepeat404Toasts_logsInstead`]
- **Not in this PR (Forge proxy for the missing endpoints):** 24 REST paths the dashboard calls
  have no matching Forge route today and 404 under Forge (2 more are near-misses mounted under
  a different prefix than the dashboard calls — `/api/thresholds*` vs. Forge's
  `/api/alerts/thresholds*`, and `/api/cluster/topology` vs. Forge's `/api/slices/topology`).
  Every one of these now benefits from the 404-suppression above (logged once, not repeatedly
  toasted), but none is proxied or stubbed by this fix. Full list, each with its calling
  dashboard file: `/api/slices` (app.js), `/api/thresholds`, `/api/thresholds` (POST),
  `/api/thresholds/{metric}` (DELETE) (alerts.js), `/api/controller/config` (GET+POST),
  `/api/ttm/status`, `/api/logging/levels` (GET+POST) (cluster.js), `/api/routes`
  (deployments.js), `/api/cluster/config` (desiredtopology.js), `/api/cluster/governors`,
  `/api/cluster/topology` (governors.js), `/api/invocations/metrics`,
  `/api/invocations/metrics/slow` (requests.js), `/api/schema/status`,
  `/api/schema/retry/{datasource}` (schema.js), `/api/cluster/storage`, `/api/storage`,
  `/api/storage/{name}`, `/api/storage/snapshot/{name}` (storage.js), `/api/deploy`,
  `/api/ab-tests` (strategies.js), `/api/streams` (streams.js).
  [mechanism: cross-referenced every `RestClient.get/post/put/del` call site in the dashboard
  JS against every registered route in Forge's 9 `*Routes.java` files]
