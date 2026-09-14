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
- **Review round 2: `streams` is inside the same decision now, not a second call after it.**
  `AetherNode.assembleNode` settled the boot in two calls — `createAll`, then the four-argument
  `defaultStreamStorage` — and only the first was two-phase, so a `streams` guard refusal orphaned
  every marker `createAll` had just committed. Both directions of that guard are reachable from the
  `[storage.encryption] streams_encrypted` flag alone, with no code defect involved, and the back-out
  then failed with verbatim the symptom this ticket is about. `createAll` now takes a
  `StorageFactory.StreamSetupRequest` and arms `streams` alongside the config-map instances, so one
  admission covers every marker; `assembleNode` makes a single call and reads `streams` out of the
  returned map under the same missing-key invariant as `artifacts` and `content`.
  [mechanism: `createAll` → `pendingSetups` + `armStreamStorage` → `Result.firstFailureOf` →
  `commitDiskMarkers`]
  [verified: `StorageFactoryEncryptionTest.bootDecision_leavesNoDiskMarkerOnAnInstance_whenTheStreamsArmRefusesOverExistingPlaintext`
  and `..._whenTheStreamsArmRefusesAMarkerWithNoKeyring` — one per guard direction, each asserting the
  directory after the refusal and then driving the back-out boot;
  `bootDecision_leavesNoStreamsMarker_whenAConfiguredInstanceRefusesTheBoot` is the symmetric case and
  `bootDecision_stampsTheInstanceAndTheSegmentsDir_whenEveryArmPasses` the control that a fix which
  simply stopped writing markers could not pass]
- The four-argument `defaultStreamStorage` still arms-then-commits its single marker for callers that
  decide nothing else. Nothing in the type system stops a future caller from reintroducing a second
  call — `assembleNode` is in the same package as the factory, so package scope fences nothing here.
  What refuses it is a test: `AetherNodeStreamsRefusalMarkerBootTest` drives
  `AetherNode.aetherNode(...)` with `streams_encrypted` on over a plaintext segments directory and an
  encrypted `artifacts` instance on a fresh one, and asserts `artifacts` carries no marker afterwards.
  The `bootDecision_*` cases pin the factory's entry point; this one pins that `assembleNode` uses it.
  [verified: `AetherNodeStreamsRefusalMarkerBootTest.aetherNode_leavesNoArtifactsMarker_whenTheStreamsArmRefusesTheBoot`
  — red when `AetherNode.java` is reverted to the two-call ordering, with the factory fix left in place]
- **Read this before adding another `StorageFactoryEncryptionTest` case and calling the boot path covered.**
  Reverting `AetherNode.java` alone to the two-call ordering — leaving the factory fix in place — reddens
  that ONE real-boot test and **all 29 tests in `StorageFactoryEncryptionTest` stay green**. Twenty-nine
  tests that exercise the factory cannot see which calls `assembleNode` makes against it. A factory test
  proves a property of the factory and nothing whatever about the wiring; if the claim is about the boot,
  the test has to go through `AetherNode.aetherNode(...)`.
- A marker write that itself fails (an I/O error on the marker file), or a crash part-way through the
  commit pass, still fails the boot and leaves the markers already written in that pass. Re-running
  the same config completes the set — the guards of the instances already stamped short-circuit on
  marker-present, and the unstamped ones re-arm over their still-empty directories.
  [verified: `StorageFactoryEncryptionTest.createAll_completesAHalfStampedSet_whenRebootedOnTheSameConfig`]
- Not covered: a DHT reverse-guard refusal post-formation (#858) can still follow disk markers an
  admitted `createAll` wrote — the DHT check needs a routable `DHTClient`, which does not exist at
  construction time, so it cannot join this decision. Filed separately. `#849` still tracks the
  missing DHT-side marker for the `streams` namespace.
- **Two claims about settling, which must not be read as one.** (a) No arm of the boot decision can wedge:
  the construction path contains **zero** `.await(` — every `Promise` in `StorageFactory` is on the
  post-formation DHT path — so `createAll` either returns a `Result` or does not return at all.
  (b) The post-formation DHT arm **is** unbounded, deliberately: `verifyDhtMarker` retries
  `Integer.MAX_VALUE` times with no attempt budget and ends only on a `Cause.Terminal` cause or the node's
  stop signal (#1052). A persistent `QuorumNotReached` therefore leaves the node not-ready indefinitely with
  disk markers already on disk. (a) is about this ticket's decision; it does not extend to (b), and (b) is
  not a regression introduced here. [mechanism: `Retry.retry().attempts(Integer.MAX_VALUE)` in
  `StorageFactory.verifyDhtMarker`]
- Marker writes are `Files.write(CREATE, TRUNCATE_EXISTING)` with no `fsync`, so a crash can lose a
  marker whose write had already returned. That is a durability property of `FileOps.writeBytes`
  shared by every marker, not an ordering question, and is out of this ticket's scope; it is not the
  `rename(2)` replace hazard of `#1118`/`#1169` either — nothing is being replaced, the guard
  short-circuits the moment a marker exists. [unverified: no crash-injection harness exists]
- `configuration.md` states the guarantee, and both windows that stay outside it, under
  "Reverse-direction refusal".
