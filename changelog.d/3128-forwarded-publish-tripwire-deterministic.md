### Fixed (2026-11-13 — #3128: the forwarded-publish transport-loop tripwire sampled stacks and flaked)
- **`EmberForwardedPublishLoopTripwireTest` no longer infers the park from stack sampling.** The park it guards (C-01, #2201)
  is the synchronous `EpochHistory.persist` -> `FileChannel.force` (sidecar file, then directory) that the FIRST append of a
  fresh owner epoch runs on the transport event-loop thread; one short stall per epoch, which a sampler hit only by luck
  (0 of 1875 samples under CI load, with a message that falsely announced C-01 fixed). The tripwire now observes the thread
  that enters `persist` through a test-only probe (`AppendLog.epochPersistProbe`, one volatile read when unset), on a fresh
  stream so every run records a fresh epoch. The disabled acceptance test asserts `persist` is never entered on a transport thread.
