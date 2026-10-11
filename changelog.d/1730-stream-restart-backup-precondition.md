### Changed (2026-10-07: #1730, #1104, acknowledged stream data across restarts)
- **Acknowledged stream records survive a whole-cluster restart only with `[backup]` configured and
  restored onto the same node ids and volumes.** A stream's log directory is named by the stream's
  incarnation, which lives only in cluster state. Without `[backup]`, the restarted cluster creates the
  stream again with a new incarnation, and the old log is not recovered: its files remain on the old
  volumes, unread. The operator runbook, the failure almanac and the consistency chapter now state this
  precondition and its reason. Storage-identity adoption (#1569) is not in this release.
- New regression tests: a silent partition of a stream owner acks nothing at consistency factor 2 once
  the owner is cut off (#1730), and `StreamCrashDurabilityTest` covers the restart with `[backup]`. Its
  tripwire asserts the gap without `[backup]` until #1569 lands.
