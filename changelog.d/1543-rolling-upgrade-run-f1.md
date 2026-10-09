### Added (2026-10-09 — #1543 part F1: the rolling-upgrade run)
- **`POST /api/v1/cluster/upgrade` now rolls the cluster.** After it stores the target version, the leader starts an upgrade run: one committed
  `UpgradeRunValue` (`upgrade-run/current`) and a leader-side reconciler that replaces every node not reporting the target version, ONE at a
  time, through the merged node-replacement service. No node is restarted under its own id. Order: cores first with the leader last (its
  replacement hands leadership over once, at the end), then workers. A node already on the target is skipped when the leader knows its version (a node that joined after bootstrap; a bootstrap peer's version is not yet carried to the other nodes) and an unknown version counts as not on the target, so a re-issued upgrade can replace such nodes again; an upgrade to a different version while a run is live is refused (409).
- **Serial only.** The replacement service allows one live replacement cluster-wide, so workers are replaced one after another; parallel
  community batches are a later release.
- **Pause, resume, abort — never mid-phase.** `GET /api/v1/upgrade/status`, `POST /api/v1/upgrade/pause|resume|abort` (OPERATOR, route target
  LEADER). Pause and abort are requests: they take effect when the replacement in flight reaches a terminal state, so a record is never
  abandoned mid-phase. A rolled-back or kept-both replacement pauses the run with the reason; resume tries a rolled-back node again.
- **Operator events** `upgrade-started`, `upgrade-completed`, `upgrade-aborted`, `upgrade-paused` (recoveries `upgrade-resumed`,
  `upgrade-pause-ended`), derived from the committed transition and raised by the cluster-events owner.
- **Wire:** `UpgradeRunKey` 2132, `UpgradeRunValue` 2133, `UpgradeRunState` 2134, `UpgradeStop` 2135 (baseline re-recorded, pure additions).
- **Ember:** `EmberCluster.nodeVersion(label)` stamps the version label of nodes booted after the call, so an upgrade test can run between two
  distinct labels on one binary.
- Not in this change: `aether cluster upgrade --wait`, deleting `rolling-aether-upgrade.sh`, `cluster apply` and the runtime-change wave path
  routed to the run, the guide rewrite (part F2).
