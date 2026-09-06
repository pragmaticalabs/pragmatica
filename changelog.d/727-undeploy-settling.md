### Fixed (2026-09-06 — #727: `SliceInvocationTest` died blind at a fixed 240s on CI; the settling run was never done)

- **The 240s ceiling is the test's own `WAIT_TIMEOUT`
  (`aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/SliceInvocationTest.java`),
  and it guards five waits, not two**: leader election and all-nodes-healthy in `@BeforeAll`, the
  echo-slice deploy wait in three tests, and the undeploy wait in
  `invokeAfterSliceUndeploy_returnsNotFound`. #727's earlier commit named the two setup waits; the
  three in-test waits still reported only `Condition with Lambda expression ... was not fulfilled
  within 240 seconds`, and none of the five said what any node was doing at expiry.
- **The settling run reproduced the signature and named its mechanism — it is not slowness.** On a
  quiet native 16-core box (2026-09-06, no reruns) ten sequential runs of the class were
  indistinguishable: leader elected 7.2s after cluster start every time, class 31.8s ± 0.1s. Pinned
  to 4 CPUs like a hosted runner: 34.4–36.0s (4 runs). With those 4 CPUs three-times oversubscribed
  by busy-loop hogs: 44.1–44.9s in four runs, and in the fifth
  `invokeAfterSliceUndeploy_returnsNotFound` errored at **240.5s** — the ticket's exact signature,
  and the stalled await was that test's DEPLOY wait (`SliceInvocationTest.java:180` before this
  change), not the setup waits the earlier #727 commit named. The node logs give the chain:
  `@BeforeEach cleanUp` deletes the previous test's blueprint without waiting; the test re-applies
  the same artifact ~50ms later; under load the node's unload and the new ACTIVATE directive cross
  (`Slice ... state is ACTIVATE but not found in SliceStore`); the leader classes that as
  deterministic ("will NOT retry"), and under `ALL_OR_NOTHING` rolls the blueprint back and removes
  it from the KV store. From then on `/api/v1/slices/status` has no echo-slice at all — the poll's
  condition can never hold and its `sliceHasFailed` fail-fast never sees the FAILED entry, which
  existed for 7ms against a 500ms poll. Two fixes, both in the test, and which is which:
  (1) `cleanUp` now waits for the undeploy to settle before the next apply, so the apply no longer
  races the unload it follows — the event-driven option, applied to the ordering that actually
  produced the failure; (2) the deploy wait fails fast when `GET /api/v1/blueprints/status/{id}`
  (#759) reports a `FAILED`/`ROLLED_BACK` outcome recorded after this test's own apply — the
  diagnostic option, so a rollback is a named failure in seconds, not a blind 240s. The 240s bound
  itself stays: it is ~30x the quiet formation time and 5x the worst emulated load, so raising it
  would only make a rollback fail slower
  [verified for (1): the hog5 run log and hang dump on the settling box (see the #727 report), and the
  fixed test green through the same 4-CPU + 8-hog conditions five times out of five, against one
  240s timeout in five before — small numbers, stated as such]
  [design intent — unverified for (2): no deterministic post-apply failure was available to drive
  the fail-fast — an artifact that is missing, or present but not a slice, is rejected at apply time
  with a 500 before any rollback can occur; it rests on the #759 status contract and the hog5 log].
- **Product defect surfaced, not fixed here: a re-apply immediately after a delete of the same
  artifact can fail deterministically.** The node reports `state is ACTIVATE but not found in
  SliceStore` when the ACTIVATE directive reaches it while the previous instance's unload is still
  in flight, and the leader treats that as non-retriable and rolls the blueprint back. An operator
  running `delete` then `apply` quickly gets the same rollback. Timing-dependent: never seen on the
  quiet box in ten runs; with the old un-awaited delete it hit 3 times in 11 runs under 3x CPU
  oversubscription, in two orderings — failure before the new instance is counted (rollback, the
  240s hole) or after it (the old instance counted as "fully deployed" 20ms after apply, then a
  FAILED entry lingering ~30s until the next reconcile). Needs its own ticket
  [mechanism: `ClusterDeploymentState.handleDeterministicFailure` on `NodeDeploymentState.Active
  .handleSliceNotFoundForActivation`; log lines at 22:16:23.894–23.990 in the hog5 run].
- **Every wait in the class now carries a name and the cluster's state at expiry** — leader, each
  node's formation state and its `/api/v1/health` answer (or why it was unreachable), and for slice
  waits the last `/api/v1/slices/status` body
  [verified: mutation probes on the same box — health condition made unsatisfiable, deploy condition
  made unsatisfiable, each wait failed at its bound carrying the alias, the leader and all three
  nodes' health bodies; with the conditions restored the class is green, 9 tests].
- **The undeploy wait could pass on a failing query.** It was a bare
  `!getSlices().contains("echo-slice")`; an error body from the status endpoint contains no
  `echo-slice` either, so a broken query satisfied "undeployed". The shared helper fails fast on an
  error body for the undeploy wait exactly as the deploy waits always did
  [verified: probe injecting a failing check into the undeploy wait's fail-fast turns the test red
  by name].
- **Two waits in the class had no bound at all, and a bounded JUnit backstop cannot end them.**
  `cluster.start().await()` and `cluster.stop().await()` were untimed. `PromiseImpl.await()` re-parks
  until the promise resolves and never consults the interrupt flag, so the 8-minute lifecycle
  backstop (#556) interrupts a thread that simply parks again: no `TimeoutException`, no thread
  dump, no test named — only failsafe's 30-minute fork wall. Observed on the settling box when two
  runs of this class accidentally split one port range: the survivor sat in `setUp:68` for 586s,
  through the 480s backstop, until killed. Both awaits are now bounded by the same 240s, and a start
  failure carries the cluster snapshot
  [verified: probe with two management ports pre-taken fails `setUp` naming the bind, within the
  bound].
- **`EmberCluster.start()` hung forever on a partial start (`aether/ember`).** A node whose start
  fails settles its promise at once; a node whose start succeeds settles only on consensus quorum.
  With two of three nodes unable to bind, the third waited for a quorum that could never form,
  `Promise.allOf` never settled, and `start()` had no failure path left — it is the mechanism behind
  the 586s above, and it turns any partial bind failure in a forge class (a leaked port from a
  previous class's wedged teardown, for example) into a silent job wall. The first node-start
  failure now aborts the start: every node is stopped under the existing 10s per-node bound and the
  bind failure is the returned cause
  [verified: `aether/ember/src/test/java/org/pragmatica/aether/ember/EmberClusterPartialStartFailureTest.java`
  — two management ports pre-bound, `start().await(60s)` returns the bind failure and the survivor's
  port is reclaimable; against the unfixed `EmberCluster` the same test fails with a 60s `Timeout`
  cause].
- **The other #727 member (`ClusterProvisioningDiagnosticsProbeTest` at 497.6s) is a shutdown hang,
  closed under #749/#750**, and on this box it passed in 23.6s inside the CI-equivalent set (16
  classes, 44 tests, 499s wall; one failure was `ClusterFormationTest` binding port 5152, taken by
  another tenant's container on the shared host). `EmberCluster.stop()` took 12.04s in all ten
  `SliceInvocationTest` runs: each node's stop runs ~2s of synchronous graceful-shutdown quiet period
  on the caller's thread before returning its promise, so the three "parallel" stops serialise. The
  10s per-node bound never fired. Not fixed here, stated so it is not mistaken for a guarantee: that
  bound's expiry is invisible — `Promise.allOf(...).map(_ -> unit())` discards the per-node
  `Result`s, so a node that timed out and a node that stopped both end in "Ember cluster stopped"
  [mechanism: `EmberCluster.stop()`; `Promise.allOf` collects `List<Result<T>>` and the `map`
  ignores it].
