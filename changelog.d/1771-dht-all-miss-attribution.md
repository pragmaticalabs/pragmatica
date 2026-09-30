### Fixed (2026-09-30 — #1771: a DHT "not found" could not be told from a key that was unreachable)
- **`DHTClient.get` returns `Option`, so an all-miss hid four different situations**: the R2 quorum resolving on its first two
  empty replies without waiting for the third R-set member; a fallback probe that timed out or was refused (degraded to
  empty); an R-set member filtered as not live; and a ring larger than the bounded probe reaches. On top of that
  `onUnresolvedAfterFallback` was never wired in production.
- The all-miss report now carries the key hex and the counts (`rSetSize`, `rSetLive`, `rSetAnswered`, `probed`,
  `probesFailed`, `unprobed`), and `AetherNode` logs it as a WARN with `verdict=absent-everywhere`, `unreachable` or
  `late-value-discarded`. `absent-everywhere` only when no late reply carried a value, every R-set member was targeted and
  answered, no probe failed and the probe covered the ring; it cannot tell a never-written key from a lost one.
  `late-value-discarded` names the replica (`lateValueFrom=`) whose reply carried the value after the R2 quorum had already
  resolved empty: a read-path false negative, reported, not changed. A ring no bigger than the R-set (3 nodes, FULL
  replication) is now reported too, once every R-set reply has arrived, without delaying the read.
  [verified: `integrations/dht/src/test/java/org/pragmatica/dht/DHTResolveFallbackTest.java`, `ResolveMissTest.java`]
- **Every line now says how long the read took and how it ended**: the all-miss WARN carries `kind=quorum-empty|fallback-degraded`
  and `elapsedMs`; a metadata read the 15s resolve timeout cut (a target departed mid-read, so nothing reports an all-miss)
  logs `outcome=timed-out elapsedMs=...` with the same key; `NotFound` says `outcome=answered-empty, elapsedMs=...`.
  [verified: `ResolveMissTest`, `DHTResolveFallbackTest`, `ArtifactStoreTest$MetadataAttributionTests`; unverified: that the
  store's `log.warn` call itself fires, only its line builder is pinned]
- `ArtifactStoreError.NotFound` names the DHT key hex, and unparseable metadata is its own `MetadataUnparseable` cause
  instead of reading as absence. [verified: `ArtifactStoreTest$MetadataAttributionTests`]
- Attribution only: resolution and quorum rules are unchanged. [unverified: the WARN in a running cluster; the three gaps of "absent" this leaves unresolved (slow third R-set replica, dead fallback holder, corrupt metadata) are now attributed, not fixed; that a
  straggler R-set replica was heard — `rSetAnswered` counts late replies but cannot prove one arrived]
