### Fixed (2026-09-13 — #1053: cloud-JVM harness read docker state on a runtime with no docker daemon)

- **The 02-chaos self-drain suite (S19/S20) unconditionally ran `docker inspect` over SSH to confirm a
  survivor's drain halt**, which fails every time on `--runtime jvm` cloud (no docker daemon; the node
  runs as systemd unit `aether-node`) — so the suite could not judge the product on that runtime
  regardless of what it did. It now reads the systemd unit's `ActiveState`/`ExecMainStatus` on JVM
  runtime instead; the container-runtime path is unchanged.
  `[mechanism: aether/tests/integration/lib/common.sh — jvm_unit_show/jvm_unit_field/jvm_unit_exec_main_status_is_two, unit-tested via aether/tests/integration/test/test-cloud-helpers.sh]`
- **`Survivor_exit_codes_are_2` was recorded as PASS with zero assertions on all of cloud.** It now
  asserts `ExecMainStatus=2` on `--runtime jvm`; the container-runtime case, which is still genuinely
  unverifiable (no docker access from the test host), is now recorded as SKIPPED rather than a
  zero-assertion PASS.
- **Cloud S20 tried a partial-drain poweron + a 360s probe before ever considering full-drain recovery**,
  and advertised a 60s recovery budget it could not meet after a confirmed full self-drain. It now
  confirms the full drain honestly (0 active cores, distinguished from a merely-unreachable mgmt API)
  and goes straight to reap + rebootstrap, asserting recovery within a budget sized to the bootstrap's
  own provisioning timeout.
- **A pre-kill stream-failover check for a CAUGHT_UP non-owner replica ran once (~1s)**, failing on a
  replica whose backfill was still converging. It is now a bounded wait; a replica that never reaches
  CAUGHT_UP still fails the step.
- Added `_cluster_active_core_count_checked` (honest-read companion to `cluster_active_core_count`) and
  an optional `value_cmd` on `wait_for` so a timeout message states the last observed value instead of
  silently reporting nothing, and so a merely-unreachable mgmt API can no longer be mistaken for a
  confirmed low reading.
