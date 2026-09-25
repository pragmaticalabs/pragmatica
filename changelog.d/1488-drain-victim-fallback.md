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
  hosts keeps at least its `minAvailable` ACTIVE instances on the remaining nodes.
  - "Remaining" means the membership FSM's counted members (MEMBER or SUSPECT, workers included),
    minus the victims chosen earlier in the same pass, minus the candidate. So a two-node drain cannot
    take two of a slice's three instances in one pass.
  - A drained node's `NodeArtifact` entries survive until its lifecycle reaches DECOMMISSIONED, so the
    KV-Store alone over-counts. Placements on a node that is not a member are ignored. So are
    placements on an earlier pass's victim: every reconciler DRAIN moves its target to DEPARTING
    through the CTM drain sink, and DEPARTING does not count. Without this, a re-evaluation pass
    counted victim 1's stale ACTIVE entry and took the second sharer.
    [verified: `LeaderReconcilerTest.DrainVictimSelection.surplusDrain_ghostPlacementOnNonMember_doesNotCountAsRemaining`,
    `surplusDrain_earlierPassVictimStillInKv_doesNotCountAsRemaining`]
    [unverified: the second test replays the DEPARTING edge itself (`membershipFsm.onDrainRequested`),
    because the fixture's CTM only records the drain. The production route
    `AetherNode.requestDrainThroughFsm` is read, not exercised by that test.]
  - `minAvailable` comes from the slice target (`SliceTargetValue.effectiveMinInstances()`): the
    blueprint `minAvailable`, default `ceil(instances/2)`, and at least 1.
  - An instance that is still loading does not count toward what remains.
  - An owner the guard refuses is skipped. If nothing else can cover the surplus, the drain is
    deferred and re-evaluated, never forced. The deferral WARN names each refused owner, the artifact
    that held it back, the ACTIVE instances that artifact would keep on the remaining nodes, and its
    `minAvailable` (`minAvailableRefusals=[owner=…, artifact=…, remainingActive=…, minAvailable=…]`).
    [verified: `LeaderReconcilerTest.DrainVictimSelection.surplusDrain_ownerRefused_deferralWarnNamesOwnerArtifactRemainingAndMinAvailable`]
  - `AetherNode` wires the KV-backed guard into the leader reconciler. [verified:
    `aether/node/src/test/java/org/pragmatica/aether/node/SliceDrainGuardWiringBootTest.java` —
    `bootedNode_wiresKvBackedSliceDrainGuard_intoItsLeaderReconciler` boots a single node and applies
    the guard it holds, read by reflection. It does not drive a drain pass: a single node never becomes leader.]
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
- Limitation: when a slice's `minAvailable` equals its instance count, no owner of that slice is ever
  drained, and the surplus stays deferred. That is what CLI/REST deploy, `addSliceTargetCommand`, A/B
  tests and rollback write today. #1497 will change those writers; until then the deferral WARN names
  the slice and its `minAvailable`, and an operator can lower `minAvailable` to let the drain proceed.
- Limitation: instances are counted per exact artifact version, while `minAvailable` is set per
  artifact base. During a rolling update the old and the new version are each compared with the full
  `minAvailable`. An owner can then be refused even when both versions together would keep the slice
  available. This errs toward deferring the drain. The owner has not ruled on counting per base.
- [unverified: a victim counts as departing only on the leader that issued its DRAIN. The DEPARTING
  edge is applied to the issuing leader's own membership FSM. After a leader change, the new leader
  counts a still-running victim's instances until its membership stops counting that node.]
