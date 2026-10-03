### Fixed (2026-10-03 — #1278, #1014: WAL recovery could not tell reclaimed history from a lost WAL head)
- **Retention now persists a reclaimed-through floor before it reclaims.**
  - The floor is a `stream-floors/<stream>/<partition>/<offset>` ref recording the highest offset retention
    reclaimed. It is written and made durable (a forced streams metadata snapshot) BEFORE the segment refs
    it licenses are dropped.
  - If the floor cannot be written or made durable, retention reclaims nothing for that partition in that pass.
  - Retention never reclaims past the contiguous sealed watermark.
  - A crash between the floor and the deletion restarts with both.
- **A restart anchors the rebuilt sealed watermark at that floor**, no longer at the lowest surviving segment
  ref. A prefix missing below the surviving refs, with no floor recording it, is lost refs, not reclaimed
  history (#1014).
- **A WAL that starts above the watermark is now REFUSED with the typed `WalHeadLost`**, naming the lost range,
  instead of being accepted with a WARN. This supersedes the accept-with-WARN rulings for #1258 and #1345, per
  the owner ruling of 2026-10-03. Retention reclaiming every segment of a partition restarts cleanly with no
  warning.
  - Operator action: keep the WAL file, then either restore the node's streams metadata snapshot if a newer
    copy exists, or serve the partition from a replica that holds it. Data written before this change has no
    floor and refuses on every replica, so neither clears it: wipe and recreate the partition.
  - `walRecoveryHeadsLost` counts each distinct lost head once, however often the refused recovery is
    re-attempted.
- **Stream incarnations.** `StreamConfig` gains an `incarnation`: one life of the name. Every durable artifact of a
  stream — its WAL directory, sealed-segment refs and reclaimed-through floors — is keyed by it, and a node opens only
  the committed life, so a stream recreated under the same name never inherits the old life's records, watermark or
  floor (a node down for the destroy, a half-done destroy, a retention pass or seal outliving the destroy). Destroy
  reclaims the old life's refs best-effort.
  - **The committed life is the only authority.** The KV applier refuses a config `Put` that would replace a
    committed life (`IncarnationFenced`), so concurrent first creates resolve to one life and a recreate commits
    only after the old life's removal applied.
  - **Writes are accepted only into the applied committed life.** Before it applies, a publish refuses with the
    retriable `StreamConfigNotYetVisible`, so no acknowledged record can be lost to a life that did not commit.
  - A create of a name with an applied committed life adopts it; a recreate always proposes a new life.
  - **Latency:** the first publish to a stream that does not exist yet waits up to 2 s for its config to commit.
  - **BREAKING:** wire shape of `StreamConfig` (pre-GA; no new tag). KV snapshots and backups written before this
    change are not supported: the codec has no default for the field. Wipe the cluster state and recreate streams.
- **BREAKING (Management API / CLI, `GET /api/storage/retention`):**
  - `walRecoveryHeadGapsAccepted` is replaced by `walRecoveryHeadsLost`, the count of `WalHeadLost` refusals.
  - Each partition row gains `reclaimedThrough`.
  - Data written before this change has no floor. A partition whose segment prefix retention already reclaimed
    rebuilds below it and refuses a compacted WAL. That is accepted pre-GA.
- Pinned by `ReclaimedThroughFloorTest`, the re-aimed tripwire
  `StreamPartitionManagerRestartAfterCompactionTest.restartAfterRetentionReclaimedEveryRef_floorAnchorsRecovery_noWarning`,
  and `StreamPartitionManagerRecoveryTest`.
