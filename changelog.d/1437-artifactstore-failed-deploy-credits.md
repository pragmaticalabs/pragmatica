### Fixed (2026-10-04 — a failed artifact deploy left the chunks it stored held behind no artifact)
- **`ArtifactStore.deploy` now gives back the chunk credits it took when the chunk fan-out fails.** A chunk
  holds only `StorageInstance#put`'s credit and no name until the metadata is written, so a deploy that failed
  part-way (tier full, DHT or I/O error) left the chunks that had landed at refCount 1 behind no artifact:
  uncollectable for ever. Only the deploy's own credits are released, so a chunk shared with a stored artifact
  returns to the count it had. Same class as #1437 (`DefaultContentStore`), on the artifact store's own chunk
  path. Deliberately limited to the chunk phase: once the metadata write has been attempted it may have landed,
  and releasing then would free a live artifact's chunks. The coordinate stays bound to the offered digest after
  a failed deploy, as designed, so an identical re-put completes it.
