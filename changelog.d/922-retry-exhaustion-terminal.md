### Fixed (2026-09-07 — #922: retry exhaustion on the transient deployment path never reached a terminal state)

- **A deployment failing for an intermittent reason that never cleared was redeployed forever.**
  `handleTransientFailure` retries a non-fatal failure five times with exponential backoff. When the
  budget was spent, `logMaxRetriesExceeded` cleared the retry counter and routed a `DeploymentFailed`
  event — but never added the artifact to `permanentlyFailed`, and that is not a resting state.
  `handleSliceFailure` issues an unload on every failure; the node's removal of the
  `NodeArtifactKey` reaches `handleSliceNodeRemoval`, which — finding the artifact still deployable —
  schedules a reconcile; and `reconcileBlueprint` is gated on `permanentlyFailed` and nothing else,
  so it redeployed the artifact with the counter restarting at 1. The result was an unbounded
  redeploy loop at roughly 1 Hz for the life of the cluster: no terminal state, no rollback, no
  outcome record, and consensus round-trips forever
  [mechanism: `handleSliceFailure` -> `issueUnloadCommand` -> `deleteSliceNodeKey` ->
  `handleSliceNodeRemoval` -> `reconcile` -> `reconcileBlueprint`, with no arm that adds to
  `permanentlyFailed`].
- **Exhaustion is now terminal, and it is the SAME terminal a deterministic failure reaches.** The
  tail of `handleDeterministicFailure` — mark `permanentlyFailed`, route `DeploymentFailed`, honour
  the declared atomicity — is extracted as `settleAsPermanentlyFailed`, and both failure branches
  converge on it. `ALL_OR_NOTHING` rolls the owning blueprint back; `BEST_EFFORT` records a FAILED
  outcome. The method was renamed from `logMaxRetriesExceeded` to `handleRetryBudgetExhausted`
  because it no longer merely logs
  [verified: `RetryExhaustionTerminalTest#intermittentFailureThatNeverSettles_reachesTerminalRollback_withinTheRetryBudget`
  asserts the terminal arrives on the sixth reported failure and NOT before, and that a reconcile
  afterwards does not re-drive the artifact; reverting only that production hunk fails it and
  `#intermittentFailureThatNeverSettles_recordsAnExplicitFailedOutcome`, and only those two].
- **The discriminator is PAST tense: has this artifact EVER reached ACTIVE.** `settleAsPermanentlyFailed`
  came from the DETERMINISTIC branch, where concluding cluster-wide from one node is sound — a
  deterministic failure is node-independent. That inference does not hold on the transient branch, and
  `retryCounters` is keyed per artifact AND node while `permanentlyFailed` is a cluster-wide
  `Set<Artifact>`. The first attempt at the fix asked the PRESENT-tense question ("is an instance
  ACTIVE right now?") and that was not enough: `handleSliceFailure` removes the failing key from
  `sliceStates` before either branch runs, so a present-tense read necessarily answers "no" once the
  transient has reached every instance, and it can never answer "yes" for a slice with
  `instances = 1`, which has no sibling to vote for it. Both of those are previously-healthy workloads,
  both self-healed before #922, and both were being condemned cluster-wide and permanently.
  `handleRetryBudgetExhausted` now settles only when `everReachedActive` is false: a deployment
  **attempt** that has produced nothing is abandoned, while a workload that **was up and fell over**
  keeps being reconciled toward its desired instance count
  [verified: `RetryExhaustionEverActiveTest#clusterWideTransient_onPreviouslyHealthyDeployment_doesNotSettle`
  and `#singleInstanceSlice_transientOnItsOnlyNode_doesNotSettle` assert on `Active.permanentlyFailed()`
  directly, and the round-2 verification probe ran the SAME two scenarios red at the previous
  head; `#control_neverActiveAnywhere_doesSettlePermanentlyFailed`
  is the opposite-polarity control and still settles, so the pins are not passing vacuously].
- **`everReachedActive` is a disjunction of three legs, and each is pinned in isolation.** No single
  evidence source covers every lifetime, so any evidence of a past ACTIVE declines the settle: an
  instance ACTIVE right now (`hasActiveInstanceElsewhere`, rebuilt from durable `NodeArtifactKey`
  entries on a new leader); `everActiveArtifacts`, which this leader watched reach ACTIVE; and the
  owning blueprint's durable `DeploymentOutcomeValue.SUCCEEDED` record, which is what makes the
  question survive failover rather than being in-memory bookkeeping. `inFlightBlueprints` remains the
  cheaper test and the wrong one — `newActive()` builds it empty, so gating on its ABSENCE would
  silently stop settling, reopening this ticket, for any deployment whose leader changed mid-flight
  [verified: each leg has a test that fails when only that leg is disabled — mutation A
  (`everActiveArtifacts.contains(artifact)` -> a non-constant always-false expression) reddens
  `#clusterWideTransient_...` and `#singleInstanceSlice_...` and nothing else; mutation B
  (`status == SUCCEEDED` -> `status == FAILED`) reddens `#durableSucceededOutcome_isEnoughOnANewLeaderWithNoInMemoryEvidence`
  and nothing else; mutation F (same treatment of `hasActiveInstanceElsewhere(artifact)`) reddens
  `#aRestoredActiveInstance_isEnoughOnALeaderThatNeverWatchedItStart` and nothing else. 13 tests in
  each phase, applied from a pristine copy, restored sha256-identical].
- **The settle is refused while core membership is unresolved.** The present-tense leg diffs
  KV-derived slice state against `activeNodes()`, and during the boot window that supplier yields
  `MembershipFsm.MEMBERSHIP_NOT_WIRED` — an empty set distinguished only by reference identity — so
  every node fails `contains` and every artifact looks abandoned; membership churn narrows the same
  read without a boot window. `coreMembershipResolved()`'s own contract requires this consultation
  and `StaleEntryCleaner` honours it at four sites. Refusing to settle costs a re-drive that the next
  exhaustion re-decides; settling on an unresolved read is irreversible
  [verified: `RetryExhaustionEverActiveTest#unresolvedCoreMembership_doesNotSettle` drives inputs
  IDENTICAL to `#control_neverActiveAnywhere_doesSettlePermanentlyFailed` apart from the member set,
  with opposite expectations; mutation C (replacing the `!coreMembershipResolved()` condition with a
  non-constant always-false expression) reddens exactly that test and nothing else].
- **"Ever reached ACTIVE" is scoped to the deployment it describes, or it becomes this ticket's own
  livelock.** Two boundaries, both load-bearing. `everActiveArtifacts` is forgotten in
  `issueDeallocationCommands` — the seam every path that takes an artifact OUT of the cluster's
  desired state funnels through — so a LATER, independent deployment of the same coordinate that never
  comes up still settles. And the durable leg is suppressed while the owning blueprint is in flight,
  because nothing removes a `DeploymentOutcomeKey` entry when a blueprint id is re-applied, so a
  re-used id whose new load order carries a brand-new slice would otherwise hand that slice the
  previous attempt's success. The boundary is deliberately REMOVAL, not a re-apply of the same
  blueprint: clearing on re-apply would put a healthy running workload one operator re-apply plus one
  cluster-wide transient away from the silent condemnation this change exists to prevent
  [verified: `#aLaterDeploymentOfARemovedCoordinate_stillSettles` and
  `#aDurableSucceededOutcome_doesNotVoteForTheAttemptStillInFlight` assert that the artifact DOES
  settle; mutations D (deleting the `everActiveArtifacts.remove(artifact)` statement) and E (deleting
  the in-flight `.filter`) redden exactly one of them each and nothing else].
- **The regression this replaced was worse than the livelock, and that is why it is called out
  separately.** The livelock was noisy and kept trying. Settling unconditionally is silent and
  terminal: a fully-deployed blueprint has already left `inFlightBlueprints`, so
  `rollbackBlueprintForArtifact` matches nothing, **no FAILED outcome is written at all**, and the
  artifact's last recorded outcome still reads SUCCEEDED while reconcile quietly refuses to replace
  the lost instance. An operator would see a healthy-looking record and a slice that never came back.
  The pins therefore assert the RECOVERY, not the absence of a rollback, because "no rollback
  happened" is equally true of a cluster that has silently stopped doing anything
  [mechanism: `trackBlueprintSliceActive` removes the blueprint from `inFlightBlueprints` before
  `rollbackBlueprintForArtifact` could match it, and `recordBestEffortFailureOutcome` — the only other
  writer of a FAILED record on this path — is the `BEST_EFFORT` branch alone].
- **The exposure was wider than it looks, because a blueprint retires on its FIRST active instance.**
  `InFlightBlueprint` builds `pendingSlices` with one entry per slice **artifact**, not per instance,
  so `trackBlueprintSliceActive` removes the blueprint from `inFlightBlueprints` and writes a
  SUCCEEDED outcome as soon as **one** instance of each slice reaches ACTIVE — whatever the desired
  instance count. A corollary worth knowing on its own: a SUCCEEDED deployment outcome attests that
  every slice started *somewhere*, **not** that the deployment reached its desired instance count
  [mechanism: `InFlightBlueprint.inFlightBlueprint` fills `pendingSlices` from
  `expanded.loadOrder()`, one entry per artifact; `trackBlueprintSliceActive` removes on
  `pendingSlices().remove(artifact)` and calls `recordSucceededOutcome` the moment that set empties].
- **An operator diagnosing an exhausted retry budget is no longer told the failure was
  deterministic.** `rollbackBlueprintForArtifact` logged "ALL_OR_NOTHING: Deterministic failure of {}
  triggers rollback of blueprint {}" — true at base, where its only caller was reached from
  `handleDeterministicFailure` alone. Routing the intermittent branch into the same terminal made that
  line contradict the `Intermittent` cause the operator is actually looking at. It now names the
  terminal and the cause instead of the branch; the branch is already named by the log line that
  immediately precedes it on either path
  [mechanism: `settleAsPermanentlyFailed` is the single caller of `rollbackBlueprintForArtifact`, and
  since this change it is reached from both `handleDeterministicFailure` and
  `handleRetryBudgetExhausted`, so no message on that path can name one branch truthfully].
- **The operator can see that the deployment did not apply.** The rollback's consensus batch carries
  a `DeploymentOutcomeValue.failed` record naming the failing slice, in the same batch as the
  blueprint removal, so the removal and the record land atomically
  [verified: `RetryExhaustionTerminalTest#intermittentFailureThatNeverSettles_recordsAnExplicitFailedOutcome`].
- **The retry budget, since the guarantee depends on it being finite and knowable.**
  `ClusterDeploymentState.Active.MAX_RETRIES` = 5 with exponential backoff of 1, 2, 4, 8, 16 s,
  jittered — about 31 s on one node. Both it and `MAX_RETRY_DELAY_SECONDS` = 30 are private
  compile-time constants: finite and knowable, but **not operator-configurable**. The 30 s cap
  **never binds at the current budget** — `Math.min(1L << (retryCount - 1), 30)` with `retryCount`
  never exceeding 5 tops out at 16 s — so it guards a larger budget rather than shaping this one. And
  the bound is **per artifact and node**, not cluster-wide: the sixth reported failure on any ONE node
  spends that node's budget, but with placement rotating across N allocatable nodes the cluster-wide
  total can reach N x (`MAX_RETRIES` + 1) reported failures, and the bound holds only while the
  allocatable node set is stable. Recovery action: an artifact that settled stays undeployable until a
  redeploy of its blueprint, which clears `permanentlyFailed` on apply
  [mechanism: `retryCounters` is keyed on `sliceKey.asString()`, rendering as
  `slices/<nodeId>/<artifact>`; `permanentlyFailed` is keyed on artifact alone and is cleared at
  exactly one site, the blueprint-apply loop].
- **What this deliberately does not close — three items, all OPEN.** (1) **No operator knob for the
  budget.** ~31 s is a short window in which to call a condition permanent, and for a blueprint of two
  or more slices one slice that never comes up still tears down its healthy siblings via
  `unloadBlueprintSlices` on that same budget. Configurable retries touch ~20 construction sites; this
  is a deferral, not a refutation. (2) **The durable leg has two gaps of its own.** The SUCCEEDED
  record can be lost — `handleSucceededOutcomeWriteFailure` documents that this write is never
  retried — and the in-flight suppression is in-memory, so a re-used blueprint id whose attempt spans
  a leader failover reads the previous attempt's record. The in-memory leg covers the first within a
  leader's lifetime and nothing covers the second; both residues fail toward RE-DRIVING rather than
  condemning, which is the reversible side of a one-way door and strictly narrower than the pre-#922
  behaviour, where every exhaustion re-drove. (3) **A workload that was genuinely up and is now
  permanently unfetchable re-drives at the reconcile cadence rather than settling.** That is the
  ruling this fix implements, not an oversight: it is convergence, logged and diagnosable, chosen over
  a silent permanent verdict on a workload the operator believes is running
  [mechanism: each residue is a leg of `everReachedActive` returning TRUE on stale or absent
  evidence, and every leg is fail-safe by construction — the disjunction can only decline a settle,
  never cause one].
- **Scope note.** This is a defect in the exhaustion machinery, reachable through every cause that is
  legitimately `Intermittent` — `CoreError.Timeout` and resource-capacity exhaustion — and it
  predates any recent reclassification work. The pins deliberately drive `CoreError.Timeout` rather
  than a newly-typed cause, so they stay honest about that. Found while reviewing #916 / #920, which
  type two activation-path causes as `Intermittent`; neither widens the set of causes that can reach
  this loop, because every path that issues an ACTIVATE is gated on the slice having been reported
  `LOADED` and a genuinely absent artifact fails earlier. **#923** — consensus exhaustion raises an
  untyped cause at 90 s, inside the 120 s chain timeout, so it classifies `Fatal` — is the same
  family and is not addressed here
  [mechanism: all four call sites of `tryActivateIfDependenciesReady` filter on `SliceState.LOADED` —
  `ClusterDeploymentState:714`, `:911`, `:1565`, `:2090`, the last being `activateDependentSlices`;
  line numbers re-derived at this branch's head, not carried over].
- **A stale surface corrected with it.** `aether/docs/specs/stream-offheap-budget-spec.md` resolved
  an open design question ("`fatal` flag for deployment failure") with "permanent failure still
  surfaces after retries". That was false on disk for as long as the loop existed — the spec was
  relying on a terminal that did not exist. It is true again with this change
  [verified: open question 7 of that spec, re-read at this branch's head; its resolution now follows
  from `handleRetryBudgetExhausted` reaching `settleAsPermanentlyFailed` for a deployment whose budget
  a genuinely unsatisfiable stream requirement will spend, since such a deployment never reaches
  ACTIVE on any node].
