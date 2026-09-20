### Changed (2026-09-20 — #1361: the write router's HRW-over-leader owner precedence is now pinned)
- **`StreamWriteRouter.hrwOwner` prefers the partition-aware HRW resolver and falls back to the arg-less
  leader resolver only when no HRW resolver is wired; production wires both, and swapping the order left
  every `aether-stream` test green.** With the swap, a publish forwards to the STREAMING task-group leader
  instead of the partition's owner — a non-owner, whose `NotOwnerAppend` is answered retryable, so the
  #485 budget burns and the publish fails permanently. No behaviour changed; the contract is pinned.
  `[verified: StreamWritePathContractTest — with both resolvers naming different nodes, all three entry
  points forward to the HRW owner (the swap reddens all three); the router's single `min-sync-replicas`
  read feeds both floor and barrier, so a config raised mid-publish moves the next publish's barrier,
  not this one's (a barrier re-read reddens all three); a slice publisher built with the frozen mode
  UNKNOWN over an EVENTUAL-committed stream publishes — the committed config decides (re-adding a
  DSP-local UNKNOWN refusal reddens it)]`
- Javadoc on `StreamWriteRouter` and `StreamPartitionManager.ensureWritableConsistency` no longer
  describes the per-site DSP/PSA local-append arms #1305 removed; it names the entity-log substrate as
  the deliberate fourth write arm that bypasses the router, and the forward handler's two
  `min-sync-replicas` reads against the router's one (#1360 item 3).
