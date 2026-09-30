### Fixed (2026-09-30 — integration harness: cluster B had no testpersistence datasource, and the restore gate waited out a permanent hold)
- **Cluster B's env files declare `database.testpersistence`.** `cloud-hetzner-b.toml` and `cloud-hetzner-jvm-b.toml`
  lacked the `node_config` section cluster A has (#598), so test-persistence, deployed on B by 13-edge-cases, failed
  its schema migration ("Config section not found") and stayed LOADED forever. Both now point at a B-owned
  `${PG_DB}_testpersistence_b`, which `run-tests.sh` resets like A's; the drop guard accepts that name and no
  other new one. A static pin checks every datasource a test blueprint declares against every cloud env file.
- **`restore_cluster_baseline`'s slice gate fails fast on a FAILED schema migration.** A starved artifact held by
  a datasource whose `/api/v1/schema/status` status is FAILED with non-empty `heldSlices` fails at once, naming the
  datasource, migration and held slices, instead of waiting out the budget and printing only LOADED. A backtick
  bug in the gate's message (bash ran `version` as a command) is fixed.
  [verified: `test/test-env-database-sections.sh` (5) and `test/test-restore-slices-gate.sh` (20); deleting B's
  section reddens 3, dropping the schema probe reddens the timing assertion. No cloud run.]
