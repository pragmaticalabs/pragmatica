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
  leak. In-tree, four test doubles were updated; a bytecode sweep of all 12,004 classes produced by the
  full reactor build found exactly five implementors (the production one plus those four), and no
  generated source in the repo references the type.
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
- **Upgrade note — a snapshot written before this fix is NOT repaired by restoring it.** Pre-fix
  snapshots carry the inflated refCount 2 verbatim, `MetadataStore.restoreLifecycles` reads it back
  unchanged, and a node restored from one inherits blocks that still never reach zero and still never
  become collectable. No migration is attempted and none runs on startup; the blocks are reclaimed
  only by an explicit `ContentStore.delete` or stream retention, which delete by id rather than by
  refcount. The new `AcrossSnapshotRestore` test proves the CORRECTED count survives a
  snapshot/restore rebuild — it does not, and cannot, prove that a rebuild repairs an old one.
- **This makes #801 materially more likely to fire, and that belongs in the merge decision.** #801 is
  the GC filter-then-delete race: a deduplicating `put` can land on a block between the orphan scan
  and the delete step, handing a caller an id whose bytes GC then removes. Its window is unchanged in
  size — but the population that can enter it is not. Before this fix, the only production blocks that
  could reach refCount 0 at all were superseded CURSOR blocks (8-byte offsets, via #737's
  `replaceRef`); content-store and segment blocks were structurally incapable of being orphaned, which
  is exactly the leak fixed here. They now join that population, so the number of trips through #801's
  window scales with content deletion/overwrite and segment retention rather than with cursor-commit
  volume alone. The per-trip probability is unchanged; the trip count is what rises. **The blast
  radius per occurrence also worsens:** a lost cursor block costs a consumer group a resume point,
  while a lost content or segment block is user data that is not re-derivable. #801 is milestoned
  rc5 and unfixed.
- **Not fixed here, and stated so it is not mistaken for covered:**
  - **Overwriting chunked content still leaks the superseded chunks.** Storing new content under a name
    whose old value was chunked releases the old manifest, but the old chunks carry no name and are
    reachable only through that manifest, so nothing decrements them and `ContentStore.delete` is the
    only path that removes them. Separate from this double count and untouched by it.
  - #802 (a block demoted to the DHT alone leaves every node's local candidate set) is unchanged.
