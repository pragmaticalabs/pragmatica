### Fixed (2026-09-07 — #915: `MultiSourceCommunitySmokeTest` hung to the 8-minute lifecycle backstop on untimed lifecycle awaits, and every other forge class could do the same)

This closes #915 and sweeps the shape out of the whole `forge-tests` module. It is the sibling of
#727/#913 — same family, different member: #727 is a poll that outlives a rolled-back blueprint,
this is an unbounded lifecycle await. #913 merged after this branch was cut; the branch has been
merged forward onto it, and several of the design decisions below were reversed as a result.

- **The two awaits the ticket names were untimed, and a third one was not in the ticket at all.**
  `cluster.start().await()` in `@BeforeAll`, `cluster.stop().await()` in `@AfterAll`, and
  `cluster.addNode(..).await()` in the worker-join helper — the last found by the module sweep below,
  and able to wedge the `@Test` body exactly as the first wedges setup. All three are now bounded and
  named.
- **An unbounded `Promise.await()` on a stalled start is not slow — it is unreachable, and interrupting
  it does not help.** Measured directly, not inferred: a 3-node `EmberCluster` with two of three
  management ports pre-bound was awaited without a bound on a separate thread. After 45s it had not
  settled and the thread was `WAITING`; 5s after `Thread.interrupt()` it had still not settled and had
  flipped to `RUNNABLE` — the interrupted waiter spinning rather than stopping. **This is why the JUnit
  lifecycle backstop could not end the hang**: the backstop's only lever is an interrupt, and
  `Promise.await()` ignores it. That defect is #914 and is NOT fixed here — this ticket makes the
  caller stop waiting, it does not make the wait interruptible
  [mechanism: `Promise.java:3300-3302`, a bare `while (result == null) { LockSupport.park(); }` loop
  inside `await()` (declared at `:3282`) with no interrupt check].
- **A bound alone would have been a fast silent failure, so the naming is the fix, not a nicety.**
  `Promise.await(TimeSpan)` expires into the constant string
  `"Promise is not resolved within specified timeout"` — identical for a cluster that never elected a
  leader and a node that never bound its port. Every expiry now carries the step's name, the bound,
  and a cluster snapshot.
- **Re-run against the merged base, the forced stall no longer stalls — and that is #913's doing, not
  this ticket's.** Same probe as before (3-node cluster, two of three management ports pre-bound,
  ports 27700+), now run on top of #913. It does not reach the 20s bound at all: it fails in **7.65s**
  with the real cause, because #913 made `start()` settle on the FIRST node failure instead of waiting
  for a quorum that can no longer form. The bounded await still names its step, and the snapshot is
  the retained start-failure state #913 captures:

  ```
  3-node cluster start (ports 27700+) did not settle within TimeSpan(20S): Failed to bind to port 27741: Address already in use
  Cluster state when the wait ended:
    leader=none (captured when the start failed; the abort has since cleared the live cluster state, so no health endpoint is probed here)
    p921-2 state=inactive leader=false port=27701 mgmt=27741
    p921-1 state=inactive leader=false port=27700 mgmt=27740
    p921-3 state=inactive leader=false port=27702 mgmt=27742
    start failures: {p921-2=Failed to bind to port 27741: Address already in use}
  ```

  Compare the first revision's dump for the same scenario: `leader=none nodeCount=3` and three
  `ready=false` lines, with no cause and no indication that a start had failed at all. Every field
  above is either read or a named absence, and the delegation is what supplies the last two lines. Two
  things follow, and both are worth stating plainly. The bound is no longer what catches THIS
  scenario — #913 catches it first — so the bound's remaining job is the stalls #913 does not cover,
  which is why the `[verified:]` weight here sits on the unit pins and their mutations rather than on
  this probe. And the diagnostic is now doing the work the ticket asked for: a reader of this failure
  is told the port, the node and the reason, not merely that something did not settle.
  The probe was scratch, run under the suite lock on non-default ports, and is not committed.

- **Two failure policies, chosen by what each site did BEFORE, not by preference.** 92 lifecycle awaits
  in this module were untimed. 52 already threw on failure and keep throwing (`settled` /
  `nodeSettled`). The other 40 read no `Result` at all — a bare `cluster.stop().await()` in `@AfterAll`
  discarded a stop that failed outright — and get `bestEffort` / `nodeBestEffort`: bounded identically,
  but an expiry or failure is LOGGED rather than thrown. The first revision threw at all 92, which
  review round 1 correctly called the highest-risk runtime delta in the PR: it introduced a NEW failure
  mode across 30+ classes, where a cleanup hiccup could redden an otherwise passing test — the same
  flakiness this ticket exists to remove. Promoting a discarded cleanup result to a test failure is a
  real improvement and a SEPARATE change. The bound is the #915 fix; the error policy is not this
  ticket's to change.
- **Round 1 correction — the snapshot no longer has its own renderer, and the field it used to omit is
  now the honest one.** The first revision rendered its own node line and deliberately omitted
  `EmberCluster.NodeStatus.state`, because `toNodeStatus` passed the string literal `"healthy"` for
  every node. #913 removed that literal: `state` is now `EmberCluster.observedState`, read from
  `AetherNode.isReady()` — the SAME sample the old `ready=` field re-derived, and the stronger of the
  two, because `ready=` re-read a live registry that a start-failure abort has already cleared. The
  omission is gone, the field is rendered, and rendering is delegated to #913's `ClusterSnapshot`,
  which is now the module's one renderer. `ClusterSnapshot.nodeLine` gains `port` and `mgmt`: the
  canonical failure this dump is read for is a port that could not be bound, and the node id alone does
  not say which port the node was asked to take.
- **Round 1 correction — the failure path #913 made COMMON was rendering as an empty block.** Since
  #913, `EmberCluster.start` settles on the first node failure and `abortStart` clears
  `nodes`/`nodeInfos` before the caller sees the failure. A dump read off `status()` alone therefore
  said "no node is registered" on exactly the failure it exists for — the un-named empty snapshot
  #913 added `ClusterSnapshot.NO_STATE` to prevent. The delegation picks up
  `EmberCluster.lastStartFailure()` with it, so the dump now carries the retained per-node state and
  each failing node's cause.
- **Round 1 correction — the honesty pin could not fail, and was tagged as if it could.** The review
  proved it by mutation: adding `+ " state=" + node.state()` to the old `nodeLine` left all six tests
  green, because every test passed an unstarted cluster whose registry is empty, so the early return
  fired and no test ever reached a node line. That `[verified:]` tag was false, which is worse than no
  tag at all. Two cases a healthy cluster never produces now drive the node line directly — a populated
  `ClusterStatus`, and a cleared registry with a retained `StartFailure` — and the populated case is
  asserted as a whole rendered block, so an ADDED field fails it too.
- **Round 2 — the one-argument accessor's own wiring is now pinned too.** Round 1 fixed an unpinned
  renderer; the review then found the same shape one level up. Every test drove the two-argument
  `snapshot(ClusterStatus, Option<StartFailure>)`, so swapping `present.lastStartFailure()` for
  `Option.none()` inside the one-argument `snapshot(EmberCluster)` — which is what all 92 call sites
  actually use — left the suite green at 14/14. It was a coverage hole rather than a live defect, but
  it is the same failure this PR spent a blocking round on, so it gets the same treatment. This is the
  one case in the set that cannot be supplied directly: `EmberCluster` captures the failure into a
  private field during `start()` and offers no seam, so the pin makes a start genuinely fail by
  holding one management port before the cluster is built, then asserts what the accessor renders
  [verified: `LifecycleAwaitStartFailureTest#aFailedStart_isRenderedByTheOneArgAccessor_fromTheRetainedCapture`;
  control 15/15 green, and the review's exact substitution turns that test — and only that test — red].

- **Every claim above that rests on a test was mutation-probed, and each probe went red.** Round 1's
  finding was a `[verified:]` tag on a pin that could not fail, so this time each claim's test was
  attacked rather than re-run. Controls first: `LifecycleAwaitTest` + `ClusterSnapshotTest` green at
  14/14, `EmberClusterObservedNodeStateTest` green at 1/1, counted from the failsafe/surefire XML.
  Then one mutation at a time, reverted between each:

  | mutation | what it fabricates | red |
  |---|---|---|
  | `nodeLine` gains `+ " state=" + node.state()` (the review's exact edit) | a duplicated field in the node line | 4 tests |
  | `EmberCluster.observedState` returns the literal `"healthy"` | #913's fabrication, reinstated | `everyNodeOfAFormedCluster_reportsItsObservedStateAsActive` |
  | `report` drops the step name | the pre-#915 nameless expiry | 2 tests |
  | `settled` ignores its `bound` | a bound that is not what ended the wait | `aPromiseThatNeverSettles_expiresAtTheBound_andNamesTheStep` |
  | `report` drops the snapshot | an expiry carrying no cluster state | same |
  | `bestEffort` ignores its `bound` | an unbounded teardown | `bestEffort_boundsTheWaitButDoesNotFailTheTest` |
  | `bestEffort` throws | the round-1 behaviour change, reinstated | same |
  | one-arg `snapshot` passes `Option.none()` for `present.lastStartFailure()` | the accessor discarding the retained failure | `aFailedStart_isRenderedByTheOneArgAccessor_fromTheRetainedCapture` (1 test) |
  | two-arg `snapshot` passes `Option.none()` to `ClusterSnapshot.render` | the empty dump on a failed start | that test **plus** `aClearedRegistryAfterAFailedStart_rendersWhatTheStartFailureRetained` (2 tests) |
  | `settled` always throws | a helper that never works | `aPromiseThatSettles_returnsItsValue` (the positive control) |
  | `snapshot` stops delegating | a second, divergent renderer | `aClusterWithNothingToReport_isRenderedByTheModulesOneRenderer` |

  Re-measured 2026-09-07 on a clean tree at `ad73f1bcd`, after `review-921` re-derived its own runs
  against the same head. The two `Option.none()` rows above were previously ONE row that named the
  one-arg mutation but recorded the two-arg mutation's red test — corrected here from measurement, not
  from reasoning. Control 15/15 green; one-arg reddens 1; two-arg reddens 2; and gutting the one-arg
  accessor to a bare constant reddens 3 (`aClusterWithNothingToReport` and `anAbsentCluster` join,
  because a constant also destroys the null-cluster branch that neither `Option.none()` substitution
  touches). Each substitution was confirmed to land on the executable expression before its result was
  read — a mutation that lands on javadoc compiles and reddens nothing, which is indistinguishable from
  an unpinned path. `review-921` independently reproduced the first two rows.

  The `observedState` probe is the one that matters most: it reddens against a REAL three-node cluster
  that formed and then reported `state=healthy` for every node — the defect in its original shape
  rather than a stand-in for it.
- **Module sweep: complete, 0 remaining.** Found by scanning every `.await()` in `forge-tests` and
  keeping those whose statement calls `start`, `stop`, `startHeldBackNodes`, `addNode`,
  `addWorkerNode`, `killNode` or `blackhole`. The first revision left 2 in `SliceInvocationTest`
  because #913 owned that file; #913 has since merged and bounded both at its own 240s `WAIT_BOUND`,
  so after the merge-forward the module has **zero** unbounded lifecycle awaits. 36 test classes
  changed here, 92 call sites.
- **Round 1 correction — the diff was 5 912 lines for 92 edits, and the excess was whole-file
  reformatting.** `jbct:format` had been run over all 36 in-scope files, reordering imports and
  rejoining lines: `DurableEntityForgeTest` changed 370 lines for 7 lifecycle lines. That churn earns
  nothing, makes a reviewer read thousands of lines to check 92 bounds, and maximises conflict surface
  against every other in-flight `forge-tests` branch. Every file was rebuilt from the base with only
  the lifecycle statements replaced, and each rebuild was checked against the reviewed revision by
  comparing both with comments, whitespace and import order stripped — 36 of 36 identical. The diff is
  now **650 insertions / 340 deletions**, and its two largest files are the new helper and its test.
- **Round 1 correction — 17 helpers the sweep orphaned are removed.** `failStart`/`failScenario`
  private statics in 13 classes became referenced only by their own declaration once the helper
  subsumed the `throw` bodies. One further orphan (`DurableEntityForgeTest.firstPort`) was already dead
  on the base and is left alone as out of scope.
- **Bounds carry stated margin over a measured healthy run, taken on a LOADED box on purpose.**
  `MultiSourceCommunitySmokeTest` green in 42.09s at load average ~10 (two sibling trees building):
  cluster start ~3-8s, whole formation 18.5s, `addNode` 1.1s, `stop` 16.6s. The cluster bound is 240s
  (~30x the measured start, ~14x the measured stop) and the single-node bound is 120s (~110x the
  measured join). 240s also matches the longest existing internal guard, which preserves the invariant
  `junit-platform.properties` states in prose: the 8-minute lifecycle backstop is double the longest
  internal guard, so the test's own await fires first and the backstop stays an outer net
  [mechanism: `LifecycleAwait.LIFECYCLE_BOUND` / `NODE_BOUND`; a quiet-box baseline was avoided
  deliberately because it would understate what a CI runner does]. The 8m backstop is
  `timeout.lifecycle.method.default`, i.e. PER lifecycle method, so a 240s bounded start in
  `@BeforeAll` and a 240s bounded stop in `@AfterAll` draw separate budgets and cannot sum into it.
- **Round 1 correction — the gate claim "this change does not add any findings" was false, and the
  arithmetic behind it was wrong too.** Forced with `-Djbct.includeTests=true`, the module reports
  **804** findings on this branch against **901** on the merged base, a net reduction of 97; at ERROR
  severity, 146 against 211. (An earlier revision of this bullet said 807/904: that counter matched
  every `JBCT-<CODE>` mention in the log rather than only finding lines, over-counting by 3 on each
  side. The ERROR figures are unaffected. A residual 7-finding WARNING-level gap against an
  independent measurement of the base remains unreconciled and was not chased.) The first revision credited a 164-line reduction to the `.onFailure`
  replacement when most of it was the wholesale reformat that has now been reverted. This PR's own
  four files carry **20 findings, all at WARNING severity and none at ERROR** — the three ERROR-level
  findings review round 1 found in them (`JBCT-EX-01` throw forbidden, `JBCT-TOT-01` partial mapper,
  `JBCT-RET-06` null check), plus three more `JBCT-RET-01` void returns, are fixed: the failure path
  goes through AssertJ's `fail` instead of a bare `throw`, `bestEffort` returns `Result<Unit>`, and the
  null cluster is handled with `Option.option` rather than a null check.
- **Residual — most of the 36 changed classes are not executed LOCALLY, but CI does run them.** The
  correction matters, because the first revision of this fragment implied CI would not exercise them.
  It does: `.github/workflows/ci.yml` has a dedicated `forge-tests` job running
  `mvn verify -B -Pwith-e2e -pl aether/forge/forge-tests -Dfailsafe.excludedGroups=Heavy`, and it
  passed in 10m50s on this branch's previous head. NOT covered by that job: the `Heavy`-tagged set, and
  `CommunityFormationProbeTest`, which is `@Disabled` at class level (#336) and so is compile-checked
  only wherever it runs. Risk per class is low and uniform — the substitution is mechanical and its
  failure mode is a compile error. Executed locally and green on this head, counted from the
  failsafe XML rather than the `.txt` headers: **27 tests, 0 failures** across
  `LifecycleAwaitTest` (8), `ClusterSnapshotTest` (6), `EmberAddNodeRoleLabelTest` (6),
  `ClusterFormationTest` (5), `MultiSourceCommunitySmokeTest` (1, green in 48.73s) and
  `CoreAbsenceFenceOrderingTest` (1) — which includes both hand-restructured classes that are
  runnable.
- **Residual — the format/lint gate does not cover this module's test sources.**
  `aether/forge/forge-tests` is not in the default reactor at all: `aether/forge/pom.xml` puts it
  behind the `-Pwith-e2e` profile, so `-pl aether/forge/forge-tests` alone fails with "Could not find
  the selected project in the reactor". Even inside that profile the module has no `src/main/java` and
  its test files are excluded by `jbct.includeTests=false`. Every finding counted above is therefore
  measured by an explicitly forced invocation and none of them reach CI.
