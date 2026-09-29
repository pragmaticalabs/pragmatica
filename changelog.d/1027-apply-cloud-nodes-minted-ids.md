### Fixed (2026-09-29 — #1027: node ids on the `cluster apply` cloud path)
- **`cluster apply` now mints each cloud node's id itself, in the auto-heal scheme (`aether-<cluster>-node-<ulid>`),
  and puts the same id into the provisioning context and the node's user-data.** Before, the provider minted an id
  the CLI never saw, so no user-data could carry it (#1695).
  The alternative the ticket proposed, threading the per-call `<source>-<role>-<i>` index, restarts at 0 on every
  wave. It would have given a scale-up or reprovisioned node the id of a LIVE member, which is the duplicate-identity
  defect itself.
- **Known limitation:** a minted id does not parse as `<source>-<role>-<i>`, exactly as an auto-heal replacement's
  does not. Rolling reprovision addresses nodes by that shape, so it does not reach apply-minted nodes. The durable,
  parseable scheme (a committed per-(source, role) high-water, reserved before provisioning) is tracked for rc5.
- [mechanism: `WaveNodeProvisioning.mintedIds` mints a fresh ULID-based id per node. Pinned by
  `WaveExecutorCloudProvisioningTest.provisionCloudNodes_twoScaleUpWaves_neverReuseAnId` and
  `…_reprovision_getsAFreshIdCarriedIntoTheUserData`]
