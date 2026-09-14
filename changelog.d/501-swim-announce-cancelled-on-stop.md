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
- `SwimProtocol` now retains the announce loop's handle (`announceFuture`); `stop()` cancels it
  alongside the probe tick, and a re-announce supersedes (cancels) the previous loop.
  [verified: `SwimProtocolTest$AnnounceSelfSuppression.stop_cancelsTheAnnounceLoop_noAnnounceAfterStop`
  — start, announce, observe ≥1 ANNOUNCE, `stop()`, then the count must not move over two further
  500 ms ticks; red `expected: 1 but was: 3` before the fix]
- Not changed: `DHTAntiEntropy.start()/stop()` retain and cancel correctly but have no production
  caller (the task is never armed on a real node) — noted, not this ticket's defect.
  [design intent — unverified]
