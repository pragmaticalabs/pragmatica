### Fixed (2026-09-29 — #1492: removing an absent blueprint still logged "App blueprint … removed")
- **Removing a blueprint that did not exist logged `App blueprint '<id>' removed`**, because the KV-Store
  publishes a removal notification even for a key it never held, and the handler did not check. A log line
  read as proof the blueprint had existed.
- The handler now checks whether a value was present, and for an absent key logs `… remove applied to an
  absent key — nothing was removed` instead.
  `[mechanism: ClusterDeploymentState.handleAppBlueprintRemove passes ValueRemove.oldValue().isPresent() to the log decision; pinned by ClusterDeploymentStateTransactionalTest.AbsentBlueprintRemoval]`
- Not changed: `KVStore.handleRemove` still publishes the notification for an absent key (about 90
  subscribers read it), and `DELETE /api/v1/blueprints/{id}` still answers `deleted` and records the audit
  event for an absent id.
