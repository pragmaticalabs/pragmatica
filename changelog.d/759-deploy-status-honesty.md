### Fixed (2026-09-04 — #759: deploy and status responses now reflect actual blueprint state, not an assumed or stale one)

- **Deploy response reports an earned status, not an assumed one, and always carries a `statusUrl`.**
  `POST /api/v1/blueprints/deploy` used to hardcode `"deployed"` before allocation ever ran. `status`
  is now read off the live deployment map at response time — `pending` (default), `degraded` (a target
  instance already `FAILED`), or `deployed` (every target instance `ACTIVE`) — and `statusUrl` always
  points at `GET /api/v1/blueprints/status/{id}` so a `pending`/`degraded` caller can poll it. `slices`
  was replaced by `targetInstances`/`activeInstances`/`failedInstances` on `/deploy`, `/publish`, and
  the status endpoint's per-slice entries, for consistent instance counts across all three. The status
  endpoint reports `FAILED` honestly: a slice with a `SliceState.FAILED` instance still present in the
  deployment map is reported `FAILED` rather than folded into `PENDING`/`DEPLOYING`, and `overallStatus`
  is `FAILED` ahead of every other bucket if any slice is.
  [mechanism: status is derived directly from `deploymentMap()` by construction; pinned in-process by
  `BlueprintDeployStatusTest`, `BlueprintStatusAggregationTest` — unit-level, not a live multi-node run]

- **The status route now consults `BlueprintService.outcome(id)` unconditionally, before `get(id)`, so
  a terminal `FAILED`/`ROLLED_BACK` outcome is authoritative for a blueprint that is NOT live** — absent
  from the KV store entirely, whether never deployed, rolled back under `ALL_OR_NOTHING`, or deleted.
  This retracts the `[design intent — unverified]` claim carried by the original deploy-response fix
  above: post-rollback `statusUrl` polls now answer `200` with `overallStatus` `FAILED`/`ROLLED_BACK`,
  `cause`, and `failingSlices`, instead of dead-ending in a permanent `404`. `404 BLUEPRINT_NOT_FOUND`
  now means only "no terminal outcome recorded and nothing live in the KV store either." `BlueprintStatusResponse`
  gained `cause` (String), `failingSlices` (List<String>), and `timestampMs` (long), degenerate (`""`,
  `List.of()`, `0L`) on the unchanged `get(id)`-derived path. When the blueprint IS still live next to a
  terminal outcome, a different rule applies — see below.
  [mechanism: `SliceRoutes.routeBlueprintStatusByOutcome` filters on `DeploymentOutcomeStatus` before
  ever reading `get(id)`; pinned by `BlueprintStatusAggregationTest`
  (`statusRoute_outcomeFailed_returns200Failed`, `statusRoute_outcomeRolledBack_returns200RolledBack`,
  `statusRoute_outcomeSucceeded_returns404`) — unit-level, not a live multi-node failure-injection run]

- **A terminal outcome next to a STILL-LIVE blueprint now aggregates real per-slice state and reports
  `PARTIAL`, instead of discarding it to the degenerate `slices = []` shape above.** Two cases land
  here: `BEST_EFFORT` records a terminal `FAILED` outcome without removing `AppBlueprintKey`
  (`ClusterDeploymentState.recordBestEffortFailureOutcome`), so siblings keep serving; and a restored
  blueprint can be fully healthy while a `ROLLED_BACK` outcome from the original failed deploy still
  lingers — `get(id)` is re-Put fresh in the same batch as the restore, it is `outcome(id)` that is
  never cleared for a blueprint id that stays live and healthy (`BlueprintService.outcome`'s "Scope —
  one documented exception"; an earlier version of this note wrongly called the live `get(id)` value
  itself a "KV-store defect"). `overallStatus` is hardcoded `PARTIAL` here rather than re-derived —
  even on the fully-healthy-restore edge, where live aggregation alone would otherwise read `DEPLOYED`
  and hide that a terminal outcome exists at all — because neither side (`get()` alone or `outcome()`
  alone) tells the whole story. `cause`/`failingSlices`/`timestampMs` still come from the outcome record.
  Operator recovery is unchanged from before this fix: a `BEST_EFFORT` `PARTIAL` clears by redeploying
  the failed slice; a lingering restore-time outcome clears the next time that blueprint id is
  redeployed or deleted.
  [mechanism: `SliceRoutes.resolveTerminalOutcomeStatus` consults `get(blueprintId)` before choosing
  between the degenerate and live-aggregated response shapes; pinned by `BlueprintStatusAggregationTest`
  (`statusRoute_blueprintLiveAndHealthyWithLingeringRolledBackOutcome_reportsPartialWithLiveSliceCounts`,
  `statusRoute_blueprintLiveWithTerminalFailure_bestEffort_reportsPartialWithSliceCounts`) — unit-level,
  not a live multi-node failure-injection run]

- **A redeploy after a prior failure now reports its own in-flight progress, not the prior attempt's
  cleared terminal outcome.** Once #818 clears the stale `DeploymentOutcomeKey` in the same consensus
  batch as the republish's `AppBlueprintKey` write, the status route's outcome-first check (above) falls
  through to live `DeploymentMap` aggregation instead of the stale outcome. No operator action needed —
  the route recomputes on every poll.
  [mechanism: for a DSL republish (`BlueprintService.publish(String)`), the clearing `KVCommand.Remove`
  is bundled by `storeBlueprintWithKey` into the same batch as the `AppBlueprintKey` `Put`
  (`publishFromArtifact` bundles the equivalent Remove via `buildAllCommands` instead — a separate path,
  not exercised by the test below); pinned end to end by a single test driving both the real clearing
  write and the route's HTTP response together:
  `BlueprintServiceTest$RedeployAfterPriorFailureTests#statusRoute_publishAfterPriorFailure_outcomeCleared_reportsInProgressNotFailed`
  (real `BlueprintService.blueprintService(cluster, store, repository)` over `TestClusterNode`/`TestKVStore`,
  a real on-disk slice jar, and the real `SliceRoutes` status handler — seeds a FAILED outcome, republishes,
  asserts the outcome is cleared and the route reports IN_PROGRESS) and its sibling
  `#statusRoute_noRepublishAfterPriorFailure_reportsFailed` (same seed, no republish, route reports FAILED)
  — component-level against real production collaborators, not a live multi-node run, so this stays
  `[mechanism: ...]` rather than `[verified: ...]` per this repo's Integration-verified bar]
