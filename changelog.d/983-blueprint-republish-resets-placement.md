### Fixed (2026-10-03 — #983: every blueprint republish reset an operator-set slice placement to `CORE_ONLY` and the autoscaled instance count to the declared one)
- **The third producer of the #698/#936/#937 defect.** `ClusterDeploymentState.handleAppBlueprintChange`
  rebuilt the whole `SliceTargetValue` from the blueprint's `ResolvedSlice` through the `sliceTargetValue(...)`
  factory, which fixes `placement = CORE_ONLY`. `/scale` is the only writer of a non-default placement and
  refuses slices that are not blueprint-owned, so every slice that could carry one was exactly a slice this
  path rewrote. The reset value is acted on (the allocation engine takes the core branch), so a republish
  moved worker-placed slices. This supersedes the "not fixed here" limitation recorded in the #936 and #937 fragments.
- **Contract the fix follows.** The blueprint has no placement key (`known-limitations.md`: "the blueprint has
  no placement key"; `ResolvedSlice` carries none), so it is silent about placement by construction and the
  committed value is the only source: a republish keeps it, and only a slice with nothing committed takes the default.
- **The autoscaled count is the same defect.** The republish also replaced `targetInstances` with the blueprint's
  declared count, and the rollout allocates from that value (`handleSliceTargetChange` reads
  `value.targetInstances()`), so a redeploy of a scaled-up slice restarted it at the declared scale and could
  overload it. Owner ruling: the autoscaled count is carried. It is clamped into the NEW blueprint's
  `[minAvailable, maxInstances]` (the CTO's reading of that ruling): a count the new bounds exclude moves to the
  nearest bound, and where `min > max` the minimum wins. The declared count applies only to a first deploy.
- **Fixed structurally.** New `SliceTargetValue.withBlueprintDeclaration(...)` re-applies a blueprint onto the
  committed value and threads `placement` and the clamped `targetInstances` through from it;
  `handleAppBlueprintChange` uses it when a value exists and the creation factory only when none does, and
  registers the same count in its in-memory blueprint so the reconciler and the committed value agree.
- **Class check.** Of the nine components of `SliceTargetValue`, `placement` and `targetInstances` are the two the
  blueprint cannot or must not overwrite; `currentVersion`, `minInstances`, owner, `maxInstances` and both
  thresholds are declared by `ResolvedSlice` and replaced on republish by contract (a blueprint that stops
  declaring an override clears it); `updatedAt` is a clock.
- [verified: `BlueprintRepublishPlacementTest` (`aether/aether-deployment`), 18 tests: placement carried, count
  carried (with and without a declared max), clamped down to a new max, clamped up to a new min, carried across
  a version change, first deploy uses the declared count and default placement. 13 mutations (plus the restore hunk) each reverted by
  content, each reddening a named subset]
- **Leader restore.** `restoreAppBlueprint` registered the declared count in memory for the same artifact
  `restoreSliceTarget` registers, and `Active.onEntry` iterates the store in JVM-salted order, so after a leader
  change the declared count won about half the time and the reconciler resized an autoscaled slice back to it.
  Both entries now register the committed count. [verified: `leaderRestore_*` tests force both restore orders;
  with the `restoreAppBlueprint` hunk reverted, the BLUEPRINT_LAST order of each of the two carry tests reddens]
- [unverified: no cluster run]
