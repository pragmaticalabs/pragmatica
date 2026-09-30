### Changed (2026-09-29 — #1497: deploy, A/B and rollback wrote minAvailable == instances)
- **CLI/REST deploy, `DeploymentManagerImpl.addSliceTargetCommand`, A/B tests and rollback wrote a slice's
  first target with `minInstances` equal to its instance count.** The #1488 drain guard can never drain an
  owner of such a slice, so a surplus drain deferred forever.
- Every writer with no explicit value now uses the blueprint default, `ceil(instances/2)`, through one
  function, `SliceTargetValue.defaultMinInstances`; the two creation factories without an explicit floor use
  it too. An explicit floor is honoured unchanged.
  `[mechanism: SliceRoutes.applyDeployCommand, AbTestManager.newSliceTarget and the defaulting factories used by DeploymentManagerImpl and RollbackManager all call defaultMinInstances; pinned by SliceRoutesScaleFloorTest, SliceTargetDefaultFloorTest and SliceTargetValueTest]`
- Known cost (owner-accepted): `minInstances` is also the autoscaler's floor, so a slice deployed with 3
  instances from the CLI can now scale down to 2.
- The A/B variant and a rollback with no prior target both write a single instance, where `ceil(1/2)` is
  1, so their floor is unchanged in value. A single-instance slice still cannot be drained without going dark.
