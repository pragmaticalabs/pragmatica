### Fixed (2026-11-06 — #2737: an upgrade abort/pause/resume answered "changed concurrently; retry")
- **An operator's `upgrade-abort`, `upgrade-pause` or `upgrade-resume` could fail with "The upgrade run changed
  concurrently; retry"** when the run's own progress commit (the replacement in flight advancing the run) landed between
  the request's read and its compare-and-set. The CLI does not retry, so the operator got an error for a request that was
  still valid. The leader now re-decides the request against the run as committed now (the rejected compare-and-set
  committed nothing, and the apply promise completes only after its batch's notifications are delivered, so the index
  already holds the commit that won): the request lands, or is refused for the run's current state (ended, not leader).
  `Conflict` reaches the caller only after 8 consecutive lost races. Pinned in `UpgradeRunServiceTest`.
