### Changed (2026-09-29 — #1497: deploy, A/B and rollback wrote minAvailable == instances)
- **CLI/REST deploy, `DeploymentManagerImpl.addSliceTargetCommand`, A/B tests and rollback wrote a slice's
  first target with `minInstances` equal to its instance count.** The #1488 drain guard can never drain an
  owner of such a slice, so a surplus drain deferred forever.
- Every writer with no explicit value now uses the blueprint default, `ceil(instances/2)`, through one
  function, `SliceTargetValue.defaultMinInstances`; the two creation factories without an explicit floor use
  it too. An explicit floor is honoured unchanged.
  `[mechanism: SliceRoutes.applyDeployCommand, AbTestManager.newSliceTarget and the defaulting factories used by DeploymentManagerImpl and RollbackManager all call defaultMinInstances; pinned by SliceRoutesScaleFloorTest, SliceTargetDefaultFloorTest and SliceTargetValueTest]`
- `minInstances` also feeds the autoscaler's floor, but since #1495 the autoscaler never scales a slice below 3
  (its floor is `max(minInstances, 3)`), so a slice deployed with 3 instances from the CLI stays at 3.
- The A/B variant writes a single instance, where `ceil(1/2)` is 1, so its floor is unchanged in value; that is
  the known exception to #1495's runtime floor (#1721). A rollback with no prior target writes 3 instances
  (#1495), with the default floor of 2.
