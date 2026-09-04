### Fixed (2026-09-05 — #294: polling is not gated to health)

- Every dashboard poll timer (primary status/events/alerts, the secondary fallback timer, and
  the requests store's own timer) now skips its tick while a client-side `degraded` flag is
  set, instead of continuing to poll a backend the operator's own health check has already
  marked unhealthy.
  [verified: `DashboardPollingGateContractTest.appJs_pollingTimers_skipWhenClusterDegraded_backOffToSlowRetryWhenHealthUnknown`,
  `DashboardPollingGateContractTest.requestsJs_pollingTimer_skipsWhenDegraded_backsOffToSlowRetryWhenHealthUnknown`]
- The gate is driven by a new, ungated health probe (run on every primary-timer tick so the
  dashboard can detect recovery, not only degradation) keyed semantically on
  `status !== 'healthy'` — there is no literal `"degraded"` wire value in either health shape
  this dashboard talks to.
  [verified: `DashboardPollingGateContractTest.checkHealth_probesVersionedHealthFirst_thenBareHealthFallback_viaProbeHealthNotPlainGet`,
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
- **Revised again after second review (a BLOCKING finding): the gate now tracks three states,
  not two.** Round one's fail-open collapsed "probe answered, no route here" and "probe
  unreachable" into the same `degraded = false` — `RestClient.get()` returns an identical `null`
  for a 404 and for a fetch-level network exception, so a total backend outage read exactly like
  a harmless missing health route, the gate cleared, and every OTHER poller resumed hammering a
  dead backend every 2-3s with network-error toasts the 404 suppression never covered — #294's
  own toast storm, reintroduced in the worst case. A new `RestClient.probeHealth()` reports
  reachability separately from the parsed body (any HTTP response, 404 included, marks the probe
  reachable; only a fetch-level exception marks it unreachable), and a pure `decideHealthState()`
  turns two such probes into one of three verdicts:
  - `healthy` — a probe answered `status: "healthy"`, or a probe answered at all with no usable
    health payload (404 on both paths: reachable, just no route here). Fails open, same as round
    one.
  - `degraded` — a probe answered `status: "unhealthy"`.
  - `unknown` — BOTH paths failed with a network-level error. `decideHealthState()`'s unknown
    branch returns `{unknown: true}` with no `degraded` key at all, so a prior `degraded = true`
    verdict is never overwritten by an outage — a total outage on top of a known-degraded cluster
    must not read back as healthy. It clears the moment either probe answers anything, even a
    404. While unknown, every poll timer backs off to a shared slow 10s retry
    (`cluster.unknownRetryDue()`), and `RestClient` routes every network-exception `.catch()`
    through `_reportNetworkFailure()`, which suppresses the toast (warning once instead) for as
    long as `healthUnknown` stays true — an isolated network blip while the server is otherwise
    known-reachable still toasts normally.
  [verified: `DashboardPollingGateContractTest.decideHealthState_reachableWithNoHealthJson_failsOpenToHealthy_neverToDegradedTrue`,
  `DashboardPollingGateContractTest.decideHealthState_bothProbesUnreachable_omitsDegradedKeyEntirely_soCallerCannotOverwriteIt`,
  `DashboardPollingGateContractTest.checkHealth_unknownBranch_returnsBeforeDegradedAssignment_priorDegradedTrueSurvivesNetworkFailure`,
  `DashboardPollingGateContractTest.checkHealth_healthUnknownFlag_assignedUnconditionally_clearsOnAnyAnswerIncluding404`,
  `DashboardPollingGateContractTest.clusterJs_declaresHealthUnknownFlag_defaultingFalse_withSharedTenSecondRetryThrottle`,
  `DashboardPollingGateContractTest.restClientJs_catchBlocks_routeThroughReportNetworkFailure_notDirectToastPerOccurrence`,
  `DashboardPollingGateContractTest.restClientJs_reportNetworkFailure_suppressesToastOnlyWhileHealthUnknown_warnsOnceInstead`,
  `DashboardPollingGateContractTest.restClientJs_probeHealth_neverToasts_reachableMeansAnyHttpResponseNot404Only`]
- A repeat 404 from an endpoint the target server has no route for is now logged once per
  method+path (`console.warn`) instead of toasting on every poll tick; every other failure
  status (5xx, network error while the server is otherwise known-reachable) still toasts on
  every occurrence — a narrow carve-out for the specific, expected case of polling an
  unimplemented endpoint, not general failure suppression.
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
