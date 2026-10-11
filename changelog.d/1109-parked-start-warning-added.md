### Added (2026-10-11 — #1109: a start parked for a target that never arrives was silent after one WARN)
- **A LOAD, ACTIVATE or ACTIVE claim a node parked for want of a committed target now raises the operator warning
  `slice-start-awaiting-target` (WARNING) once it has stayed parked for the bound** (2 minutes, LOADING's own bound, measured on the node's
  clock from the first refusal; `NodeDeploymentContext.setParkedStartBound`). `SliceState.LOAD` has no timeout, so the leader's stuck-transitional sweep
  never sees a parked LOAD and nothing else reported it. Subject `<artifact>@<node>`.
- **The park stays retry-able**: nothing is written, failed or unloaded. The next target or routing put for the base re-drives it as before (#1083).
- **Its recovery `slice-start-deferral-ended` (INFO)** is raised when a warned park ends (target arrived, or the entry was superseded, removed or dropped as
  stale), once, for the same subject; a park that ended before the bound raises nothing. Raised once per park, never per tick.
  `[verified: NodeDeploymentStateRollbackOrphanTest$ParkedStartEscalates]` (the one-millisecond-short arm stays silent).
- Guarantee: the event reports a start held past the bound on a node that is up; delivery is best-effort like every per-node event, and the
  log line is always written. A node that is down or partitioned reports nothing.
