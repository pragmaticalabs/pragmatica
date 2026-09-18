### Fixed (2026-09-18 — #1227: CLOUD_MODE latched false on every `--env cloud` run)
- **`lib/common.sh` derives `CLOUD_MODE` from `ENV_TYPE` at SOURCE time, and `run-tests.sh` sources it
  before parsing arguments.** On `--env cloud` it therefore latched `"false"` from the default
  `ENV_TYPE=docker` and exported that to every suite subprocess, while `ENV_TYPE` was set to `"cloud"`
  moments later. `run-tests.sh` now re-derives and re-exports `CLOUD_MODE` after the argument loop,
  and logs the resolved pair so the dispatch is visible rather than assumed.
- **The harness ran half in each mode.** Six branch points use `CLOUD_MODE` (`kill_node`,
  `start_node`, `restart_all_nodes`, …) against two using `ENV_TYPE`, so a cloud run took cloud paths
  in some functions and docker paths in others. That is why `cloud_kill_vm` correctly deleted a VM
  while `restart_all_nodes` attempted a docker-compose cycle over SSH to the remote host.
- **Consequence measured on a cloud round at `23c9b532c`:** after `12-network`, the leader was briefly
  unreachable, `restore_cluster_baseline` escalated to `restart_all_nodes`, that ran the compose path,
  SSH to the remote host timed out (`rc=255`), and a cluster reporting `leader='hetzner-eu-core-3'`
  and `ready=4/5` — degraded, not dead — was declared unrecoverable. `03-scaling`, `02y-stream-crash`,
  `02w-entity-crash` and `02s-selfdrain` were hard-skipped. Half of cluster B was unreachable on cloud
  by construction, and the run reported it as a cluster failure rather than a dispatch failure.
- The cloud recovery model in `restart_all_nodes` was already implemented and correct; nothing ever
  reached it.
