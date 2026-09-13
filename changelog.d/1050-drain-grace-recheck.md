### Fixed (2026-09-13 — #1050: a surplus drain's grace expiry reaped without re-checking the cluster)
- **A surplus drain's grace-expiry reap no longer terminates a node the cluster has since come to need.** `graceTerminate`
  used to call `terminateNode` unconditionally. It now re-checks, for `OVERPROVISION_*` drains only: the issuing CTM is
  still active (leader), and the core-counted members other than the target are still quorum-safe and still cover the
  configured core count. If any check fails the reap is skipped and logged with the reason; the DRAIN command is cleared
  either way. The inputs are the `LeaderReconciler`'s own drain-decision inputs (`MembershipFsm.coreCountedMembers()` and
  the configured core count). [mechanism: `ClusterTopologyManagerRecord.graceReapVerdict`, pinned by
  `ClusterTopologyManagerActuatorTest.DrainGraceRecheck` through the real `drainNode` → scheduler path]
- `JOIN_GRACE_REAP` and `OPERATOR_COMMAND` drains still reap as issued. A never-joined zombie has no other reaper and is
  normally reaped during the deficit it was provisioned to fill. [mechanism: `DrainReason.isSurplusTrim`]
- **What this does not cover.** A DRAIN that reached its target cannot be withdrawn: `DrainProcedure.initiate` runs once
  and halts the node within its 30s grace, 6s in the observed run. A surplus drain is also routed through the issuer's
  `MembershipFsm`, whose DEPARTING timeout (`splitTimeout`, 15s) reaps the target via `NodeRemoved` while the issuer is
  still leader, before this 60s backstop fires. The new check therefore changes the outcome only when the issuer has lost
  leadership, or the target has not been reaped by then. [unverified: no multi-node run; unit-level only]
