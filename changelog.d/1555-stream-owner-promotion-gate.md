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
  answering nothing never reaches DEAD (#1563). Such a stall is no longer silent: after two SWIM suspect
  windows of continuous probe failure the partition stays blocked and is reported once as a CRITICAL warning
  naming the partition, the unreachable members and the responders, and on the partition status read
  (`ownerActivationBlock` on `STREAM_REPLICAS`). It waits for those members or for an operator (#1569's
  surface). An automatic bound is a post-GA follow-up (#1579), conditional on the ack-time replica set
  becoming durable: replica sets are HRW over live members and change with membership, so a quorum-overlap
  rule against the replica set at probe time is not sound.
- **Divergent tails refuse promotion (interim, KIP-101 shape).** No ring or WAL record carries an owner epoch,
  so a returning ex-owner's never-acked tail looked, by head alone, like history to catch up from — or, when it
  was the highest, like the log to serve, losing records acknowledged after it left. The gate now compares the
  last 1024 offsets it and each peer both hold (offset, timestamp, payload) before trusting or out-ranking
  that peer, and compares the chosen catch-up source pairwise with every other responder — a candidate that
  lags two lineages agrees with both on its own short log, so only the pairwise check stops it pulling one
  lineage's tail over records the other acknowledged. Two peers that merely lag differently along one lineage
  never disagree, since only offsets both hold are compared. Any disagreement refuses promotion in either direction — nothing can tell which lineage was
  acknowledged — and is reported once as a CRITICAL warning naming the partition, the divergent node and both
  heads, and on the partition status read. The partition waits for an operator to pick the source (#1569,
  AD14). The warning is a WARN until the operator-warning channel (#1574) lands.
- **Limitation of the divergence check:** the window is a named constant, not an enforced bound. Nothing caps
  how far an owner may append beyond its last acknowledged offset (the replica floor requires in-sync peers to
  exist, and peer lag is measured against the freshest peer, not the owner's head), so a divergence starting
  more than 1024 offsets below the lower of the two heads is not detected, and offsets that either side has
  already evicted are not compared. The complete detect-and-flag over a durable per-log epoch history is
  #1596; the cluster never auto-truncates.
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
- `DivergentTailPromotionTest` pins both divergent-tail directions on real rings (the survivor never pulls the
  returning node's tail; the returning node is never activated over the lower peer, and the block is reported
  once and on the status read); `LowCandidateTwoLineagesTest` pins the lagging-candidate case (refused, naming
  both peers), its positive control and differently-lagging peers of one lineage; `OwnerActivationTest` pins the unreachable-member report and its window.
