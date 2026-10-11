### Fixed (2026-09-30 — #1782: DHT all-miss attribution counts departed replicas and warns only for artifact metadata)
- **The all-miss report now carries `departed=N`**: replicas that left the ring while still owing a reply to an in-flight read
  (the #1770 re-issue path). Any departure makes the verdict `unreachable`, since that replica's "absent" was never heard.
  [verified: `DHTDepartureAttributionTest`, `ResolveMissTest`]
- **A value within the opted-in grace window is returned and not reported `late-value-discarded`**; the default read still
  discards it and says so. [verified: `DHTResolveFallbackTest`]
- **The all-miss WARN is scoped to artifact METADATA keys** (`artifacts/…/meta`). The file-list and version-list keys share the
  `artifacts/` prefix but are read before their first write, so a first deploy no longer WARNs `absent-everywhere`; they log at
  DEBUG. [verified: `ArtifactStoreTest$ResolveTimeoutTests.isArtifactMetadataKeyHex_recognisesOnlyMetadataKeys`;
  unverified: the WARN/DEBUG split in a running cluster]
