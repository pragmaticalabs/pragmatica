### Fixed (2026-11-06 — #2749: `NodeReplacementRealRegistryReapTest.aForcedClear_retiresNothing_underTheLegacyBinding` read the cleared warning before its hand-off)
- **Test-only flake.** The operator-warning sink hands each warning to its own thread by design, so the
  `instance-termination-cleared` warning raised inside `clearUnconfirmed` can reach the test's list after the call
  returned. The test asserted it at once and failed when the hand-off thread had not run yet. It now waits for the
  warning, and a gate on the hand-off thread holds the warning until the clear has returned, so the late-arrival
  schedule is the one that always runs. No product change: the asynchrony is the sink's documented contract.
