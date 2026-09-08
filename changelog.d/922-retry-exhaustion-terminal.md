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
