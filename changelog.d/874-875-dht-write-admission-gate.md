### Fixed (2026-09-05 — #874: DHT writes could reach the tier before admission; #875: a refused admission check never resolved the gate)
- **A write reaching a DHT-backed storage instance during the marker-check window could persist
  plaintext into a namespace the check later finds already encrypted.** #858's admission latch
  (`DhtStorageTier.readGate`, resolved post-formation by `StorageFactory.verifyDhtMarker`) gated `get`
  only, reasoning only a read could observe a not-yet-verified namespace. That missed two things:
  `AetherNode.start()` brings `managementServer`/`appHttpServer` up *before* `verifyDhtMarkers()` runs,
  so an ungated `put` reachable over HTTP (e.g. a Maven `deploy`) could write ahead of the check; and
  `exists` is itself a DHT round trip, not a local, gate-free check. `put`, `delete` and `exists` are
  now gated on the same `readGate` as `get` — no operation can reach the DHT tier ahead of admission.
  Because the DHT is a cluster-wide shared store (`DhtStorageTier#isShared`), a write that landed ahead
  of the check would have replicated to peers and survived this node's own subsequent
  `EncryptedTierRequiresKeyring` abort; this closes that window without changing `AetherNode`'s startup
  ordering, since every DHT operation — not just HTTP-surface timing — is now the enforcement point.
- **A refused admission check left the gate unresolved, so a caller racing it waited the full 30 s
  `admissionTimeout` and saw the wrong error.** `StorageFactory.verifyDhtMarker` resolved `readGate`
  only on success (`.onSuccess(...)`); its failure branch (no keyring, marker already present) left
  `readGate` pending. A reader/writer arriving during that window timed out with
  `StorageError.TierNotAdmitted` instead of the real refusal cause
  (`EncryptionError.EncryptedTierRequiresKeyring`). The failure branch now resolves the same gate with
  the refusal cause, so a racing caller fails immediately with the real cause instead of waiting out
  the bound.
- `configuration.md`'s DHT-marker-timing section corrected: it previously claimed the check runs
  "before the node reports ready", which reads as if HTTP surfaces wait for it. They don't — what
  blocks during that window is every DHT-tier operation on the pending namespace, not the HTTP surface.
- **Round 1 (review) fix — the #875 regression test could not fail, it could only wedge.**
  `verifyDhtMarker_resolvesReadGate_withRefusalCause_onProductionFailurePath` ended in an unbounded
  `c.readGate().await()`; a regression on `StorageFactory.verifyDhtMarker`'s failure branch
  (dropping `check.readGate().resolve(Result.failure(cause))`) would hang the test forever instead of
  failing it — `readGate` is independent of `DhtStorageTier`'s `admissionTimeout` (that bound applies
  only to the `.map()`-derived promise inside `DhtStorageTier#admission`, never to `readGate` itself),
  so the comment claiming a 30 s production bound would eventually rescue it was false. Now bounded
  with the file's existing `SHORT_MARKER_TIMEOUT` (150 ms); the false comment is replaced with the
  correct mechanism. Proved by mutation: reverting the two-line fix makes the test fail in ~11s
  wall-clock with a real assertion (`CoreError.Timeout` where `EncryptionError.EncryptedTierRequiresKeyring`
  was expected), not a hang; restoring the fix returns it to a 0.28s pass.
- **Round 1 (review) fix — a gated write/read answered HTTP 500, not 503.**
  `StorageError.TierNotAdmitted` is not a `CoreError.Timeout`, so `MavenProtocolRoutes.sendFailureResponse`
  fell through to `internalError` (500) for a state that is transient by construction — telling
  `mvn deploy` "server bug, do not retry" instead of "retry me". `TierNotAdmitted` now maps to 503
  Service Unavailable with a `Retry-After: 1` header; every other cause is unaffected and still gets 500.
- [verified: `DhtStorageTierTest$Admission` (six `@Test` methods: pending-gate-timeout and
  immediate-refusal cases for `get`/`put`/`delete`/`exists`, including two that assert directly against
  the raw DHT client that a gated write never persists, whether it times out or is refused) — 6/6, read
  from surefire `<testcase>` counts (not the `.txt` summary, which undercounts `@Nested` classes);
  `StorageFactoryEncryptionTest#verifyDhtMarker_resolvesReadGate_withRefusalCause_onProductionFailurePath`
  (drives the real no-keyring-refusal path through `StorageFactory.verifyDhtMarker` and reads
  `readGate()` directly, without resolving it by hand; mutation-probed per above) — 1/1;
  `MavenProtocolRoutesTierNotAdmittedTest` (503+`Retry-After` for `TierNotAdmitted`, plain 500 with no
  `Retry-After` for every other cause) — 2/2;
  `aether/aether-storage` MODULE suite (full, `mvn -pl aether/aether-storage test`) — 30/30;
  `aether/node` MODULE suite (full, `mvn -pl aether/node test`, not a reactor total — no full-monorepo
  reactor run was performed this round since only `aether/node` sources changed) — 1138/1138, 1 skipped
  (pre-existing, unrelated to this change);
  `mvn jbct:check -pl aether/node -fae` — 0 format issues, 0 lint errors (aether-storage source is
  unchanged this round, not re-gated)]
- **What is NOT covered:** `AetherNode.start()`'s startup ordering is unchanged — `managementServer`/
  `appHttpServer` still come up before `verifyDhtMarkers()` resolves; protection now comes from gating
  every DHT-tier operation rather than delaying HTTP-surface startup, which was weighed and rejected
  because it would delay unrelated, non-DHT HTTP traffic (health checks, other endpoints) for up to the
  full 30 s bound. `Promise.allOfOrCancel` in `StorageFactory.verifyDhtMarkers` (plural) cancels
  sibling in-flight marker checks on the first failure; the exact cancellation cause a cancelled
  sibling's `readGate` now resolves with is a strict improvement over the pre-#875 hang but its precise
  type is unverified here. The `Retry-After: 1` value is a deliberately small, unmeasured hint
  [design intent — unverified], not a measured recovery-time bound.
