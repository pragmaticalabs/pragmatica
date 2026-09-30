### Changed (2026-09-28 — #1529 part 2: epochs order by cluster incarnation first; workers and replay rewinds survive a cold restart)
- **`Epoch` is `(incarnation, rabiaTerm, localCounter)` and `RewindEpoch` is `(incarnation, generation,
  rewind)`, both ordered incarnation-first.** A cold restart restarts the Rabia term, so before this every
  epoch-bearing write of the new run lost to the restored, numerically higher epochs of the previous run.
  Every place an epoch travels as primitives carries the incarnation too: the on-disk consumer cursor
  block (old 24/40-byte blocks still decode, as incarnation 0), the ClusterSync
  ping/pong and peer observations, the DHT put/migration messages, and the management API (`epoch`
  objects gain `incarnation`; epoch strings are `incarnation:term:counter`).
  [verified: `aether/slice/src/test/java/org/pragmatica/aether/slice/kvstore/KVStoreAetherEpochFenceTest.java` (AcrossAColdRestart)]
- **Wire break (pre-GA):** the shapes of `Epoch`, `StreamCursorCheckpointValue`, `ClusterSyncPing`/`Pong`,
  `CommunityReport`, `PeerConnectivityObservation`, `PeerHealthObservation`, `DHTMessage.PutRequest`/`KeyValue`
  and the worker metadata `ManifestRequest`/`Manifest` changed; mixed-version clusters are not supported.
  The KV backup (`BackupEntryCodec`) stores values through these generated codecs, so a backup written
  before #1529 does not restore epoch-bearing values after it.
- **Workers survive a core cold restart.** The metadata `Manifest` carries the cluster incarnation and the
  worker's `ManifestRequest` its installed one; a newer incarnation resets the worker's revision latch, so
  the restored core's lower revision is no longer answered with `core-behind-worker` and refused forever.
  A manifest from an older incarnation is refused whatever its revision.
  [verified: `aether/node/src/test/java/org/pragmatica/aether/worker/metadata/WorkerMetadataColdRestartTest.java` — component level, real server and client; the Ember cold-restart version waits for #1533's restore]
- **A replay rewind minted after a cold restart wins over the surviving on-disk cursor.** `NodeReplayCursor`
  mints the first rewind of a newer incarnation as `(incarnation, generation, 1)`, which outranks the
  previous run's rewind epochs however high.
  [verified: `aether/node/src/test/java/org/pragmatica/aether/node/stream/ClusterCursorStoreTest.java`, `aether/node/src/test/java/org/pragmatica/aether/node/projection/NodeReplayCursorMintTest.java`]
- **`aether cluster await-quiesced --epoch` takes `incarnation:term:counter`**; the pre-#1529 `term:counter`
  form is refused (`400`) rather than read as incarnation 0, which would rank below every epoch a cluster
  mints and report quiescence at once. `aether cluster ownership` gains `EPOCH-INC`/`HW-INC` columns.
- **The integration harness's quiescence barriers send `incarnation:term:counter` and fail loudly.**
  `lib/generation.sh` reads the epoch from the top-level `epoch` object and bumps only the counter for
  `current+N`. A `400` from the barrier route, or an epoch spec it cannot build, aborts the calling
  suite whatever `|| true` wraps the call, because a barrier that silently does nothing invalidates every
  later result. [verified: `aether/tests/integration/test/test-generation-epoch.sh` (offline, stubbed
  curl)] [unverified: not run end-to-end against a live cluster]
- **`ClusterIncarnationValue` gains `incarnationId`**, a ULID minted fresh at every genesis mint and every
  restore and compared for equality only (#1625): a reused incarnation number no longer names the same
  incarnation. Shape change of a backed-up value (pre-GA; tag unchanged).
  [verified: `aether/node/src/test/java/org/pragmatica/aether/node/ClusterIncarnationTest.java`]
- [limit: incarnation-advances-only-with-1533] The incarnation is read from the committed
  `ClusterIncarnationKey` (#1529 part 1). Until #1533's restore advances it, `GitBackedPersistence` restores
  the key as-is, so a cold restart keeps the same incarnation and epochs, worker latches and rewinds behave
  exactly as before #1529. The restore-path behaviour above is what the tests show with an advanced
  incarnation.
