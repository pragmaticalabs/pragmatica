### Fixed (2026-09-10 — #937: operator-set slice placement was silently reset to `CORE_ONLY` on the first autoscale or A/B write, and the reset value was acted on)
- **The placement was not merely uncopied — it was inexpressible.** Every
  `SliceTargetValue.sliceTargetValue(...)` overload except the placement-taking one hardcodes
  `CORE_ONLY`, and both rebuilding producers used the 7-arg overload. An operator who set a placement
  through `POST /api/slices/scale` (which preserves it correctly) had it reset by the first autoscale
  decision or A/B write.
- **The reset is acted on, not merely stored.** `ClusterDeploymentState.handleSliceTargetChange` feeds
  `value.effectivePlacement()` straight into `SliceAllocationEngine.issueAllocationCommandsWithPlacement`.
  A workload deliberately placed on worker nodes was **relocated onto the core** on its first scale
  event — no command, event or log saying so. This is worse than its sibling defects (#698 owner,
  #936 `minInstances`), which corrupt data that is later consulted; this one changes where the slice
  runs.
- **Fixed at the shared root.** See #936's entry: `ControlLoopContext` now derives the scaling Put
  from the observed value (`observed.withInstances(newInstances)`) rather than rebuilding it, so
  placement survives by construction along with every other component.
  `AbTestManager.targetPreservingOverrides` likewise transforms the value it already reads
  (`current.withVersion(version).withInstances(1, 1)`) instead of enumerating the fields to carry —
  the enumeration is what let placement go missing while the method's own javadoc read as exhaustive.
- **The structural obstacle #937 names is dissolved rather than worked around.** `ClusterController.Blueprint`
  still has no placement component and did not need one: the registration holds the whole
  `SliceTargetValue` and the scaling projection is derived from it. Adding a placement field would
  have fixed placement and left the next component to be found by whoever reads the constructor next.
- **New `SliceTargetValue.withInstances(int, int)`** for producers that pin both instance counts
  (A/B canary, promote, restore) without disturbing anything else the value carries.
- **Deleted the dead `lookupPlacement` helper** in `ClusterDeploymentState` (#937 acceptance 5) — a
  `@SuppressWarnings("unused")` reader of the very field this ticket is about, with zero callers. The
  live consumer is `handleSliceTargetChange`.
- [verified: `ControlLoopPlacementPreservationTest` (`aether/aether-control`) — the end-to-end chain
  #937 recorded as `[mechanism:]` and never executed. An operator-placed slice is registered through
  the production feeder, scaled by a real evaluation cycle, and **the value the autoscaler actually
  emitted** is delivered to a live `ClusterDeploymentState.Active` as a `SliceTargetPutReceived`
  event; the assertion is that the allocation engine writes a `WorkerSliceDirectiveValue` carrying
  `WORKERS_ONLY`. Under the reset placement the engine takes the core branch and writes no worker
  directive at all, so the two outcomes are mutually exclusive rather than merely different]
- [verified: opposite polarity in the same class — a genuinely `CORE_ONLY` slice must produce **no**
  worker directive, so the assertion above cannot be satisfied by an engine writing one
  unconditionally]
- [verified: `SliceTargetOverridePreservationTest.AbTestPromotePreservesOverrides` (`aether/aether-invoke`)
  — placement survives both A/B writes, the canary deploy and the promotion]
- [unverified: no cluster run. Every claim above is from unit-level tests and source read in this
  tree; the relocation was never reproduced against a live multi-node cluster with real worker nodes]
