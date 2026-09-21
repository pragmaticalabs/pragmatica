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
  (no newer/older policy exists). **The shape slices actually carry is CARET**: `PackageSlicesMojo.toArtifactInfo`
  writes every `[infra]`/`[shared]` line as `^<version>`, so in production a major bump is a `Conflict` and a
  minor/patch difference is `Compatible`; qualifiers order as `VersionPattern.compareVersions` orders them
  (lexicographic — see the follow-up ticket on `""` < `SNAPSHOT` and `rc10` < `rc9`). `Exact` (equality) is
  reachable only from a hand-written dependency file.
  - **`Conflict`** (the loaded version does not satisfy the requesting slice's pattern, in either
    direction): the requesting slice's load **fails** with the new
    `SliceLoadingFailure.Fatal.SharedLoaderVersionConflict`, whose message names the requesting slice,
    the requested coordinates, the loaded coordinates and the slice that loaded them — e.g.
    `slice slice-b requires org.example:lib:2.0.0 but org.example:lib:1.0.0 is already loaded by slice-a`.
    Fatal, so the cluster spends no retry budget on it (#930): no retry can change what the shared loader
    holds. The shared loader's state is unchanged (the first version still wins); what changed is that
    the second slice is refused instead of downgraded. Recovery: align the `[infra]` version across the
    slices that declare it, declare it `[shared]` (whose existing rule loads a conflicting version into
    the slice's own loader), or restart the node — a same-node upgrade (`slice-b:2.0.0` declaring `^2.0.0`
    after `slice-b:1.0.0` loaded `^1.0.0`) is refused naming its own predecessor, and only a restart
    releases what the shared classloader holds. A slice refused on its second `[infra]` entry leaves its
    first entry in the shared loader attributed to itself; nothing can remove a `URLClassLoader` URL, so
    this partial state is inherent and the message says so.
    [verified: same test class — `…inTheCaretShapeThePluginEmits_fails`, `…olderExactRequest_alsoFails`,
    `…isTypedFatal`, `…namesTheSharedRequester`]
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
    again stays a success no-op; the equality includes the qualifier, `1.0.0-SNAPSHOT` ≠ `1.0.0`).
    [verified: `SharedLibraryClassLoaderTest.addArtifact_refusesADifferentVersion_namingBothVersionsAndRequesters`,
    `…refusesTheSameBaseVersionWithADifferentQualifier`]
  - **Inside that window the refusal is re-evaluated against the rule, not taken as final.** The guard
    compares exact versions but the rule is `pattern.matches(loaded)`: a `^1.0.0` request whose competitor
    landed `1.2.0` during locate is Compatible and reuses `1.2.0` — the outcome it would have had if the
    competitor had landed first — instead of failing Fatal (rev1416 M1). On `[shared]` the same
    re-evaluation routes a genuine conflict to the per-slice fallback.
    [verified: `SharedDependencyLoaderTest.infraCompatibleRequestInsideTheCheckToAddWindow_reusesTheLandedVersion`,
    `…sharedCompatibleRequestInsideTheCheckToAddWindow_reusesTheLandedVersion`,
    `…sharedConflictInsideTheCheckToAddWindow_takesThePerSliceFallback_notRuntimeProvided`]
  - `SharedDependencyLoader.addInfraToSharedLoader` / `addToSharedLoader` discarded `addArtifact`'s
    `Result`; both now propagate it. [verified: `SharedDependencyLoaderTest.infraConflictInsideTheCheckToAddWindow_stillFailsLoudly`
    and `…sharedConflictInsideTheCheckToAddWindow_takesThePerSliceFallback_notRuntimeProvided`, which
    interleave a competing load between `checkCompatibility` and `addArtifact` deterministically]
  - `[shared]`'s `loadIntoShared` chained `.orElse(registerAsRuntimeProvided)` AFTER the add, so an
    add refusal would have been re-routed into a runtime-provided registration — a no-op success on a
    key already held. The locate is now resolved to an `Option` before the add is chained, so only a
    failed locate registers runtime-provided. [verified: `…sharedConflictInsideTheCheckToAddWindow_takesThePerSliceFallback_notRuntimeProvided`
    reddens when the `orElse` is put back after the add — a runtime-provided no-op leaves `conflictingJarUrls` empty]
  - `registerRuntimeProvided` keeps its first-registration-wins guard (a duplicate cannot change the
    held version and `checkCompatibility` precedes it on the sequential path); it now records the
    requester so a later `[infra]` conflict against it is attributable.
    [verified: `runtimeProvidedRegistration_recordsTheRequester`]
- To name both requesters the loader now records who first loaded each artifact
  (`SharedLibraryClassLoader.loadedBy`), and `processSharedDependencies` / `processInfraDependencies`
  take the requesting slice's artifact, passed from `DependencyResolver` as `manifest.artifact()`.
  [verified: `aether/slice/src/test/java/org/pragmatica/aether/slice/dependency/DependencyResolverInfraConflictTest.java`
  drives both `resolve` and `resolveWithContext`, for both the `[infra]` and the `[shared]` call site, with
  slice jars whose dependency files carry the plugin's caret shape, and asserts the message names
  `org.example:slice-b:1.0.0` (from the manifest) and `org.example:slice-a:1.0.0`]
- **The worker path no longer silences the refusal** (rev1416 N1). `WorkerDeploymentManager.handleDeploymentFailure`
  forwarded a bare `nodeArtifactValue(FAILED)` — no reason, `fatal=false` — so cluster-wide a permanent
  version conflict (and every other `Fatal` on that path) read as retryable and the two slices that
  disagreed were named only in that worker's log. It now forwards
  `NodeArtifactValue.failedNodeArtifactValue(cause, …)`: the message and the classified `fatal` flag reach
  consensus. The disposition of an UNTYPED cause is decided per phase, as the FSM decides it (#930): the
  load phase is classified `PERMANENT` at its boundary (as `NodeDeploymentState.handleLoadingFailure` —
  retrying re-runs the same deterministic work), and everything after it — activation
  (`materializeAll()` resource connects, `slice.start()`) and publication — is `RETRY` (as
  `handleActivationFailure`, the site that closes #923 — an unreachable database must not roll a
  blueprint back). A cause typed at its raise site classifies the same way in either phase.
  [verified: `aether/node/src/test/java/org/pragmatica/aether/worker/deployment/WorkerDeploymentManagerTest.java`
  — `loadFailure_isForwardedWithItsReasonAndFatalFlag`, `untypedLoadFailure_isForwardedAsPermanent`,
  `untypedActivationFailure_isForwardedAsRetryable`, `typedFatalActivationFailure_isForwardedAsFatal`,
  control `intermittentLoadFailure_isForwardedAsRetryable`]
  [unverified: the worker's failure handler does not unpublish routes/subscriptions the way the FSM's
  `handleActivationFailure` does — pre-existing, not touched here]
- [unverified: no multi-node run drove two deployed slices with conflicting `[infra]` versions; pinned at
  the resolver's entry points and the worker manager's forwarded record, not on a live cluster]
