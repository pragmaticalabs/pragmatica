### Fixed (2026-10-01 — integration harness: a lookup miss counted a FAIL no log line showed)
- **`cloud_public_ip` and `cloud_server_id` reported a lookup miss through a counted `log_fail` on the stdout their callers
  capture with `$(...)`.** The `[FAIL]` text went into the caller's variable while the #1511 fail file still counted it, so
  `run_test` recorded FAIL with no `[FAIL]` line in the log (cloud run 4: 02-chaos S19 twice, 12-network S05 twice; in S05
  the swallowed text even became a firewall-detach `--server` argument). A miss is now nothing on stdout, a `log_warn` on
  stderr and rc 1; argument errors stay loud. Every capture site handles the miss itself (audit in the PR).
- **S19 tier 2 reads the survivor's drain state at the pre-kill cached address** (`cloud_ssh_ip`, factored out of `cloud_ssh`),
  so a CTM-replacement survivor gets the authoritative exit-code read instead of the approximate tier-3 proof. An
  unresolvable address says "IP unresolvable — SSH not attempted" instead of blaming an SSH lockout that never happened.
  `_s19_resolve_survivor_ip` no longer prints its WARN on the stdout it is captured from.
- **`cloud_heal_partition` detaches from the servers named by the firewall's own `applied_to`**, not via a node-to-IP lookup.
  `run_test` prints any counted `[FAIL]` it could not show.
- **`cloud_server_id` separates "gone" from "unknown".** rc 1 = the address is known and no server holds it (the only
  departed/deleted evidence); new rc 3 = the address could not be resolved or `hcloud server list` failed (says nothing
  about the VM). S19 tier 4, kill-multiple, `cloud_kill_vm` and `cloud_revive_vm` accept only rc 1 as gone; rc 3 is one
  visible counted FAIL. Without this, silencing the phantom count scored a still-running survivor as departed.
- **"No KV-writes after drain trigger" is a visible SKIP on cloud.** It returned 0 before asserting anything and was scored PASS
  (its "no docker/SSH on cloud" reason was false). No journalctl check is implemented.
  [verified: `aether/tests/integration/test/test-chaos-harness.sh` E9, `test-cloud-helpers.sh` P1-P8,
  `test-partition-heal-detach.sh` S1-S2, `test-chaos-harness.sh` G10, E9d-g, KV1-7; each pin reddens with its production hunk reverted. No cloud run.]
