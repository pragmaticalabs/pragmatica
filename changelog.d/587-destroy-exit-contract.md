### Fixed (2026-09-13 — #587: `cluster destroy` exited 1 on a fully clean account)
- **A drain or shutdown failure followed by a COMPLETE cloud cleanup exited `ExitCode.ERROR` with the
  registry entry already removed.** #521's contract — non-zero + entry KEPT — is the retry signal for a
  cleanup failure; applied to drain failures it told scripts to re-run a destroy that had nothing left
  to do and could not even find the cluster. `printSummary` now exits `0` once cleanup is complete and
  the entry is gone, and reports the failures by node instead of swallowing them into an exit code:
  `Warning: 2 of 3 drain operations failed (core-2, core-3) before the VMs were deleted; cloud cleanup
  is complete and nothing is left to retry.` Cleanup failure is unchanged: entry kept,
  `CLEANUP_FAILED`. `cli.md` states the contract: **non-zero ⇔ the registry entry was kept ⇔ a re-run
  has work to do.**
  [verified: `aether/cli/src/test/java/org/pragmatica/aether/cli/cluster/ClusterDestroyExitContractTest.java`
  — drain-failed and shutdown-failed with complete cleanup exit 0 and name the nodes; cleanup failure
  still keeps the entry and exits `CLEANUP_FAILED`; a clean run prints no warning]
- **The ticket's silent drain phase (1) was already fixed** — `drainAllNodes` prints each node's drain
  start, decommissioned/timeout outcome, and the phase ceiling (#995/#1023 rounds). Nothing changed.
- **The disruption budget refusing a terminal teardown (2) is NOT changed here.** It is tracked as
  #1032 as a design gap (a teardown-scoped intent the budget honours), and draining below the quorum
  floor has no leader left to carry the drain, so a bypass alone would not produce a graceful drain.
  Deliberately left to that ticket. [design intent — unverified]
