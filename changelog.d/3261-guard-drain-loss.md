### Fixed (2026-11-14 — #3261: EngineMarkerGuard lost drained work across a close and reopen)
- **A guard that closed and reopened before its opener ran the drained work discarded that work.** The second drain replaced the pending drained actions instead of appending to them, so an activation or deactivation parked before the first opening could never run.
- `drain()` now appends the newly parked actions behind any drained work still pending; each action runs exactly once. [verified: aether/node EngineMarkerGuardTest#aCloseAndReopenWhileTheOpenerWaitsForTheLock_neverLosesDrainedWork]
