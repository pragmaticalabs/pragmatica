### Fixed (2026-10-04 — three more Promise loops that nested stack frames per batch or statement, measured)
- **The artifact chunk fan-out (`ArtifactStore.boundedFanOut`), the retention age learning pass
  (`RetentionEnforcer.learnInBatches`) and the test-kit schema migration runner (`SchemaMigrations.applyFrom`) are now loops.**
  Each ran the next step's continuation inline whenever a step settled synchronously, 6 stack frames per batch or statement
  (measured at 3,200 items: 2,480, 2,505 and 12,074 frames), so depth grew with artifact size, restart backlog or script length
  until a 1 MB stack overflowed. A settled step is consumed in place and a pending one suspends and resumes (the #1392 / #1395
  shape): the same probes now read 90, 116 and 82 frames at every size.
  [mechanism: `ArtifactStoreFanOutDepthTest`, `RetentionAgeLearningDepthTest`, `SchemaMigrationsDepthTest` assert the maximum depth
  at 400 batches (2,000 statements) is within 60 frames of 2 batches (10 statements); each is RED with its production file reverted.]
