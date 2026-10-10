### Fixed (2026-10-04 — #1437: a partially failed chunked put leaked the chunks it had already stored)
- **`DefaultContentStore.putChunked` now releases the chunks it stored when the put fails.** Each chunk holds only
  `put`'s credit and no name, so a failure at chunk `k` (tier full, I/O or encryption error) or at the manifest
  swap left chunks `0..k-1` at refCount 1 behind no manifest: uncollectable, and with deduplication an over-count
  that kept a chunk shared with an existing document alive after that document was deleted. The put gives back
  exactly the credits it took, awaited before the failure is reported; a release that itself fails is logged and
  does not mask the original cause. The previous document is still released only after a successful swap.
