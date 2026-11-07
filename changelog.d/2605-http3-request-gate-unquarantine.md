### Changed (2026-11-07 — #2605: Http3RequestGateWireTest quarantined methods flaky in CI)
- **Two `Http3RequestGateWireTest` methods were quarantined** for an unexplained CI-only failure; the mechanism is the FIN-only frame loss fixed by #2746, so the quarantine is lifted. `[mechanism: #2843 verifier, 1300/1300 green with the fix, 1 in 1300 failing at the base]`
