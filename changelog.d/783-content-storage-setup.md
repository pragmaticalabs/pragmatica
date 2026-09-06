### Fixed (2026-09-04 — #783: `content` storage instance bypassed demotion/GC and could never be encrypted)

- **`StorageFactory.defaultContentStorage(Option<DHTClient>)` built the `content` `StorageInstance` (the shared per-node instance `ContentStore` resources provision through) entirely outside `storageSetups` — no `MetadataStore`, no `DemotionManager`, no `StorageGarbageCollector` — so `StorageMaintenanceDriver` (#250/#803, ticks every registered `StorageSetup`) never reached it and memory usage grew unbounded.** The same bypass also kept `content` out of the config-aware, keyring-aware `createAll`/`createOne` path (#253), so it could never be encrypted regardless of `[storage.encryption]` — #830 shipped a boot-time WARN naming this gap. `defaultContentStorage` is deleted; `createAll` now synthesizes a `content` entry through `createOne`, mirroring the synthesized `artifacts` default from #830, whenever `[storage.content]` isn't explicit
  [mechanism: `StorageFactory.createAll` — `if (!configs.containsKey(CONTENT_NAME))` branch; `AetherNode` reads `storageSetups.get("content").instance()` in place of the old `defaultContentStorage` call; verified, in-JVM through the real `StorageFactory` + real composite managers + real `StorageMaintenanceDriver` (not multi-node): `StorageMaintenanceWiringTest#createAll_realMaintenanceDriverTick_reachesSynthesizedContentInstance` pins REGISTRATION and the #250 shared-DHT guard; `#createAll_realMaintenanceDriverTick_actuallyShrinksContentMemoryTier` pins that the memory cache ACTUALLY shrinks across one tick (memory-tier residency measured before/after from content's own `MetadataStore`, plus content's own `DemotionManager.stats().bytesMoved()`); `#createAll_realMaintenanceDriverTick_actuallyCollectsOrphanedContentBlock` pins that an orphaned block is ACTUALLY collected (disk file gone from the filesystem, lifecycle record gone, block unreadable). Mutation-probed: cutting `demotionManager.demote()` out of `StorageMaintenanceDriver.tick()` reddens ONLY the demotion test, and cutting `garbageCollector.collectGarbage()` reddens ONLY the GC test -- each pins its own property and neither is satisfied by the other's work]. Every instrument reads content's OWN manager, never the composite — a composite counter could be satisfied entirely by `artifacts`.
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

### Correction: what mutation probe A actually reddens

An earlier revision of this fragment claimed that reverting `createAll`'s content-synthesis hunk
"reddens all five #783 tests". **That was false**, and it is corrected here rather than quietly
reworded, because a probe result in a changelog is durable evidence and the next person deciding what
is covered will rely on it.

Re-run and measured, not reasoned about: reverting the hunk reddens **6 of the 8** #783 tests --
`Tests run: 30, Failures: 6`. The six are the ones that let `createAll` synthesize `content`:

- `StorageFactoryEncryptionTest#createAll_synthesizedContent_usesSiblingDiskPath_distinctFromArtifacts`
- `StorageFactoryEncryptionTest#createAll_synthesizedContent_readsPreExistingBlock_underOldContentBlocksDhtPrefix`
- `StorageFactoryEncryptionTest#createAll_synthesizedContent_failsClosedOnPreExistingPlaintext_whenKeyringPresent`
- `StorageFactoryEncryptionTest#createAll_synthesizedDefaultContent_isEncrypted_whenKeyringPresent`
- `StorageFactoryEncryptionTest#createAll_synthesizedDefaultContent_staysPlaintext_whenKeyringAbsent`
- `StorageMaintenanceWiringTest#createAll_realMaintenanceDriverTick_reachesSynthesizedContentInstance`

(The count moved from five to six when the upgrade-hazard test below was added; it is stated here as
re-measured against the current base, not carried forward from the earlier run.)

The two it does **NOT** redden are the acceptance-item-3 pair,
`#createAll_realMaintenanceDriverTick_actuallyShrinksContentMemoryTier` and
`#createAll_realMaintenanceDriverTick_actuallyCollectsOrphanedContentBlock`. Both pass an explicit
`[storage.content]` section, and `createAll` builds every explicit `configs` entry before it reaches
the synthesis branch -- so with the hunk reverted their `content` setup is still built and both stay
green.

**How acceptance item 3 is actually earned, then: by composition of two pins, not by either test
alone.** `reachesSynthesizedContentInstance` pins that the synthesis branch registers `content` in
`storageSetups` (red under probe A). The acceptance pair pins that a `createAll`-built `content`
setup really demotes and really collects (red under the probes that cut `demote()` and
`collectGarbage()`). The composition is sound because the construction path is identical either way
-- `createOne` -> `assembleSetup`, one `StorageSetup` with real managers -- and only the
`StorageConfig`'s origin differs. The explicit section exists in those two tests solely to get a
memory budget small enough to cross the 0.9 demotion watermark; the synthesized default hardcodes
256 MB, which would need ~230 MB of writes to demote.

### Upgrade hazard: `content` becomes encrypted on upgrade alone, and pre-existing content is unreadable

**Read this before upgrading a node that already has `[storage.encryption]` configured.**

`content`'s synthesized default takes `encrypted = keyring.isPresent()`. That means content
encryption is **not opt-in**: it is triggered by keyring presence alone. An operator who configured
`[storage.encryption]` for `artifacts` gets `content` encrypted by the upgrade itself, with no
`[storage.content]` section and no config change of any kind.

Consequence: content blocks written before the upgrade are plaintext, and after it they are read
through an `EncryptingStorageTier`. They become **unreadable** -- and #253 ships detection, not
migration, so there is **no migration path** for them. The failure is loud and typed, never a silent
pass-through of unauthenticated bytes:

- **Disk tier** -- `wrapLocalDisk` refuses at boot when the directory holds block files with no
  `.encryption-enabled` marker: a loud boot failure (`EnablingOverExistingPlaintext`).
- **DHT tier** -- no directory to scan, so there is no forward-direction boot guard and
  `verifyDhtMarker` stamps the namespace unconditionally; each pre-existing block then fails per-read
  with `EncryptionError.LegacyPlaintextBlock`
  [verified: `StorageFactoryEncryptionTest#createAll_synthesizedContent_failsClosedOnPreExistingPlaintext_whenKeyringPresent`
  -- seeds a raw plaintext block under `content-blocks/`, boots WITH a keyring, and asserts the typed
  fail-closed error rather than a miss or a pass-through. Its keyring-absent inverse is
  `#createAll_synthesizedContent_readsPreExistingBlock_underOldContentBlocksDhtPrefix`].

To keep pre-existing content readable, set `[storage.content] encrypted = false` explicitly before
upgrading. The path to actually re-encrypting existing blocks is tracked as #831.

**Upgrade procedure.** Aether does not support rolling upgrades (#666/#434); the supported path is a
full-cluster stop, upgrade, then start. This hazard is an illustration of that envelope, not a new
defect: in a mixed-version cluster an upgraded node would write ciphertext into the shared
`content-blocks` namespace while a not-yet-upgraded node reads it through the old bare tier and hands
the framed bytes back as content. Nothing here makes mixed-version clusters safe for any other
subsystem either -- do not read it as though they were.
