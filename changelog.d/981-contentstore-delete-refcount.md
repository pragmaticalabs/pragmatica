### Fixed (2026-09-21 — #981: ContentStore.delete bypasses refcounting: overwriting chunked content leaks its chunks, and deleting one name destroys a deduplicated block another name holds)
- **`DefaultContentStore` deleted by block id, around the reference counts.** From the producers: a chunk
  block is written with plain `StorageInstance.put` and carries no name — `put`'s credit is the only thing
  holding it — and only the manifest is named (`putRef`). Overwriting the name decremented the manifest
  (`repointRef`) and nothing ever decremented its chunks, so every superseded chunk sat at refCount 1
  forever, reachable from no name and no manifest, and never a GC candidate. `delete` called
  `StorageInstance.delete(blockId)` — `deleteFromAllTiers` then `removeLifecycle`, unconditional on
  refCount — so where two names deduplicated to one block, deleting either destroyed it for the other.
  [verified: `ContentStoreDeleteRefcountTest` — red on `d444d22c6`: overwrite collects `1` where `5` (manifest
  + 4 chunks) must be collected; re-storing the same chunked content leaves every chunk at `[2, 2, 2, 2]`;
  after deleting one of two names, `get` of the other fails with `Content not found` (direct) or `One or
  more content chunks are missing` (chunked); deleting the last name leaves the collector `0` to collect]
- **Per operation, what the fix earns and how.** *Overwrite:* `put` reads the chunk ids of the manifest the
  name currently points at, stores the new content, and only then releases each previous chunk with the
  new `StorageInstance.release(BlockId)` — the debit matching `put`'s credit (`withRefCountDecremented`,
  the same decrement `deleteRef` applies; no tier delete). Store-before-release is what keeps a failed
  overwrite from touching the previous document, and what makes re-storing the same content credit each
  chunk (dedup) before releasing it — net zero. *Delete of one of two names:* `deleteRef(name)` decrements
  the block the name pointed at and each chunk gives back one credit; a block another name still holds
  stays above zero and readable through that name. *Last release:* whatever reaches zero reports
  `isOrphaned`, and `StorageGarbageCollector` collects it through the lifecycle record it already reads —
  the content store no longer calls `StorageInstance.delete` at all, so there is no second delete path
  and physical removal happens on the collector's cycle, after its grace period, not inside `delete` —
  for the node's PRIVATE tiers: the collector skips a cluster-shared tier by design (#250), so a superseded
  or deleted document's copy in a shared DHT tier is no longer removed by anything (the old `delete` did
  remove it, while destroying deduplicated blocks); cluster-wide DHT reclamation is #802.
  [mechanism: `DefaultContentStore.put`/`delete`/`previousContent`/`releaseDisplaced`/`releaseAllChunks`,
  `DefaultStorageInstance.release`/`swapRef`/`dropRef`]
- **Whose chunks get released is decided by the atomic ref swap, never by a pre-read of the name.** New
  `StorageInstance.swapRef` / `dropRef` report the id the swap or removal actually displaced (from the
  same `MetadataStore.replaceRef` / `removeRef` call, exactly once per displaced id); the content store
  releases that manifest's chunks — the pre-read list when the displaced id is the one it read, otherwise
  that manifest read after the swap. Round 1 of this fix released the pre-read manifest's chunks: two
  overwrites of the SAME name interleaved would both pre-read the same manifest and both release its
  chunks, so a third name deduplicating to them lost them — the base only leaked the loser's chunks.
  Two deletes of one name likewise release once. [verified: `ContentStoreDeleteRefcountTest.SameNameConcurrency`
  — put1 parked at its first chunk write, put2 completes, put1 resumes; red under a pre-read release with
  `One or more content chunks are missing` on the third name] [unverified: the pre-read is still what
  fails the put loudly when the current block cannot be read; a concurrent writer's manifest is read
  AFTER the swap, at refCount 0, so a collector grace shorter than one put's tail leaks that one
  manifest's chunks — bounded]
- **Bounded leak, not recoverable:** a crash (or a metadata snapshot cut) between the swap and the chunk
  releases leaves that one document's chunks at refCount 1 with no manifest reachable — the base's leak
  for that single overwrite; no sweep enumerates manifests. Store-then-release is still the right order:
  the reverse would let a collector cycle inside the grace take a live document's chunks.
- **Merge order: after #1411 (#801).** With this change every content delete/overwrite drives blocks to
  zero for the collector; without #1411's take-the-record-first `deleteFromPrivateTiers`, a deduplicating
  `put` landing inside the collector's tier delete is credited on a record that is then wiped. #1411 chains
  that put behind the in-flight collection. [verified: rev1420 P4 — red on this branch alone, green merged]
- `StorageInstance.release`, `swapRef` and `dropRef` are `default`s (no-op; pre-read composed with
  `putRef`/`deleteRef`), so an implementation with no lifecycle record (the test doubles in `aether-stream`
  and `artifact-repo`) compiles untouched. A double that delegates to a real instance must delegate these
  three too, or it re-opens the same-name race for the code under it.
- **A `put` over a name whose current manifest cannot be read FAILS, loudly** (`delete` already did): the
  previous-manifest read in `previousChunkIds` propagates the storage failure and nothing is written. Ruled,
  not an oversight — recovering to "nothing to release" would leak the previous chunks forever, since the
  collector only ever takes refCount-0 blocks. The operability gap it leaves (such a name can be neither
  overwritten nor deleted) is filed separately. [mechanism: `DefaultContentStore.previousChunkIds` /
  `chunkIdsOf`]
