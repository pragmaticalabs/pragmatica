### Fixed (2026-09-08 — #698: `SliceTargetValue.owningBlueprint` erased at reconstruction by the autoscaler and the A/B writer)

_Line numbers below are derived at `origin/release-1.0.0-rc4` @ `78960de46`, not at the branch head;
this branch adds +12 lines to `ClusterDeploymentState.java`._

- **Two producers rebuilt a `SliceTargetValue` from a subset of its fields and hardcoded
  `Option.none()` for the owner**, while carefully carrying every other field forward.
  `ControlLoopContext.applyScaling` (`aether-control`) did it on every autoscale decision;
  `AbTestManager.targetPreservingOverrides` (`aether-invoke`) did it on every A/B lifecycle write,
  in a method whose name and javadoc both promise preservation. Both now carry the owner
  [verified: mutation-probed. Replacing `currentBlueprint.owningBlueprint()` with `Option.none()` in
  `ControlLoopContext.applyScaling` turns 2 of 5 red in `ControlLoopOwnerPreservationTest`
  (`applyScaling_ownedSlice_carriesOwnerOntoTheScaledValue`: "expected: Some(org.example:owning-app:1.0.0)
  but was: None()"); restoring returns 5/5 green. Replacing
  `current.flatMap(SliceTargetValue::owningBlueprint)` with `Option.none()` in `AbTestManager` turns
  1 of 2 red in `SliceTargetOverridePreservationTest`
  (`AbTestPromotePreservesOverrides#concludeTest_preserves_autoscaler_overrides_on_promoted_version`);
  restoring returns 2/2 green].
- **The loss was only observable at the consumer, one failover later.** `ClusterDeploymentState`
  resolves a slice's `schemaRequired` through its owner (`restoreSliceTarget`,
  `handleSliceTargetChange`); an absent owner takes the historical unowned default `true`. A slice
  deployed with `schema_required = false` therefore came back after an autoscale plus a leader
  restore asserting schema *was* required — #555's original symptom, moved one layer upstream. The
  end-to-end path is now pinned by feeding **the exact value the autoscaler emits** into a real
  `ClusterDeploymentContext` restore
  [verified: mutation-probed. `#autoscalerOutput_restoredAfterFailover_keepsSchemaRequiredFalse` goes
  red under BOTH producer-side mutations with "expected: false but was: true" — literally #698's
  symptom reproduced — and green on restore].
  **A decorative test was found and repaired rather than documented.** As first written, the
  opposite-polarity twin `#autoscalerOutput_restoredAfterFailover_resolvesSchemaRequiredTrue` did
  **not** redden under any of the three mutations. It could not: with the owner erased the resolution
  falls through to the historical default `true` — the very value that test expects — so it passed
  *for the wrong reason* while looking like coverage of the defect it could not see. It now also
  asserts the restored blueprint's **owner**, which the erased path predicts as `None()` and the
  correct path as `Some(OWNER)` — mutually exclusive with the default, hence discriminating
  [verified: adding that assertion moves the measured failure count from 2 of 5 to **3 of 5** under
  both `applyScaling-owner` and `feeder-owner`; Maven's own total line reads
  `Tests run: 5, Failures: 3` and the surefire XML testcase count agrees. Unmutated baseline and
  post-restore run are both 5 tests / 0 failures, so the greens are real runs, not empty selectors].
  Two tests remain deliberately unpinned by these probes and are labelled as such:
  `#applyScaling_unownedSlice_leavesOwnerAbsent` (guards against a fabricated owner) and
  `#applyScaling_ownedSlice_carriesOperatorOverridesOntoTheScaledValue` (guards the override fields).
  **3 of 5 are owner-erasure detectors; the other 2 are companion guards, and the distinction is
  recorded in the test file itself so a future reader cannot miscount it.**
- **Schema-failure reports also under-counted.** `handleSchemaFailed` lists `slicesOwnedBy(owner)`;
  a slice whose owner had been dropped was invisible to it, so an operator was told fewer slices
  were blocked than actually were. No code change was needed for this beyond the producer fix — the
  report reads the same owner field
  [mechanism: `ClusterDeploymentState.handleSchemaFailed` → `slicesOwnedBy` → `isOwnedBy`, which
  filters on `blueprint.owner()`; with the owner preserved the slice is in the set. Not separately
  asserted — no test in this change drives `handleSchemaFailed`].
- **`ControlLoopContext` holds no KV-store handle, so the fix is a model field, not a
  read-modify-write.** `ClusterController.Blueprint` — the autoscaler's in-memory model — gained
  `owningBlueprint`, threaded from the `SliceTargetValue` at `ControlLoop.onSliceTargetPut`, the
  map's sole feeder. **The rejected alternative was the read-then-`Put` the ticket suggested**:
  it would have added a store read on the autoscaler's hot path, which is exactly the shape open
  tickets #906 (`WorkerDeploymentManager` lost update) and #805 (`bestEffortFailureCommand`) flag.
  This fix performs **no new read at either site** — `applyScaling` already issued an unconditional
  blind `Put` built from the in-memory mirror and still does, and `AbTestManager` already re-read
  the current value for its override fields, so #698 widens that existing read's field set by one
  and changes no control flow
  [mechanism: `ControlLoopContext` has no `KVStore` field — only `ClusterNode`, the command channel;
  `blueprints.put` has exactly one call site (`putBlueprint`), whose only production caller is
  `ControlLoop.onSliceTargetPut`]
  [verified: that feeder is a real enforcement point, not incidental plumbing — a third probe
  replacing `value.owningBlueprint()` with `Option.<BlueprintId>none()` at
  `ControlLoop.onSliceTargetPut` turns the same 2 of 5 red and restores to 5/5 green. The owner
  invariant therefore has three enforcement sites (two producers plus the feeder) and each was
  mutated independently, rather than one instance being taken as proof of the set].
- **Stated plainly, not claimed away: neither write is lost-update-safe, and this fix does not make
  it so.** `applyScaling` emits an unconditional `KVCommand.Put`, so a concurrent writer's update to
  the same `SliceTargetKey` can still be clobbered. That exposure is pre-existing and unchanged in
  shape and magnitude by #698; it is #906/#805's subject, not this ticket's, and closing #698 must
  not be read as evidence the path is race-free.
- **`putBlueprint` deliberately has no owner-less overload at its full arity.** The 6-argument form
  was replaced rather than kept alongside the new 7-argument one, so a caller that has no owner must
  say `Option.none()` explicitly. Inheriting that default silently is precisely what erased the
  owner on every autoscale event for eleven days.
- **#555's consumer-side resolution is KEPT, and is not a workaround left beside its own fixed root
  cause.** `SliceTargetValue` carries no `schemaRequired` field at all, so resolving through the
  owner is the only route back to the deployed value; removing it would reintroduce #555 wholesale.
  What #698 changed is that resolution's *input*, not its necessity — with the producers fixed,
  `.or(true)` now means "genuinely unowned slice" instead of "owner erased in transit". Recorded at
  both call sites in `ClusterDeploymentState` so the next reader cannot mistake it for redundant,
  and `SchemaRequiredResolutionTest`'s class javadoc — which documented this defect as *open* — is
  corrected to record it as closed.
- **Every `SliceTargetValue` construction site was enumerated, not sampled.** Seven in main sources
  outside `AetherValue`'s own factories: the two defects above, plus `KVStoreSerializer` (parses the
  owner off the wire), `ClusterDeploymentState.handleAppBlueprintChange` (deploy time, passes the
  owning blueprint), and three `existing.map(...).or(fresh)` fallbacks — `SliceRoutes`,
  `RollbackManager`, `DeploymentManagerImpl` — whose mapped branch preserves the owner via
  `withInstances`/`withVersion` and whose fallback branch fires only when no value existed, so its
  `none()` is a genuinely absent owner rather than an erased one. All five non-defective sites were
  read and left unchanged
  [mechanism: `grep -rn "new SliceTargetValue(\|sliceTargetValue(" --include='*.java'` over
  `/src/main/`; positive control — the same pattern recovers all four sites #698 names by hand
  (`ControlLoopContext`, `AbTestManager`, and the two correct reference sites `SliceRoutes`,
  `RollbackManager`), 4 of 4].

**Not fixed here**

- **Fixing #698 does NOT unblock PR #924.** #924's blocking finding has two independent doors, and
  door 2 — a partially deployed blueprint across a leader failover — needs no autoscaler and
  survives this fix untouched. #933 was filed for this same defect and is closed as a duplicate of
  #698. There is no gating relationship between this change and #924.
- **`SliceTargetValue.placement` is erased by the SAME two producers — a third instance of this
  bug class, found by re-auditing the construction sites for field *values* rather than field
  presence. Not fixed, no ticket yet.** Every `sliceTargetValue(...)` factory overload except the
  one taking an explicit `placement` hardcodes `DEFAULT_PLACEMENT` (`"CORE_ONLY"`), and both
  `applyScaling` and `targetPreservingOverrides` use overloads that do. A non-default placement is
  operator-settable through the management API (`POST /api/slices/scale`, `ScaleRequest.placement`)
  and is preserved correctly on that path by `applyScaleToExisting` → `withPlacement`. It is then
  reset to `CORE_ONLY` by the first autoscale or A/B write, and the reset value is acted on:
  `handleSliceTargetChange` calls `issueAllocationCommandsWithPlacement(newArtifact,
  desiredInstances, value.effectivePlacement())`, so the slice is re-allocated under the default
  placement. `ControlLoopContext` cannot fix this the way it fixes the owner —
  `ClusterController.Blueprint` has no `placement` field either, so it needs the same model-field
  treatment or a deliberate ruling that placement is not autoscaler-preserved
  [mechanism: `AetherValue.SliceTargetValue` factories at :95/:107/:119/:134/:169 all pass
  `DEFAULT_PLACEMENT`; producers at `ControlLoopContext.applyScaling` and
  `AbTestManager.targetPreservingOverrides` use the 7-arg overload (:169); consumer at
  `ClusterDeploymentState:1303`. Not executed — no test in this change drives a non-default
  placement through an autoscale].
- **Separate defect, filed as #936, deliberately NOT fixed here. #698's fix does NOT fix #936.**
  They are independent bugs in the *same constructor call* — `minInstances` is argument position 3,
  `owningBlueprint` position 4 — so a reader who sees the owner preserved must not assume the whole
  call was audited.
  `applyScaling` writes `newInstances` as **both** `targetInstances` and `minInstances`, while the
  in-memory model it updates one line earlier keeps `currentBlueprint.minInstances()`. The Put then
  feeds `onSliceTargetPut`, which overwrites the model with `minInstances = newInstances`. So a
  slice's floor ratchets up to its current count on the first autoscale, and
  `computeRequestedInstances`' `Math.max(minInstances, instances - reduceBy)` can never return
  anything below it — **the autoscaler cannot scale down after its first scale-up**. Left untouched
  under #698's scope discipline; it is a different field, a different failure, and needs its own
  ticket and its own test. It also **fails silently**: `prepareChangeToBlueprint` returns
  `Option.none()` when `newInstances == currentBlueprint.instances()`, so a slice pinned at a
  ratcheted floor emits no command, no event and no log — indistinguishable from an autoscaler with
  nothing to do
  [mechanism: `ControlLoopContext.applyScaling` 7-arg `sliceTargetValue(version, newInstances,
  newInstances, ...)` vs. `putBlueprint(artifact, newInstances, currentBlueprint.minInstances(),
  ...)` immediately above it; `AetherValue.SliceTargetValue.effectiveMinInstances()` returns
  `Math.max(1, minInstances)`. Searched open issues for `minInstances` and `applyScaling` via
  `oss/internal/related-tickets.sh` before filing — only #698 and the generic tech-debt umbrella
  #175 hit].
