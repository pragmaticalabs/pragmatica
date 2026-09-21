### Fixed (2026-09-21 — #1184: `[infra]` version conflicts were swallowed; the first version won permanently and silently)
- **`SharedDependencyLoader.loadInfraIntoShared` discarded the `CompatibilityResult`**, so a slice
  declaring `[infra] org.example:lib:2.0.0` after another slice had loaded `1.0.0` was logged at DEBUG as
  "already loaded" and ran against `1.0.0`. The runtime computed `Conflict[loadedVersion=1.0.0,
  required=Exact[2.0.0]]` and threw it away; the only later symptom was #758's "class absent" diagnostic
  pointing at a rebuild that would not have helped.
  [verified: `aether/slice/src/test/java/org/pragmatica/aether/slice/dependency/SharedDependencyLoaderTest.java`
  — `infraVersionConflict_failsTheSecondSliceLoad_namingBothVersionsAndBothRequesters` is RED on the
  base with `slice-b's [infra] org.example:lib:2.0.0 was accepted although 1.0.0 is loaded; loader holds
  {org.example:lib=1.0.0} and serves [file:/repo/org.example-lib-1.0.0.jar]`]
- Guarantee per case, applying the existing rule `CompatibilityResult.check` = `required.matches(loaded)`
  (no newer/older policy exists; `Exact` is equality, `^`/`~`/ranges/comparisons as `VersionPattern`
  defines them):
  - **`Conflict`** (the loaded version does not satisfy the requesting slice's pattern, in either
    direction): the requesting slice's load **fails** with the new
    `SliceLoadingFailure.Fatal.SharedLoaderVersionConflict`, whose message names the requesting slice,
    the requested coordinates, the loaded coordinates and the slice that loaded them — e.g.
    `slice slice-b requires org.example:lib:2.0.0 but org.example:lib:1.0.0 is already loaded by slice-a`.
    Fatal, so the cluster spends no retry budget on it (#930): no retry can change what the shared loader
    holds. The shared loader's state is unchanged (the first version still wins); what changed is that
    the second slice is refused instead of downgraded. Recovery: align the `[infra]` version across the
    slices that declare it, or declare it `[shared]`, whose existing rule loads a conflicting version
    into the slice's own loader.
    [verified: same test class — `…olderExactRequest_alsoFails`, `…isTypedFatal`,
    `…namesTheSharedRequester`]
  - **`Compatible`** (the loaded version satisfies the pattern, e.g. `^1.0.0` against loaded `1.2.0`):
    unchanged — the slice reuses the loaded version, nothing is added. Same exact version twice is a
    no-op. [verified: `infraCompatibleRequest_reusesTheLoadedVersion`, `infraSameVersionTwice_isANoOp`]
  - **`[shared]`** is unchanged: a conflict still goes to the slice's own loader.
    [verified: `sharedVersionConflict_stillLoadsIntoTheSliceLoader`]
- **Sibling swallow points on the same path, each fixed:**
  - `SharedLibraryClassLoader.addArtifact` returned success after a WARN when a different version was
    already held. That guard is reachable in production even after `checkCompatibility`, because the
    repository locate between check and add is asynchronous, so two slices loading concurrently could
    still hit the silent downgrade. It now returns the same `SharedLoaderVersionConflict` (same version
    again stays a success no-op). [verified: `SharedLibraryClassLoaderTest.addArtifact_refusesADifferentVersion_namingBothVersionsAndRequesters`]
  - `SharedDependencyLoader.addInfraToSharedLoader` / `addToSharedLoader` discarded `addArtifact`'s
    `Result`; both now propagate it into the load `Promise`. [mechanism: `flatMap(… .async())` replaces
    `map(… unit())`]
  - `[shared]`'s `loadIntoShared` chained `.orElse(registerAsRuntimeProvided)` AFTER the add, so an
    add refusal would have been re-routed into a runtime-provided registration — a no-op success on a
    key already held. The locate is now resolved to an `Option` before the add is chained, so only a
    failed locate registers runtime-provided. [mechanism: `locateOptional` in `SharedDependencyLoader`]
  - `registerRuntimeProvided` keeps its first-registration-wins guard (a duplicate cannot change the
    held version and `checkCompatibility` precedes it on the sequential path); it now records the
    requester so a later `[infra]` conflict against it is attributable.
    [verified: `runtimeProvidedRegistration_recordsTheRequester`]
- To name both requesters the loader now records who first loaded each artifact
  (`SharedLibraryClassLoader.loadedBy`), and `processSharedDependencies` / `processInfraDependencies`
  take the requesting slice's artifact, passed from `DependencyResolver` as `manifest.artifact()`.
  [verified: `aether/slice/src/test/java/org/pragmatica/aether/slice/dependency/DependencyResolverInfraConflictTest.java`
  drives both `resolve` and `resolveWithContext` with a slice jar whose dependency file declares the
  conflicting `[infra]` version and asserts the message names `org.example:slice-b:1.0.0` (from the
  manifest) and `org.example:slice-a:1.0.0`]
- [unverified: no multi-node run drove two deployed slices with conflicting `[infra]` versions; the
  claim above is pinned at the resolver's entry points, not on a live cluster]
