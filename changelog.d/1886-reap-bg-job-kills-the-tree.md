### Fixed (2026-10-05 — #1886: `reap_bg_job` could leave a background job's child running, failing `stub-suites` at random)
- **`reap_bg_job` (integration harness, `lib/common.sh`) now kills the job's whole process tree.** It used to kill the
  job's direct children and then the job, which left two survivors: a child the job respawned between those two kills
  (a `while :; do sleep 1; done` loop that ignores SIGTERM does this), and any grandchild. The survivor outlived the
  reap, so `test-load-bodies-and-vm-capture.sh` (whose last case reaps such a job) failed the `stub-suites` leak check
  in about one run in five while every assertion passed, and a real `03-scale-down` reap of the demotion capture could
  leave its ssh behind. Each process is now stopped before its children are listed and killed after them, so nothing
  can be respawned or orphaned in between. The suite now counts a reaped job's surviving children and grandchildren
  (D9, D9b) instead of relying on the harness to notice them, and its hanging-ssh stub is a single process.
