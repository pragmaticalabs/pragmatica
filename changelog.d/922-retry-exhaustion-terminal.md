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
  afterwards does not re-drive the artifact; reverting only the production hunk leaves the deployment
  unsettled after 12 reported failures].
- **The operator can see that the deployment did not apply.** The rollback's consensus batch carries
  a `DeploymentOutcomeValue.failed` record naming the failing slice, in the same batch as the
  blueprint removal, so the removal and the record land atomically
  [verified: `RetryExhaustionTerminalTest#intermittentFailureThatNeverSettles_recordsAnExplicitFailedOutcome`].
- **The retry budget, since the guarantee depends on it being finite and knowable.**
  `ClusterDeploymentState.Active.MAX_RETRIES` = 5, with `MAX_RETRY_DELAY_SECONDS` = 30 and
  exponential backoff of 1, 2, 4, 8, 16 s, jittered. Both are private compile-time constants: finite
  and knowable, but **not operator-configurable**. The sixth reported failure spends the budget.
  Recovery action: the artifact stays undeployable until a redeploy of its blueprint, which clears
  `permanentlyFailed` when the blueprint is applied.
- **Scope note.** This is a defect in the exhaustion machinery, reachable through every cause that is
  legitimately `Intermittent` — `CoreError.Timeout` and resource-capacity exhaustion — and it
  predates any recent reclassification work. The pin deliberately drives `CoreError.Timeout` rather
  than a newly-typed cause, so it stays honest about that. Found while reviewing #916, which types
  two activation-path causes as `Intermittent`; #916 does not widen the set of causes that can reach
  this loop, because every path that issues an ACTIVATE is gated on the slice having been reported
  `LOADED` and a genuinely absent artifact fails earlier
  [mechanism: all four call sites of `tryActivateIfDependenciesReady` filter on `SliceState.LOADED` —
  `ClusterDeploymentState:1550`, `:694`, `:894`, `:1897`]. The two changes are therefore independent
  and ship as separate pull requests.
- **A stale surface corrected with it.** `aether/docs/specs/stream-offheap-budget-spec.md` resolved
  an open design question ("`fatal` flag for deployment failure") with "permanent failure still
  surfaces after retries". That was false on disk for as long as the loop existed — the spec was
  relying on a terminal that did not exist. It is true again with this change.
