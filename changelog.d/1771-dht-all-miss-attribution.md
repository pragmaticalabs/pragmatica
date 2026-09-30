### Fixed (2026-09-30 — #1771: a DHT "not found" could not be told from a key that was unreachable)
- **`DHTClient.get` returns `Option`, so an all-miss hid four different situations**: the R2 quorum resolving on its first two
  empty replies without waiting for the third R-set member; a fallback probe that timed out or was refused (degraded to
  empty); an R-set member filtered as not live; and a ring larger than the bounded probe reaches. On top of that
  `onUnresolvedAfterFallback` was never wired in production.
- The all-miss report now carries the key hex and the counts (`rSetSize`, `rSetLive`, `rSetAnswered`, `probed`,
  `probesFailed`, `unprobed`), and `AetherNode` logs it as a WARN with `verdict=lost` or `verdict=unreachable`. `lost` only
  when every R-set member was targeted and answered, no probe failed and the probe covered the ring.
  [verified: `integrations/dht/src/test/java/org/pragmatica/dht/DHTResolveFallbackTest.java`, `ResolveMissTest.java`]
- `ArtifactStoreError.NotFound` names the DHT key hex, and unparseable metadata is its own `MetadataUnparseable` cause
  instead of reading as absence. [verified: `ArtifactStoreTest$MetadataAttributionTests`]
- Attribution only: resolution and quorum rules are unchanged. [unverified: the WARN in a running cluster; that a
  straggler R-set replica was heard — `rSetAnswered` counts late replies but cannot prove one arrived]
