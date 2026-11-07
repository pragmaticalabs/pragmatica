### Changed (2026-11-06 — #2807: slice-to-slice failover re-routes only a request that was provably not sent)
- **A refused write no longer waits out the invocation timeout, and is the only failure the runtime re-routes.**
  A typed slice-to-slice call whose write the transport refuses (back-pressure, no peer state, encode failure) now
  fails at once and goes to another instance, chosen by the normal endpoint selection
  with every refusing instance excluded, at most `timeouts.invocation.max_retries` (default 3) times and never past
  the caller's deadline. `[verified: aether/aether-invoke/src/test/java/org/pragmatica/aether/invoke/SliceInvokerNotSentRerouteTest.java,
  unit level with a stub transport]`
- An unknown outcome (a timeout, including a dead connection whose frame may still be delivered from the offline
  buffer), a target that departed after the send (now the typed `SliceInvokerError.TargetDeparted`, whose log no
  longer claims a retry) and a callee failure are surfaced once and never re-sent, because a non-idempotent call
  must not run twice. `[mechanism: SliceInvoker re-routes on ForwardError.NotSent only]`
- `timeouts.invocation.max_retries` was a dead key; it now bounds the NotSent re-routes. The uncalled
  `SliceInvoker.invokeWithRetry` (which re-sent after any failure, including timeouts) and
  `SliceInvokerError.AllInstancesFailedError` are removed. `timeouts.invocation.retry_base_delay` is still not read.
- When every instance refuses (or the re-routes are used up), `invoke` fails with the new
  `SliceInvokerError.NoInstanceAccepted`, deliberately not an `HttpError`: a route that returns it answers 500 and never
  says "safe to retry", because the route's own handler may already have applied effects before it made the call.
  `ForwardError.NotSent` keeps its client wording on the forwarding hop only.
- Completion-awaited calls and scheduled tasks fail fast too: a refused write now fails them at once with
  `ForwardError.NotSent` (was `CompletionUnknown` after the timeout), and the scheduler records that fire as failed.
  They are not re-routed.
