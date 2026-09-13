### Fixed (2026-09-13 — #1069: Heavy 5→7 scale probes sent the pre-#581 scale body and reported the HTTP 400 as a #336 stall)
- **`ScaleUpFiveToSevenProbeTest` and `ArtifactChurnSurvival5to7to5ProbeTest` still posted `{coreCount, expectedVersion}`** to
  `POST /api/v1/cluster/scale`. Since #581 the request is `ScaleRequest(source, role, count, expectedVersion)`, so the server
  refused every call with HTTP 400 `Type mismatch: expected int, got unknown`. Neither probe read the status: the rejected
  call left the configured count at 5, and the probes waited out their budget and reported a "5→7 stall … #336 reproducing
  IN-JVM". Every conclusion about #336/#467 drawn from them since #581 was measuring a refused HTTP call.
- Both probes now send `{source:"", role:"core", count, expectedVersion}` (the shape `PostRestartSlowRejoinDeficitFillProbeTest`
  already sends) and fail before any membership wait unless the response is 2xx, reports the requested `newCount`, and
  reports `configVersion` one past the fencing version it was sent. A transport failure also fails immediately.
- **`03-scaling/test-01-quorum-safety.sh` had the same stale body and passed without testing anything.** Its three rejection
  tests sent `{coreCount}` and accepted any `>= 400`, so the decoder's 400 satisfied them before the quorum or max check ran.
  They now send `{role, count, expectedVersion}` and require the refusal detail to name the validator's own message
  (`Quorum safety violation`, `Invalid core max`). The above-max case moves from 20 to 21, because 20 is even and the
  validator refuses it as `Invalid core count` before it compares against `coreMax`.
- `cluster-management-spec.md` documented a `{core_count, expected_version}` request and a 400 for quorum violations. It now
  shows the current request and response and the statuses the route actually returns (quorum violation is 409).
