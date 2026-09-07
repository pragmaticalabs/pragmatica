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
- **The retry is bounded and its exhaustion is stated.** `handleTransientFailure` allows 5 attempts
  with exponential backoff (1, 2, 4, 8, 16 s, capped at 30 s, jittered), re-driving `reconcile()`
  each time. At exhaustion `logMaxRetriesExceeded` logs at ERROR, clears the counter and routes a
  `DeploymentFailed` event. Nothing here is unbounded and no new retry mechanism was added
  [mechanism: `ClusterDeploymentState.Active.MAX_RETRIES` = 5, `MAX_RETRY_DELAY_SECONDS` = 30].
- **`classify`'s catch-all deliberately STAYS fail-permanent** — the reviewable judgement of this
  change, so the reasoning is recorded rather than assumed. The cause universe on the loading and
  activation paths is open, so the default arm is chosen for the failure mode it produces.
  Permanent-by-default fails loudly and bounded: the blueprint is rolled back, `ALL_OR_NOTHING`
  holds, and the operator sees something they can act on. Intermittent-by-default would fail quietly
  in the direction that matters — a genuinely permanent failure reaching an unclassified path would
  be retried five times and then abandoned with **no rollback**, leaving exactly the half-deployed
  blueprint `ALL_OR_NOTHING` promises cannot exist. Trading a wrong rollback for a silently broken
  atomicity guarantee is the worse trade
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
- **Residual, stated plainly: a transient failure that exhausts its retries does not roll back.**
  `logMaxRetriesExceeded` routes `DeploymentFailed` and stops; it does not call
  `rollbackBlueprintForArtifact`. So an activation race that somehow never settles now leaves a
  blueprint in flight rather than rolled back. This is pre-existing behaviour for every intermittent
  failure and is not introduced here, but this change puts one more cause on that path and it should
  not be discovered later as a surprise.
- Scope note: this is the product half of #727. PR #913 fixed the **test** so it stops producing the
  race, which removed the flake without touching the defect; this change removes the defect. The
  race was reproduced under CPU load 3 times in 11 runs across both orderings, never on a quiet host
  in ten runs.
