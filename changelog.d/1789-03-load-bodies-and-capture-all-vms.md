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
  [verified: `aether/tests/integration/test/test-load-bodies-and-vm-capture.sh` (6 stub tests); dropping the body
  capture reddens 2, ignoring the registry reddens 2. No cloud run.]
