### Fixed (2026-09-18 — #1228: a suite asserting first-time behaviour was handed a fourth-time database)
- **`10-database` failed on every cloud run, and the server was correct every time.** The baseline
  endpoint answered `409 Conflict — versioned migrations already applied up to version 900`, which is
  exactly what it should do for an already-baselined datasource. `Schema_status_after_baseline` passed
  with `COMPLETED` in the same run; only the baseline call failed, because it had already happened.
- **The Hetzner PG VM is long-lived and shared, and `ensure_cloud_pg_database` creates only when
  absent** — so migration state accumulated in it indefinitely. The first run that ever baselined that
  database succeeded and every run since inherited its result. Measured 2p/1f on four consecutive runs
  across both container and JVM runtimes: stable, runtime-independent, and not a product defect.
- `reset_cloud_pg_database` drops and recreates the database before bootstrap, when no slice holds a
  connection, so the suite gets the first-time database it asserts against. It is **guarded to names
  ending `_testpersistence`** because it issues a `DROP DATABASE`; verified in both directions —
  the fixture name proceeds, while `postgres`, `aether_forge` and an injection attempt are refused
  before any SSH is attempted.
- Accepting the 409 as a pass was rejected: it would make the assertion vacuous, passing whether or
  not baseline works. That is the same shape this repo has already fixed twice in assertions matching
  a field name or an echoed value.
