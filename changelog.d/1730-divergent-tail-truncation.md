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
    warning `stream-divergent-tail-truncated` names the offsets. With `confirmation_factor` >= 2 the cut is logged at INFO and raises no warning: an acknowledgement needed every
    in-sync member, the owner among them, so nothing the writer was told is lost and no operator action exists.
- **A replica no longer acks records it never received.** A replica that completed backfill above the owner's head was
  marked CAUGHT_UP at its own higher offset with no comparison, and the owner then counted that offset as a
  confirmation. It now compares the owner's last 1,024 records first and is promoted at the OWNER's head.
  (#1890) A divergence older than that window is found by the next window and cut back one window at a time, to the
  first divergent offset; a copy with a WAL is cut at the exact offset by owner-epoch provenance.
- **A restarted replica hides its recovered tail** until its backfill has verified it against the owner; before, the
  whole tail was readable at once, including an ex-owner's unacknowledged records.
- **Who may cut, and what is flagged (verifier findings).** A replica cuts its tail only against the COMMITTED owner of a
  LATER epoch than the records about to go, never as the committed owner itself, never at or below the sealed floor, and
  the rule is evaluated again inside the cut. A divergence the repair resolves raises no durable flag and no
  `STREAM_PARTITION_FLAGGED` error (before, an ordinary failover left `MARKED_DIVERGED` standing with nothing to clear it);
  the flag is raised, once, only when the repair is refused. A replica below the owner now compares its last 1,024 held
  records with the owner's too (it used to pull from its head + 1 and be promoted over a divergent middle), and a re-verify
  of a CAUGHT_UP replica whose row is above a new owner's head is capped at that head (#1890, second path).
