### Added (2026-11-11 — #3021: Staged repository router and Ember seams for the JUnit extension)
- **`RepositoryType.Staged(root, moduleSet)`** (code-only, not parseable from TOML): a router whose factory arm locates a module's
  own coordinates through the staged root ALONE and every other coordinate through the Maven local repository, for both
  `locate(Artifact)` and `locate(Artifact, classifier)`. A staged miss is `ArtifactNotFound`; it never falls through to a jar
  of the same coordinate in `maven.repo.local`. `Staged.refuseShared` refuses a module coordinate that is also `[shared]`.
- **Ember seams** (before `start`, refused after): `withSliceRepositories`, `withConfigSource`, `withHarnessTempRoot` (the
  participation-marker dir is now created lazily), `withRoutePublicationHeld` (+ `releaseRoutePublication`), `routeTable(nodeId)`;
  `withSliceRepositories` refuses a null, empty or null-holding list with an `InvalidSliceConfig`. `StagedPreCheck` is the early
  `EmberAppNotPackaged` refusal through the same router, with a bypass seam for the fail-closed probe. It refuses a missing, empty,
  non-regular or unreadable jar and one without a readable manifest, keeps the locate cause in the text and picks the remedy by
  coordinate kind (module: package it; other coordinate: install it into the Maven local repository). **It does not check
  staleness**: a readable jar passes even when the sources changed since it was packaged; that is deferred to #3021 step 3 (the
  extension, which knows the build), and the refusal text says so.
- `HttpRoutePublisher.holdRoutePublication` / `releaseRoutePublication` let a test publish routes after slice activation returned:
  while held a publication is recorded as pending (not in the local route table, not in the cluster), `unpublishRoutes` cancels
  it, the release publishes the pending set. A condition the test releases, not a timer. Its only main caller is `EmberCluster`,
  as `StagedPreCheck.bypassed()` has none [verified: aether/ember `StagedSeamCallerCensusTest`].
  [verified: aether/node `RepositoryFactoryStagedTest`, aether/ember `StagedPreCheckTest`, `EmberClusterStagedSeamsTest`,
  aether/aether-invoke `HttpRoutePublisherPublicationHoldTest`]
