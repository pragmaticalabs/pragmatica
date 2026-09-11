### Fixed (2026-09-11 — #1012: metadata snapshot pruning deleted the file `LATEST` had just been pointed at)

- **Two defects in `integrations/storage` combined into self-perpetuating durable-state corruption.**
  `InMemoryMetadataStore`'s mutation epoch starts at zero and `restoreLifecycles`/`restoreRefs` only
  *increment* it, while `StorageFactory.applySnapshot` read the restored snapshot's own epoch and
  used it for a log line only — so the epoch restarted near zero on every boot. `DefaultSnapshotManager`
  derives the snapshot file name from that epoch (`snapshot-%06d.dat`), points `LATEST` at the new
  file, and then prunes. From the second boot onward the freshly written low-epoch file sorted lowest
  and **the prune deleted the target `LATEST` named moments earlier**, leaving a dangling pointer that
  no later boot could restore from — which produced another low-epoch snapshot, and so on.
- **Pruning can no longer delete the live pointer's target.** `DefaultSnapshotManager.prunableVictims`
  subtracts whatever `LATEST` currently names from the over-retention prefix, wherever that file's
  epoch sorts. An unreadable `LATEST` yields the unfiltered prefix: with no live pointer there is
  nothing to protect and refusing to prune would let the directory grow without bound. Retention is
  unchanged in the ordinary case, where `LATEST` names the newest file and is never in the prefix;
  in the anomalous case the directory holds one file above `retentionCount` for that round rather
  than breaking the pointer.
- **Prune candidates are ordered by the epoch the file name *encodes*, not by the name.** The old
  `Comparator.comparing(fileName)` agreed with numeric order only while the epoch stayed below
  1_000_000; past that boundary `snapshot-1000000.dat` sorts ahead of `snapshot-999999.dat` and the
  newest snapshots would have been deleted first. A name carrying no parsable epoch sorts oldest, so
  foreign files are pruned ahead of real snapshots — and are still protected while `LATEST` names one.
- **The restored epoch now reaches the metadata store.** `MetadataStore` gains
  `restoreEpoch(long)` alongside `restoreLifecycles`/`restoreRefs`; `applySnapshot` calls it last,
  after the two restore calls that bump the epoch themselves. `InMemoryMetadataStore` raises the epoch
  and **never lowers it** (`epoch.updateAndGet(current -> Math.max(current, snapshotEpoch))`): the epoch is a monotonic mutation counter and
  the snapshot file-name sequence at once, so restoring an older snapshot over a store that has moved
  on must not hand out names the store has already retired.
- **Why this mattered beyond one lost file.** A controlled three-arm experiment on a quiet host
  (CLEAN / STALE / REPAIRED, interleaved, arms differing by exactly five `LATEST` pointer files over
  byte-identical WALs) measured the backpressure burst at ~59k events per run with the pointers broken
  versus ~6.1k with them repaired — 9.66×, non-overlapping ranges, 99.93% of the traffic on the DHT
  lane. That burst is what exhausts Forge's 60 s start budget in #718. [reported mechanism, not
  verified by this change: a dangling `LATEST` leaves `SegmentIndex.rebuildFromRefs` empty and
  `lastSealedOffset` at −1, so the WAL replays from offset 0 — past the `maxCount` floor each record
  evicts one, producing one-event seals, a `putRef` per seal and a `WRITE_THROUGH` to the DHT tier.
  The tests below pin the pointer's survival, not this downstream chain.]
- **Pinned by four tests, each reddening a distinct named set under a single-hunk revert**:
  `SnapshotManagerTest.forceSnapshot_afterEpochReset_keepsLatestTargetOnDisk` (writes a high-epoch
  snapshot set, simulates a restart that resets the epoch, writes again, and asserts the target
  `LATEST` names still exists and still restores) pins the guard;
  `forceSnapshot_prunesByEpochOrder_notByFileName` pins the ordering across the six/seven-digit
  boundary and deliberately asserts nothing about `LATEST`, so it cannot redden for the guard;
  `restoreEpoch_raisesEpochToSnapshotValue` and `restoreEpoch_neverLowersEpoch` pin the store
  contract. `StorageFactorySnapshotEpochTest.createAll_afterRestart_continuesSnapshotEpochFromDisk`
  pins the wiring through the real `createAll` boot path — the only reachable caller of the private
  `applySnapshot` — and carries a fixture control (`epochOnDisk == 6`) so a snapshot that never
  reached disk cannot make it pass vacuously.
- **#1013 was considered and deliberately not bundled** — see the PR discussion. An honest fix needs
  `SnapshotManager.restoreFromLatest()` to change from `Option<MetadataSnapshot>` to
  `Result<Option<MetadataSnapshot>>` so a legitimate first boot is distinguishable from a failed
  restore; that is a public-API change in `integrations/storage`, not a one-liner, and the failure it
  would surface is already logged at WARN by `DefaultSnapshotManager.readAndValidateSnapshot`.
