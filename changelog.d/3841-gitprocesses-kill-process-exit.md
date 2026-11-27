### Fixed (2026-11-27 — #3841: `GitProcesses.kill` returned true while a root git process still read alive)
- **`kill` answered `true` on the exit of each root's handle, but `Process.isAlive()` of that root could still read `true`.** The JDK completes a
  handle's `onExit` and the `Process`'s own exit in separate stages, and the handle's can be seen first (2 of 12000 kills with a real `sleep`, six JVMs
  in parallel; 0 of 12000 when waiting on the `Process`). The stop and the timed-out git path act on `true` as "no git of the tree runs". `kill` now also
  waits for every root's `Process` to report exited within the same bound, so `true` means each root's `isAlive()` is `false`.
  [verified: `GitProcessesTest.kill_isTrueOnlyOnceTheRootProcessItselfReadsExited`] [mechanism: `GitProcesses.awaitExited`]
