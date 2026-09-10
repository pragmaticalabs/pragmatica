### Fixed (2026-09-10 — #892: a slice unload now actually closes the resources it provisioned)

- **No provisioned resource was ever closed on a deployed node, for two independent reasons on one
  path.** Either alone was sufficient; both reported success while doing nothing, so nothing
  upstream could tell. They sit downstream of #268 (the cache the release drains) and #891 (the
  close dispatch the release invokes): with those two fixed the machinery was correct and still
  inert in production.
  1. **The node's `ResourceProviderFacade` never overrode `releaseAll`.**
     `AetherNode.createResourceProviderFacade` built it as an anonymous class implementing the two
     `provide` overloads only, so the release fell through to the interface's
     `Promise.unitPromise()` default and **no provider was ever reached**. Enumerated: 10
     `ResourceProviderFacade` implementations in main sources; this was the only production one
     affected (four in `SliceLoadingContext` override it, `SliceScopedResourceProvider` overrides it
     and forwards *to this same facade*, two refuse provisioning outright and so have nothing to
     release, one is the test kit's in-memory map).
  2. **The release identity could never match a provisioning scope even when it arrived.**
     Provisioning is scoped by `SliceLoadingContext.SliceAwareResourceProvider` to the deployed
     `Artifact` — `groupId:artifactId:version`, three segments, set by `DependencyResolver` from
     `artifact.asString()`. Release was driven by a slice's generated `stop()`, which passes a
     compile-time literal from `FactoryClassGenerator.computeSliceArtifactCoordinate`:
     `groupId:artifactId-kebab(SliceName)` — two segments, no version. `SpiResourceProvider`
     compares scope strings for equality. **Measured on the tree at the time of the fix: 34
     generated `stop()` bodies, every one two-segment, 0 able to match.**

- **Both fixed by removing the second source of truth rather than by reconciling two strings.**
  - `SliceAwareResourceProvider.releaseAll` now releases under the id it *provisions* under,
    discarding the caller's argument. The processor cannot be fixed into agreement — it has no
    version option to emit (`SliceProcessor`'s `@SupportedOptions` carries `slice.groupId` and
    `slice.artifactId` only) and compile-time code cannot know a deployment's version — so the
    identity is taken from the one place holding the deployed `Artifact`. This also repairs
    **already-compiled slice jars**: no slice needs rebuilding to release correctly.
  - New `ResourceProvider.facade()` returns a complete forwarding adapter; `AetherNode` uses it
    instead of a hand-rolled partial one. Naming the adapter does not make partial implementation
    impossible — the interface default is still inheritable — but it removes the reason to write
    one.
  - The generator still emits its two-segment coordinate and the parameter is still forwarded where
    the slice-aware wrapper is absent (a context with no slice id). That branch closes nothing
    either way: such a context injects no provisioning scope, so its resources land in the
    provider's unattributed scope, which no release matches by design (#268 R2). It is preserved as
    the contract, not as a working fallback. `computeSliceArtifactCoordinate` carries a comment
    saying so, so the asymmetry is legible from the generator's side too.

- **No envelope version bump:** the `slice-processor` change is a comment; generated output is
  byte-identical.

- **What is verified, and by what.** [verified:
  `ReleaseIdentityTest#unloadSlice_closesTheResourceTheSliceProvisioned`,
  `#deactivateSlice_closesTheResourceTheSliceProvisioned` (`aether/slice-testkit`)] — a real slice
  jar loaded, activated and unloaded through `SliceStore`, the deployment entry points
  `NodeDeploymentState` calls, with the real `SpiResourceProvider`, the node's own
  `SliceScopedResourceProvider` wiring, and the processor-generated `ReleaseProbeFactory` whose
  `stop()` carries the literal the generator emitted. The assertion is on whether the RESOURCE was
  closed, with a control asserting it is not closed before the unload.
  `#generatedStop_emitsACoordinateNoDeployedArtifactCanEqual` measures the premise from the current
  processor's own output rather than restating the ticket: the emitted literal does not even parse
  as an `Artifact`, so no `artifact.asString()` scope can equal it — a structural mismatch, not a
  version skew a tolerant comparison could bridge.
  [verified: `SliceLoadingContextReleaseIdentityTest` (`aether/slice-api`)] pins the substitution
  itself, including the invariant stated without naming either string — the id released IS the id
  provisioning is scoped to — and the forwarding branch for a context with no slice id.
  [verified: `SpiResourceProviderLifecycleTest$FacadeView` (`aether/resource/api`)] pins that the
  facade forwards the release to the provider, does not widen it to other scopes, and shares the
  provider's cache.

- **What is NOT verified.** This is the node's slice-lifecycle path in-process — no cluster, no
  ports, no consensus. The one-line `AetherNode` call site is **not independently pinned by a test**:
  `createResourceProviderFacade` is a private static method with no seam, so its correctness rests
  on `ResourceProvider.facade()` being complete (which is pinned) plus review of the call site. A
  live-cluster measurement of a real resource's release — the Netty `EventLoopGroup` thread and FD
  counts — is #895, which was deliberately held pending this fix.

  [mechanism: release identity is read from the deployed `Artifact` the loading context already
  provisions under, so the two sides cannot be computed independently — all production hunks
  mutation-probed, each reverted in turn with its named tests confirmed red, then restored and
  reconfirmed green]
