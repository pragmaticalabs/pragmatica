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
- **The terminal is scoped, because a cluster-wide inference does not transfer to a node-local
  cause.** `settleAsPermanentlyFailed` came from the DETERMINISTIC branch, where concluding
  cluster-wide from one node is sound: a deterministic failure is node-independent, so failing on one
  node really does mean the artifact is bad everywhere. **That inference does not hold on the
  transient branch** — a transient failure on node A says nothing about node B. Reusing the terminal
  without re-examining the scope of the flag it sets was the defect, not the choice of flag:
  `retryCounters` is keyed per artifact AND node, while `permanentlyFailed` is a cluster-wide
  `Set<Artifact>`. So roughly 31 s of trouble on ONE node condemned a slice that was deployed and
  healthy on every other node. `handleRetryBudgetExhausted` now settles only when
  `hasActiveInstanceElsewhere` is false: a deployment **attempt** with nothing ACTIVE anywhere is
  abandoned, while a **running** workload suffering a node-local transient keeps being reconciled
  toward its desired instance count
  [verified:
  `RetryExhaustionTerminalTest#exhaustionOnOneNode_whileTheArtifactIsActiveElsewhere_stillRecoversTheLostInstance`
  deploys on both nodes, spends the whole budget on one, and asserts the RECOVERY — reconciliation
  still re-drives the artifact and the lost instance comes back. Reverting only the
  `hasActiveInstanceElsewhere` guard fails exactly that test and leaves the other three green].
- **The regression it prevents was worse than the livelock it replaced, and that is why it is called
  out separately.** The livelock was noisy and kept trying. Settling unconditionally is silent and
  terminal: a fully-deployed blueprint has already left `inFlightBlueprints`, so
  `rollbackBlueprintForArtifact` matches nothing, **no FAILED outcome is written at all**, and the
  artifact's last recorded outcome still reads SUCCEEDED while reconcile quietly refuses to replace
  the lost instance. An operator would see a healthy-looking record and a slice that never came back.
  The pin deliberately asserts the recovery rather than the absence of a rollback, because "no
  rollback happened" is equally true of a cluster that has silently stopped doing anything — which is
  the bug itself.
- **The discriminator is read from state that survives leader failover.**
  `hasActiveInstanceElsewhere` tests `sliceStates` for an ACTIVE instance on a live node rather than
  asking whether the blueprint is still in `inFlightBlueprints`. The in-flight map is the cheaper test
  and the wrong one: `newActive()` builds it empty and only a live `AppBlueprintPutReceived` populates
  it, so gating on it would silently stop settling — reopening this ticket — for any deployment whose
  leader changed mid-flight. Verifying a condition's meaning is not verifying its lifetime
  [mechanism: `Active.onEntry` -> `rebuildStateFromKVStore` -> `rebuildSliceStateFromKVStoreEntries`
  repopulates `sliceStates` from durable KV entries, while nothing repopulates `inFlightBlueprints`].
- **The operator can see that the deployment did not apply.** The rollback's consensus batch carries
  a `DeploymentOutcomeValue.failed` record naming the failing slice, in the same batch as the
  blueprint removal, so the removal and the record land atomically
  [verified: `RetryExhaustionTerminalTest#intermittentFailureThatNeverSettles_recordsAnExplicitFailedOutcome`].
- **The retry budget, since the guarantee depends on it being finite and knowable.**
  `ClusterDeploymentState.Active.MAX_RETRIES` = 5 with exponential backoff of 1, 2, 4, 8, 16 s,
  jittered — about 31 s on one node. Both it and `MAX_RETRY_DELAY_SECONDS` = 30 are private
  compile-time constants: finite and knowable, but **not operator-configurable**. Two things the
  earlier wording got wrong. The 30 s cap **never binds at the current budget** —
  `Math.min(1L << (retryCount - 1), 30)` with `retryCount` never exceeding 5 tops out at 16 s — so it
  guards a larger budget rather than shaping this one. And the bound is **per artifact and node**, not
  cluster-wide: the sixth reported failure on any ONE node spends that node's budget, but with
  placement rotating across N allocatable nodes the cluster-wide total can reach
  N x (`MAX_RETRIES` + 1) reported failures, and the bound holds only while the allocatable node set
  is stable — a steady supply of fresh nodes gives each a fresh counter. Recovery action: the artifact
  stays undeployable until a redeploy of its blueprint, which clears `permanentlyFailed` on apply
  [mechanism: `retryCounters` is keyed on `sliceKey.asString()`, rendering as
  `slices/<nodeId>/<artifact>`; `permanentlyFailed` is keyed on artifact alone and is cleared at
  exactly one site, the blueprint-apply loop].
- **What this deliberately does not close.** A transient affecting EVERY node for longer than the
  budget still settles the artifact, since by then nothing is ACTIVE anywhere. The fix narrows the
  evidence required for a cluster-wide permanent verdict from one node's budget to every node's, and
  does not remove the case; removing it entirely would mean never settling a previously-healthy
  artifact, which is the livelock with no terminal at all. Relatedly, a running workload that cannot
  reach its desired instance count keeps retrying at the reconcile cadence — convergence, not the
  defect this ticket was filed about, but a candidate for backing off rather than terminating.
- **Scope note.** This is a defect in the exhaustion machinery, reachable through every cause that is
  legitimately `Intermittent` — `CoreError.Timeout` and resource-capacity exhaustion — and it
  predates any recent reclassification work. The pin deliberately drives `CoreError.Timeout` rather
  than a newly-typed cause, so it stays honest about that. Found while reviewing #916 / #920, which
  type two activation-path causes as `Intermittent`; neither widens the set of causes that can reach
  this loop, because every path that issues an ACTIVATE is gated on the slice having been reported
  `LOADED` and a genuinely absent artifact fails earlier. **#923** — consensus exhaustion raises an
  untyped cause at 90 s, inside the 120 s chain timeout, so it classifies `Fatal` — is the same
  family and is not addressed here
  [mechanism: all four call sites of `tryActivateIfDependenciesReady` filter on `SliceState.LOADED` —
  `ClusterDeploymentState:1551`, `:700`, `:897`, `:1901`, the last being `activateDependentSlices`].
- **A stale surface corrected with it.** `aether/docs/specs/stream-offheap-budget-spec.md` resolved
  an open design question ("`fatal` flag for deployment failure") with "permanent failure still
  surfaces after retries". That was false on disk for as long as the loop existed — the spec was
  relying on a terminal that did not exist. It is true again with this change
  [verified: open question 7 of that spec, re-read at this branch's head; its resolution now follows
  from `handleRetryBudgetExhausted` reaching `settleAsPermanentlyFailed` for a deployment whose budget
  a genuinely unsatisfiable stream requirement will spend, since such a deployment never reaches
  ACTIVE on any node].
