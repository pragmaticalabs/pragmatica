### Fixed (2026-09-10 — #812: content-store and stream-segment blocks were double-counted, so GC could never collect one)

- **Every block written through `ContentStore.put` or `StorageSegmentSink.seal` was credited TWICE for
  one named reference, so it could never reach refCount 0 and the production `StorageGarbageCollector`
  could never collect it.** `StorageInstance.put` credits the block it writes — a fresh write starts its
  lifecycle at refCount 1, a deduplicating write increments an existing one — and `createRef` then
  credited it again for the name. Both call sites paired the two, leaving refCount 2 for one logical
  reference; an explicit `deleteRef` afterwards only brought it back to 1, `isOrphaned()` stayed false,
  and the block stayed on disk forever. This was the same defect #737 fixed for cursor commits, still
  live on the two paths that write the bulk of a node's data.
- **`StorageInstance.putRef(name, content)` is the write-and-ref primitive**: it stores (or deduplicates)
  the content, points `name` at the resulting block crediting it exactly once, and releases whatever
  `name` previously pointed to. Both call sites now use it; `createRef` is retained and documented as
  what it actually is — the *aliasing* primitive, for adding a second name to a block some other
  reference already holds — with an explicit warning never to pair it with `put`.
- **Neither increment was removed, and that is deliberate.** `put`'s credit is the only thing holding an
  UNNAMED block above zero: content-store chunks and artifact chunks carry no ref of their own (only the
  manifest is named), so dropping that credit orphans every chunk of a live document and the collector
  deletes them — a use-after-free, worse than the leak. `createRef`'s credit is correct for its own job.
  It is the pairing that was wrong, not either half. [verified:
  `ContentStoreReclamationTest$ChunkedContent#put_chunkedContent_namesOnlyTheManifest_andChunksSurviveCollection`
  is the counter-mutation guard — under exactly that alternative "fix" it reddens with
  `collectGarbage` returning 4 instead of 0, four chunks taken out from under a readable document. An
  independent pre-existing witness reddens with it:
  `StorageInstanceWriteOnlyDemotionTest#writeThrough_duplicatePutWhileWriteInFlight_refCountSurvivesFinalization`]
- **The `replaceRef` default fallback is now correct by construction on every implementor.** It composed
  `put` with `createRef` and so carried the same double count — plus a second defect, never decrementing
  the block it superseded — onto any `StorageInstance` that did not override it. It now delegates to
  `putRef`, which is abstract, so an implementation cannot silently inherit a leaking one.
  `DefaultStorageInstance` implements `putRef` with what was its `replaceRef` body; #737's ordering
  (credit the new target before releasing the superseded one) is unchanged and still pinned by
  `DefaultStorageInstanceReplaceRefOrderingTest`.
- **Source-incompatible for out-of-tree implementors of `StorageInstance`**: `putRef` is abstract, so an
  external implementation must add it. That is the point — the alternative default is a silent unbounded
  leak. In-tree, four test doubles were updated.
- **What is pinned is the collection, not the arithmetic.** Each new test runs a real `collectGarbage`
  cycle and asserts the block is gone from the metadata store AND from the tier, and asserts that
  BEFORE the refcounts — a count assertion placed first aborts a mutation probe before the consequence
  is ever reached, which would leave the probe proving that a counter moved rather than that a block
  was reclaimed. The lifecycle is driven through the production APIs — `ContentStore.put` /
  `StorageSegmentSink.seal`, then `deleteRef` or an overwrite of the same name — into the production
  `DefaultStorageGarbageCollector`, with elapsed time the only hand-fed input. [verified:
  `ContentStoreReclamationTest` (`integrations/storage`, 4 tests), `StorageSegmentSinkTest$Reclamation`
  (`aether/aether-stream`, 2 tests) — each production hunk was reverted individually and the named
  tests confirmed red on their collection assertions: reverting `DefaultContentStore` reddens 4 of
  `integrations/storage`'s 270 tests, reverting `StorageSegmentSink.seal` reddens 2 of
  `aether/aether-stream`'s 732]
- **[unverified: no cluster run.]** All evidence is in-JVM against `MemoryTier`. Nothing here was
  exercised on a multi-node cluster, and a DHT-backed instance is additionally governed by
  `deleteFromPrivateTiers` (#250) and by #802 — this change does not speak to either.
- **Not fixed here, and stated so it is not mistaken for covered:**
  - **No migration.** A snapshot written before this fix carries refCount 2, and a node restored from it
    inherits blocks that stay uncollectable. `ContentStoreReclamationTest$AcrossSnapshotRestore` proves
    the corrected count SURVIVES a snapshot/restore rebuild, not that a rebuild repairs an old one.
  - **Overwriting chunked content still leaks the superseded chunks.** Storing new content under a name
    whose old value was chunked releases the old manifest, but the old chunks carry no name and are
    reachable only through that manifest, so nothing decrements them and `ContentStore.delete` is the
    only path that removes them. Separate from this double count and untouched by it.
  - #801 (a deduplicating put can resurrect a block between GC's orphan scan and its delete) and #802
    (a block demoted to the DHT alone leaves every node's local candidate set) are unchanged.
