### Fixed (2026-09-07 — #916: a transient activation race was classified as permanent and rolled the blueprint back)

- **An activation that lost a race with an in-flight unload permanently failed the deployment.**
  Delete a blueprint and re-apply the same artifact before the unload has completed, and the new
  `ACTIVATE` finds the slice already gone from the node's `SliceStore`. The node raised an untyped
  cause for that, which fell through `SliceLoadingFailure.classify`'s permanent catch-all to
  `Fatal.UnexpectedError`; the leader marked the artifact permanently failed and, under the default
  `ALL_OR_NOTHING`, rolled the whole blueprint back. Nobody decided a concurrent-unload race was
  non-retriable — it inherited fatality from the fallback arm. The cause is now typed
  `Intermittent.SliceNotInStore` where it is raised, so it reaches the cluster's existing bounded
  retry instead
  [verified: `ActivationRaceNotFatalTest#unloadActivateCrossing_isFatalIsFalse_andBlueprintIsNotRolledBack`
  — both FSM halves run for real and the node's own emitted consensus command is replayed into the
  leader; reverting only the production hunk turns `fatal` back to `true` and the blueprint's KV key
  back into the rollback's remove-set].
- **Reachable through the endpoints an operator actually uses**, not only under test load:
  `DELETE /api/v1/blueprints/{id}` followed by `POST /api/v1/blueprints` for the same artifact. The
  operator saw a `ROLLED_BACK` blueprint with nothing indicating a retry would have worked
  [mechanism: `NodeDeploymentState.Active.handleSliceNotFoundForActivation` -> `transitionToFailed` ->
  `AetherValue.NodeArtifactValue.failedNodeArtifactValue` -> `classify` ->
  `ClusterDeploymentState.Active.handleDeterministicFailure` -> `rollbackBlueprintForArtifact`].
- **The same race had a second ordering that did not roll back, and it was not the safe one.** When
  the previous deployment's instance was still `ACTIVE` at apply time, the leader counted the
  blueprint fully deployed and retired it from `inFlightBlueprints` before the activation failure
  arrived; `rollbackBlueprintForArtifact` then iterated an empty map and did nothing. That ordering
  never escaped `permanentlyFailed` — it escaped only the rollback, leaving the artifact marked
  permanently failed with no rollback, no retry and no `ROLLED_BACK` record, stuck until the next
  apply cleared the flag at `handleAppBlueprintChange`. It was quieter, not healthier. Typing the
  cause closes both orderings, because neither now reaches `handleDeterministicFailure`
  [mechanism: `trackBlueprintSliceActive` removes the blueprint from `inFlightBlueprints` before
  `rollbackBlueprintForArtifact`'s loop runs; `permanentlyFailed.add` happens either way].
- **The retry is bounded and its exhaustion is now genuinely terminal.** `handleTransientFailure`
  allows 5 attempts with exponential backoff (1, 2, 4, 8, 16 s, capped at 30 s, jittered),
  re-driving `reconcile()` each time. The sixth reported failure spends the budget and
  `handleRetryBudgetExhausted` settles the artifact: it is marked permanently failed, a
  `DeploymentFailed` event is routed, and the declared atomicity is honoured — `ALL_OR_NOTHING`
  rolls the owning blueprint back, `BEST_EFFORT` records a FAILED outcome
  [verified: `ActivationRaceNotFatalTest#intermittentFailureThatNeverSettles_reachesTerminalRollback_withinTheRetryBudget`
  asserts the terminal arrives on the sixth reported failure and not before, and that a reconcile
  afterwards does not re-drive the artifact; reverting only the `ClusterDeploymentState` hunk leaves
  it unsettled after 12 reported failures].
- **That exhaustion path did not previously stop, and this release fixes it (#922).** Review round 1
  found the mechanism this fragment originally described was wrong in the mild direction. The old
  `logMaxRetriesExceeded` cleared the retry counter and routed `DeploymentFailed` without adding the
  artifact to `permanentlyFailed`. That is not a resting state: every failure already issues an
  unload, the node's removal of the `NodeArtifactKey` reaches `handleSliceNodeRemoval`, which —
  finding the artifact still deployable — scheduled a reconcile, and `reconcileBlueprint` is gated
  by `permanentlyFailed` and nothing else, so it redeployed the artifact with the counter restarting
  at 1. An intermittent cause that never settled was therefore re-driven at roughly 1 Hz for the
  life of the cluster: no terminal state, no rollback, and consensus round-trips forever. This was
  pre-existing and reachable by every intermittent cause, not only the one this ticket types
  [mechanism: `handleSliceFailure` -> `issueUnloadCommand` -> `deleteSliceNodeKey` ->
  `handleSliceNodeRemoval` -> `reconcile` -> `reconcileBlueprint`, with no arm that adds to
  `permanentlyFailed`].
- **`classify`'s catch-all deliberately STAYS fail-permanent** — the reviewable judgement of this
  change, so the reasoning is recorded rather than assumed. The cause universe on the loading and
  activation paths is open, so the default arm is chosen for the failure mode it produces. Note the
  original argument for it has been withdrawn: it rested on intermittent-by-default breaking
  atomicity outright, which was true only while retry exhaustion failed to settle. Now that both
  buckets reach a terminal state with a rollback, the gap is one of cost and latency, not of
  guarantee, and it is narrower than this ticket first claimed. What still favours permanent: a
  permanent cause typed intermittent pays six load/activate cycles over roughly a minute of backoff,
  each issuing an unload and a reconcile through consensus, and holds the blueprint half-deployed
  and in flight for that window before reaching the identical terminal, whereas a transient cause
  typed permanent fails at once and is recovered by one redeploy; and a cause arriving here
  unrecognised is more often a genuine defect than a blip, because the transient conditions on these
  paths are the ones the code already knows about and types
  [verified: `SliceLoadingFailureClassifyTest#unrecognisedCause_staysPermanent`, and
  `ActivationRaceNotFatalTest#genuinelyUnclassifiedCause_stillRollsBackTheBlueprint` drives an
  untyped cause through both FSMs and asserts the rollback still happens — which doubles as the
  positive control proving the non-rollback assertion above can fail].
- **The price of keeping that default is an obligation, now written down where it binds.** A
  transient cause on these paths must be typed `Intermittent` at its raise site, because reaching
  `classify` untyped means permanent. Two causes have been paid for this way: this ticket's, and
  `SliceInvoker.verifyEndpointExists`, which typed its own activation-order race for the identical
  reason. A third cause on the same chain, `SLICE_NOT_LOADED_FOR_REGISTRATION`, is typed here too —
  the slice can be evicted between the activation lookup and the invocation-registration lookup, so
  it carried the same defect and was fixed with it
  [mechanism: both constants in `NodeDeploymentState.Active` now build `Intermittent.SliceNotInStore`
  rather than `Causes.forOneValue`].
- **Operator-visible: the reported failure text for this condition changed**, because the cause type
  changed. Where the blueprint status endpoint and `DeploymentFailed` events used to carry
  `"Unexpected slice loading error: Slice <artifact> state is ACTIVATE but not found in SliceStore"`,
  they now carry `"Slice <artifact> not present in SliceStore during activation (a concurrent unload
  may still be in flight)"`. Any alert or log filter keying on the old string will stop matching. The
  node-side log line for it also drops from ERROR to WARN, since the cluster now recovers from it on
  its own; retry exhaustion remains an ERROR.
- **Residual, stated plainly: transient causes on the activation path that are still typed
  permanent.** Consensus exhaustion inside the activation chain raises a plain
  `Causes.cause("Consensus … timed out after 2 retries")`, which `classify` does not recognise and
  so buckets `Fatal`. It is reachable by arithmetic rather than assumption: each attempt carries a
  30 s timeout and 2 retries, so the plain cause is raised at 90 s, inside the 120 s activation
  chain timeout that would otherwise have produced an `Intermittent` `CoreError.Timeout`. A quorum
  loss lasting more than 90 s during activation therefore permanently fails the artifact and rolls
  the blueprint back for a condition that clears by itself. The bar is high — three consecutive 30 s
  timeouts means consensus was genuinely unavailable, not merely slow, so a healthy cluster never
  reaches it and no deployment that would have succeeded under ordinary load is rolled back — which
  makes it an outage-recovery and maintenance-window defect rather than an ordinary-operation one.
  Pre-existing, out of scope here, and tracked separately; named because this ticket's own
  obligation to type transient causes at their raise site leaves it unpaid
  [mechanism: `NodeDeploymentState.Active.retryConsensusOperation` and `retryApply`,
  `CONSENSUS_OPERATION_TIMEOUT` = 30 s x (`CONSENSUS_MAX_RETRIES` = 2) + 1 = 90 s <
  `DEFAULT_ACTIVATION_CHAIN_TIMEOUT` = 120 s].
- Scope note: this is the product half of #727. PR #913 fixed the **test** so it stops producing the
  race, which removed the flake without touching the defect; this change removes the defect. The
  race was reproduced under CPU load 3 times in 11 runs across both orderings, never on a quiet host
  in ten runs.
