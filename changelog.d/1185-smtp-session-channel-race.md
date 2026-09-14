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
  initializer cannot report. [verified: `SmtpChannelInitializerOrderTest.greetingIsAnsweredEvenWhenItArrivesBeforeTheConnectListenerRuns`]
- **The underlying interleaving still happens and is now harmless.** A probe built to production's
  own bootstrap shape measured the connect listener as not-yet-run at the first read in **3 of 3000**
  connections both before and after the change; what changed is that the session no longer depends
  on it. 3000 sequential sends through the real `SmtpClient` against a scripted loopback server went
  from **5 failures to 0**. [verified: probe measurement, 3000 sends per arm, idle event loop]
- **The race needs an IDLE event loop, not a busy one** — 3200 sends across 16 concurrent senders at
  load 100 produced zero failures, because a backlogged loop delays connect completion and lets the
  caller register its listener in time. Sequential sends against a fast server is the worst case.
  **So a pass on a loaded machine is not evidence of correctness here — it is the condition under
  which the defect hides.** A 145-module reactor passing `resource-notification` on a busy 16-core
  box and CI failing it are not in contradiction, and neither run says anything about correctness.
  [mechanism: the caller only loses the race when the loop is quick]
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
