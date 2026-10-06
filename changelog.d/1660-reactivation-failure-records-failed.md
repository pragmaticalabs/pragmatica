### Fixed (2026-10-04 — #1660, #1452: a failed reactivation no longer leaves a node silently non-hosting)
- **A slice whose reactivation failed after a quorum return stayed listed ACTIVE on that node and was
  never redeployed.** `NodeDeploymentState.handleReactivationFailure` unregistered the slice and
  dropped the local deployment without writing anything, so the committed `NodeArtifactKey` kept the
  ACTIVE it carried before the outage. No reconcile saw a missing instance, and every reader of
  committed `NodeArtifactKey` state (the `DeploymentMap` behind `/slices/status`, the leader's
  reconcile, `EndpointRegistry`, `ControlLoop`, `StreamConsumerManager.candidateNodes`) kept counting
  a node that held no bridge. It now commits a **non-fatal** `FAILED` for its own key:
  the leader's failure path unloads it and re-drives the instance, and the committed `FAILED` is
  surfaced as a WARNING `DeploymentFailed` cluster event naming the node, the artifact and
  `Reactivation after quorum restore failed: <cause>`. The flag is forced non-fatal whatever the
  cause's type, so a reactivation failure can never condemn the artifact or roll back its blueprint.
- **A suspended slice that left the `SliceStore` during the outage** took the same silent exit
  (log, drop, no write). It now goes through the same path.
- **The quorum-loss `suspendSlice` writing no transition is deliberate**, and is now documented as
  such: `RabiaEngine.validateSubmission` rejects every `apply` with `QuorumPaused` / `NodeInactive`
  while the quorum is gone, so no write could commit. The stale ACTIVE lasts for the outage and is
  bounded by the quorum's return, where the reactivation either restores the slice or commits
  `FAILED`.
