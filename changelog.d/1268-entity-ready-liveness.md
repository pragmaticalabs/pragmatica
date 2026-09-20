### Fixed (2026-09-19 — #1268: EntityFold.ready() could return null or hang forever, wedging an entity key)
- **`EntityFold.ready()` could return `null`, and could hand out a promise that nothing would ever
  resolve.** Both wedged an entity key permanently, and the second could wedge the whole partition.
  - The `null` came from the CAS loser re-reading the rebuild slot after a failed rebuild had cleared it.
  - The unresolved promise came from a synchronous throw out of the rebuild, which left the slot holding
    a promise with no completion attached.
  - Separately, the per-key executor let a throwing operation escape its launch task, so that operation's
    promise never resolved and every later operation on the key hung behind it.
- `ready()` now re-enters on a lost CAS and lifts the rebuild, as #701 already did for `caughtUp()`. The
  executor now lifts a throwing operation into a failed promise.
  [mechanism: every exit from `ready()` now returns a promise completed by `completeRebuild`, and
  `PerKeySerialExecutor.launch` resolves its target from a lifted result] — pinned by unit tests in
  `EntityFoldReadyLivenessTest` and `PerKeySerialExecutorLaunchTest`; there is no live-path test.
