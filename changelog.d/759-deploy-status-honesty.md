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
  a terminal `FAILED`/`ROLLED_BACK` outcome wins over whatever the live KV entry currently holds** —
  including a stale non-empty value the with-previous rollback path can leave behind (a KV-store defect
  tracked separately, out of scope here). This retracts the `[design intent — unverified]` claim carried
  by the original deploy-response fix above: post-rollback `statusUrl` polls now answer `200` with
  `overallStatus` `FAILED`/`ROLLED_BACK`, `cause`, and `failingSlices`, instead of dead-ending in a
  permanent `404`. `404 BLUEPRINT_NOT_FOUND` now means only "no terminal outcome recorded and nothing
  live in the KV store either." `BlueprintStatusResponse` gained `cause` (String), `failingSlices`
  (List<String>), and `timestampMs` (long), degenerate (`""`, `List.of()`, `0L`) on the unchanged
  `get(id)`-derived path.
  [mechanism: `SliceRoutes.routeBlueprintStatusByOutcome` filters on `DeploymentOutcomeStatus` before
  ever reading `get(id)`; pinned by `BlueprintStatusAggregationTest`
  (`statusRoute_outcomeFailed_returns200Failed`, `statusRoute_outcomeRolledBack_returns200RolledBack`,
  `statusRoute_outcomeSucceeded_returns404`,
  `statusRoute_blueprintPresentStalePreFailure_outcomeRolledBack_returns200RolledBack`) — unit-level,
  not a live multi-node failure-injection run]

- **A redeploy after a prior failure now reports its own in-flight progress, not the prior attempt's
  cleared terminal outcome.** Once #818 clears the stale `DeploymentOutcomeKey` in the same consensus
  batch as the republish's `AppBlueprintKey` write, the status route's outcome-first check (above) falls
  through to live `DeploymentMap` aggregation instead of the stale outcome. No operator action needed —
  the route recomputes on every poll.
  [mechanism: `BlueprintService.buildAllCommands` bundles a `KVCommand.Remove` of the outcome key into
  the republish/delete batch; the route-level effect is pinned by `BlueprintStatusAggregationTest#statusRoute_redeployAfterPriorFailure_outcomeCleared_reportsInProgressNotFailed`
  (stubbed `BlueprintService`, exercises the real route handler) and the KV-level clearing itself by
  `BlueprintPublishOwnershipTest#OutcomeClearedAtPublish` (`aether-deployment`, real `publish()` against
  test-double cluster/store) — both unit/component-level, not a live multi-node failure-injection run;
  no single test drives both the real clearing write and this route's HTTP response together]
