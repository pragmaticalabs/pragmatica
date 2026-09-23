### Fixed (2026-09-23 — #1442: `integrations/consensus` now passes the format/lint gate CI has never run on it)

- **`integrations/consensus` was failing `jbct:check` on 4 format issues and 3 lint errors, and
  nothing reported it.** The root pom sets `jbct.skip=true`, `aether/pom.xml` overrides it to
  `false`, and no module under `integrations/` or `core/` overrides anything — so the gate skips
  these modules silently and exits green. Only an agent invoking it locally with
  `-Djbct.skip=false` ever sees the result. This is #1442's abstract claim with a concrete instance
  attached; **#1442 itself stays open**, because the defect is the CI configuration, not these files.
- Gate result for the module: **69 files checked, 0 format issues, 0 lint errors** (was 4 and 3).
  309 warnings remain and are non-fatal — deliberately untouched, since the gate does not fail on
  them and sweeping 309 of them is a different change.
- The three lint errors were **idiom violations, not latent bugs**, and are worth naming as such
  rather than dressed up:
  - `QuicClusterClient.initiateConnection` and `QuicClusterNetwork.evictStaleConnection`
    (`JBCT-RET-06`) null-checked a parameter in place. Both now wrap at the boundary with
    `Option.option(...)`, which is what the rule asks for. Behaviour is identical in both: the same
    conditions still refuse the dial, and the same `isActive()` guard still gates the close. No
    signature changed, so no call site moved.
  - `RabiaEngine.stop` (`JBCT-RET-07`) dropped a `Result` in a statement. Its failure *was* already
    handled — routed into `stoppedCompletion` so `stop()` settles instead of hanging — so the fix
    consumes the chain in a named `submitStop()` rather than changing what happens. Shutdown
    semantics are unchanged.
- The four format fixes are whitespace and line-joining only.
