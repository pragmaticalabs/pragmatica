### Fixed (2026-09-22 — #1387: WAL replay after a restart marked unacknowledged offsets visible)
- **A restart promoted "durable on the owner, unacknowledged by min-sync" to "visible".** A partition's
  visibility watermark is `min(durable, acknowledged)`, but recovery restored only the first half of it:
  `OffHeapRingBuffer.seedHead` set `visibleOffset` to the durable sealed floor and `StreamPartitionManager`'s
  `placeRecord` replayed the WAL tail with the plain `append`, which has no durability gate and is visible at
  once. Readers then saw records whose publishers were told at most that the outcome is unknown, and which no
  replica is known to hold. Measured before the fix as `visible = 4`, `fetch(0) = [0,1,2,3,4]` where
  `[0,1,2]` was honest `[verified: aether/aether-stream/src/test/java/org/pragmatica/aether/stream/StreamRestartVisibleWatermarkTest.java]`.
- **Replay now appends durable-but-not-visible** (`OffHeapRingBuffer.appendDurable`, built on the existing
  `appendOrdered`, which already appends without advancing visibility or waking a consumer), and recovery
  recomputes the watermark ONCE from the live acknowledgement state with the same expression the live path
  uses. `advanceVisible` is monotonic, so the sealed floor that was visible before the restart is never
  lowered by this: **a restart hides nothing a reader could see before it**
  `[mechanism: StreamEntry.restoreVisibleWatermark, advanceVisible is a monotonic max]`.
- **`minSyncReplicas` is read from the config being materialized, not from the `streams` map.** The new
  `AcknowledgedOffsetSource` seam exists for exactly this: while `StreamEntry.fromConfig` recovers a
  partition the entry is not in that map yet, and `minSyncReplicasFor` would answer `0` — "no acknowledgement
  required" — reinstating the defect on the createStream path while the lazy-materialize path was fixed
  `[verified: the same test, reddened by a mutation that uses the map lookup]`.
- **`minSyncReplicas <= 1` is unaffected and cannot observe any of this.** `replicatedThrough` answers
  `Long.MAX_VALUE` when no peer acknowledgement is required, so visible tracks durable and the whole replayed
  tail is visible the moment recovery finishes `[verified: the same test's min-sync-1 case, which guards
  against the fix over-hiding rather than standing as a control for the defect]`.
- `[unverified: with `minSyncReplicas >= 2` and a peer that never returns, a replayed tail stays
  durable-but-invisible indefinitely — nothing re-replicates an unacknowledged tail on owner recovery
  (`replicateEvent` is reached only from the publish path). This restores the pre-restart verdict rather than
  adding a new refusal, and closing it is a separate change.]`
- `[unverified: a prefix that was SEALED while still unacknowledged still reads as visible across a restart.
  `seedHead`'s floor is the sealed watermark, and eviction under pressure seals ahead of the acknowledgements
  (#1352's `unacknowledgedEvictee_isSealed_andReadsAsNotYetVisible` pins that it can). Same defect class, a
  smaller offset range; only persisting the acknowledged watermark would close it.]`
