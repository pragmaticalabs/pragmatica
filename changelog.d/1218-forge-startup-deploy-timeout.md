### Fixed (2026-10-04 — #1218: Forge's startup blueprint deploy ignored `start_timeout_seconds`)
- **`aether-forge --blueprint` bounded the startup deploy by a hardcoded 10 s**, which the documented
  `cluster.start_timeout_seconds` did not cover, so raising it on a slow host changed the formation wait
  and left the step that actually timed out alone. The deploy now shares that budget, and the timeout
  failure names the budget and its setting. The fail-closed exit on a failed deploy is unchanged.
