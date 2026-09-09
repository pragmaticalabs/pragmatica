### Fixed (2026-09-08 — #929: two of a node's event loops deadlocked on per-member membership monitors, wedging SWIM and QUIC processing and hanging cluster shutdown)

- **A transition listener ran while the FSM held one member's monitor, and that listener acquired every
  other member's.** `MembershipFsm$MemberTracking.dispatch` was `synchronized` and published to
  `transitionSink` under the dispatching member's monitor. The listener runs synchronously into
  `AetherNode.onFsmTransition` → `propagateMemberCount` → `strictCoreObservedMemberCount`, which
  streams the member map calling the `synchronized` `isStrictCoreMember()` on **every** member. A
  thread therefore held member X's monitor and requested A's, B's, C's… in `ConcurrentHashMap`
  iteration order. One node's SWIM loop (`onSwimSuspect`) and QUIC loop (`onLivenessGone`) dispatching
  on two different members each held one monitor and wanted the other's. **The lock order is a hash
  order, so no ordering discipline can repair it** — the nesting itself had to go. The class already
  documented the contract this broke ("the listener is called under the per-member monitor —
  implementations must be cheap and non-blocking"); three javadoc blocks asserting that are now
  corrected, including one in `AetherNode` that cited the same defect elsewhere as a
  *safe-by-precedent* justification for this one.
- **The fix stages the fan-out and publishes it with the monitor released, serialised per member by a
  separate guard.** `applyEvent` mutates state under the monitor and stages each listener call;
  `dispatch` runs them under `MemberTracking.transitionGuard`. **All six sinks are staged, not only the
  one that deadlocked** (`transitionSink`, `deltaSink` JOINED and REMOVED, `onEnteredDead`,
  `onJoinGraceReaped`, `onEnteredDeparting`, `onDepartingRecovery`) — every one of them was violating
  the same contract, and fixing one leaves five doors open
  [verified: `MembershipFsmTransitionListenerLockingTest#concurrentDispatchOnTwoMembers_doesNotDeadlock`
  and `#transitionListener_runsWithNoMemberTrackingMonitorHeld`; reinstating `synchronized (this)`
  around the staged fan-out — the defect's exact shape — reddens both].
- **The guard is what makes this a fix rather than a trade.** It preserves both properties the old
  `synchronized dispatch` provided: one member's transitions stay totally ordered, and a listener still
  observes that member in exactly the state its record names. Only the incidental blocking of *readers*
  of that member's state is dropped, which is the deadlock's single edge; a reader's value is
  unchanged, since it previously blocked and then read the same post-transition state. A bare
  "publish after the monitor is released" would have lost both properties
  [verified: `MembershipFsmTransitionListenerLockingTest#oneMembersTransitions_stayOrderedAndMatchObservedState_underConcurrentDispatch`;
  removing the guard from **both** `dispatch` and `inTransition` — the naive fix — reddens it].
- **Five PUBLIC ingresses depend on that guard alone, and the first revision of this work certified
  that dependence as harmless.** `onSwimUnknown`, `onPeerConnected`, `onJoinGraceExpired`,
  `onDrainRequested` and `onDownHysteresisMet` reach `dispatch` **without** going through
  `inTransition`, so for them the guard inside `dispatch` is the entire serialisation. Removing it
  leaves **225 stale observations of 800 dispatches** while all 959 module tests stay green, because
  the ordering pin above drives its member only through `inTransition`-wrapped ingresses. The first
  revision ran that mutation, saw green, and recorded it as *invalid — it landed somewhere that did
  not matter*. **An exclusion recorded with a reason is stronger than silence, and therefore more
  dangerous when the reason is wrong**: the label certified as irrelevant the single lock acquisition
  five public entry points rely on, and became a documented reason never to look again
  [verified: `MembershipFsmTransitionListenerLockingTest#aDirectDispatchIngress_isSerialisedAgainstAGuardedOne_onTheSameMember`,
  driving `onSwimHealthy` against `onDrainRequested` on one member; removing the guard from `dispatch`
  alone reddens it and nothing else in the class. The 225-of-800 figure is the reviewer's measurement of
  the unpinned gap; the pin closing it is measured separately. Found by adversarial verification, not by
  the author].
- **Lock order is always guard-then-monitor, which cost four call sites their `synchronized`.**
  `promoteIfObserved`, `terminalizeIfStillDeparting`, `expireJoinGrace` and `evictIfStillConfirmedDead`
  were `synchronized` methods that called `dispatch`; left alone they would have inverted the order and
  reintroduced a deadlock through the timer paths. Each now takes the guard first and reads state
  through a short `synchronized` accessor
  [mechanism: `MembershipFsm.java`, `MemberTracking.transitionGuard` and the four entry points; a
  static check over the file reports zero `synchronized` methods reaching a guard-taking call, with the
  six guard-takers as its positive control].
- **The six ingress drivers are now atomic, which they were not before.** `healthy`, `suspect`,
  `faulty`, `departed`, `livenessGone` and `peerDisconnected` are multi-step operations whose steps
  each took the per-member monitor **separately**. They now run under the member's guard — a
  strengthening, and what keeps `evictIfStillConfirmedDead`'s check-march-clear sequence indivisible
  against the co-confirmation flag writes [mechanism: `MembershipFsm.inTransition`].
- **Every `transitionSink` consumer was audited for a dependence on the old timing, and none has one.**
  All six listener chains terminate in a non-blocking hand-off — a queue plus virtual-thread drain, a
  CAS-debounced reconcile trigger, an immediately resolved `Promise`, or `Promise`-returning rebalancer
  calls — none blocks, and none depends on the old semantics. `TransitionJournal` stamps each entry
  with its own sequence and timestamp under the ring's own monitor, so cross-member append order was
  never load-bearing. `QuorumLossDetector.onMemberCountChanged` stores the count and schedules or
  cancels. The one aggregate read, `strictCoreObservedMemberCount`, was already non-atomic across
  members. `ConsistentHashRing`'s write methods hold a `ReentrantReadWriteLock` over pure map
  operations with no callbacks — a leaf [mechanism: traced from `AetherNode` wiring to each chain's
  first asynchronous hand-off; independently re-derived by verification].
- **Correction to that audit: one chain DOES re-enter the FSM synchronously.** The first statement of
  this was "no chain synchronously re-enters `dispatch` for **any** member", and that is false. The
  join-grace reap chain does — `LeaderReconciler` → `ClusterTopologyManagerRecord` → `AetherNode` →
  `fsm.onDrainRequested(target)` → `dispatch` → the guard. It is safe because the re-entry is on the
  **same** member, where the guard is reentrant; the true claim is that no chain re-enters for a
  **different** member, which is what would deadlock. Worth stating precisely rather than broadly,
  because that re-entry acquires the guard **inside `dispatch`** — the exact acquisition whose removal
  was wrongly scored harmless above [mechanism: chain traced by verification, not by the author].
- **The lock order is not what earns the safety, and the real invariant is the fragile one.** The
  fan-out holds one member's guard while acquiring every OTHER member's monitor — that is precisely
  what the quorum re-feed does. What makes it acyclic is that **the per-member monitor is a LEAF**:
  nothing acquired under it acquires anything else (`MembershipState` contains no `synchronized`, and
  `applyEvent` reaches only the state table, the timer helpers and a list append). That property is a
  precondition of this fix rather than an incidental fact, and it is breakable from outside the class
  — the factories taking an explicit `FsmObserver` run it under the monitor, and production only
  happens to wire `noop()`. Recorded at the guard's declaration, and **pinned**: an observer supplied
  through the public factory is shown to run under the monitor, so a concurrent member walk cannot
  complete while it is on the stack — with a control proving the walk finishes once the monitor is
  released, so the assertion cannot be satisfied by a thread that never ran
  [verified: `MembershipFsmTransitionListenerLockingTest#anObserverPassedToThePublicFactory_runsUnderThePerMemberMonitor_soItMustNotTakeLocks`;
  dropping `synchronized` from `applyEvent` reddens it. Read a green result as "the constraint is still
  real", not as "all is well": if observer invocation ever moves out from under the monitor this test
  SHOULD go red and be rewritten].
- **The SWIM transport shutdown is bounded, and it no longer claims success when it did not
  complete.** Both waits were bare `.sync()`. Netty's own `shutdownGracefully()` timeout cannot rescue
  that, because it is enforced by `confirmShutdown()` running *on* the event loop — a wedged loop
  cannot enforce its own timeout. Each wait is now bounded at 5 s. **The unconditional
  `LOG.info("SWIM transport stopped")` is gone**: it fired even when JUnit's backstop interrupt was
  what ended the wait, over a transport that was never stopped — dumps show its event-loop thread
  still RUNNABLE ten seconds later. That false line is why #727, #749 and #750 each read past the wedge
  underneath it
  [verified: `NettySwimTransportBoundedShutdownTest#shutdownThatCannotComplete_reportsFailureAndDoesNotLogSuccess`,
  driven against a genuinely wedged event loop; reinstating the unconditional log reddens it, and
  removing the bound reddens its failure assertion. Paired with `#healthyShutdown_succeedsAndLogsSuccess`,
  which requires the same appender to *see* the success line, so the absence cannot go vacuous].
- **A shutdown future that COMPLETED WITH A FAILURE was still reported as success — a new dishonesty
  introduced by the change that exists to remove one.** Netty's `Future.await(long)` returns true when
  the future is **done**, whatever its outcome, and `isSuccess()` was never consulted, so a failed
  close took the success branch and logged the success line over it. The pre-#934 `.sync()` rethrew
  that cause. The first revision was honest about timeouts and dishonest about failures, and its own
  honesty pin could not reach the branch
  [verified: `NettySwimTransportBoundedShutdownTest#aShutdownFutureThatCompletedWithAFailure_isNotReportedAsSuccess`,
  with `#aShutdownFutureThatCompletedSuccessfully_isReportedAsSuccess` as the control so the pin
  discriminates on outcome rather than on `awaitBounded` always failing; new
  `SwimError.ShutdownFailed` carries the cause. Found by adversarial verification].
- **`doStop` regained its exception lifting.** It had become a bare `return stopChannel();`, so an
  unchecked exception from `DnsNameResolver::close` or `ch.close()` would leave `stop()` as a thrown
  exception rather than a `Result` failure — an errors-as-values regression on a shutdown path
  [mechanism: `NettySwimTransport.doStop`, `Result.lift` restored with the inner `Result` flattened].
- **Cleanup no longer gates an already-resolved value.** `SelfAddressResolver.stopThen` used
  `transport.stop().flatMap(_ -> carried.async())`, so a failing cleanup discarded a
  successfully-resolved address and the node fell back to an unverified hostname; this change widens
  the failure set that can trigger it. **Not a regression** — the outer `overallCap()` (5 s) expires at
  or before the inner bound, so the observable outcome is unchanged from pre-#934, where `.sync()`
  simply hung until that cap fired. The shape was wrong without the behaviour differing
  [mechanism: the cleanup failure is now logged and recovered before the carried result is surfaced].
- **The 2 s quiet period is dropped, and that is worth ~18 s of every nine-node teardown.** SWIM is
  fire-and-forget UDP with nothing to drain, so Netty's default quiet period bought nothing and was
  paid on the caller's thread. The 2.00 s per node is a measured constant — a green CI teardown showed
  2.008 / 2.005 / 2.003 / 2.003 / 2.002 / 2.004 / 2.003 s across seven nodes — so removing it takes
  ~18 s off a nine-node teardown on any machine
  [mechanism: `NettySwimTransport.SHUTDOWN_QUIET_PERIOD_MS`; no test pins the timing, and the constant
  is from the diagnosis's CI control run, not from this branch].
- **`EmberCluster.stop()` submits each node stop, so the bound can see the part that blocks.**
  `AetherNode.stop()` runs a long synchronous prologue before returning its promise, and `.toList()` is
  eager — so every prologue previously ran to completion on the caller's thread before any promise
  existed to bound, and #915's `.timeout(NODE_TIMEOUT)` decorated only the async tail. The same held
  one level up, where `LifecycleAwait.bestEffort("cluster stop", c, c.stop())` evaluates `c.stop()` as
  an argument, fully, before its 240 s bound starts counting. This raises stop concurrency, which is
  the input that triggers the deadlock, so it ships in the **same commit** as the FSM fix — a revert
  takes both, which is what the ordering constraint actually required
  [mechanism: `EmberCluster.submitStop`; no test pins the concurrency. Observed once, in the forge run
  below: nine `Stopping Aether node` lines within 02:45:14.304–14.305, against sequential before].
- **Residual: one listener chain is not cheap.** `onEnteredDeparting` fans out to a 1024-partition
  rebalance while the member's guard is held, so the javadoc's "cheap and non-blocking remains the
  right shape" understates what that chain does. Not a regression — it did the same under the monitor
  before — but a slow listener now stalls every further transition of that member rather than every
  reader of it [mechanism: chain identified by verification].
- **Residual, not fixed: `NODE_TIMEOUT` can now be exhausted by the transport bound alone.** The SWIM
  transport's 5 s + 5 s worst case sits inside a prologue that part 4 put under a 10 s `NODE_TIMEOUT`.
  A node hitting the transport bound exhausts the node bound, `Promise.allOf` fails, and
  `EmberCluster.stop()`'s `.onSuccess(this::clearClusterState)` is skipped, leaving the registries
  populated after a failed stop. Same shape as pre-#934, but materially more reachable now. Unlike
  both start-abort paths, `EmberCluster.stop()` has no `.recover()`
  [mechanism: `EmberCluster.stop` / `NODE_TIMEOUT`; not exercised by any test today].
- **Observed teardown, single run, stated load — and its evidence was not retained.**
  `MultiSourceCommunitySmokeTest` on the dev box (nine nodes in one JVM, box otherwise quiet):
  **6.249 s** from `Stopping Ember cluster` to `Ember cluster stopped`, all nine SWIM transports
  stopping within the same millisecond. **This is not compared against the 8-minute CI hang or the
  ~16.6 s CI healthy baseline** — different machine, and a cross-machine ratio is not a measurement;
  what transfers is the 2.00 s-per-node constant above. Re-measured with the console log **retained**: **6.358 s**, nine
  `Stopping Aether node` lines all in the same millisecond, nine transports stopped, and zero hits for
  `HANG DIAGNOSTIC` / `timed out after` / `TimeoutException` / `ShutdownTimeout` /
  `ShutdownInterrupted` / `SWIM transport shutdown did NOT complete` — against controls `INFO` = 1930
  and both teardown markers = 9. The first revision reported the same shape from a log it did not keep,
  and its zero-hits came from an artifact whose controls were **0**, i.e. an instrument blind to
  teardown. The log is now kept so anyone can re-derive these. **One green run of an intermittent
  test is not evidence the race is gone** — the deadlock evidence is the deterministic probe and its
  mutation.
- **Negative result — the new failure path bounding the shutdown was expected to surface has NO
  PRODUCER.** Two mechanisms could make a shutdown wait reach the 5 s bound: the wedged event loop,
  removed by part 1, and Netty's 2 s quiet period, removed by part 3 itself. Both are gone, leaving a
  UDP socket that completes about three orders of magnitude below the bound — there is no population
  between "~1 ms" and "never" for the bound to catch. So the absence is *explained*, not merely
  observed, and the bound is not decorative: removing it lets the wait outlast a deliberately wedged
  loop (class time 6.2 s → 10.04 s), which is what proves the bound and not the wedge clearing is what
  ends it.
- **An intermittent CI failure, stated as intermittent and NOT as exonerating.** PR #934's
  `build-and-test` failed once on head `be5cd96e5` (`EmberClusterPartialStartFailureTest`) and
  **passed on a re-run of the identical commit**; it does not reproduce in **16 local runs** (1
  in-suite, 10 idle, 5 with all 8 cores saturated — the load was real: test method 8.03 s → 9.23 s).
  So the failure is **intermittent, not deterministic**, which 16 runs in a different environment could
  not have established on their own. Separately and more strongly, **the most direct causal route from
  this change is refuted by construction**: both start-abort paths `.recover(_ -> Unit.unit())` every
  element, so `Promise.allOf` cannot fail and the registry clear fires unconditionally — part 3's new
  `ShutdownTimeout`/`ShutdownInterrupted` cannot suppress it — and this change's only `ember` edit is
  reached from `@AfterEach`, after the assertion that failed. Those are two independent arguments: the
  re-run removes determinism, the refutation removes the mechanism.
  **Neither exonerates the change, and the mechanism is not established.** Non-reproduction was
  measured in a different environment from the one that failed, and an explanation that clears the
  change under test is the claim that most deserves testing rather than the one that ends the
  investigation — **which applies to the passing re-run too**, arriving as it did at the moment it was
  most welcome. An intermittent failure is entirely compatible with a change that introduces a rare
  race. No cause is offered here, deliberately
  [mechanism: refutation traced through `abortStart` and `handleStartResults`; non-determinism from a
  same-commit re-run; non-reproduction over 16 local runs].
- **Pins that could not fail, and a mutation that proved nothing — all three found by mutating rather
  than re-running.** The monitor pin asserted its verdict *after* the dispatch returned, where the
  blocked walker had already completed the instant the monitor was released; it stayed green against
  the reintroduced defect while the sibling probe went red, and now snapshots the verdict inside the
  listener. The ordering pin's failure window was a few instructions wide, so 1 600 concurrent
  dispatches all passed with the guard removed; it now widens the window deliberately and asserts the
  stronger property. And the guard mutation described above was scored invalid when it was the most
  informative one in the set.
- **Verification scope, stated as what it is.** Local, counted from the reporting run's own surefire
  XML after wiping stale reports (the `.txt` headers report 0 for `@Nested` classes): `aether-deployment`
  961, `integrations/swim` 178, `aether/ember` 6, `aether/node` 1205 — **2 350 tests, 0 failures**.
  The format/lint gate is **not lifecycle-bound and skips silently**, so it was forced with
  `-Djbct.skip=false` and accepted only where the output reported a nonzero file count: 88, 150 and 6
  files clean for the three `aether/` modules, and **18 files for `swim`**, which the root `jbct.skip`
  default had previously let exit green having examined nothing. Swim's format ledger: base 1 issue →
  this change briefly introduced a second → back to 1, the remaining one being `SwimProtocol.java`,
  proven pre-existing by content identity against the base blob and deliberately left alone rather than
  folded into a release-blocker diff. **A pre-existing violation in a gate-exempt module also bans every
  module after it from the same forced invocation**, which is part of why that debt survived unseen.
  Three `JBCT-CAUSE-02` warnings are added, one per new `SwimError` record, matching the pattern all
  five records in that file already share.
- **What is NOT covered, stated because a green suite invites the opposite conclusion.**
  `aether/dead-surface-gate` exists to scan every prior module's `target/classes` for unreachable public
  surface. It was executed rather than merely compiled — and executing it does **not** exercise the
  scan: `DeadSurfaceCommissioningTest` is `@Disabled` at class level ("commissioning-time only … must
  not gate CI on their resolution", #519), so all three of its tests skip, for this run and for CI
  alike, since CI reaches the module only inside the reactor where the same annotation applies. This
  change removes no symbol, which is the direction removal breakage travels, and its dependents'
  compile closure was confirmed across 99 modules. But the scan itself ran for nobody. Local green is
  not the merge criterion and is not offered as one.
