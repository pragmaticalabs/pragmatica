### Fixed (2026-10-03 — #983: every blueprint republish reset an operator-set slice placement to `CORE_ONLY`)
- **The third producer of the #698/#936/#937 defect.** `ClusterDeploymentState.handleAppBlueprintChange`
  rebuilt the whole `SliceTargetValue` from the blueprint's `ResolvedSlice` through the `sliceTargetValue(...)`
  factory, which fixes `placement = CORE_ONLY`. `/scale` is the only writer of a non-default placement and
  refuses slices that are not blueprint-owned, so every slice that could carry one was exactly a slice this
  path rewrote. The reset value is acted on (the allocation engine takes the core branch), so a republish
  moved worker-placed slices. This supersedes the "not fixed here" limitation recorded in the #936 and #937 fragments.
- **Contract the fix follows.** The blueprint has no placement key (`known-limitations.md`: "the blueprint has
  no placement key"; `ResolvedSlice` carries none), so it is silent about placement by construction and the
  committed value is the only source: a republish keeps it, and only a slice with nothing committed takes the default.
- **Fixed structurally.** New `SliceTargetValue.withBlueprintDeclaration(...)` re-applies the components a
  blueprint declares (version, `targetInstances`, `minInstances`, owner, `maxInstances`, both thresholds) onto
  the committed value and threads `placement` through from it; `handleAppBlueprintChange` uses it when a value
  exists and the creation factory only when none does. A component added to the record later is carried by
  construction instead of defaulted.
- **Class check.** Of the nine components of `SliceTargetValue`, `placement` is the only one the blueprint
  cannot express; the other seven are declared by `ResolvedSlice` and are replaced on republish by contract
  (a blueprint that stops declaring an override clears it), and `updatedAt` is a clock. No other component is reset.
- [verified: `BlueprintRepublishPlacementTest` (`aether/aether-deployment`), 7 tests, 5 red before the fix.
  Mutation table, each reverted by content: placement to default, call site reverted to the factory, and
  keeping the committed version / owner / max / up-threshold / down-threshold / instances / min each redden
  a named subset of the class]
- [unverified: no cluster run; the relocation consequence is read from `handleSliceTargetChange`, as in #937]
