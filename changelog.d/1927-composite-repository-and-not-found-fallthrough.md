### Fixed (2026-10-04 — #1927: the schema orchestrator's repository ignored all but the first repository, and two services folded any failure into "try the next source")
- **`AetherNode.compositeRepository` is now a real composite.** It returned `repositories.getFirst()`, so schema orchestration ignored every
  repository after the first. It is the new `CompositeRepository` (the logic `SliceStore` already had, now shared): every configured
  repository is consulted in order for both `locate(artifact)` and `locate(artifact, classifier)`, and the artifact is reported not found
  only when every repository answered "absent", unavailable (naming each outcome) when one could not answer.
- **`SchemaOrchestratorService` and `BlueprintService` fall through only on a genuine not-found.** `resolveArtifactBytes` chained the
  repository locate and the artifact store with `orElse`, so an unreachable repository, or a located artifact whose bytes could not be
  read, fell through and surfaced as the next source's "not found" (or was silently replaced by the store's copy). The new
  `Repository.orElseWhenAbsent` / `Repository.isAbsent` let only `Repository.Absent` and `ArtifactNotFound` fall through; every other
  failure is reported as itself (same rule as #1436 for the shared-library locate).
  [mechanism: `CompositeRepositoryTest` (5), `BlueprintArtifactResolutionTest` and `SchemaArtifactResolutionTest` (8) were RED at rc4 `9768217b8`.]
