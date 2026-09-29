### Fixed (2026-09-29 — cloud-init enabled the node unit, so a rebooted VM relaunched under a removed node id)
- **The JVM cloud-init ran `systemctl enable --now aether-node.service`.** That linked the unit into
  `multi-user.target` with a node id fixed per VM. A rebooted VM therefore relaunched under an id the cluster had
  already removed (membership is terminal-removal). The rejoin was refused, and the VM billed without ever joining.
  This is the same-id hazard behind #1467 and #1543.
  The unit is now started, never enabled; after a reboot, CTM auto-heal replaces the node under a fresh id. The
  container path already ran `docker run --restart no`.
  `deployment-recovery.md` §2.3 and §4.4 now say the same thing: a host-level unit must not start on boot while the
  node id is fixed per host. §2.3 used to permit bringing "the same VM back" with a boot-time unit. (The manual runbook
  already stopped enabling the unit in #1709.)
- [mechanism: `NodeUserDataRenderer.appendJvmUnit`; pinned by
  `UserDataTemplatePeersTest.JvmMode.render_launchesTheJvmUnderASystemdUnit_thatDoesNotRestartIt`. That test used to
  assert `enable --now` with the reason "so a host reboot brings it back"; it asserted the defect as the spec, and
  now asserts the opposite]
