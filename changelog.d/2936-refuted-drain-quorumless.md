### Fixed (2026-11-09 — #2936: a refuted drain re-admitted a node that could not write to the quorum)
- **A node that lost quorum during a partition was re-admitted DEPARTING→MEMBER by its own SWIM refutation at the
  heal**, and stayed forwardable for HTTP routes and slice invocations on the survivors until it self-drained (~60 s
  in the E11 r6 run). A refutation proves the node is alive, not that it can write to the quorum.
- A core now reports its own quorum loss (`QuorumLossDetector`: armed and below threshold or consensus-PASSIVE) on its
  SWIM self-ALIVE as the gossiped `quorum=lost` label, bumping its incarnation on every change so receivers order it.
  Survivors hold such a node in DEPARTING on a refuted drain and keep it out of the forward filter
  (`MembershipFsm.forwardableMembers`, used by the AccessibilityFilter and the worker endpoint directory). A DEAD
  identity is not reopened by a newer incarnation while the node still reports quorum loss.
- Bounded rejoin: when the node regains quorum it clears the report with a new incarnation, which re-admits it on the
  clearing incarnation itself (or reopens it through the normal rejoin if the DEPARTING timeout terminalized it first)
  [design intent — unverified beyond the in-JVM FSM tests: no multi-node run with failure injection exercised it].
  `reachableMembers` is unchanged: it also scopes SWIM observation, and narrowing it could hide the report clearing.
