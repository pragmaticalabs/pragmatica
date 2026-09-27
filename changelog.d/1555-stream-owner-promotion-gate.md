### Fixed (2026-09-27 — #1555: a healed ex-owner could act as stream owner on a stale view or a short ring)
- **A stream partition owner that was partitioned away and then healed back could act as owner before it
  was entitled to.** With #1550 placement live again, the healed node either reclaimed ownership and appended
  new events at offsets it had never received — replicas then held different events at the same offsets and
  acknowledged records were lost — or kept claiming `servedByOwner` on a stale committed ownership view and
  served truncated reads. Before #1550 the ~18 s self-drain hid this, but a heal inside that window could hit
  the same gap.
- **Owner promotion gate** (`OwnerActivation`). A node acts as owner of a partition — accepts appends, reports
  `servedByOwner`, serves reads as owner — only once it is activated for the committed ownership record it
  holds: a no-op consensus round refreshes its committed view and the record must still name it; every other
  live placement member's watermark is probed and any higher suffix is pulled from the highest holder; the
  activation is then bound to that exact record, so any later ownership change requires the gate again. A
  partition's first owner skips only the refresh round. Quorum loss clears every activation. Until activated,
  appends and owner reads are refused with the transient `OwnerNotActivated` (forwarded publishes retry it;
  local publishes retry it within a short bound).
- **An unreachable live member blocks promotion** until the membership FSM declares it dead and it leaves the
  live placement set; promotion never proceeds while a reachable member holds a higher watermark. Each probe
  times out on its own, but there is no bound independent of DEAD: a member that keeps handshaking while
  answering nothing never reaches DEAD, and the partition then stays unpromoted until that member is removed
  (#1563).
- **Sticky ownership.** Every node now routes, and computes its role and `servedByOwner`, from the COMMITTED
  ownership record (HRW placement only before a record exists), so nodes agree on the owner even while their
  membership views disagree. Only the leader's ownership writer judges liveness, and it moves ownership only
  when the committed owner leaves its live set — a higher-ranked node that joins becomes a replica instead of
  taking ownership back, which removes the hand-back trigger. While a dead owner's record stands, its
  partition answers retryable refusals until the leader rewrites it.
- **Accepted residual:** at `min-sync-replicas` 0 an append acknowledged by the previous owner in the instant
  before the ownership flip reaches it can be lost — the acknowledgement is owner-local by definition there.
  `min-sync-replicas` >= 1 closes it.
- `OwnerActivationTest` pins each gate, and `OwnerPromotionGateShapesTest` pins the three ex-owner shapes on
  real partition rings with no failure detection involved: a reclaim with a short ring (appends only after
  catching up), a zombie with a stale committed view (no append, no `servedByOwner`, no owner read), and a
  hand-back after a move (the earlier activation is not reused).
