### Fixed (2026-09-08 — #805: schema gate pre-filtered a stale in-memory map; best-effort outcome merge was a read-then-Put)

- **The schema activation gate and `SchemaRoutes.heldSlices` shared a predicate but not its inputs.**
  #760's first round made both call `ClusterDeploymentState.blocksSliceActivation`, which resolves
  `schemaRequired` from the KV store. The gate still reached that predicate through the node-local
  `Active.blueprints` mirror — taking the slice's owner from it *and* pre-filtering on its
  `schemaRequired` flag — while the route read the committed `SliceTargetValue` and pre-filtered on
  nothing. The mirror is rebuilt from KV notifications and therefore lags them, so a stale entry made
  the gate hold a slice the route simultaneously reported as not held. Sharing a predicate is not
  sharing a decision while its inputs disagree.
  Both call sites now resolve the owner through one new static,
  `ClusterDeploymentState.resolveSliceOwner(KVStore, Artifact)`, reading the committed
  `SliceTargetKey`/`SliceTargetValue` record; the mirror pre-filter is gone. Dropping it removes no
  check — `blocksSliceActivation` already resolves `schemaRequired` per candidate record from the same
  store, so the mirror's copy was only ever a second, divergent answer to a question already being
  asked. [verified: `SchemaActivationGateTest$StaleMirrorDivergence` — four tests planting a mirror
  entry that contradicts the committed record, covering a stale owner, a mirror-only owner, a stale
  `schemaRequired = false` over a declared `true`, and gate/route agreement; all four confirmed red on
  a revert of the production hunk alone]
- **`recordBestEffortFailureOutcome` merged the failing-slice list by read-then-Put, outside any
  compare-and-set.** The read happens when the command is built, the Put applies after consensus, so
  two BEST_EFFORT slice failures both in flight before either applies read the same base and each Put
  the other's slice id away. This is not a narrow window: `RabiaEngine` holds `pendingBatches` in a
  `ConcurrentSkipListMap` keyed by a SHA-256 content hash and every proposal site takes
  `firstEntry()`, so the surviving write is chosen by content hash, not by submission order — a coin
  flip on the happy path, needing no failed consensus round.
  `AetherValue.DeploymentOutcomeValue` now carries an `outcomeVersion` and implements `VersionFenced`
  (RFC-0018, #570), so the applier rejects any Put that is not the immediate successor of the
  committed value. Rejection alone does not preserve the id — the write is simply dropped — so the
  merge path also confirms after its own apply resolves and retries against the now-current committed
  value, bounded at 5 attempts, logging at ERROR and naming the slice if the budget is exhausted.
  [verified: `BestEffortOutcomeMergeTest` — 11 tests; `$ConcurrentFailures` stages two merges pending
  simultaneously and releases them in BOTH orders, `$ApplierFence` pins the rejection at the applier,
  `$SequentialFailures` pins the uncontended path. Both halves mutation-probed independently:
  removing `VersionFenced` turned 6 of 9 red, and removing the confirm-and-retry with the fence intact
  turned exactly the 4 `$ConcurrentFailures` red while `$ApplierFence` stayed green]
- **Guarantee, stated per operation.** A BEST_EFFORT deterministic slice failure observed by the
  leader's `ClusterDeploymentState.Active` adds its artifact id to the committed
  `DeploymentOutcomeValue.failingSlices`, or logs at ERROR that it did not. This is *not* atomicity:
  it is the applier's successor-version fence (which makes a merge built on a stale read fail rather
  than overwrite) plus a bounded read-after-apply retry on the proposer (which is what turns a
  rejected write back into a recorded one). The re-read is sound because `ClusterNode.apply`'s Promise
  resolves after the local state machine has applied the decision — `RabiaEngine.commitChanges` calls
  `stateMachine.process` before `promise.succeed`. Convergence is bounded, not guaranteed: five
  contended attempts end in a logged, operator-visible gap rather than an unbounded resubmission loop.
  [mechanism: `KVStore.staleSuccessorWrite` + `submitBestEffortFailureOutcome`'s confirm-and-retry]
- **No behaviour change for ALL_OR_NOTHING**, and it is pinned rather than assumed. Fencing the record
  makes every writer of it fenceable, including the three ALL_OR_NOTHING terminal writers, so all four
  production write sites now derive their version from the committed value via
  `Active.nextOutcomeVersion`. A writer left stamping a blind first version would be silently rejected
  the moment a record already existed. The pre-existing ALL_OR_NOTHING coverage in
  `ClusterDeploymentStateTransactionalTest` cannot speak to this — it inspects the proposed command
  list from a recording node that never applies anything, so it stays green whether the applier accepts
  the write or drops it. [verified:
  `BestEffortOutcomeMergeTest$AllOrNothingUnaffected#theTerminalOutcome_isAccepted_overAnAlreadyCommittedRecord`,
  which asserts on committed state after a real apply and was confirmed red when
  `failedOutcomeCommand` was reverted to a blind version stamp]
- **The owner path this record depends on was audited, not assumed.** `recordBestEffortFailureOutcome`
  reaches `DeploymentOutcomeKey` through `Blueprint::owner`, which traces to
  `SliceTargetValue.owningBlueprint` — the field #698 found the autoscaler and A/B writer erasing. An
  erased owner does not corrupt the record, it means no record is written at all, so the lost-update
  fix above would have been correct and irrelevant for every autoscaled or A/B-tested slice. All five
  external writers of `SliceTargetValue` were enumerated: the two #698 named are fixed by #940
  (`a75e6af42`, this branch's base) and verified to carry the owner through; the other three
  (`DeploymentManagerImpl.addSliceTargetCommand`, `RollbackManager.updateSliceTargetForRollback`,
  `SliceRoutes.applyDeployCommand`) all follow `existing.map(current -> current.withX(...))
  .or(<owner-less fallback>)`, and every `withX` helper on the record preserves `owningBlueprint`, so
  the owner-less fallback fires only for a genuinely absent record. The path is intact at this head.
  [verified: `BestEffortOutcomeMergeTest$OwnerResolutionFromTheSliceTargetRecord`, driven through the
  real `SliceTargetPutReceived` notification rather than by seeding the mirror; mutation-probed by
  erasing the owner as it enters the mirror, which turns the positive case red with "no
  deployment-outcome record was committed" while the owner-less negative case stays green]
- **The owner lookup itself now consults both sources, mirror first.** #698/#940 closed *erasure*;
  the mirror could still fail to answer for reasons unrelated to it — an artifact whose entry
  `removeNonTargetVersions` dropped, or the window after a leader failover before
  `rebuildSliceStateFromKVStoreEntries` runs. Either way the result is not a degraded record but no
  record at all. `Active.resolveOutcomeOwner` reads the mirror first and falls back to the committed
  `SliceTargetValue`.
  **The order is load-bearing, and the obvious fix is the wrong one:** resolving from the committed
  store alone regresses, because `handleAppBlueprintChange` populates the mirror in the same pass that
  only *queues* the `SliceTargetKey` Put — between the two the mirror legitimately names an owner the
  store does not yet carry, so a store-only lookup would stop recording failures for the whole deploy
  window. Consulting both strictly widens what is recorded: the worst case is attributing a failure to
  an owner about to change, never losing the record. For a ticket about lost records that is the right
  asymmetry. [verified: `BestEffortOutcomeMergeTest$OwnerResolutionFallback` — each source pinned
  alone; mutation-probed in both directions, and the two probes are orthogonal: mirror-only turns
  `whenOnlyTheCommittedRecordNamesTheOwner` red while the other stays green, and committed-only turns
  `whenOnlyTheMirrorNamesTheOwner` red — along with 6 further tests, which is the regression the
  fallback ordering exists to prevent]
- **Wire format:** the `deployment-outcome` TOML value goes from 4 fields to 5 (`outcomeVersion`
  appended). Consistent with the rc-line posture RFC-0018 O1 already records — rc releases do not
  support mixed-version co-application, and the KV serializer format already diverges between rcs.
