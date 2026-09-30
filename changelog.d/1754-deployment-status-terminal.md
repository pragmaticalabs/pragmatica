### Fixed (2026-09-30 — #1754: `GET /deploy/{id}` returned 404 for a rolled-back or completed deployment after a leader blip)
- **`DeploymentManagerImpl.status(id)` read only the in-memory map**, and `activate()` rebuilds that map without terminal
  deployments, so any STRATEGIES deactivate/activate turned every `ROLLED_BACK`, `COMPLETED` or `FAILED` deployment into a
  404 while its `DeploymentKey` still sat in the KV. `status` now falls back to the committed record (read-only
  reconstruction, nothing cached), which also covers a node that never held the deployment. `list`, `activeRouting` and
  `checkNoActiveDeployment` still filter `isActive`.
  [mechanism: `status` = map entry, else `DeploymentKey` in the KV; pinned by
  `DeploymentManagerImplStatusTest.TerminalDeploymentSurvivesReassignment`, 3 tests red when the fallback is removed, with
  `OtherReadersStayActiveOnly` staying green as the control]
