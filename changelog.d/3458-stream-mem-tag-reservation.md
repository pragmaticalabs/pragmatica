### Added (2026-11-20 — #3458: in-memory streams, wire-tag reservation and storage-key tripwire)
- **Wire tags 3010..3017 are reserved for in-memory streams** in `wire-tag-ranges.txt`. The line reserves the range only; no tag is pinned in `SystemTags`. [verified: `SystemTagsLedgerTest`]
- **A tripwire pins that `storage` is still an unknown stream key.** A `[streams.x]` section with `storage = "memory"` is refused with `UnknownStreamKeys` until the key lands; the test then names the suite to enable. [verified: `StreamStorageKeyTripwireTest`]
