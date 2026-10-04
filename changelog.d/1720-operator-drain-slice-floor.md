### Fixed (2026-10-04 — #1720: operator drain and shutdown bypassed the slice `minAvailable` floor)
- **`POST /api/v1/nodes/drain|shutdown` checked only the core disruption budget and READY state.** The automatic drain
  has refused, since #1488, to take a node whose removal leaves a hosted slice below its `minAvailable` ACTIVE
  instances; an operator could drain the node holding the second-to-last instance while another drain was in flight and
  take the slice to 1, then 0. (A WORKER target bypassed even the core budget, so the floor was its only possible guard.)
- **Both routes now run the same KV-backed guard**, `SliceOwnershipQuery.minAvailableDrainViolations`, with
  `remaining = counted members - pending drains - target` (workers count: slices run on them). A drain that would breach
  is refused `409`, naming every slice and its counts. Admission is serialised against concurrent operator drains by
  `NodeLifecycleRoutes.admitOperatorDrain`'s monitor, and the pending set is updated inside it, so two requests cannot
  both pass against one snapshot.
- **Override:** query parameter `force=true` (`aether nodes drain|shutdown <id> --override-floor`, `aether cluster drain
  <id> --override-floor`). A forced breach is admitted and raises operator warning `slice-floor-breached-by-force` naming
  each slice, so it is never silent. The core quorum budget is NOT overridden by `force`.
- **`aether cluster destroy` passes `force`** on every drain and shutdown: it takes every slice below its floor by
  definition. Rolling-restart and scale-down waves (`WaveExecutor`) do not, so they are now refused when a drain would
  take a hosted slice below its floor.
- Docs: per-operation guarantee table (automatic drain, operator drain, forced drain, destroy) in
  `slice-developers/deployment.md`, the routes and the new warning code in `management-api.md`, flags in `cli.md`.
- Pinned by `NodeLifecycleRoutesSliceFloorTest` (the real guard against a real KV store, through the real routes, incl.
  the concurrent-admission case) and `OperatorDrainForceFlagTest`.
