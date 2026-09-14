### Fixed (2026-09-14 — #1178: a test disabled as "flaky on slow CI runners" was mis-specified, and its stated reason was the inverse of the truth)

- **`CertificateRenewalSchedulerStaleTimerTest#immediateRenewalBranch_storesScheduledFutureForCancellation`
  asserted a transient that no correct implementation can promise.** After `start()` on the
  immediate-renewal branch it required `ctx.scheduledTask` to hold `Some(future)`. On that branch
  the tick is scheduled with a **zero** delay, so it runs on a `SharedScheduler` virtual thread and
  drives `Healthy -> Renewing -> RetryBackoff` *before `start()` returns to the caller*:
  `Healthy.onExit` has already drained the holder and `Renewing` arms no timer. `None` at that
  instant is correct behaviour, not a defect. The assertion could only pass by winning a race.
- **The `@Disabled` reason inverted the attribution, and that is the more expensive half.** It read
  *"Flaky on slow CI runners … Local 0/3 fails, CI 2/2 fails"*, i.e. an environment problem on slow
  machines. Measured on an idle 16-core host: **5/5 failures with total CPU across all processes at
  6.2 %**, and **13/21 overall** — while **injecting 32 CPU spinners made it PASS more often (5 of
  8)**. It is not flaky and it is not slow-runner-specific; a *faster* machine fails it more
  reliably, because the virtual thread wins the race sooner. A reason that names the wrong cause
  sends every later reader looking in the wrong place, and this one sat unchallenged behind an
  annotation that guaranteed nobody would run it.
  [mechanism: JUnit's condition evaluator deactivated via
  `-Djunit.jupiter.conditions.deactivate=org.junit.jupiter.engine.extension.DisabledCondition`, so
  **no source was modified to obtain these numbers**; the switch was controlled in both directions —
  `Skipped: 1` with the flag absent, `Skipped: 0` with it present — and the idle/loaded arms were
  interleaved within each round rather than run as blocks]
- **The test now pins what the immediate branch actually promises** and is enabled, not re-disabled:
  that it renews exactly once, and that it leaves the scheduler **armed** rather than silent
  (`RetryBackoff.onEntry` re-arms the timer). A scheduler left with no scheduled tick never retries,
  which is the consequence worth a test; the identity of the holder mid-transition is not.
  Renamed to `immediateRenewalBranch_renewsOnceAndRearmsTheTimer`.
  [mechanism: sequence established by direct observation rather than inference — holder polled every
  50 ms from `start()`: **`None` at t=0, `Some(...)` from t=50 ms, stable to t=500 ms**,
  `issueCertificate` call count 1, `None` after `stop()`. The probe was a scratch file, deleted; no
  production code was changed by this fragment's work]
- **Production code is deliberately unchanged.** An earlier revision of this fix added a
  happens-before gate so each `onEntry` publishes its future before the scheduled body can run
  (three sites: `Healthy.onEntry`, `RetryBackoff.onEntry`, `configureShortValidity`). It was
  **reverted**: with it applied the test still failed **20/20**, which proves the store ordering is
  not what this test observes, and a production change that no test reddens for is an unpinned
  behaviour change in a release branch. The ordering concern is recorded in the ticket instead.
- **Known limit, pre-existing, NOT introduced here and NOT fixed here — the sibling test
  `stopAfterImmediateRenewal_clearsScheduledTask` is genuinely racy at about 6 %** (3 failures in 50
  runs on an idle box, all of them this test, none of them the rewritten one). It is not a test
  defect: `Fsm.dispatch` reads `currentState` and then calls `handle`, with the transition CAS
  inside, so a tick's `RetryBackoff.onEntry` can store a retry future **after** a concurrent
  `stop()`'s `Stopped.onEntry` has drained the holder — leaving **a live, uncancelled retry timer on
  a stopped scheduler**. Fixing that means changing how the FSM orders transition side effects
  against concurrent dispatches, which is a design decision, not an edit to make inside this PR.
  [mechanism: `Fsm.dispatch` (`integrations/statemachine`), measured failure text
  `[Stopped.onEntry → cancelScheduledTask drains the holder to None] Expecting value to be true but
  was false` at `CertificateRenewalSchedulerStaleTimerTest:144`]
