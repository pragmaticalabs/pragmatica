### Fixed (2026-09-04 — #783: `content` storage instance bypassed demotion/GC and could never be encrypted)

- **`StorageFactory.defaultContentStorage(Option<DHTClient>)` built the `content` `StorageInstance` (the shared per-node instance `ContentStore` resources provision through) entirely outside `storageSetups` — no `MetadataStore`, no `DemotionManager`, no `StorageGarbageCollector` — so `StorageMaintenanceDriver` (#250/#803, ticks every registered `StorageSetup`) never reached it and memory usage grew unbounded.** The same bypass also kept `content` out of the config-aware, keyring-aware `createAll`/`createOne` path (#253), so it could never be encrypted regardless of `[storage.encryption]` — #830 shipped a boot-time WARN naming this gap. `defaultContentStorage` is deleted; `createAll` now synthesizes a `content` entry through `createOne`, mirroring the synthesized `artifacts` default from #830, whenever `[storage.content]` isn't explicit
  [mechanism: `StorageFactory.createAll` — `if (!configs.containsKey(CONTENT_NAME))` branch; `AetherNode` reads `storageSetups.get("content").instance()` in place of the old `defaultContentStorage` call; verified, in-JVM through the real `StorageFactory` + real composite managers + real `StorageMaintenanceDriver` (not multi-node): `StorageMaintenanceWiringTest#createAll_realMaintenanceDriverTick_reachesSynthesizedContentInstance` pins REGISTRATION and the #250 shared-DHT guard; `#createAll_realMaintenanceDriverTick_actuallyShrinksContentMemoryTier` pins that the memory cache ACTUALLY shrinks across one tick (memory-tier residency measured before/after from content's own `MetadataStore`, plus content's own `DemotionManager.stats().bytesMoved()`); `#createAll_realMaintenanceDriverTick_actuallyCollectsOrphanedContentBlock` pins that an orphaned block is ACTUALLY collected (disk file gone from the filesystem, lifecycle record gone, block unreadable). Mutation-probed: cutting `demotionManager.demote()` out of `StorageMaintenanceDriver.tick()` reddens ONLY the demotion test, cutting `garbageCollector.collectGarbage()` reddens ONLY the GC test, and reverting the `createAll` synthesis hunk reddens all five #783 tests]. Every instrument reads content's OWN manager, never the composite — a composite counter could be satisfied entirely by `artifacts`.
- **`content` is now encrypted whenever `[storage.encryption]` is configured with a resolvable keyring, and stays plaintext otherwise, with no explicit `[storage.content]` section required** — `encrypted = keyring.isPresent()`, same rule as the synthesized `artifacts` default
  [verified: `StorageFactoryEncryptionTest#createAll_synthesizedDefaultContent_isEncrypted_whenKeyringPresent` / `#createAll_synthesizedDefaultContent_staysPlaintext_whenKeyringAbsent`].
- **#830's boot-time WARN ("'content' storage instance is NOT covered") no longer fires** — content is covered like every other instance now
  [verified: `AetherNodeContentStorageWarnBootTest#assembleNode_doesNotWarnOnContentStorage_whenKeyringConfigured`, a real boot on ephemeral ports with log4j appender interception; red-before is re-adding the retired WARN call].
- **The synthesized default's disk/snapshot paths are siblings of the synthesized `artifacts` default** (`artifactsDiskPath.resolveSibling("content")`, then `/blocks` and `/snapshots` — the same convention `streamDataDir` already uses), never the bare `StorageConfig.storageConfig()` default — reusing that bare default verbatim would have collided both instances' snapshot files (and disk blocks) in the same directory, since `assembleSetup` reads `snapshotPath` with no per-instance subdirectory of its own
  [verified: `StorageFactoryEncryptionTest#createAll_synthesizedContent_usesSiblingDiskPath_distinctFromArtifacts` — two synthesized instances, distinct `basePath`s through the real factory].
- **The DHT tier keeps the `content-blocks` key prefix**, so a block written under the old bypass path stays reachable through the new instance — `buildTiers` derives `dhtKeyPrefix = name + "-blocks"` from the instance name `"content"`, reproducing the old hardcoded prefix exactly, with no code change needed
  [verified: `StorageFactoryEncryptionTest#createAll_synthesizedContent_readsPreExistingBlock_underOldContentBlocksDhtPrefix` — seeds a block under `content-blocks/<id>` directly, reads it back through the new synthesized instance].
- **Tier sizes:** `content`'s memory tier stays at 256 MB (`memory_max_bytes`, unchanged from the old bypass default) plus the optional DHT tier (unchanged); it now additionally gets a 10 GB disk tier (`disk_max_bytes`) between them — the old bypass path had no disk tier at all, so this is new capacity and new coverage, not a regression of the memory ceiling.
- Docs updated to drop the now-resolved content-encryption/demotion-GC exclusion: `known-limitations.md`, `configuration.md` (storage-encryption coverage section), `feature-catalog.md` row 207 (Hierarchical Storage Engine).
- **Known limitation carried forward, filed separately by the reviewer:** any two explicitly-configured `[storage.X]` sections that both omit `disk_path`/`snapshot_path` still collide on the bare default (`/data/aether/storage`), and `LocalDiskTier.calculateUsedBytes()` double-counts across them — this fix's sibling-path derivation only covers the *synthesized* `artifacts`/`content` defaults, not explicit sections. No data loss (GC/demotion are `MetadataStore`-driven, not directory-scan-driven) — an accounting-only hazard.
- **Scope boundary — #812:** `DefaultContentStore`'s write path (`putDirect`/`storeManifestAndCreateRef`) pairs `storage.put()` with `storage.createRef()`, the exact double-count pattern #812 tracks — a written-then-deleted content block never reaches `refCount == 0`. Before this fix that bug was inert for `content` (nothing ever ran GC against it); after this fix, GC now runs but will not actually collect an orphaned content block until #812 lands. Not fixed here.

### Findings surfaced by this fix, NOT fixed here

- **`createAll` is not atomic with respect to on-disk encryption markers.** When one instance fails to
  construct, instances built before it keep whatever `EncryptingStorageTier.wrapLocalDisk` already
  stamped on their directories. A later keyring-less boot then refuses on a `.encryption-enabled`
  marker for a directory that never received a single ciphertext block. This predates #783 — it
  already applied to the synthesized `artifacts` default whenever no explicit `[storage.artifacts]`
  section is configured — and #783 only widens the population it can bite by adding `content`. It is
  the disk-side twin of the DHT-side hazard #858 removed structurally. Not fixed here: the remedy sits
  in #253/#858/#831's marker lifecycle, not in this ticket's wiring change
  [evidence: `StorageFactoryEncryptionTest#createAll_leavesNoDhtMarker_whenDiskGuardRefusesBeforeDhtEncryptionIsApplied`
  needed an explicit `[storage.content]` entry after this change, exactly as it already needed one for
  `artifacts`, and for the identical reason — the comment there records it].
- **A written-but-never-read block is invisible to demotion** (`integrations/storage`, all instances,
  not just `content`). `StorageInstance.writeToAllTiers` promotes into the memory cache tier and calls
  `recordTierPresence` BEFORE `trackNewBlock` creates the lifecycle record; `MetadataStore.computeLifecycle`
  is `computeIfPresent`, so that promotion record is silently dropped. The block is then PHYSICALLY in
  the memory tier while the metadata store says it lives on disk alone, and
  `DefaultDemotionManager.selectCandidates` — which picks from `listBlocksByTier(MEMORY)` — cannot see
  it even with the tier over its high watermark. A subsequent read re-records presence, so demotion
  works normally after write-then-read (which is why the demotion test above fills via normal use).
  Consequence worth stating plainly: a write-only workload can hold the memory tier at its ceiling with
  nothing demotable. Outside this ticket's module; reported, not fixed.
- **Ticket wording vs. shipped behaviour:** acceptance item 3 asks that "orphaned DHT blocks are
  actually collected". They are deliberately NOT — `DefaultStorageGarbageCollector.deleteBlock` calls
  `StorageInstance.deleteFromPrivateTiers`, never `delete`, precisely so this node's local refcount
  cannot delete a block another node may still reference (#250's guard). Collection is a private-tier
  operation by design; the tests pin that, not the ticket's phrasing.

### Reconciliation with other unreleased fragments

Two fragments in this same unreleased set describe the `content` gap as current and are superseded by
this one — a reader assembling the release notes should take this fragment as authoritative for
`content`:

- `changelog.d/253-storage-encryption-at-rest.md` states the `content` instance is "architecturally
  unencryptable" and that boot logs a WARN naming the gap. Both were true of #253 as shipped; after
  #783 `content` IS covered and the WARN is retired.
- `changelog.d/858-dht-marker-check-post-formation.md` states `content` "is still routed through a
  separate, keyring-less factory path ... and is not yet subject to either marker check". After #783 it
  is routed through `createAll` and inherits the post-formation check automatically — exactly as that
  fragment predicted it would.

Those two files are other tickets' artifacts and are left untouched here.
