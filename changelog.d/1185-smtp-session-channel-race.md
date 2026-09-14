### Fixed (2026-09-14 — #1185: the SMTP greeting could arrive before the session had a channel to answer on)

- **`SmtpSession.sendCommand` dereferenced a null `channel` when the server greeting won a race with
  the connect listener.** `SmtpClientImpl.connectWithTimeout` assigned the channel from
  `bootstrap.connect(address).addListener(...)`, and **nothing ordered that assignment before the
  first inbound read.** Netty notifies a listener added to an ALREADY-COMPLETED future through a
  task queued on the event loop, while an inbound read is delivered from `processSelectedKeys()` —
  which runs before `runAllTasks()` in the same loop iteration. A server that greets the instant it
  accepts therefore reached `handleGreeting` → `sendCommand` with `channel` still unset, and the
  resulting `NullPointerException` was caught by `SmtpResponseHandler.exceptionCaught` and turned
  into `SmtpError.ConnectionFailed`. [mechanism: queued listener notification loses to a pending
  read]
- **The channel is now assigned in `SmtpChannelInitializer.initChannel`**, which runs on the event
  loop while the pipeline is being built — ordered before `SmtpResponseHandler` is in the pipeline,
  so before any reply can be delivered to it. `handleConnect` keeps only the failure path, which the
  initializer cannot report. **This is deterministic by construction, not a narrowed window:**
  `session.setChannel(ch)` and `addLast("handler", new SmtpResponseHandler(session))` are consecutive
  statements on one thread, and Netty delivers no inbound event to a handler that is not yet in the
  pipeline. The assignment therefore precedes the first read by program order on the event loop, for
  every connection, at any load — there is no interleaving left to lose.
  [verified: `SmtpChannelInitializerOrderTest.greetingIsAnsweredEvenWhenItArrivesBeforeTheConnectListenerRuns`]
- **The underlying interleaving still happens and is now harmless.** A probe built to production's
  own bootstrap shape measured the connect listener as not-yet-run at the first read in **3 of 3000**
  connections both before and after the change; what changed is that the session no longer depends
  on it. Interleaved pre/post arms, alternating within each of 6 rounds across load 5.8→81.5, gave
  **6 failures in 3000 sends before the fix and 0 in 3000 after**, with the post-fix arm holding 0
  across the whole load range while the pre-fix arm varied inside those same rounds.
  [verified: interleaved probe measurement, 3000 sends per arm, sequential sender]
- **Two independent load factors, pointing in OPPOSITE directions.** The caller must be descheduled
  between `connect()` and `addListener()`, so **machine CPU contention raises** the rate; but the
  event loop must be prompt to complete the connect first, so **event-loop backlog suppresses** it.
  Measured, one sequential sender throughout: at 1-min load ~9 the unfixed code failed **0 of 3000**;
  with CPU burners across load 21→81 it failed **6 of 3000**; with 16 concurrent senders sharing one
  event loop at load 100 it failed **0 of 3200** — a busy machine, but a backlogged loop.
  **Consequence: neither "it passed on a quiet box" nor "it passed on a busy box" is evidence of
  correctness.** A 145-module reactor passing `resource-notification` on a loaded 16-core machine
  and CI failing it are not in contradiction; the discriminating condition is busy machine + quiet
  loop, which is what a single module's sequential test does under a module-parallel reactor.
  [mechanism: the caller loses only when it is preempted AND the loop is free to finish first]
- **`SmtpReplyCodeClassificationTest`'s two CI failures were this one defect, not a second retry
  bug.** An attempt killed by the NPE fails as `ConnectionFailed`, which is `Cause.Transient`, so it
  is retried — and it abandons its socket right after the greeting. The test's scripted server
  catches `IOException` OUTSIDE its `while (true)`, so the resulting broken-pipe write ends its
  accept loop; later attempts are never accepted and time out. That yields
  `mailFrom550_isPermanent_soOneAttempt` = 2 (a spent extra attempt) and
  `auth454_isTransient_soThreeAttempts` = 2 (a lost one) from a single mechanism. The retry
  accounting in `Retry` was correct throughout: it classified every cause it was actually given.
  [verified: out-of-tree probe against the test's own server halves — an abandoned socket ends the
  accept loop with `Broken pipe`; timing-dependent, one run in six saw it accept once more first]
- **`SmtpSession.channel` is now `volatile`.** It is written on the event loop and read off it by
  `onTimeout` → `closeChannel`, whose stated purpose is to close the socket of a timed-out session;
  a plain field permits that read to see `null` and skip the close. It cannot be pinned by a test:
  a missing `volatile` is a Java Memory Model violation that correct hardware is free to hide, so a
  test that fails to redden is evidence about the test, not about the field. Reverting it left all
  28 module tests green on **arm64**, which is weakly ordered and therefore the *more* likely of the
  two architectures here to expose it; CI's x86_64 hides it more thoroughly still. Keep it.
  [mechanism: cross-thread visibility of the channel field; not pinned by a test — a missing
  volatile is unobservable in practice on both arm64 and x86_64]
