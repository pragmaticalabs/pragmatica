### Fixed (2026-09-22 — #1169, #1190: a block replace that unlinked the previous copy first, and an encryption marker that was never fsynced)
- **#1169 `LocalDiskTier.writeThenRename` now publishes the partial with `FileOps.moveAtomic`
  (`REPLACE_EXISTING + ATOMIC_MOVE`, one `rename(2)`; the partial is a sibling, so EXDEV is unreachable).**
  It was `FileOps.moveReplace` = `Files.move(REPLACE_EXISTING)` alone, and the JDK's non-atomic move unlinks
  the target before renaming: a reader in that window found NOTHING at the block path, and a crash there lost
  the block, contradicting the tier's "previous copy never touched" javadoc and the #910 ruling's
  "`rename(2)` atomic" premise (corrected in `know: 812d4de2c`). Guarantee now: a reader concurrent with a
  replace sees the previous complete copy or the new one, never absence; a failed rename or a crash during it
  leaves the previous copy in place. [verified: `LocalDiskTierAtomicReplaceTest` — the directory-partial
  probe at the real call site (`rename(dir, file)` is ENOTDIR under `ATOMIC_MOVE` and the previous bytes
  survive; on the base the move SUCCEEDED and left a directory at the block path), and a reader racing 2,000
  replaces: base 3,526 absent reads of 49,436 on the build host (ext4), 0 with the fix; H1 revert reddens
  both plus the squatter test below]
- **Consequence stated, fixture corrected:** an EMPTY directory squatting a block path now fails the put and
  is left in place, exactly as a non-empty one already did. `CacheTierPartialWriteTest` (#1095) pinned the
  opposite — "the JDK removes it before the rename" — which specified the unlink step as a feature; the
  fixture was wrong, not the fix, because that step is the one that made every real replace observably
  absent. The reservation is released and no partial is left, as before. [verified: the non-empty half is
  `CacheTierPartialWriteTest.localDiskTier_failedWrite_releasesTheReservation`, named rather than alluded to,
  and now asserts BOTH halves of the shared outcome (put fails AND the squatter survives untouched) so the
  parity the empty-squatter test cites is actually checkable; it previously pinned the failure alone. Only
  the EMPTY case discriminates the two move implementations — `rename(file, non-empty-dir)` is refused under
  both — which is why the empty one is the test H1 reddens]
- **#1190 `FileOps.writeBytesDurable(path, bytes)` (new, `core`): one channel writes the bytes and
  `force(true)`s them (data + inode), then the PARENT DIRECTORY is opened and `force(true)`d so the entry
  naming the file is on the device too. `EncryptingStorageTier.writeMarker` uses it.** `FileOps.writeBytes`
  is unchanged and its javadoc now says it does not fsync; nothing else in the product needed the cost (the
  cache-tier partial, the jar caches). No sibling-and-rename for the marker: it is create-only and torn-safe
  (presence is the signal; zero bytes already handled), and a temp file orphaned at the directory root would
  be counted by `isBlockFile` as a plaintext block and refuse the next enable. [verified: JFR `jdk.FileForce`
  (threshold 0, emitted by `FileChannelImpl.force` itself) records a force for the marker path and one for
  its directory while `commitMarker` runs, with a plain `writeBytes` control emitting none; the base recorded
  `[]`; dropping either force reddens `FileOpsTest.writeBytesDurable_forcesFileAndParentDirectory_beforeReturning`
  and `EncryptionMarkerDurabilityTest`. Both observers also pin the two properties the guarantee actually
  rests on, not merely that a force occurred against each path: the ORDER (`containsSubsequence`, file then
  directory — the events are sorted by start time, so the assertion is about issue order) and the METADATA
  flag (`metaData == true`, i.e. `force(true)`/fsync, not `force(false)`/fdatasync). Each is reddened by its
  own mutation — reordering the two forces, and `force(true)` → `force(false)` — and both mutations left the
  previous unordered, path-only assertions fully green]
- **Crash-window truth after the fix.** Before `commitMarker` returns: the marker may be absent or torn. Torn
  is safe (presence is the signal). Absent is fail-safe: the guard only arms a marker write over an EMPTY
  directory, and `StorageFactory.createAll` hands out the tiers only after every marker has committed, so no
  ciphertext block can exist beside an absent marker. After the return: the marker and its directory entry
  have been fsynced, so a crash cannot un-create it. [unverified: power loss — what the device does with a
  completed fsync is outside the JVM; the pin is that the fsyncs were issued] [unverified: Windows — a
  directory cannot be opened as a channel there, so `writeBytesDurable` FAILS rather than silently weakening;
  Linux and macOS honour both forces, measured on both]
