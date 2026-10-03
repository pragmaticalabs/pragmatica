### Fixed (2026-10-03 — #1868: every drain wait polled for a `DECOMMISSIONED` state the server never emits)

- **Rolling restart, drain-and-destroy, scale-down, replacement, `cluster destroy`, `cluster drain --wait` and
  `rolling-aether-upgrade.sh` all timed out.** They keyed on `state == "DECOMMISSIONED"` (the script also on `ON_DUTY`), but
  `NodeReportedState` has only SYNCING / READY / DRAINING: a drained node runs `DrainProcedure`, `Runtime.halt(2)`s, and
  simply stops reporting. Each wait ran its full budget and aborted the operation behind it (`docker stop`, destroy and
  reprovision never ran).
- **Completion is one rule, `DrainCompletion.isComplete`: the drained node's OWN management address refuses the connection
  (`ConnectException`), observed after the drain was accepted.** No HTTP answer is ever completion, a 404 included: the
  lifecycle GET is leader-routed, so a 404 polled on the target was relayed by a live process, and the leader's readiness
  view is soft state that reads 404 for a LIVE draining node after a transient QUIC evict, after three missed pongs, and on a
  newly elected leader. A timeout, a reset, a DNS failure, a 503 and any 200 are not completion either.
- **The wait cannot be entered without an accepted drain.** The WaveExecutor sites call `ClusterHttpClient.drainNodeAndAwait`
  (drain request, then wait).
- **`cluster destroy` and `cluster drain --wait` have no target address**, so after an accepted drain they report
  `DrainCompletion.NotObservable` at once instead of polling for a verdict they cannot reach: destroy records the node as
  "drain accepted, completion not observable" and goes on to shutdown without a wait; `drain --wait` exits non-zero with that
  message. **Behaviour change:** destroy no longer waits out a drain grace before shutting nodes down (the old wait always
  timed out, then shut down anyway). `drain --wait` no longer accepts `--timeout`.
- **`rolling-aether-upgrade.sh`** needs `--node-endpoint <id>=<host:port>` per node and waits for that address to refuse
  connections; a node with no endpoint is not shut down. Its `ON_DUTY` state, which does not exist either, is now `READY`.
- **Producer fix: `HttpClientError.fromException` unwraps `CompletionException`/`ExecutionException`.** `JdkHttpOperations`
  delivers failures wrapped, so a refused connection used to surface as a generic `Failure`, never as `ConnectionFailed`. Because
  that makes these types real for every consumer of the client, their retry classification was narrowed: `Timeout` is no longer
  `Cause.Transient` (a timed-out request may already have executed; `RetryOn.TRANSIENT` and the notification senders would
  duplicate a non-idempotent call), and `ConnectionFailed` is transient only for a refusal or an unknown host (the request
  provably never reached a server), not for a reset mid-request.
- **The per-node lifecycle GET answers 503 without an authoritative view**, as LIST already did. Defence in depth only: the route is
  leader-routed, so it does not make the leader's 404 trustworthy.
- The old `ClusterHttpClientDrainStateTest` hand-fed `"state":"DECOMMISSIONED"`; the first replacement scripted a ready-made
  `ConnectionFailed` the real client never produced. The tests now script raw exceptions through the real mapping and run the
  real `JdkHttpOperations` against a closed socket and a live 404 listener.
- [unverified: `rolling-aether-upgrade.sh` still calls unversioned `/api/nodes/...` paths and `/api/nodes/activate`; whether
  they route was not checked.]
- [unverified: the server's 404 for a node that has departed is still soft state; an authoritative departure fact
  (`MembershipFsm` past Departing) would let a cluster-endpoint poll complete soundly. Follow-up, not this change.]
