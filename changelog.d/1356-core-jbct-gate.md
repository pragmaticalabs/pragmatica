### Changed (2026-09-20 — #1356: core/ and jbct/slice-processor-tests brought to a clean `jbct:check` with `-Djbct.skip=false`)
- **`core/` had 4 format-dirty files (`Causes`, `Deadline`, `Promise`, `Verify`) and 10 lint errors,
  and `jbct/slice-processor-tests` had 5 format-dirty files, and no gate ever saw them**: both trees
  inherit `jbct.skip=true` from the root pom, so a direct `mvn jbct:check -pl core` skipped silently
  and exited green, and a lifecycle build never runs `check` there. All 9 files are formatted by the
  rc4 plugin. Of the 10 lint errors, 2 are fixed (`CancellableTask.cancelIfPresent` goes through
  `Option`; `Idempotency.cleanupExpiredEntries` returns `Unit`) and 8 are waived at the site with a
  one-line reason: `Option.option(T)` and `Verify.notNull`/`blank` ARE the null boundary;
  `Option.from`/`toOptional` ARE the `java.util.Optional` interop boundary (the import is dropped for a
  fully-qualified use, because a `JBCT-BND-01` import diagnostic sits outside every suppressible
  scope); `Promise`'s three `Completion` list walkers use `null` as the intrusive-list terminator on
  the completion hot path. Measured with `-Djbct.skip=false`: core `Running JBCT check on 62 Java
  file(s)`, 10 → 0 lint errors, 4 → 0 format issues; slice-processor-tests `47 Java file(s)`, 5 → 0.
- **Not changed here:** `jbct.skip` stays `true` at the root. Flipping it would gate `core/`,
  `integrations/` (including `swim`) and `jbct/` on the next direct `jbct:check`, but NOT the
  test-only modules (`jbct.includeTests=false` — the gate modules themselves stay ungated) and NOT a
  lifecycle build (`check` is not lifecycle-bound); `integrations/` has not been measured and is not
  known clean. That flip is a per-tree decision (#1356 item 3), separate from this fix-as-found.
