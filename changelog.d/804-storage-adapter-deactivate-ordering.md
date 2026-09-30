### Fixed (2026-09-29 — #804: DelegatedStorageAdapter reported inactive regardless of whether its managers stopped)
- **`deactivate()` flipped the adapter to inactive before, and regardless of, the demotion manager's and
  garbage collector's own deactivation.** A manager whose deactivation failed left the adapter reporting
  inactive while a maintenance pass could still be scheduled or running. The adapter now reports inactive
  only once both managers deactivated; a failure keeps it active, is logged at WARN with the cause, and a
  later `deactivate()` retries.
- **A partial activation left one manager running behind an inactive adapter.** When the first manager
  started and the second failed, the first was never stopped, and no later `deactivate()` could reach it.
  It is now stopped before `activate()` returns. Both paths are unreachable with today's managers, which
  always succeed; this closes them before a failing implementation lands.
  [verified: `aether/aether-storage/src/test/java/org/pragmatica/aether/storage/DelegatedStorageAdapterTest.java`]
