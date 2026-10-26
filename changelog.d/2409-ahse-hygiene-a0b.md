### Changed (2026-10-25 — #2409: storage boot and degrade hygiene)
- **A `disk_path` an operator wrote that cannot be opened now fails the boot** instead of running silently on memory plus the
  shared tier. Tiers the node placed itself (the synthesized `artifacts` and `content`, derived paths) still degrade, now with a
  `storage-disk-tier-unavailable` operator warning, the gauge `aether.storage.disk.degraded{instance}` and the log line. `streams`
  is unchanged.
- **A second node on the same disk-tier mount refuses to boot.** A node claims the mount with an exclusive OS file lock
  (`FileChannel.tryLock`) on `.mount-claim`, held while the tier is in use; the kernel releases it when its holder exits, so no
  liveness is guessed and no stale claim is reclaimed. Guarantee: excludes every process taking the claim on the same host
  kernel, containers sharing the directory included, and a second node id inside one JVM. Limits: advisory (a process that does
  not take the claim is not excluded); across hosts on a network filesystem it holds only as far as that filesystem's lock
  service enforces byte-range locks (a lock service that loses state can release a live holder); a filesystem without locks fails
  the claim loudly. A boot refused after the claim was taken gives it back.
- **Memory tiers are bounded to 25% of the maximum heap** across all instances: a larger configured total is cut in proportion,
  with a WARN and a `storage-memory-tier-clamped` operator warning. At the stock `-Xmx256m` the 25% is 64 MiB, 16 MiB of it the streams ring, so 48 MiB is shared by the instances' tiers and the default 256 + 256 MiB is cut (the 1 MiB floor can push the sum above 25%).
- **Storage degrade paths raise operator warnings once per transition**, with paired recoveries where one exists:
  `storage-demotion-refused`/`-resumed`, `storage-snapshot-write-failed`/`-recovered`, `storage-snapshot-fell-back`,
  `storage-tier-full`/`-writable` (a write refused by a full durable disk tier; one warning per episode, ended by a write at least as large as the smallest refused one), `storage-dht-marker-unverified`/`-verified`
  (the post-formation encryption-marker check cannot complete), `storage-maintenance-not-started`/`-started` (demotion, promotion
  or garbage collection could not be activated) and `storage-wal-tail-cut` (a stream log cut a torn tail at open).
- **The storage readiness gate reaches write-ready**: `isWriteReady` on the storage management endpoints is true once the node
  reports ready (it was false for ever, because nothing signalled consensus sync).
