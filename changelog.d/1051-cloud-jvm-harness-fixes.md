### Fixed (2026-09-13 — #1051: 02-chaos cannot judge the product on cloud --runtime jvm)

- **The 02-chaos self-drain suite (S19) confirmed a survivor's drain halt with `docker inspect` over SSH**, which
  fails every time on cloud `--runtime jvm` (no docker daemon; the node runs as systemd unit `aether-node`). It now
  reads the unit's `ActiveState`/`ExecMainStatus` on that runtime; the container-runtime read is unchanged. A cloud
  run without `CLOUD_RUNTIME` is refused rather than defaulted to the container read.
  `[mechanism: aether/tests/integration/lib/common.sh jvm_unit_is_drain_halt / jvm_unit_assert_drain_halt; decision logic stub-tested by aether/tests/integration/test/test-cloud-helpers.sh and test/test-chaos-harness.sh]`
- **`Survivor_exit_codes_are_2` was recorded as PASS with zero assertions on all of cloud.** On `--runtime jvm` it
  now asserts the drain-halt signature (`ActiveState=failed`, `ExecMainStatus=2`). Where there is nothing to read —
  cloud `--runtime container`, or a run whose race arbitration found quorum held, so no survivor drained — it is
  recorded as SKIPPED.
  `[mechanism: _s19_exit_code_disposition in aether/tests/integration/suites/02-chaos/test-self-drain-quorum-loss.sh; stub-tested by test/test-chaos-harness.sh]`
- **Cloud S20 could not recover a full self-drain within the budget it stated.** It now confirms the drain from
  positive per-VM evidence over every VM carrying the cluster's `aether-cluster` label (the set the reap deletes): no
  VM may be running or unreadable, and at least one must be positively drain-halted (`aether-node` loaded with
  `ActiveState=failed` or `ExecMainStatus=2`, or an exited container). VMs whose unit is stopped or not loaded, or
  that the provider reports not found, count only beside a halted one, so a cluster still bootstrapping is never
  reaped. A failed or empty enumeration refuses the reap, as does a VM that an earlier listing showed and a later one
  omits unless the provider reports it deleted; a VM counts as deleted only on hcloud's exact
  `hcloud: Server not found: <id>` response. Management-API silence is never taken as death: the same confirmation
  now gates every recovery reap in the harness, including `restart_all_nodes`' candidates (0 active cores, silent
  management ports, stragglers, no progress), which the suite's cleanup reaches. End-of-run teardown, which deletes
  the run's clusters by design, is unchanged. It
  then reaps and rebootstraps and asserts 5 healthy cores within 600s measured from the start of confirmation (not
  scaled by `TIMEOUT_SCALE`), printing the elapsed time and the budget, followed by the standard recovery path's
  leader, readiness and test-echo baseline barriers.
  `[design intent — unverified]` on a live cloud cluster; the decision logic is stub-tested by
  `test/test-chaos-harness.sh`.
- **A pre-kill stream-failover check for a CAUGHT_UP non-owner replica ran once (~1s)**, failing on a replica still
  converging. It is now a bounded wait that judges only owner-authoritative views and targets the owner named by the
  view it judged; a replica that never reaches CAUGHT_UP still fails the step.
  `[mechanism: test_identify_owner_and_caught_up_replica in aether/tests/integration/suites/02-chaos/test-stream-replica-failover.sh; stub-tested by test/test-chaos-harness.sh]`
- `_cluster_active_core_count_checked` reads the topology once and returns a non-zero status, never a number, when
  the read fails or does not parse. `wait_for` takes an optional value reader: one read per poll, the predicate is not
  evaluated on a failed read, and the timeout message states the last value read or that the read failed.
  `[mechanism: aether/tests/integration/lib/cluster.sh and lib/common.sh; stub-tested by test/test-chaos-harness.sh]`
- **The harness watchdog stood down when only the harness PID died**, leaving the hung case's group running — the
  shape of the 2,342-process leak under a top-PID-only driver. It now kills its process group whenever the harness
  is gone, not only at the deadline. The `stub-suites` CI job cap rises from 15 to 25 minutes: three suites each
  wedged at the 300s ceiling cost ~15.4 min with the kill grace, which exceeded the old cap.
  `[verified: G5 in aether/tests/integration/test/test-harness-guards.sh — reds with left=2 when the watchdog's group kill on a gone PID is reverted, greens with it]`
- **The watchdog's `sleep` tick outlived the harness on every normal exit** — the exit trap killed the watchdog,
  its `sleep 1` child stayed orphaned in the harness's group for up to a second, and the stub-suite runner in CI
  read that as a leaked process (a coin-flip on each run). The tick is now a fifo read with no child process.
  `[verified: G6 in test-harness-guards.sh reads the group at the instant of a normal exit — left=1 with the sleep tick, left=0 with the fifo tick]`
