### Fixed (2026-09-08 — #957: threshold alerts were evaluated only while a dashboard client was connected, and the webhook forwarder was never constructed)
- **Threshold evaluation was gated on having a UI client.** `DashboardMetricsPublisher` only
  evaluated thresholds while a dashboard was connected, so an unattended cluster — the case alerting
  exists for — raised nothing at all. Evaluation now runs independently of any connected client.
- **`AlertForwarder` was never constructed in production code.** It existed, consumed a real config
  and had passing unit tests, but nothing built it, so no webhook was ever delivered. It is now
  constructed and bound at node start, and the binding is pinned by a bytecode-reachability test
  rather than by a unit test that could pass against a never-instantiated component.
- **`ThresholdAlert` was constructed nowhere under `src/main`.** The alert value existed only in
  tests. It is now raised on the real evaluation path.
- **Sustained breaches raise once, not once per evaluation cycle**, so a threshold that stays
  breached no longer floods the operator.
- **`X-Aether-Served-By` is propagated on forwarded management responses**, and forwarded framing
  headers are skipped when a management response is re-written — previously the re-write could emit
  a response carrying another node's framing.
