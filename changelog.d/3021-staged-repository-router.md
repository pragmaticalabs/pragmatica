### Added (2026-11-11 — #3021: Staged repository router and Ember seams for the JUnit extension)
- **`RepositoryType.Staged(root, moduleSet)`** (code-only, not parseable from TOML): a router whose factory arm locates a module's
  own coordinates through the staged root ALONE and every other coordinate through the Maven local repository, for both
  `locate(Artifact)` and `locate(Artifact, classifier)`. A staged miss is `ArtifactNotFound`; it never falls through to a jar
  of the same coordinate in `maven.repo.local`. `Staged.refuseShared` refuses a module coordinate that is also `[shared]`.
- **Ember seams** (before `start`, refused after): `withSliceRepositories`, `withConfigSource`, `withHarnessTempRoot` (the
  participation-marker dir is now created lazily), `withRoutePublicationDelay`, `routeTable(nodeId)`; `StagedPreCheck` is the
  early `EmberAppNotPackaged` refusal through the same router, with a bypass seam for the fail-closed probe.
- `HttpRoutePublisher.setRoutePublicationDelay` lets a test publish routes after slice activation returned.
  [verified: aether/node `RepositoryFactoryStagedTest`, aether/ember `StagedPreCheckTest`, `EmberClusterStagedSeamsTest`,
  aether/aether-invoke `HttpRoutePublisherPublicationDelayTest`]
