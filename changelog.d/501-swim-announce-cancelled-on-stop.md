### Fixed (2026-09-14 — #501: audit of `SharedScheduler.scheduleAtFixedRate` sites; the SWIM announce loop outlived `stop()`)
- **Audit result (51 call sites in 30 files at `f4e87cffc`, `src/main` only):** 49 retain the
  future and cancel it on the owning component's stop/close/onExit path; 1 has no lifecycle by
  design (`Idempotency`'s cleanup tick, owned by map reachability and self-cancelling once the weak
  reference clears); 1 did not — `SwimProtocol.announceJoin` kept its `ScheduledFuture` in a local
  `AtomicReference`, so `stop()` cancelled the probe tick but not the join-announce loop, and a
  protocol stopped within 30 s of joining kept sending ANNOUNCE to every seed every 500 ms until
  its 60-attempt cap. The ticket's "9 futures in `AetherNode.periodicTasks`" is stale: all 15
  `AetherNode` sites now go through `PeriodicTasks.defer`, cancelled in `stop()`. The full table is
  in `oss/internal/fix-501-report-2026-09-14.md`.
- **The announce loop's lifetime is now `stop()`'s alone, not the probe tick's.** `SwimProtocol`
  retains the loop's handle (`announceFuture`), and `stop()` cancels it **before** its
  `PROTOCOL_NOT_RUNNING` guard — a protocol whose tick was never armed previously returned that
  failure and cancelled nothing, leaving 30 s of ANNOUNCE from a stopped node.
  [verified: `SwimProtocolTest$AnnounceSelfSuppression.stopWithoutStart_cancelsTheAnnounceLoop` and
  `.stop_cancelsTheAnnounceLoop_noAnnounceAfterStop`; each asserts a frozen ANNOUNCE count over a
  window in which an independent `SharedScheduler` control task is observed firing, so an empty read
  from a stalled scheduler cannot pass as a cancelled loop]
- Cancelling the handle is not by itself enough, so `announceStopped` (set by `stop()`, cleared by
  `start()`) is the code that **refuses** an announce from a stopped protocol, at three points:
  `announceJoin` arms nothing, and `runAnnounceAttempt` sends nothing — checked at its head and again
  **per seed**, because `cancel(false)` does not interrupt an attempt already inside its sends.
  `announceJoin` now also takes `lifecycleLock`, so arming is serialized against `start()`/`stop()`
  instead of racing them.
  [verified: `.announceJoinAfterStop_armsNothing` (an `announceJoin` landing after `stop()` arms no
  loop, and a later `stop()` is not needed to silence it) and
  `.stopDuringAnAttempt_doesNotAnnounceToTheRemainingSeeds` (the transport parks inside the send to
  the first seed, `stop()` lands while that attempt is demonstrably in flight, and the second seed is
  never announced to)]
  [mechanism: a latch, not `lifecycleLock` held across the attempt — `NettySwimTransport.resolveAndSend`
  falls back to SYNCHRONOUS DNS resolution for an unresolved seed host, which under that lock would
  block `stop()` for the resolver timeout]
- A re-announce supersedes — **cancels** — the previous loop, rather than merely replacing the handle.
  [verified: `.reAnnounce_cancelsThePreviousLoop_supersededSeedGoesSilent` — the superseded loop's seed
  set goes silent while the superseding loop is observed still firing at its own seed; discriminated by
  seed set rather than by rate, so a cancelled loop cannot pass by being slow. Previously unpinned:
  dropping the cancel left all 196 tests of `integrations/swim` green]
- Not changed: `DHTAntiEntropy.start()/stop()` retain and cancel correctly but have no production
  caller (the task is never armed on a real node) — noted, not this ticket's defect.
  [design intent — unverified]
- [unverified: the residual in-flight window — an attempt that has passed the per-seed check may still
  complete that one seed's send after `stop()` returns. Bounded to a single datagram to a single seed,
  and it cannot re-arm: `runAnnounceAttempt` never reschedules itself, and the executor's re-arm is
  what `cancel(false)` stops]
