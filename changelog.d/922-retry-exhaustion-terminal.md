### Fixed (2026-09-08 — #922: retry exhaustion on the transient deployment path never reached a terminal state)

- **A deployment failing for an intermittent reason that never cleared was redeployed forever.**
  `handleTransientFailure` retries a non-fatal failure five times with exponential backoff. When the
  budget was spent, the exhaustion arm cleared the retry counter and routed a `DeploymentFailed`
  event — but never added the artifact to `permanentlyFailed`, and that is not a resting state.
  `handleSliceFailure` issues an unload on every failure; the node's removal of the
  `NodeArtifactKey` reaches `handleSliceNodeRemoval`, which — finding the artifact still deployable —
  schedules a reconcile; and `reconcileBlueprint` is gated on `permanentlyFailed` and nothing else,
  so it redeployed the artifact with the counter restarting at 1. The result was an unbounded
  redeploy loop at roughly 1 Hz for the life of the cluster: no terminal state, no rollback, no
  outcome record
  [mechanism: `handleSliceFailure` -> `issueUnloadCommand` -> `handleSliceNodeRemoval` ->
  `reconcile` -> `reconcileBlueprint`, with no arm that adds to `permanentlyFailed`].

- **Exhaustion is now terminal, but only when a blueprint APPLY is still outstanding for the
  artifact.** `handleRetryBudgetExhausted` consults `deploymentApplyOutstanding`, which reads two
  durable KV entries: the artifact's owning blueprint is outstanding when `AppBlueprintKey(id)` is
  present and no `DeploymentOutcomeKey(id)` record exists. Outstanding means the apply failed, so it
  settles into the same terminal a deterministic failure reaches — `permanentlyFailed`,
  `DeploymentFailed`, and `ALL_OR_NOTHING` rolls the owning blueprint back while `BEST_EFFORT`
  records a FAILED outcome. Not outstanding means the blueprint already reached its terminal and this
  is reconciliation of a running workload, which converges and is never condemned
  [verified: `RetryExhaustionTerminalTest#intermittentFailureThatNeverSettles_reachesTerminalRollback_withinTheRetryBudget`
  asserts the terminal arrives on the sixth reported failure and NOT before, and
  `#intermittentFailureThatNeverSettles_recordsAnExplicitFailedOutcome` asserts the record; both go
  red under mutation B below].

- **`ALL_OR_NOTHING` is a promise about the APPLY, and that is what changed.** Three earlier attempts
  asked "has this artifact ever been healthy?" and each was defeated by a different store that could
  not answer: present-tense liveness, which `handleSliceFailure` erases before either branch runs; an
  in-memory `everActiveArtifacts`, rebuilt empty on a new leader; and the blueprint's durable
  SUCCEEDED record reached through `SliceTargetValue.owningBlueprint`, a pointer
  `ControlLoopContext.applyScaling` and `AbTestManager.targetPreservingOverrides` overwrite with
  `Option.none()` (#698). Every one of those failures was SILENT, and silence meant condemn — the
  irreversible direction. Asking about the operation instead removes the class: attribution comes
  from `ExpandedBlueprint.loadOrder()`, which no other subsystem writes, so an autoscaled or
  A/B-tested slice is no longer one leader failover plus one transient away from a permanent
  cluster-wide condemnation
  [verified: `RetryExhaustionApplyOutstandingTest#autoscaledSliceWithErasedOwner_afterFailover_isNotCondemned`
  seeds a SUCCEEDED record with the owner erased and asserts the artifact is not condemned; it goes
  red under mutation A, which is what shows the assertion can fail].

- **Every failure mode of the new predicate falls on the reversible side.** The settle needs both
  halves — blueprint present AND outcome absent — so a torn or unattributable read answers "not
  outstanding" and re-drives rather than condemning. A `registerOnly` blueprint, which is stored and
  deliberately never deployed, is excluded for the same reason: it would otherwise look permanently
  mid-apply
  [verified: `#aTornBatch_blueprintVisibleBeforeTheStaleOutcomeIsRemoved_doesNotCondemn`,
  `#anArtifactNoBlueprintDeclares_isNotCondemned`, `#registerOnlyBlueprint_isNeverOutstanding`].

- **The durability the verdict rests on is #759's, and it is pinned rather than assumed.**
  `BlueprintService` writes `Put(AppBlueprintKey)` and `Remove(DeploymentOutcomeKey)` into ONE
  `ClusterNode.apply` batch at all three of its write paths, and one `apply` becomes exactly one
  `Batch` (`RabiaEngine.prepareBatch`) decided once by consensus and handed to `KVStore.process` as a
  unit — so no replica applies one without the other and no crash can split them
  [verified: `BlueprintServiceTest.RedeployAfterPriorFailureTests#publish_writesBlueprintAndClearsStaleOutcome_inOneConsensusBatch`
  and `#delete_removesBlueprintAndItsOutcome_inOneConsensusBatch` assert the two commands share a
  batch, not merely that the end state is right; splitting them into two `apply` calls reddens the
  first and leaves the pre-existing `#statusRoute_publishAfterPriorFailure_outcomeCleared_reportsInProgressNotFailed`
  GREEN, which is precisely the regression the older test cannot see].

- **What the batch does NOT buy, stated because #759's own comment overstates it.** That comment says
  the pair lands "atomically, so at any instant `id` is either in flight with no outcome, or terminal
  with exactly one". `KVStore.process` applies a batch's commands one at a time into a
  `ConcurrentHashMap` under no cross-command lock, so a reader on another thread CAN observe the map
  between the two writes. The pairing is atomic per consensus DECISION, not per instant. It does not
  matter here, and by construction rather than luck: both torn states answer "not outstanding"
  [mechanism: `KVStore.process` -> `processCommand` per command, storage is a `ConcurrentHashMap`
  with no batch-scoped lock; only blueprint-present-AND-outcome-absent returns true].

- **A comment that was load-bearing and false is deleted rather than corrected.** The removed
  `blueprintDeploymentSucceeded` carried: *"Nothing removes a `DeploymentOutcomeKey` entry when a
  blueprint id is re-applied"*, and on that basis suppressed its own durable leg while the blueprint
  was in flight, marked *"load-bearing rather than an optimisation"*. It was false at that head:
  `BlueprintService` removes exactly that entry, in the same consensus batch as the blueprint write,
  at `buildAllCommands`, `storeBlueprintWithKey` and `removeFromStore`. Recorded here because a
  silently corrected record loses the fact that it was ever wrong, and this one made a second wrong
  premise (`Blueprint::owner`, contradicted by open ticket #698) invisible underneath it.

- **An artifact can be declared by more than one blueprint, and the settle needs EVERY one of them
  to be non-terminal.** `hasConflictingOwnership` rejects a blueprint whose artifact is already owned
  by one with a DIFFERENT base, but blueprints sharing a base and differing only in version are the
  upgrade path, and a slice unchanged across an upgrade appears in both. The first version of this
  code resolved attribution with `owners.getFirst()` over a `HashMap` scan, so when one declaring
  blueprint had SUCCEEDED and another was mid-apply, whether a running slice was condemned depended
  on iteration order. `deploymentApplyOutstanding` now requires every declaring blueprint to lack a
  terminal record, which gives the succeeded one a veto and removes the nondeterminism
  [verified: `RetryExhaustionApplyOutstandingTest#aSliceDeclaredByBothASucceededAndAnInFlightBlueprint_isNotCondemned`;
  weakening the veto from `allMatch` to `anyMatch` reddens that test and, across all 19 tests in the
  three exhaustion classes, ONLY that test].

- **`registerOnly` is a third conjunct, and here is why it is not a quiet qualifier.** A
  `registerOnly` blueprint is stored in KV so a strategy-based deploy can locate the upgrade target,
  while the `SliceTargetValue` Put that would activate it is suppressed — so it has no outcome record
  and never will. Read without the exclusion it looks permanently mid-apply, and it is reachable
  precisely in the case above: publishing v2 as register-only means two blueprints declare the same
  unchanged slice, one of them never terminal. The exclusion is what keeps attribution answerable
  [verified: `#registerOnlyBlueprint_isNeverOutstanding`; deleting the `value.registerOnly()` guard
  reddens that test and only that test].

- **The test fixture could not previously hold the state this design reads, and that is disclosed
  rather than assumed away.** The predicate is `AppBlueprintKey` present AND `DeploymentOutcomeKey`
  absent. The inherited `RecordingClusterNode` only RECORDED what the leader submitted — `apply`
  never reached the `KVStore` — so the second conjunct was unconditionally true and the predicate
  degenerated to "blueprint present". Every "does not settle" assertion would have passed without
  exercising the mechanism. The fixture now applies each submitted batch into the same store the FSM
  reads, as consensus does for every replica including the leader's own
  [verified: `#instrumentCheck_theFixtureHoldsBothOutcomeStates_soTheAssertionsAreNotVacuous` asserts
  the record absent, drives the production path (`trackBlueprintSliceActive` -> `recordSucceededOutcome`
  -> `submitBatch` -> `cluster.apply`), then reads SUCCEEDED back through the same
  `DeploymentOutcomeKey` the predicate consults. Reverting the fixture to record-only — deleting
  `kvStore.process(kvStore.createBatch(batch))` — reddens 3 of the 12 tests in that class: the
  instrument check, `#control_applyAlreadySucceeded_doesNotSettle` and
  `#partiallyAppliedBlueprint_condemnsTheSliceThatCameUp_andRecordsIt`. The other 9 stay green, so
  the instrument check is load-bearing for exactly those three rather than decorative].

- **One parked assertion is deliberately REVERSED, and the reason is recorded so it does not read as
  lost coverage.** Round 3b's parked
  `aSliceThatCameUpUnderAPartiallyDeployedBlueprint_isNotCondemned`
  (`oss/internal/park-924-round3b-2026-09-08.patch`) asserted that a slice which came up under a
  never-completed `ALL_OR_NOTHING` blueprint must not be condemned, treating it as a workload owed
  convergence. It is replaced by `#partiallyAppliedBlueprint_condemnsTheSliceThatCameUp_andRecordsIt`,
  which asserts the opposite. The blueprint never applied, so the operator was promised
  all-or-nothing and got neither; the honest terminal is a rollback WITH a record, not indefinite
  re-driving of half a deployment. The #924 round-4 review classified this shape as class-2 harm
  because the old design condemned it silently and wrote nothing — the objection was to the silence,
  and the record is what removes it. The parked patch's fixture-fidelity half is carried forward and
  is load-bearing (previous bullet).

- **Mutation battery.** Applied one at a time from a committed base, tree restored via
  `git checkout --` and verified pristine after each phase; controls ran first and were green.
  A (`deploymentApplyOutstanding` body -> always true) reddens 6 of 10
  `RetryExhaustionApplyOutstandingTest` plus 1 `RetryExhaustionTerminalTest` — every
  "must not condemn" case. B (-> always false) reddens the other 4 plus both #922 acceptance tests in
  `RetryExhaustionTerminalTest` — every "must settle" case. The pair is what shows the predicate is
  load-bearing in both directions rather than a one-way guard. C (registerOnly exclusion deleted)
  reddens exactly `#registerOnlyBlueprint_isNeverOutstanding`. D (attribution switched back to
  `Blueprint::owner`) reddens `#aNewVersionUnderItsOwnBlueprint_settles_whileTheOlderVersionSucceeded`
  and `#newLeader_withApplyStillOutstanding_stillSettles`, and — reported because it is not the
  result one would predict — does NOT redden the erased-owner test, since under that mutation
  attribution simply fails and failing attribution now declines to settle. That is the safe
  direction, and it is why the erased-owner assertion is validated by A instead.

- **Scope, stated so it is not read as more than it is.** `handleDeterministicFailure` is untouched
  and keeps its cluster-wide `permanentlyFailed` verdict. No multi-node or failure-injection run
  backs any claim above; every pin is an FSM-harness test driving the real `ClusterDeploymentState`
  and `BlueprintService` over an in-process `KVStore`.
