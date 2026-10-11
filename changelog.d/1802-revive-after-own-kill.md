### Fixed (2026-10-01 — integration harness: a cleanup revive of a VM the run itself deleted counted two FAILs)
- **`cloud_kill_vm` records each VM it deletes** (node id and hetzner server id, per run, removed on exit), and
  `cloud_revive_vm` (hence `start_node` in cloud mode) treats a node this run deleted as an INFO no-op ("restored by the
  baseline scale-up, not revived") returning 0. After a delete the node's address no longer resolves, so the revive's
  `cloud_server_id` was rc 3 (unknown) and 02w's cleanup recorded two counted FAILs for the harness's own deletion (cloud run
  6, a passing 10/0 suite). Any other unresolvable node still fails loudly.
  [verified: `aether/tests/integration/test/test-chaos-harness.sh` KR1-KR5. No cloud run.]
