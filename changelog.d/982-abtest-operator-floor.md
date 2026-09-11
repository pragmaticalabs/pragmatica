### Fixed (2026-09-11 — #982: A/B writer pins minInstances=1 and never restores it — an operator's floor of 5 silently becomes 1 for the duration of any A/B test)
- **An operator's availability floor was replaced by `1` on the first A/B write and never put back**,
  so a slice configured with `minInstances = 5` ran under an effective floor of 1 from the moment a
  test started — and kept it after the test ended, because the conclusion writes re-applied the same
  pin rather than restoring anything. All three A/B lifecycle writes (`createTest`, `promoteWinner`,
  `restoreBaseline`) went through one `targetPreservingOverrides` that set **both** instance counts
  to `1`, so there was no write left that could restore the floor. Nothing errors and nothing logs:
  the cluster simply runs with a lower minimum than configured.
- **The clobber is durable, not in-memory.** The value is written as a `KVCommand.Put` through
  consensus into the replicated KV store, and `ClusterDeploymentState.restoreSliceTarget` reads it
  back through `effectiveMinInstances()` on restore. It therefore survives a leader change and a full
  cluster restart — a clean boot re-derives the floor of 1 from the durable record.
  `[mechanism: AbTestManager.cacheAndPersistTest / clusterNode.apply → ClusterDeploymentState.restoreSliceTarget]`
- **The canary write never needed to lower the floor**, which is what makes the fix a preservation
  rather than a trade-off. The variant is a *different artifact version*, so
  `handleSliceTargetChange` allocates it from zero instances and `issueAdjustmentCommands` scales
  **up** to `targetInstances`. `minInstances` is read in exactly two places, both reductions —
  `SliceAllocationEngine.issueScaleDownCommands` caps removals at `activeCount - minInstances`, and
  `DecisionTreeController` gates a scale-down on `instances > minInstances` — and teardown bypasses
  both by issuing unload commands directly. A preserved floor can neither hold a canary above one
  instance nor pin a variant that has to be removed.
  `[mechanism: SliceAllocationEngine.issueAdjustmentCommands / issueScaleDownCommands / issueDeallocationCommands, DecisionTreeController.decideForArtifact]`
- `targetPreservingOverrides` is split into the two writes the A/B lifecycle actually wants, both
  deriving from the observed value as #936/#937 established: `variantTarget` places the canary at one
  instance and carries the floor untouched; `concludedTarget` promotes or restores at
  `effectiveMinInstances()`.
  `[verified: aether/aether-invoke/src/test/java/org/pragmatica/aether/update/AbTestOperatorFloorTest.java — seeds target 6 / floor 5, then returns the A/B writer's OWN output through the store as consensus does before concluding. The feedback step is load-bearing: without it the conclusion write reads the pristine seed, sees the floor it asked for, and passes against the defect]`
- **A concluded slice is no longer left parked below the floor it declares.** The conclusion writes
  previously left `targetInstances = 1`, so a promoted winner ran one instance under a floor of 5 and
  nothing ever raised it — the allocation engine drives to `targetInstances`, and no path climbs to
  the floor. Promotion and rollback now write `effectiveMinInstances()`, which is the operator's
  floor clamped to at least one.
  `[verified: AbTestOperatorFloorTest.concludeTest_restoresOperatorFloor_afterTheCanaryWrite, rollbackTest_restoresOperatorFloor_afterTheCanaryWrite]`
- **[unverified: this restores the floor's worth of capacity, NOT the exact pre-test
  `targetInstances`.]** A slice scaled to 8 above a floor of 5 concludes at 5 and climbs again on
  load. Recovering the exact count would mean persisting it in `AbTestValue` for the lifetime of the
  test, which needs a wire change; the floor is the operator's stated guarantee and is already in the
  record, so it is what this fix restores. An operator who scales a slice *while* a test is running
  is likewise not modelled — the conclusion write derives from the observed record, so a mid-test
  scale is carried, not reverted.
- **[unverified: not fixed here, found while tracing this one.]** `AbTestManager` never writes a
  `VersionRoutingKey`, so `ClusterDeploymentState.activeRoutings` does not contain the artifact base
  during an A/B test and `handleSliceTargetChange` deallocates the baseline version outright on the
  canary write. There is then no baseline left for the split rule to route to. Separately, one
  `SliceTargetValue` per artifact base cannot represent "baseline at 5, variant at 1" at all. Both
  are outside this fix and need their own ticket.
- **Removed `SliceTargetValue.withInstances(int, int)`.** Its only caller repo-wide was the defect,
  and its javadoc advertised the exact conflation #982 identifies ("for a producer that pins a slice
  to a fixed size — an A/B variant, a canary or a promotion"). Leaving it would have been dead
  surface recommending the bug. The single-argument `withInstances(int)` already preserves the floor,
  which is what both new writers use. `[mechanism: repo-wide grep over all *.java outside target/ — one caller, converted]`
