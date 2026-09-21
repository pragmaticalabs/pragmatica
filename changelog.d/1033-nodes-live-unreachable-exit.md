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
  `blueprint list/get/status`, `artifact …` and the rest — takes the same path, so all of them now
  refuse an unreachable cluster. The `cluster …`, `storage …`, `ttm …`, `whoami` and `stream …`
  families were already honest: they receive a `Result<String>` from `ClusterHttpClient` and fold the
  failure before printing. `[mechanism: one `printQuery` entry, `isErrorResponse` before dispatch]`
- **`nodes live --only-alive` erased the error before the formatter saw it:** `LiveNodesFilter.onlyAlive`
  rebuilt any parseable document — including the envelope — into `{"nodes":[],"liveCount":0,"zombieCount":0}`.
  It now passes through untouched any document without a `nodes` array. `[verified: LiveNodesFilterTest.onlyAlive_errorEnvelope_returnsOriginalUnchanged]`
- Not changed here: the message text for a refused connection reads `HTTP operation failed:
  java.net.ConnectException` rather than `Connection failed: Connection refused`, because the JDK
  client delivers the cause wrapped in a `CompletionException` that `HttpClientError.fromException`
  does not unwrap. It names the failure; it could name it better. `[design intent — unverified]`
