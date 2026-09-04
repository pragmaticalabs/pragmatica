### Fixed (2026-09-05 — #294: polling is not gated to health)

- Every dashboard poll timer (primary status/events/alerts, the secondary fallback timer, and
  the requests store's own timer) now skips its tick while a client-side `degraded` flag is
  set, instead of continuing to poll a backend the operator's own health check has already
  marked unhealthy.
  [verified: `DashboardPollingGateContractTest.appJs_pollingTimers_skipWhenClusterDegraded`,
  `DashboardPollingGateContractTest.requestsJs_pollingTimer_skipsWhenClusterDegraded`]
- The gate is driven by a new, ungated health probe (run on every primary-timer tick so the
  dashboard can detect recovery, not only degradation) keyed semantically on
  `status !== 'healthy'` — there is no literal `"degraded"` wire value in either health shape
  this dashboard talks to.
  [verified: `DashboardPollingGateContractTest.restClient_probesVersionedHealthFirst_thenBareHealthFallback_semanticallyNotByLiteralDegradedString`,
  `DashboardPollingGateContractTest.clusterJs_declaresDegradedFlag_defaultingFalse`]
- **Revised after first review:** the probe now tries `GET /api/v1/health` FIRST — the only
  health route the real node's Management API serves, which has migrated ahead of this
  dashboard (`ManagementRoute`, #300) — falling back to bare `GET /health`, what Forge actually
  serves. The first cut probed bare `/health` only, which meant the gate silently never engaged
  against a real node at all (every probe 404'd, `degraded` never left its default). Forge's own
  `HealthResponse` is hardcoded to always report `"healthy"` and can never signal degradation, an
  honest limit on this gate's real-world triggerability against Forge.
  [mechanism: `StatusRoutes.java` bare `/health` route vs. `ManagementServer`'s `/api/v1/health`;
  Forge's `HealthResponse` construction is `new HealthResponse("healthy", ...)` unconditionally]
- **Fail-open, also added after first review:** if BOTH the versioned and bare paths fail to
  answer, `degraded` is explicitly set to `false`, never left to drift or fabricated as `true`.
  Conflating "probe unreachable" with "reports degraded" would let a target that answers
  neither health path permanently gate off every other poll with no path back — the very probe
  meant to detect recovery would itself never succeed. The failure is warned once per session
  (`console.warn`), not re-logged on every 2s tick.
  [verified: `DashboardPollingGateContractTest.checkHealth_bothProbesFail_failsOpen_neverSetsDegradedTrue`]
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
