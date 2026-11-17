### Changed (2026-11-11 — #3016: GitProcesses: pin the production stop-confirmation wiring and the interrupt during awaitStopped)
- Test-only. Two #2979 survivors are now pinned, no production line changed.
- `kill(processes, bound, freeze)` confirms a stop with the real `/proc` check: a freeze that stops nothing holds the first round to the freeze's half of the bound `[verified: GitProcessesTest#kill_waitsForTheStopToBeConfirmed_byTheProductionCheck]`.
- An interrupt that lands during the stop wait is recorded and the caller's flag is set again `[verified: GitProcessesTest#kill_interruptedDuringTheStopWait_keepsTheFlag]`.
