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
- **The retry is bounded, and what happens at its end is stated accurately.** `handleTransientFailure`
  allows 5 attempts with exponential backoff (1, 2, 4, 8, 16 s, capped at 30 s, jittered), re-driving
  `reconcile()` each time. The budget is `ClusterDeploymentState.Active.MAX_RETRIES`, a private
  compile-time constant — finite and knowable, but not operator-configurable. No new retry mechanism
  was added here [mechanism: `MAX_RETRIES` = 5, `MAX_RETRY_DELAY_SECONDS` = 30].
- **Correction: retry exhaustion does NOT stop, and this ticket originally said it did.** Review
  round 1 caught it. `logMaxRetriesExceeded` clears the retry counter and routes `DeploymentFailed`
  but never adds the artifact to `permanentlyFailed`, so the unload that every failure issues removes
  the `NodeArtifactKey`, `handleSliceNodeRemoval` finds the artifact still deployable and schedules a
  reconcile, and `reconcileBlueprint` — gated on `permanentlyFailed` and nothing else — redeploys it
  with the counter restarting at 1. An intermittent cause that never settles is re-driven at roughly
  1 Hz with no terminal state. **This is pre-existing and is neither fixed nor worsened here**; it is
  tracked by #922. Its sustaining entry point is `ArtifactNotFound` on the LOAD leg, already
  `Intermittent` before this change. The causes typed by this ticket cannot sustain it: every path
  issuing an ACTIVATE is gated on the slice having been reported `LOADED`, so a genuinely absent
  artifact fails earlier at `handleLoadingFailure` and never reaches them
  [mechanism: all four call sites of `tryActivateIfDependenciesReady` filter on
  `SliceState.LOADED` — `ClusterDeploymentState:1550`, `:694`, `:894`, `:1897`].
- **Split note.** The terminal-state fix for that loop is a separate change, on its own ticket and
  its own pull request (#922) — different concern, different blast radius, and either can land or be
  reverted without the other. The correction to `aether/docs/specs/stream-offheap-budget-spec.md`,
  whose stated resolution depends on that terminal existing, travels with #922 rather than with this
  ticket.
- **`classify`'s catch-all deliberately STAYS fail-permanent** — the reviewable judgement of this
  change, so the reasoning is recorded rather than assumed. The cause universe on the loading and
  activation paths is open, so the default arm is chosen for the failure mode it produces.
  Permanent-by-default fails loudly and bounded: the blueprint is rolled back, `ALL_OR_NOTHING`
  holds, and the operator sees something they can act on. Intermittent-by-default would fail in the
  direction that matters, and worse than first argued: a genuinely permanent failure reaching an
  unclassified path would not be "retried five times and then abandoned" — per the correction above
  it would be re-driven indefinitely, with no terminal state at all. Trading a wrong rollback for an
  unbounded loop is plainly the worse trade, so the ruling stands on a stronger footing than the one
  originally written for it
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
  [verified: `ActivationRaceNotFatalTest#sliceEvictedBeforeInvocationRegistration_isReportedIntermittent`
  drives the node FSM through a store that holds the slice for the activation lookup and evicts it on
  `activateSlice`, placing the eviction in exactly that window; reverting this second constant alone
  to `Causes.forOneValue` turns that test red and leaves the other two green].
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
