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
  and physical removal happens on the collector's cycle, after its grace period, not inside `delete`.
  [mechanism: `DefaultContentStore.put`/`delete`/`previousChunkIds`/`releaseAllChunks`,
  `DefaultStorageInstance.release`]
- `StorageInstance.release` is a `default` that releases nothing, so an implementation with no lifecycle
  record (the test doubles in `aether-stream` and `artifact-repo`) compiles untouched; a double that
  delegates to a real instance must override it too. [unverified: concurrent `put`/`delete` on the SAME
  name — the previous-manifest read and the release are only ordered, not atomic with the ref swap, the
  same shape `repointRef` already documents for #737; not pinned by a test]
