### Fixed (2026-09-08 — #930: deployment permanence was decided by a cause's Java type, not by the operation; folds in #923)

- **A cause nobody had bothered to type decided whether a blueprint rolled back.**
  `SliceLoadingFailure.classify` recognised three shapes — an already-typed `SliceLoadingFailure`,
  transient capacity via `ResourceCapacityExhausted.isTransientCapacity`, and `CoreError.Timeout` —
  and returned `Fatal.UnexpectedError` for everything else. `isFatal()` (literally
  `this instanceof Fatal`) was stored as a plain boolean on the KV record, crossed consensus to the
  leader, and `ClusterDeploymentState.handleSliceFailure` branched on it into
  `handleDeterministicFailure`: `permanentlyFailed`, "will NOT retry", `DeploymentFailed`, and under
  `ALL_OR_NOTHING` a full blueprint rollback. Because almost every cause raised on this path is a
  plain `Causes.cause`, the catch-all — not the raise site — was the decision
  [mechanism: `classify` -> `isFatal()` -> `SliceNodeValue.fatal` -> `handleSliceFailure` ->
  `handleDeterministicFailure` -> `rollbackBlueprintForArtifact`].

- **The catch-all is gone; the permanence of an unrecognised cause is now an argument.**
  `classify(Cause, Unrecognised)` takes `RETRY` or `PERMANENT` from its caller and there is no
  single-argument form, so a new raise site must state its intent or fail to compile — #930
  acceptance 1. The three recognised shapes are unchanged and still classify identically whatever the
  caller declares, because those are evidence about the cause rather than about the operation
  [verified: `SliceLoadingFailureClassifyTest#unrecognisedCause_atAPermanentSite_isFatal` and
  `#unrecognisedCause_atARetrySite_isIntermittent` are mutually exclusive on the same input, so
  hard-coding either arm reddens the other; `#aTypedCause_classifiesIdenticallyUnderBothDispositions`
  and `#aTimeout_staysIntermittent_evenAtAPermanentSite` pin the shapes the argument must not reach].

- **#923: a consensus outage during deployment no longer rolls the blueprint back.**
  `performActivation`'s chain reaches consensus at every `publish*` leg through
  `NodeDeploymentState.applyWithRetry`, which after `CONSENSUS_MAX_RETRIES = 2` attempts of
  `CONSENSUS_OPERATION_TIMEOUT = 30s` raises an untyped
  `Causes.cause("Consensus batch timed out after 2 retries")` — 90 s, inside the deployment chain's
  own window, so it wins. That landed in `handleActivationFailure`, hit the permanent catch-all, and
  a genuine cluster OUTAGE was recorded as a permanent deployment fault. The site now declares
  `Unrecognised.RETRY`
  [verified: `ActivationRaceNotFatalTest#activationFailingWithAnUntypedConsensusCause_isNotFatal_andDoesNotRollBackTheBlueprint`
  drives the real node FSM against a store whose activation fails with that untyped cause, replays the
  node's OWN emitted consensus command into the leader FSM, and asserts both that the report is not
  fatal and that the blueprint is not removed].

- **All five raise sites now declare a disposition, and each says why.**
  `NodeDeploymentState.handleLoadingFailure` -> `PERMANENT` (an unrecognised load failure is usually
  the artifact: bad coordinate, malformed manifest, unreadable envelope — the retryable load failures
  are typed at their own raise sites and pass through unchanged);
  `handleSliceNotFoundForActivation` -> `RETRY` (unreachable, since #916 types the cause, but stated
  so a future edit cannot inherit a default); `handleActivationFailure` -> `RETRY` (the #923 site
  above); `handleDeactivationFailure` -> `RETRY` (a failed teardown says nothing about whether the
  artifact can be deployed); and `SliceFactory.invokeFactory` -> `PERMANENT` (a slice's own factory
  method throwing under reflection is a defect in that slice and will throw identically on every node).

- **`ALL_OR_NOTHING` is not weakened — the bound moved rather than disappearing.** #916 kept the
  catch-all permanent on the argument that an intermittent default would retry a genuinely fatal cause
  and then abandon it without a rollback. #922 removed the abandonment: a spent retry budget now
  settles permanently, with a rollback and a durable `DeploymentOutcomeValue`, whenever the owning
  blueprint's apply is still outstanding. So a genuinely fatal cause reported at a `RETRY` site costs
  a bounded five attempts and then reaches the same terminal, instead of that terminal depending on
  the cause's Java type
  [verified: `ActivationRaceNotFatalTest#genuinelyUnclassifiedCause_stillRollsBackTheBlueprint` is the
  positive control — a `PERMANENT` classification still removes the blueprint, so the "does not roll
  back" assertions above are not passing vacuously; the bounded-then-terminal half is pinned in
  `RetryExhaustionTerminalTest`, see the #922 fragment].

- **`SliceLoadingFailure.classify`'s only two call sites were reported as two; there are three, and
  one of them is dead.** The ticket and its `know:` commit name `AetherValue.java:312` and `:962`.
  `:962` sits inside `NodeArtifactValue.failedNodeArtifactValue`, which has NO production caller —
  both production sites that build a FAILED `NodeArtifactValue` (`NodeDeploymentState`'s
  `updateSliceStateWithRetry` and `updateSliceStateWithExtraCommandsAndRetry`) construct the record
  directly and copy `fatal` across from the already-classified `SliceNodeValue`. A third call site,
  unlisted, is `SliceFactory.invokeFactory`'s `.mapError(SliceLoadingFailure::classify)`. So the
  `fatal` flag that crosses consensus is decided at exactly one place, `failedSliceNodeValue`, and the
  dead factory is kept with the explicit parameter rather than deleted so nothing can reach a silent
  default through it later
  [mechanism: repo-wide grep for `SliceLoadingFailure.classify` / `::classify` over non-test sources,
  with `NodeArtifactValue.` usage as the positive control — 6 production hits for the sibling
  factories, 0 for `failedNodeArtifactValue`].

- **Stale surfaces swept.** `Intermittent.SliceNotInStore`'s javadoc asserted in the present tense that
  `classify` has a catch-all "deliberately permanent", and `NodeDeploymentState`'s
  `SLICE_NOT_FOUND_FOR_ACTIVATION` referred to it as current. Both now name what replaced it, and both
  record that typing the cause at its raise site is still correct — for a stronger reason than before,
  since permanence there is a property of the cause rather than of the operation.

- **Scope.** `handleDeterministicFailure` is untouched. No multi-node or failure-injection run backs
  any claim above; every pin is an FSM-harness test driving the real node and leader state machines.
