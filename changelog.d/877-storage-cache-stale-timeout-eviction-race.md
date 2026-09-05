### Fixed (2026-09-05 — #877: storage read cache could hand a fresh reader someone else's stale timeout)
- **A caller of `StorageInstance.get()` could receive a PREVIOUS attempt's failure instead of a
  fresh load**, because `SingleFlightCache.deduplicate()`'s eviction (`onResultRun` removing the
  settled map entry) dispatches asynchronously, off-thread
  [mechanism: `PromiseImpl.processActions` submits `onResultRun` completions via
  `AsyncExecutor.INSTANCE.runAsync`, which returns before the removal executes — unordered relative
  to a blocked `.await()` waking up]. For the whole window between a promise resolving and its
  eviction actually running, the map still held the stale, already-settled promise; any caller of
  `deduplicate()` for that `BlockId` — not only a thread that was itself blocked in `.await()` —
  landing in that window hit `computeIfAbsent`'s stale entry and inherited its result. When the
  previous attempt was a hung loader that had hit its in-flight bound, the new caller inherited that
  `CoreError.Timeout`, defeating the bound's own purpose (unstopping subsequent readers). Reachable
  in production: `StorageInstance.get()` routes every real block read through `deduplicate()`
  concurrently and non-blockingly, exactly the near-simultaneous-readers pattern the cache exists to
  coalesce; the window widens under carrier contention, i.e. worse exactly under the load where the
  cache matters most.
- `deduplicate()` now uses `compute` instead of `computeIfAbsent` and treats an existing-but-
  resolved map entry as absent, so a settled entry can never be handed out regardless of whether the
  async cleanup has run yet — this closes the race instead of narrowing it
  [verified: `SingleFlightCacheTest$HungLoaderEvictionTests#deduplicate_hungLoader_nextCallerFreshEvenWithCleanupQueuedBehindSaturatedCarriers`,
  which busy-spins exactly `jdk.virtualThreadScheduler.parallelism` virtual threads to hold every
  carrier hostage before the bound fires, forcing the cleanup dispatch to still be queued when the
  next caller arrives — 5/5 deterministic FAIL against the pre-fix `computeIfAbsent`, 5/5
  deterministic PASS against this fix, both ~0.17-0.19s (no wall-clock race or ambient-load
  dependence in either direction); `deduplicate_hungLoader_evictedAfterBoundAndNextCallerFresh`
  covers the same eviction contract without forcing the race window].
- **Honest limit on the claim:** this was found while chasing #859, an intermittently failing test
  (`SingleFlightCacheTest$HungLoaderEvictionTests.deduplicate_hungLoader_evictedAfterBoundAndNextCallerFresh`,
  "Expected fresh load to succeed: Promise timed out after 150ms") — the failure message is
  *consistent with* a previous attempt's `Timeout` resurfacing, but the race did not reproduce under
  ambient CI-like load in 40+ attempts across two independent investigations (raw CPU pressure,
  carrier-parallelism pinned to 1, host busy-loops). The causal link between this defect and the
  #859 CI failures is therefore **not proven, only consistent**. This fix closes a real window on
  its own merits, independent of whether it explains #859; whether the CI flake also disappears is
  a prediction to be confirmed by CI staying green over subsequent runs, not asserted as fact here.
  #859's test asserts a single-shot fresh load and needs no wall-clock poll once this race is
  closed — it is fixed as a consequence, not with a separate change.
