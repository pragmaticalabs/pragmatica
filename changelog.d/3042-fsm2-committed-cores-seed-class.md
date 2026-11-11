### Fixed (2026-11-11 — #2887: FSM2 misclassified a core missing from the boot topology as a worker forever)
- `Absence.classOf` classed a directive-less core as CORE only from a boot snapshot of `topology.coreNodes()`. With cloud-discovery
  majority boot a slow core is absent from that snapshot, so it stayed an unassigned worker (UNJUDGEABLE) and its rows were not removed
  if it departed before the leader activated (#2868 shape). The poller now samples every member admitted with `role=core` (voter or not: surplus, swapped-out and joining cores are not voters)
  into a new `Seams.coreRoleMembers` seam every cycle, and the class is CORE when the node is in the boot set or in that set. The boot
  `seedCores` now keeps only role-core topology entries, so a listed worker departs under the worker rule.
