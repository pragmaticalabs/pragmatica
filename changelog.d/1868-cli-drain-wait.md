### Fixed (2026-10-03 — #1868: every CLI drain wait polled for a `DECOMMISSIONED` state the server never emits)

- **Rolling restart, drain-and-destroy, scale-down, replacement, `cluster destroy` and `cluster drain --wait` all timed out.**
  The waits keyed on `state == "DECOMMISSIONED"`, but `NodeReportedState` has only SYNCING / READY / DRAINING: a drained node
  runs `DrainProcedure`, halts, and stops reporting, so the only observables of completion are the lifecycle route's 404 and the
  halted process refusing connections. Each wait therefore ran its full 120 s budget and aborted the operation behind it
  (`docker stop`, destroy and reprovision never ran).
- **One completion predicate, `DrainCompletion.isComplete`, now serves every site.** A 404 is completion; a connection failure
  is completion only when the polled address was the drained node itself (a cluster-endpoint poll says nothing about the target);
  a timeout, any other HTTP error and any 200 (READY, SYNCING, DRAINING) are not.
- **A wait cannot be entered without an accepted drain.** The WaveExecutor sites call `ClusterHttpClient.drainNodeAndAwait`, which
  is the drain request followed by the wait, so a pre-first-pong 404 or an unreachable node is never read as "drained".
- **The per-node lifecycle GET no longer answers 404 from a node that has no authoritative view.** LIST already returned
  503 + leader hint without an authoritative (leader) or fresh cached (follower) readiness view; the per-node GET did not, so
  a cold follower answered 404 for every node. It now returns the same 503, and the CLI treats 503 as not complete.
- [unverified: `cluster destroy` and `cluster drain --wait` poll through the cluster endpoint; if that endpoint is itself a node
  being drained, its connection failures are (correctly) not completion, so those waits can still time out.]
- The old `ClusterHttpClientDrainStateTest` hand-fed `"state":"DECOMMISSIONED"`; it specified the defect and is replaced by tests
  driven with the shapes `NodeLifecycleRoutes` actually returns.
