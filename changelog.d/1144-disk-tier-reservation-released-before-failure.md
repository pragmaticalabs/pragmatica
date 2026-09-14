### Fixed (2026-09-14 — #1144: `LocalDiskTier` released a failed write's reservation after the failure was already observable)
- **The release ran as an `onFailure` handler**, which the resolver hands to the `AsyncExecutor`
  and does not wait for, so a caller whose `await` had returned could still read `usedBytes()`
  with the failed block counted — CI read 2048 where `CacheTierPartialWriteTest` expected 0, and a
  caller retrying near capacity could be refused with a spurious `TierFull` in that window.
  [mechanism: `PromiseImpl.processActions` submits `CompletionOnResult` handlers through
  `runEventHandlers` and unparks the awaiting join inline]
- The release is now a `withFailure` dependent action on the put's promise (the
  `replaceResult` shape): `CompletionMap` applies it on the resolving thread and only then resolves
  the promise the caller awaits, so **the reservation is released before the put's failure is
  delivered to the caller** — a happens-before, not a race the handler usually wins. Still released
  exactly once; the partial-file discard, the previous-copy guarantee and the `TierFull` refusal
  are unchanged.
  [verified: `integrations/storage/src/test/java/org/pragmatica/storage/CacheTierPartialWriteTest.java`
  `localDiskTier_failedWrite_releasesTheReservation_beforeTheFailureIsDelivered` — the writer seam
  holds every virtual-thread carrier until the caller has read `usedBytes`, so the handler shape
  reds on every run (10/10 at the tip) and the dependent action needs no carrier (10/10 green)]
