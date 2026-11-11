### Fixed (2026-11-11 — #2887: FSM2 misclassified a core missing from the boot topology as a worker forever)
- `Absence.classOf` classed a directive-less core as CORE only from a boot snapshot of `topology.coreNodes()`. With cloud-discovery
  majority boot a slow core is absent from that snapshot, so it stayed an unassigned worker (UNJUDGEABLE) and its rows were not removed
  if it departed before the leader activated (#2868 shape). The poller now samples the consensus-installed voter set into a new
  `Seams.committedCores` seam every cycle, and the class is CORE when the node is in the boot set or in the committed set. The committed
  set survives restart and leader change because a new leader samples it fresh.
