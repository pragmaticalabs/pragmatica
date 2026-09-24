### Fixed (2026-09-24 — #1488: scale-down never completed when every member hosted a slice)
- **`LeaderReconciler.selectDrainVictims` excluded every slice owner with no fallback**, so once each
  member hosted a slice instance (an autoscaler scale-up under load does this) the drain set came back
  empty on every pass. The surplus was deferred forever: 61 deferrals on the rc4 Hetzner run
  `03-scaling/Scale_down_7_-_5_under_load`, leaving 6 members where 5 were configured.
- Owning a slice now **lowers a node's victim preference instead of excluding it**. The order is:
  ephemeral non-owners, mature configured non-owners, mature ephemeral owners, then mature configured
  owners. The non-owner order is unchanged, so every cluster with enough non-owners to cover the
  surplus drains the same nodes as before.
  [verified: `aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/membership/ntt/LeaderReconcilerTest.java`
  — `DrainVictimSelection.surplusDrain_everyMemberOwnsSlices_drainsExactlySurplus`, `surplusDrain_nonOwnersShortOfSurplus_fallsBackToOwners`,
  `surplusDrain_everyMemberOwnsSlices_ephemeralOwnersBeforeConfigured`, `surplusDrain_mixedOwnersAndNonOwners_nonOwnersPreferred`]
- An ephemeral owner must now pass the drain-safety grace, like a configured seed. Only ephemeral
  NON-owners were ever exempt from it, and the reason for that exemption ("owns nothing") does not
  apply to an owner. A young owner pool is deferred with a follow-up reconcile. It is not drained.
  [verified: `LeaderReconcilerTest.DrainVictimSelection.surplusDrain_youngEphemeralOwners_deferredByGrace`]
- Draining an owner does not drop its slices. A drained node reports `DRAINING` and leaves the READY
  set that placement allocates from. The deployment manager's drain-eviction loop then starts a
  replacement on another allocatable node and unloads the original only after the replacement is
  ACTIVE.
  [mechanism: `ClusterDeploymentState.allocatableNodes` = core members ∩ READY reporters;
  `NodeReportedStateHolder` reports `DRAINING` once `onDrainStarted` fires (the commanded-drain
  handler in `AetherNode`); `deployReplacementForDrain` → `checkReplacementAndUnload`]
- [unverified: no multi-node or cloud run of the 7→5 scale-down-under-load scenario has been done with
  this change. The claim that scale-down now completes there is design intent.]
- [unverified: the leader is still not excluded from victim selection. It was not excluded before
  either. A leader chosen as a victim never drains (#1089). An all-owner cluster can now reach the
  leader through the owner tier, which is the path this fix opens.]
