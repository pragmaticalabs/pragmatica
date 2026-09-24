### Fixed (2026-09-24 — #1488: scale-down never completed when every member hosted a slice)
- **`LeaderReconciler.selectDrainVictims` excluded every slice owner with no fallback**, so once each
  member hosted a slice instance (an autoscaler scale-up under load does this) the drain set came back
  empty on every pass. The surplus was deferred forever: 61 deferrals on the rc4 Hetzner run
  `03-scaling/Scale_down_7_-_5_under_load`, leaving 6 members where 5 were configured.
- Owning a slice now **lowers a node's victim preference instead of excluding it**. The order is:
  ephemeral non-owners, mature configured non-owners, mature ephemeral owners, then mature configured
  owners.
  [verified: `aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/membership/ntt/LeaderReconcilerTest.java`
  — `DrainVictimSelection.surplusDrain_everyMemberOwnsSlices_drainsOneNonLeaderOwner`,
  `surplusDrain_nonOwnersShortOfSurplus_fallsBackToOwners`,
  `surplusDrain_everyMemberOwnsSlices_ephemeralOwnersBeforeConfigured`,
  `surplusDrain_mixedOwnersAndNonOwners_nonOwnersPreferred`]
- **Draining an owner reduces the instance count of every slice it hosts by one.** The replacement is
  not in place before the node goes: a drained node stops accepting work and exits on its own drain
  grace, whether or not the deployment manager's eviction loop has placed a replacement. The missing
  instance is re-placed afterwards. To bound that, a new guard picks an owner only if every slice it
  hosts keeps at least its `minAvailable` ACTIVE instances on the remaining nodes. The count already
  subtracts the victims chosen earlier in the same pass, so a two-node drain cannot take two of a
  slice's three instances.
  - `minAvailable` comes from the slice target (`SliceTargetValue.effectiveMinInstances()`): the
    blueprint `minAvailable`, default `ceil(instances/2)`, and at least 1.
  - An instance that is still loading does not count toward what remains.
  - An owner the guard refuses is skipped. If nothing else can cover the surplus, the drain is
    deferred and re-evaluated, never forced.
  [verified: `LeaderReconcilerTest.DrainVictimSelection.surplusDrain_twoOwnersShareMinAvailableSlice_onlyOnePickedPerPass`,
  `surplusDrain_singleInstanceSliceOwner_neverPicked`, `surplusDrain_everyOwnerGuardedOut_defersInsteadOfDraining`,
  `surplusDrain_remainingInstanceStillLoading_doesNotCountTowardMinAvailable`. These use the real
  KV-backed `SliceOwnershipQuery` over an in-memory store; there is no multi-node run.]
- **The leader is now drained last.** It is considered only after every other member, under the same
  tier rules. It is still chosen when it is the only candidate that can cover the surplus. This is
  #1089 option B: a tie-break, not an exclusion.
  [verified: `surplusDrain_leaderAndAnotherCandidate_leaderNotChosen`, `surplusDrain_leaderSoleCandidate_leaderChosen`]
- An ephemeral owner must now pass the drain-safety grace, like a configured seed. The "owns nothing"
  reason that exempts an ephemeral non-owner does not apply to an owner. A young owner pool is
  deferred with a follow-up reconcile.
  [verified: `LeaderReconcilerTest.DrainVictimSelection.surplusDrain_youngEphemeralOwners_deferredByGrace`]
- [unverified: no multi-node or cloud run of the 7→5 scale-down-under-load scenario has been done with
  this change. The claim that scale-down now completes there is design intent.]
- [unverified: the guard sees only committed KV placements. A slice instance placed after the drain
  pass reads the KV-Store is not counted. A victim's slices are re-placed only after it leaves, so
  even while the guard holds, the drained slice's capacity is one instance lower until the
  replacement is ACTIVE.]
