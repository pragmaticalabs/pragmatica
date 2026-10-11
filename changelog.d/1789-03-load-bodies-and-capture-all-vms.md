### Added (2026-10-01 — integration harness: attribute 03 Scale_down's 503s)
- **The load generator records each non-2xx response's body.** One line per failure (UTC time, status, endpoint, body up
  to 512 bytes) goes to `failure-logs/<suite>/load-failures-<test>.log`, and `stop_load` logs a per-detail histogram
  beside the status one, so the 503 branch that answered ("Quorum disappeared", "Route table propagating", a transient
  cause) is visible. At S-triple-prime 12 of 482 requests failed and only the status survived.
- **The cloud capture covers every VM a suite touched.** The fail-time and suite-end captures enumerated only the
  provider's current VMs, so a scale-down victim (core-4, the node the load was pinned to) was missing from the
  manifest. A per-suite VM registry (reset at suite start, filled at suite start, every test start and every
  capture) is captured too; a remembered VM the provider no longer lists is still tried with a short bound and
  marked `remembered-not-listed`, or `GONE` when it does not answer.
- **03 Scale_down records who left and whether the load target was among them.** One INFO line per scale-down step
  (members before/after, removed nodes, the load target and its node id, "load target was a victim: yes/no/unknown").
  Recording only. An opt-in `SCALE_LOAD_TARGET_VICTIM=1` (default off, a no-op) re-aims the running load at a node
  that leaves the voter set, to reproduce the defect on demand.
  [verified: `aether/tests/integration/test/test-load-bodies-and-vm-capture.sh` (6 stub tests) and
  `test-scale-down-victims.sh` (6); dropping the body
  capture reddens 2, ignoring the registry reddens 2; always-"no" victim logging, the flag ignored, the retarget
  removed and the load loop ignoring the override each redden their pin. No cloud run.]
