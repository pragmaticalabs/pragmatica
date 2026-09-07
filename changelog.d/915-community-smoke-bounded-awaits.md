### Fixed (2026-09-07 — #915: `MultiSourceCommunitySmokeTest` hung to the 8-minute lifecycle backstop on untimed lifecycle awaits, and every other forge class could do the same)

This closes #915 and sweeps the shape out of the whole `forge-tests` module. It is the sibling of
#727/#913 — same family, different member: #727 is a poll that outlives a rolled-back blueprint,
this is an unbounded lifecycle await. It does not touch `SliceInvocationTest`, which #913 owns.

- **The two awaits the ticket names were untimed, and a third one was not in the ticket at all.**
  `cluster.start().await()` in `@BeforeAll`, `cluster.stop().await()` in `@AfterAll`, and
  `cluster.addNode(..).await()` in the worker-join helper — the last found by the module sweep below,
  and able to wedge the `@Test` body exactly as the first wedges setup. All three are now bounded and
  named [verified: `MultiSourceCommunitySmokeTest` green in 40.5s with all three bounded, run
  2026-09-07 in `pragmatica-stream-h`].
- **An unbounded `Promise.await()` on a stalled start is not slow — it is unreachable, and interrupting
  it does not help.** Measured directly, not inferred: a 3-node `EmberCluster` with two of three
  management ports pre-bound (so quorum can never form and `Promise.allOf` never settles) was awaited
  without a bound on a separate thread. After 45s it had not settled and the thread was `WAITING`;
  5s after `Thread.interrupt()` it had still not settled and had flipped to `RUNNABLE` — the
  interrupted waiter spinning rather than stopping; 10s after the interrupt it was `WAITING` again and
  still unsettled. **This is why the JUnit lifecycle backstop could not end the hang**: the backstop's
  only lever is an interrupt, and `Promise.await()` ignores it. That defect is #914 and is NOT fixed
  here — this ticket makes the caller stop waiting, it does not make the wait interruptible
  [mechanism: `Promise.java:3282-3306`, a bare `while (result == null) LockSupport.park()` loop with no
  interrupt check; `park()` returns immediately whenever the interrupt flag is set].
- **The same stall, awaited WITH the bound, fails in 21.4s and names what it waited for.** Against the
  identical pre-bound-port cluster the bounded await produced:
  `3-node cluster start (ports 26700+) did not settle within TimeSpan(20S): Promise is not resolved
  within specified timeout` followed by `leader=none nodeCount=3` and one line per node reporting
  `ready=false`. Before: a class-level ERROR at ~515s whose entire text was the backstop's own timeout
  [verified: forced-stall probe, 2026-09-07, both arms in one run; the probe was scratch and is not
  committed — the committed pin for the same mechanism is `LifecycleAwaitTest`].
- **A bound alone would have been a fast silent failure, so the naming is the fix, not a nicety.**
  `Promise.await(TimeSpan)` expires into the constant string
  `"Promise is not resolved within specified timeout"` — identical for a cluster that never elected a
  leader and a node that never bound its port. Every expiry now carries the step's name, the bound,
  and a cluster snapshot [verified: `LifecycleAwaitTest#aPromiseThatNeverSettles_expiresAtTheBound_andNamesTheStep`;
  three mutation probes each turn it red — hardcoding a different bound, dropping the step name, and
  dropping the snapshot — and the control run is green].
- **The snapshot deliberately does NOT render `NodeStatus.state`.** On this branch
  `EmberCluster.toNodeStatus` passes the string literal `"healthy"` for every node, so the field cannot
  distinguish a formed cluster from a wedged one; printing it would put a fabricated observation into
  the one artifact read when things are already wrong. `ready` is read from the node instead
  (`AetherNode.isReady()`). #913 fixes the literal at its source; this change neither waits on that nor
  touches it [verified: `LifecycleAwaitTest#theSnapshotNeverRendersTheFabricatedHealthyLiteral`].
- **Module sweep: 94 untimed lifecycle awaits found, 92 fixed, 2 deliberately untouched.** Found by
  scanning every `.await()` in `forge-tests` and keeping those whose statement calls `start`, `stop`,
  `startHeldBackNodes`, `addNode`, `addWorkerNode`, `killNode` or `blackhole`. The 2 untouched are
  `SliceInvocationTest.java:68` and `:95`, which PR #913 is bounding in the same round; touching them
  would collide. 36 test classes changed [verified: after the sweep the same scan reports exactly those
  2 remaining].
- **Bounds carry stated margin over a measured healthy run, taken on a LOADED box on purpose.**
  `MultiSourceCommunitySmokeTest` green in 42.09s at load average ~10 (two sibling trees building):
  cluster start ~3-8s, whole formation 18.5s, `addNode` 1.1s, `stop` 16.6s. The cluster bound is 240s
  (~30x the measured start, ~14x the measured stop) and the single-node bound is 120s (~110x the
  measured join). 240s also matches the longest existing internal guard, which preserves the invariant
  `junit-platform.properties` states in prose: the 8-minute lifecycle backstop is double the longest
  internal guard, so the test's own await fires first and the backstop stays an outer net
  [mechanism: `LifecycleAwait.LIFECYCLE_BOUND` / `NODE_BOUND`; a quiet-box baseline was avoided
  deliberately because it would understate what a CI runner does].
- **Residual — 32 of the 36 changed classes are compile-verified only, not executed.** Running the
  whole module is the 25-minute CI job. Executed locally and green: `MultiSourceCommunitySmokeTest`,
  `ClusterFormationTest`, `CoreAbsenceFenceOrderingTest`, `EmberAddNodeRoleLabelTest`,
  `LifecycleAwaitTest` — 19 tests, 0 failures, counted from the failsafe XML. Those include both
  runnable classes whose call sites had to be restructured by hand rather than mechanically.
  `CommunityFormationProbeTest` was also hand-restructured but is `@Disabled` at class level (#336), so
  it is compile-checked only and CI will not exercise it either.
- **Residual — the format/lint gate does not cover this module's test sources, and this change does not
  make it.** `mvn jbct:check -pl aether/forge/forge-tests -Djbct.skip=false` exits green while
  examining NOTHING: the module has no `src/main/java`, and its test files are excluded by
  `jbct.includeTests=false`. The plugin says so itself rather than reporting a silent pass. Forced with
  `-Djbct.includeTests=true`, the module reports pre-existing findings in files this PR never touches
  (`HangDiagnosticExtension`, `TestArtifacts`, `StreamOwnerFailover*`). This change does not add any:
  886 findings on the pristine base against 722 on this branch, a net reduction of 164, because
  replacing multi-line `.onFailure(cause -> { throw .. })` chains with one helper call removes flagged
  constructs. The 722 that remain are out of scope here
  [verified: both counts produced by the same command, base measured by checking the module out at
  `origin/release-1.0.0-rc4` in place and restoring afterwards].
