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
- **The per-node lifecycle GET's 404 now means "membership committed this node's departure".** It answers 404 only when the
  node's `MembershipFsm` state is `Dead` (or the id was never a member). A tracked, non-departed member missing from the soft
  readiness view (a transient QUIC evict, three missed pongs, a freshly elected leader's empty map) answers 503
  "readiness unknown" instead of the 404 it used to give a LIVE draining node. Without an authoritative view it still answers
  503 + leader hint, as LIST does.
- **`cluster drain --wait` and `cluster destroy` poll through the cluster endpoint** (a member other than the target), where
  that committed-departure 404 is the completion signal (`DrainCompletion.isDeparted`); a 503, a refused connection to the
  endpoint and any 200 are not. **`cluster destroy` drains the node serving its requests LAST** (found by matching the endpoint
  host to a node's transport host via `NODE_ENDPOINT_GET`; if that cannot be matched the order is left as enumerated), waits for
  that node on its own address refusing connections, and sends no shutdown request to a node whose departure it observed
  (it would go through an endpoint that may be that halted node).
- **`rolling-aether-upgrade.sh`** needs `--node-endpoint <id>=<host:port>` per node and waits for that address to refuse
  connections; a node with no endpoint is not shut down. Its `ON_DUTY` state, which does not exist either, is now `READY`.
- **Producer fix: `HttpClientError.fromException` unwraps `CompletionException`/`ExecutionException`.** `JdkHttpOperations`
  delivers failures wrapped, so a refused connection used to surface as a generic `Failure`, never as `ConnectionFailed`. Because
  that makes these types real for every consumer of the client, their retry classification was narrowed: `Timeout` is no longer
  `Cause.Transient` (a timed-out request may already have executed; `RetryOn.TRANSIENT` and the notification senders would
  duplicate a non-idempotent call), and `ConnectionFailed` is transient only for a refusal or an unknown host (the request
  provably never reached a server), not for a reset mid-request.
- The old `ClusterHttpClientDrainStateTest` hand-fed `"state":"DECOMMISSIONED"`; the first replacement scripted a ready-made
  `ConnectionFailed` the real client never produced. The tests now script raw exceptions through the real mapping and run the
  real `JdkHttpOperations` against a closed socket and a live 404 listener.
- **`rolling-aether-upgrade.sh` called routes that do not exist:** `/api/nodes/...` (every `ManagementRoute` is under `/api/v1`;
  no unversioned alias) and `/api/nodes/activate` (no such route). Paths are now `/api/v1/nodes/...` and the activate step is gone.
- [unverified: destroy's serving-node match assumes a node's cluster-transport host equals the management endpoint's host;
  on a cluster where they differ (private transport, public management) nothing matches and the order is left as enumerated.]
- [unverified: that a halted node's `MembershipFsm` reaches `Dead` within the drain wait on a live cluster: pinned with a
  real `MembershipFsm` driven by `onSwimDeparted`, not observed in a running cluster.]
