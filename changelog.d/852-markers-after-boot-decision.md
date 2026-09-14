### Fixed (2026-09-14 — #852: `StorageFactory.createAll` wrote disk encryption markers before the aggregate boot decision)
- **`createAll` built every instance eagerly, and `EncryptingStorageTier.wrapLocalDisk` stamps
  `.encryption-enabled` at boot, so an instance built before the one whose guard refused kept its
  marker although the node never started.** Backing that sibling out to `encrypted = false` then
  tripped its own reverse guard (`EncryptedTierRequiresKeyring`) on a directory that never held
  ciphertext, and the marker had to be deleted by hand. The DHT half of the ticket's premise is
  already closed by #858 (that marker is checked and written post-formation, outside `createAll`);
  this is the disk half, which `StorageFactoryEncryptionTest` had documented as "not fixed here".
  [mechanism: `createAll` → `createOne` → `buildTierList` → `wrapLocalDisk` (guard + `writeMarker`)
  per instance, then `Result.firstFailureOf`]
- Construction is now two-phase: `EncryptingStorageTier.armLocalDisk` runs the guard and returns the
  wrapped tier with its marker write pending (`ArmedLocalDisk.commitMarker`); `createAll` assembles
  every instance, decides with `firstFailureOf`, and only then commits every pending marker. A
  refused boot writes no marker; an admitted boot writes them all. `wrapLocalDisk` keeps its
  single-instance semantics (arm then commit) for the `streams` segment tiers.
  [verified: `StorageFactoryEncryptionTest.createAll_leavesNoDiskMarkerOnASibling_whenALaterInstanceRefusesTheBoot`
  — `LinkedHashMap` order forces the healthy encrypted instance to be built before the refusing one;
  no marker on the sibling afterwards, and the sibling reboots with `encrypted = false`;
  `createAll_writesDiskMarker_whenEveryInstancePasses` is the control]
- A marker write that itself fails (an I/O error on the marker file, not a guard refusal) still fails
  the boot, and markers committed before it in the same pass stay. [design intent — unverified]
- Not covered: the `streams` segment tiers are built by a separate factory call and are outside this
  decision; a DHT reverse-guard refusal post-formation (#858) can still follow a disk marker written
  by an admitted `createAll` — that is the next boundary out, not this one (#849 for the streams
  namespace).
- `configuration.md` states the guarantee under "Reverse-direction refusal".
