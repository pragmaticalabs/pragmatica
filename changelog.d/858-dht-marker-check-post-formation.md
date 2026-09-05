### Fixed (2026-09-05 — #858: DHT encryption-marker check moved off the boot path)
- **Every boot with a DHT-backed storage instance and no keyring hung for 30 s, then aborted.**
  `StorageFactory.createAll` — called from the `AetherNode` constructor, before cluster formation —
  awaited a DHT get/put (`refuseIfDhtEncryptedWithoutKeyring`/`writeDhtMarker`, the reverse-direction
  guard from #253/#830) against a `DistributedDHTClient` that can only route once `start()`'s cluster
  formation resolves. The construction-time DHT operation could never complete: every real boot with a
  DHT tier failed `"Failed to create storage '<name>'"` after the full `DHT_MARKER_TIMEOUT`. #830's own
  tests never caught this because they exercise `InMemoryDHTClient`, which answers immediately with no
  cluster to form.
- **The DHT marker check now runs from `start()`, after cluster formation resolves and before the node
  reports ready**, generically over every DHT-backed storage instance `StorageFactory` assembles (not a
  hardcoded list — covers `artifacts`, any `[storage.<name>]` instance, and #783's future `content`
  routing through the same path once merged). Construction itself now never touches the DHT: it stages
  a `DhtMarkerCheck` (instance name, DHT key prefix, keyring key id if any) per DHT tier and returns
  immediately. Two outcomes, same fail-closed causes as before, just later:
  - No keyring, marker present on the DHT tier → `start()` fails with `EncryptedTierRequiresKeyring`
    (identical cause to the pre-existing disk-side check, which is unchanged — a disk read needs no
    cluster and stays synchronous during construction).
  - Keyring configured → the marker is written for real once formation resolves, bounded by the same
    30 s timeout; a wedged DHT now fails `start()` with a distinct `DhtMarkerCheckTimedOut` cause
    instead of masquerading as the disk-side refusal.
  A DHT tier serves no reads until its own check completes (gated internally on `DhtStorageTier`, not
  a separate config knob). A boot that fails either cause stops the node the same way any other
  `start()` failure does: exit code `1` (`Main#exitWithError`).
- [verified: `StorageFactoryEncryptionTest#createAll_leavesNoDhtMarker_whenDiskGuardRefusesBeforeDhtEncryptionIsApplied`,
  `#verifyDhtMarker_fails_whenDhtCarriesEncryptionMarker_andDiskUnavailable_andNoKeyringSupplied`,
  `#verifyDhtMarker_fails_withDhtMarkerCheckTimedOut_whenDhtClientNeverResponds` (pins a DHT client
  whose get/put never resolve — construction completes without awaiting it; red-before against the
  reverted hunk hangs ~30 s) — 16/16;
  `DhtStorageTierTest` — 11/11;
  `AetherNodeDhtMarkerPostFormationBootTest#construction_staysUnderFiveSeconds_whenArtifactsInstanceHasDhtTierButNoKeyring`,
  `#start_writesDhtMarkerPostFormation_absentBeforePresentAfter_readThroughNodesOwnDhtClient` — real,
  self-forming single-node boot through `AetherNode.start()`, marker read back off the node's own
  `DHTClient`, absent before formation and present with the active key id after — 2/2;
  `EmberClusterForeignAdmissionTest` — 4/4 (regression guard: unaffected by this change, re-run because
  it forms a real cluster through the same `createNode`/`start()` path);
  `aether/node` full module suite — 1135/1135;
  `mvn jbct:check -pl aether/node,aether/aether-storage` — 0 format issues, 0 lint errors, clean on
  both touched modules;
  `./forge.sh ci` — 16/16 classes, 48 tests, 0 failures/errors, 1 expected skip (the acceptance
  instrument named in #858 — the pre-fix baseline errors 15/16 in `@BeforeAll` on the same suite)]
- **What is NOT covered:** the disk-side marker guards (#253/#830) are unchanged — they run
  synchronously during storage construction because a local-disk read needs no cluster, and this fix
  does not move them. #783's `content` instance is still routed through a separate, keyring-less
  factory path (`StorageFactory.defaultContentStorage`) and is not yet subject to either marker check;
  when #783 merges `content` through `createAll`, it inherits this post-formation check automatically
  — no hardcoded per-instance list to update. The `stream-segments` DHT namespace gap tracked as #849
  is untouched by this fix.
