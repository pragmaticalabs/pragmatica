### Changed (2026-10-25 — #2409: storage boot and degrade hygiene)
- **A `disk_path` an operator wrote that cannot be opened now fails the boot** instead of running silently on memory plus the
  shared tier. Tiers the node placed itself (the synthesized `artifacts` and `content`, derived paths) still degrade, now with a
  `storage-disk-tier-unavailable` operator warning, the gauge `aether.storage.disk.degraded{instance}` and the log line. `streams`
  is unchanged.
- **A second node on the same disk-tier mount refuses to boot.** A `.mount-claim` file names the node and its process; a claim of
  a dead process is reclaimed and a graceful shutdown releases it.
- **Memory tiers are bounded to 25% of the maximum heap** across all instances: a larger configured total is cut in proportion,
  with a WARN and a `storage-memory-tier-clamped` operator warning. At the stock `-Xmx256m` the default 256 + 256 MiB is cut.
- **Storage degrade paths raise operator warnings once per transition**, with paired recoveries where one exists:
  `storage-demotion-refused`/`-resumed`, `storage-snapshot-write-failed`/`-recovered`, `storage-snapshot-fell-back`,
  `storage-tier-full`/`-writable` (a write refused by a full durable disk tier), `storage-dht-marker-unverified`/`-verified`
  (the post-formation encryption-marker check cannot complete), `storage-maintenance-not-started`/`-started` (demotion, promotion
  or garbage collection could not be activated) and `storage-wal-tail-cut` (a stream log cut a torn tail at open).
- **The storage readiness gate reaches write-ready**: `isWriteReady` on the storage management endpoints is true once the node
  reports ready (it was false for ever, because nothing signalled consensus sync).
