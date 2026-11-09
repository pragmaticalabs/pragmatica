### Fixed (2026-11-09 — #2937: a DEAD verdict reached as a follower waited for the stale sweep after election)
- Membership removal inputs (`NodeRemoved`, `WorkerLeave`) reach only the current leader, and a Dormant deployment FSM drops them, so a node
  that saw a peer die while it was a follower and was then elected left the peer's departure to the stale-departure backstop. On `Rebuild`
  (FSM2) and on activation (legacy engine) the leader now synthesises one removal input per id in local membership's DEAD set, a `NodeRemoved`
  for a core and a `WorkerLeave` for a worker (deployment-fsm-spec C17r, draft 8.1a). In FSM2 the C17 guard and the R-4.3.0 fenced departure
  apply unchanged, so a re-admission that commits first refuses the departure; the legacy engine has no such fence, and synthesises only
  for ids still DEAD in local membership. [design intent — unverified end-to-end; unit-level: `RebuildDeadSetTest`,
  `ClusterDeploymentStateWorkerRemovalTest$DeadSetAtActivation`]
- Only an id that still has residue is synthesised: a departure record, an activation directive, or desired or observed slice rows (legacy:
  a directive or node-artifact rows). Membership keeps DEAD ids forever, so without this guard every election would write a fresh DEPARTED
  record for a fully cleaned-up id, and nothing would ever remove it (NT-169).
