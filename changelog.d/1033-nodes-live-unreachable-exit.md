### Fixed (2026-09-21 — #1033: `aether nodes live` reports an empty table and exits 0 when it cannot reach the cluster at all)
- **A transport failure was rendered as "no nodes".** `AetherCli.fetch` folds a failed send into a
  `{"error":"…"}` envelope instead of a `Result`, and `OutputFormatter.printQuery` treated that
  envelope as a document: in table mode it navigated to the missing `nodes` array and drew a header
  over nothing, exit 0; in JSON mode it pretty-printed the envelope to stdout, exit 0. The reader could
  not tell "asked, zero nodes" from "could not ask". `printQuery` now refuses an error envelope before
  any format or `--quiet` handling: the cause goes to stderr (`Error: …`, or `{"error":"…"}` under
  `--format json`) and the exit code is 1 (3 for a 404 ProblemDetail), the same routing
  `checkResponseError` already gave the commands that call it explicitly. A genuine document with zero
  rows still renders its header and exits 0. `[verified: aether/cli/src/test/java/org/pragmatica/aether/cli/UnreachableClusterExitTest.java]`
  (the real entrypoint in a child JVM against a released loopback port, stdout and stderr captured
  separately) and `OutputFormatterErrorTest`.
- **One consumer, one fix:** every query command that prints a `fetch` result through `printQuery` —
  `status`, `nodes`, `nodes live`, `slices`, `routes`, `versions`, `metrics …`, `alerts`, `traces`,
  `blueprints list/get/status`, `artifacts …` and the rest — takes the same path, so all of them now
  refuse an unreachable cluster. The `cluster …`, `storage …`, `ttm …`, `whoami` and `stream …`
  families were already honest: they receive a `Result<String>` from `ClusterHttpClient` and fold the
  failure before printing. `[mechanism: one `printQuery` entry, `isErrorResponse` before dispatch]`
- **`nodes live --only-alive` erased the error before the formatter saw it:** `LiveNodesFilter.onlyAlive`
  rebuilt any parseable document — including the envelope — into `{"nodes":[],"liveCount":0,"zombieCount":0}`.
  It now passes through untouched any document without a `nodes` array. `[verified: LiveNodesFilterTest.onlyAlive_errorEnvelope_returnsOriginalUnchanged]`
- **The action commands had the inverse defect** (found in review): `printAction` printed its success line over
  the same envelope and exited 0, so `scale foo -n 3`, `logging set/reset`, `config set/remove`,
  `thresholds set/remove`, `controller evaluate`, `scheduled-tasks pause/resume/trigger`,
  `observability depth-set/depth-remove/config-set/config-remove`, `ab-tests conclude`, `streams
  publish` and `consumer-group join/leave` (20 sites) reported an operation that never ran as one
  that succeeded — `scale --wait` then polled `unknown / N` to the deadline. `printAction` now takes
  the same refusal as `printQuery`, so `scale --wait` exits before the wait starts.
  `[verified: UnreachableClusterExitTest.scale_*, configSet_*, scheduledTasksTrigger_*, scaleWait_refused_exitsNonZero_beforeAnyPolling]`
- **A non-2xx with a JSON body that is neither an envelope nor a ProblemDetail rendered as a document**
  (e.g. a gateway's `500 {"message":"boom"}`): `formatErrorResponse` passed any body starting with `{`
  through. It now passes a body through only when it is itself an error envelope or a ProblemDetail,
  and wraps anything else as `{"error":"HTTP <status>: …"}`. Aether's own management server always
  answers a ProblemDetail, so this is reachable only through something in front of it.
  `[verified: UnreachableClusterExitTest.nodesLive_gateway500_…, scale_gateway500_…]`
- Not changed here: the message text for a refused connection reads `HTTP operation failed:
  java.net.ConnectException` rather than `Connection failed: Connection refused`, because the JDK
  client delivers the cause wrapped in a `CompletionException` that `HttpClientError.fromException`
  does not unwrap. It names the failure; it could name it better. `[design intent — unverified]`
