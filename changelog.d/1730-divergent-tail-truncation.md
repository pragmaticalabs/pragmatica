### Fixed (2026-10-04 — #1730 phase 2: a replica's divergent tail is cut back and refetched, and a replica no longer acks records it never received)
- **Divergent-tail truncation (KIP-101).** A replica that holds records its committed owner never had, or different
  ones at the same offsets (an ex-owner that returned with an unacknowledged tail, an in-sync member that lagged an
  election), was quarantined for good and, being live, blocked an elected owner's activation.
  - The replica now cuts its WAL, epoch history and ring back to the last offset it shares with the owner (one
    ordered section: WAL, then epochs, then ring) and refetches from the owner. The new `AppendLog.truncateSuffix` and
    `OffHeapRingBuffer.truncateSuffix` are the primitives; readers retry across a cut.
  - An owner elected from the committed ISR leaves a divergent peer out of its catch-up instead of refusing to
    activate. Every other refusal (a candidate with no committed ISR, two peers disagreeing with each other, nothing
    comparable) is unchanged.
  - With `confirmation_factor` 1 the discarded records may have been acknowledged by their writer; the new operator
    warning `stream-divergent-tail-truncated` names the offsets. With `confirmation_factor` >= 2 the cut is only logged.
- **A replica no longer acks records it never received.** A replica that completed backfill above the owner's head was
  marked CAUGHT_UP at its own higher offset with no comparison, and the owner then counted that offset as a
  confirmation. It now compares the owner's last 1,024 records first and is promoted at the OWNER's head.
- **A restarted replica hides its recovered tail** until its backfill has verified it against the owner; before, the
  whole tail was readable at once, including an ex-owner's unacknowledged records.
