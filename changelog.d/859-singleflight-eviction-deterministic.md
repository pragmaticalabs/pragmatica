### Fixed (2026-09-05 — #859: SingleFlightCache eviction raced its own cleanup, letting a caller inherit a stale Timeout)
- **A caller arriving right after a hung loader's in-flight bound fired could inherit the OLD
  `CoreError.Timeout` instead of getting a fresh load**, because `deduplicate()`'s eviction
  (`onResultRun` removing the map entry) dispatches asynchronously off-thread
  [mechanism: `PromiseImpl.processActions` submits `onResultRun` completions via
  `AsyncExecutor.INSTANCE.runAsync`, unordered relative to a blocked `.await()` waking up]. Any
  caller of `SingleFlightCache.deduplicate()` — not only a thread that was itself blocked in
  `.await()` — landing in that window before the removal ran would hit the still-mapped, already-
  resolved entry via `computeIfAbsent` and inherit its stale result. Reachable in production
  through `StorageInstance.get()`, which calls `deduplicate()` concurrently for every block read;
  the window widens under exactly the load (virtual-thread carrier contention) where the cache
  matters most.
- `deduplicate()` now uses `compute` instead of `computeIfAbsent` and treats an existing-but-
  resolved map entry as absent, so a settled entry can never be handed out regardless of whether
  the async cleanup has run yet — this closes the race instead of narrowing it
  [verified: `SingleFlightCacheTest$HungLoaderEvictionTests#deduplicate_hungLoader_evictedAfterBoundAndNextCallerFresh`,
  single-shot assertion, no polling].
