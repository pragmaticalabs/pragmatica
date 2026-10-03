### Fixed (2026-10-03 — #1868: every CLI drain wait polled for a `DECOMMISSIONED` state the server never emits)

- **Rolling restart, drain-and-destroy, scale-down, replacement, `cluster destroy` and `cluster drain --wait` all timed out.**
  The waits keyed on `state == "DECOMMISSIONED"`, but `NodeReportedState` has only SYNCING / READY / DRAINING: a drained node
  runs `DrainProcedure`, halts, and stops reporting, so the only observables of completion are the lifecycle route's 404 and the
  halted process refusing connections. Each wait therefore ran its full 120 s budget and aborted the operation behind it
  (`docker stop`, destroy and reprovision never ran).
- **One completion predicate, `DrainCompletion.isComplete`, now serves every site, and what counts depends on what was polled.**
  Polling the drained node itself (the WaveExecutor sites), only a REFUSED connection is completion: any answer it serves,
  404 included, came from its live process (the lifecycle GET is leader-routed, so the target relays the leader's view).
  Through the cluster endpoint (`cluster destroy`, `cluster drain --wait`), the leader's 404 is completion and a connection
  failure is not. A timeout, a reset, a DNS failure, any other HTTP error and any 200 (READY, SYNCING, DRAINING) are not.
- **`HttpClientError.fromException` unwraps `CompletionException` / `ExecutionException`.** `JdkHttpOperations` maps the
  failure a dependent `CompletableFuture` stage delivers, which is wrapped, so every refused connection and timeout surfaced
  as a generic `Failure`: the drained node's halt could never complete a wait, and the WaveExecutor sites still timed out.
- **A wait cannot be entered without an accepted drain.** The WaveExecutor sites call `ClusterHttpClient.drainNodeAndAwait`, which
  is the drain request followed by the wait, so a pre-first-pong 404 or an unreachable node is never read as "drained".
- **The per-node lifecycle GET has the same authority guard as LIST** (503 + leader hint without an authoritative or fresh
  cached readiness view), and the CLI treats 503 as not complete. Defence in depth: both routes are `LEADER`-targeted, so
  `ManagementServer` forwards them from a follower and the handler runs on the leader, where the guard always passes.
- [unverified: `cluster destroy` and `cluster drain --wait` poll through the cluster endpoint; if that endpoint is itself a node
  being drained, its connection failures are (correctly) not completion, so that wait times out, and in `destroy` every later
  drain and shutdown request also goes to the halted endpoint (sibling fallback covers enumeration only).]
- [unverified: the leader's 404 that the cluster-endpoint waits read is soft state. It drops a LIVE draining node on a
  transient QUIC evict until the node's next pong, after three silent ping intervals, and on a newly elected leader until the
  first pongs arrive, so those two commands can report completion early inside that window.]
- The old `ClusterHttpClientDrainStateTest` hand-fed `"state":"DECOMMISSIONED"`; it specified the defect and is replaced by tests
  driven with the shapes `NodeLifecycleRoutes` actually returns.
