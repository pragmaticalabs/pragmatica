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
- **A copy that has not been compared with the committed owner serves and acknowledges nothing from the epoch's start.** A
  demoted owner still holding its old tail, or a replica whose committed epoch advanced while it was away, answered a
  consumer correctly diverged to the new epoch's start with its OLD records at those offsets, and a replica whose row equalled
  the new owner's head was re-acked for records it held in another version (#1890, the equal-length case). Now such a copy
  answers the retriable `ReplicaNotVerified` for offsets at or above the start, sends no acknowledgement and asks for the
  compare, which the backfill redrive runs at once (the no-compare shortcut holds only within a verified epoch); the owner's
  gate also forgets the registry row of a peer it leaves out as divergent. Until a copy is verified, and while the cluster does
  not record where an epoch began, every offset of a non-empty copy counts as at or above the start.
- **The gate relaxes for a divergent peer only when the divergence lies in the peer's RING and above the candidate's durable
  sealed floor** (and only for a candidate that the committed ISR names). The gate still compares the peer's range through its
  tier, so a difference found in data the peer has sealed is detected and keeps the activation refused: the peer's own repair
  cannot cut there. A peer whose ring tail is not known is not relaxed for.
- **A replica's confirmation carries the owner epoch it was made under** (`ReplicateAck.ownerEpoch`, wire change); an owner counts
  a peer's row, or a late ack, only while it is its current epoch, so a confirmation from an earlier tenure can no longer resolve a
  later tenure's await (#1890, the stale-row path).
- **The CF1 loss report survives a restart.** What a cut discards is recorded beside the WAL BEFORE the cut (temp file, fsync,
  atomic rename, directory fsync) until the repair settles, and reported at reopen when the process died first; a cut whose
  witness cannot be made durable is refused (the replica stays quarantined and the repair is retried), and a witness that
  cannot be read at reopen is reported as an unknown range, never dropped.
- **One truncation report.** At `confirmation_factor` 1 the warning `stream-divergent-tail-truncated` is raised once per
  truncation, when the repair completes, with the final range, the epoch of the discarded records and `ackedAtOwner=true`, not
  once per window step.
  A repair that does not settle within twelve backfill redrive ticks (60 s) is reported anyway with `repairSettled=false` and the
  range known so far; if it later settles with a larger range, one more event (a distinct id: partition, epoch, first cut offset,
  settled flag) follows, so at most two per truncation.
- The gate asks a remote peer where its ring begins over the catch-up read class (no data revealed), not the consumer class, so a
  peer that has not yet been compared with the owner of the new epoch still answers it and the relaxation is reachable.

- **A new replica no longer raises `HISTORY_MISSING` after an ordinary failover.** A source that does not yet list the pulling
  node as a replica of the partition answers its catch-up as a consumer read, which carries no owner-epoch history; the replica
  applied those records with no provenance and its first live append (the first publish after the failover) then raised
  `STREAM_PARTITION_FLAGGED ... HISTORY_MISSING`, with nothing to clear it. The catch-up answer now says whether it was given as a
  replica (`ReadForwardResponse.historyVouched`, wire change: true only when the source treated the reader as a replica, so an empty
  history then means the source keeps no log). A page of records that is not vouched fails the catch-up; nothing is applied and the
  next pull (a live-batch gap, or the 5 s backfill redrive) asks again. A vouched page with an empty history, a source that keeps no
  log, is delivered as before.
  A source that keeps answering as a consumer read for a minute raises the operator warning `stream-catchup-source-not-answering`
  (once per partition episode): the replica is out of the in-sync set until the source's placement view lists it.
- Removed `verifyBelowTheCut` (dead since the window request: `backfillFromOwner` already requests the window below the head).
