### Fixed (2026-09-07 — #727 (partial): `SliceInvocationTest` died blind at a fixed 240s on CI; the settling run was never done)

This fixes the **reproduced member** of #727 — the 240s undeploy/re-apply race in `SliceInvocationTest` —
and bounds that class's lifecycle awaits. It does not close the ticket. 240.5s is the value of the
ceiling constant, not a fingerprint of a cause: any stall of that wait produces it, so five CI
occurrences prove the same wait expired, not that the same mechanism expired it. Both failing CI logs
also carry `HttpRoutePublisherImpl.unpublishRoutesFromCluster ... Failed to unpublish HTTP routes`
shortly before the summary and the reproducing run carries none; that error is demonstrably benign in
passing classes, which establishes it *can* be benign, not that it *was*. And the sibling member
(`MultiSourceCommunitySmokeTest` at 515.4s, a different 480s backstop) is untouched. #727 stays open.

- **The 240s ceiling is the test's own `WAIT_TIMEOUT`
  (`aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/SliceInvocationTest.java`),
  and it guards five waits, not two**: leader election and all-nodes-healthy in `@BeforeAll`, the
  echo-slice deploy wait in three tests, and the undeploy wait in
  `invokeAfterSliceUndeploy_returnsNotFound`. #727's earlier commit named the two setup waits; the
  three in-test waits still reported only `Condition with Lambda expression ... was not fulfilled
  within 240 seconds`, and none of the five said what any node was doing at expiry
  [verified: `WAIT_TIMEOUT` is derived from the single `WAIT_BOUND` constant and is the only `atMost`
  in the class — `awaitFormation`, `awaitSlices` and both lifecycle awaits read it, so the five waits
  are enumerable from the source; the three CI class-level ERRORs at 240.4-240.5s are in the #727
  report].
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
  quiet box in ten runs; with the old un-awaited delete the race FIRED 3 times in 11 runs under 3x CPU
  oversubscription — once as a failure and twice without one, which is the interesting part. The two
  orderings: failure before the new instance is counted (rollback, the 240s hole, the one red) or after
  it (the old instance counted as "fully deployed" 20ms after apply, then a FAILED entry lingering ~30s
  until the next reconcile — both runs still passed 9/9). That the SAME race self-heals in one ordering
  and rolls the blueprint back in the other is the sharpest available argument that the classification,
  not the timing, is the defect: the transient branch re-drives `reconcile()`, and ordering 1 was denied
  that only because a catch-all `Fatal` fallback made a concurrent-unload race look deterministic. Why
  ordering 2 escapes `permanentlyFailed` is not traced here (plausibly the blueprint had already retired
  from `inFlightBlueprints`, so the rollback loop matched nothing) — stated as unverified, and it belongs
  to the ticket, not here. **Now filed as #916**; this change works around it and does not fix it
  [mechanism: `ClusterDeploymentState.handleDeterministicFailure` on `NodeDeploymentState.Active
  .handleSliceNotFoundForActivation`, whose `SLICE_NOT_FOUND_FOR_ACTIVATION` cause reaches
  `SliceLoadingFailure.classify`'s `Fatal.UnexpectedError` fallback arm]
  [verified for ordering 1: log lines at 22:16:23.894–23.990 in the hog5 run; for ordering 2 and for the
  3-in-11 count, which spans both phases: the round-4 r1 and r4 run logs, which are the two greens].
- **Every wait in the class now carries a name and the cluster's state at expiry** — leader, each
  node's observed consensus state and its `/api/v1/health` answer (or why it was unavailable), and for
  slice waits the last `/api/v1/slices/status` body. The per-node state field was a hardcoded literal
  when this bullet was first written; see review round 1 below for what it reports now and how that is
  pinned
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

#### Review round 1 (2026-09-07)

- **The state dump printed a hardcoded constant as if it were an observation, and that was the whole
  point of the dump.** `EmberCluster.toNodeStatus` passed the string literal `"healthy"` into every
  `NodeStatus`; no code path produced any other value. Every node in every dump, and every
  `/api/nodes/status` body, read `state=healthy` — including a node that never formed. A reader of the
  next formation stall would have seen `leader=none` beside three healthy nodes and looked in the wrong
  place, which is worse than omitting the field. The value is now read from the node
  (`AetherNode.isReady()`, the consensus-active sample the readiness pong answers from) and is named
  for what it measures: `active` / `inactive`, never an unqualified health verdict. The test-side dump
  reports a health endpoint it could not reach as `unavailable (<cause>)`, never as a state
  [verified: `aether/ember/.../EmberClusterObservedNodeStateTest` — a formed three-node cluster reports
  every node `active`; `aether/ember/.../EmberClusterPartialStartFailureTest` — a cluster where two of
  three nodes cannot bind reports every node `inactive`; restoring the `"healthy"` literal turns both
  red, a constant `active` turns the second red, a constant `inactive` turns the first red;
  `aether/forge/forge-tests/.../ClusterSnapshotTest` — an unanswerable health probe renders
  `health=unavailable (...)` and the line contains no claim of health]
- **The cluster snapshot was empty on exactly the start failures it was added for.** Both start-failure
  paths (`abortStart` and `handleStartResults`) run `clearClusterStateOnFailure` before the failure
  reaches the caller, so the snapshot the caller then printed read an emptied registry: `leader=none`
  and zero node lines. `EmberCluster.lastStartFailure()` now retains what `status()` answered at the
  moment of failure, plus each failing node's cause, captured before the stops begin and reset at the
  head of every `start()`. The dump labels a retained snapshot as captured rather than presenting it as
  live, and says so by name when there is neither a live registry nor a retained failure
  [verified: `EmberClusterPartialStartFailureTest` asserts the live registry is empty AND the retained
  snapshot names all three nodes with their real state and the bind failures; moving
  `captureStartFailure` below `clearClusterStateOnFailure` turns it red on an empty snapshot]
- **The deploy fail-fast queried a follower while the apply and the delete went to the leader.** It
  used node 1 unconditionally; under the replication lag it targets, that yields a missed fail-fast
  rather than a false red, but it blunts the check in exactly the conditions it exists for. One port
  helper now serves the apply, the delete and the fail-fast
  [mechanism: `SliceInvocationTest.leaderOrAnyMgmtPort`, `getLeaderManagementPort().or(anyMgmtPort())`]
- **A bounded await whose expiry named nothing.** `EmberClusterPartialStartFailureTest.tearDown`
  discarded the `Result` of `cluster.stop().await(30s)`, so an exhausted bound passed silently — the
  same blind wait this ticket exists to remove. It now asserts, and names the cause when it fails.
  It doubles as the pin for the idempotent-stop claim: by the time it runs, `abortStart` has already
  stopped every node, so the assertion is evidence rather than a docstring
  [verified: same test class, green with the abort's stops preceding it]
- **Every remaining `await()` in `SliceInvocationTest` is bounded**, including the three on the failure
  path — the path that runs when things are already wrong. `PromiseImpl.await()` re-parks until
  resolved and never consults the interrupt flag, so an unbounded await there outlives JUnit's
  lifecycle backstop [mechanism: a single 60s `HTTP_BOUND`, far above each request's own 10s JDK
  timeout, so it fires only if the promise never settles at all]
- **An ambiguous blueprint-status body now fails loudly instead of silently.** The fail-fast's
  timestamp reader took the first `"timestampMs"` in the body. That is correct today
  (`BlueprintStatusResponse` carries exactly one, `BlueprintSliceStatus` none) and would have broken
  silently the day a per-slice timestamp is added: the guard would compare the wrong number and stop
  firing, which reads exactly like a deployment that never failed. A second match is now an error
  naming the body; an absent one still returns 0, which fails the guard, so the default stays "do not
  fail fast" [mechanism: `SliceInvocationTest.outcomeTimestampMs`]

#### Residuals — known, not fixed here

- **#916** — a transient activation race is classified as permanent: `SliceLoadingFailure.classify`'s
  catch-all `Fatal` arm rolls the blueprint back under `ALL_OR_NOTHING`. The product half this
  test-side change works around.
- **#914** — `Promise.await()` ignores interruption, so a bounded JUnit backstop cannot end an untimed
  await. Every await in the classes touched here is bounded; the underlying behaviour is unchanged.
- **#915** — `MultiSourceCommunitySmokeTest`'s two untimed lifecycle awaits, the same pattern in
  #727's other member.
- **The `HttpRoutePublisherImpl` unpublish error in both failing CI logs is unexplained.** Six benign
  occurrences inside `EmberCluster.stop()` in passing classes show it *can* be teardown noise; nothing
  shows it *was*, in those two failures.
- **`EmberCluster.stop()` discards its per-node `Result`s**, so a node that exhausted the 10s bound and
  a node that stopped cleanly both end in "Ember cluster stopped". Unchanged, and the reason the stop
  assertions added here are on the aggregate only
  [mechanism: `Promise.allOf(...).map(_ -> unit())` drops the `List<Result<T>>`].
- **`EmberClusterPartialStartFailureTest` keeps fixed ports** because it must pre-bind two of them on
  purpose, so it remains exposed to another tenant of a shared box holding one of its other ports.
  `EmberClusterObservedNodeStateTest` probes for a free block instead, after a first run failed on a
  contended 25702.
- **`applyStartedAtMs` compares the test JVM's clock against the node's**, which is sound only because
  Forge runs every node in this JVM on this host. Not a portable guard.
