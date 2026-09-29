### Fixed (2026-09-29 — #1188: `Promise.withResult`'s ordering guarantee was unstated and misread)
- **`withResult` is a dependent step: its action runs BEFORE the returned promise resolves**, so every
  step chained on that promise observes the action's effects. The converse was being assumed: a latch
  released by the action does not imply the returned promise is resolved. The Javadoc now states both
  directions and says to chain on, or await, the returned promise instead. No behaviour change; the
  test that relied on the converse was corrected in #1605.
  [verified: `core/src/test/java/org/pragmatica/lang/PromiseTest.java`
  `withResult_runsTheActionBeforeTheReturnedPromiseResolves`, `withResult_chainedStepAlwaysObservesTheActionsEffect`]
