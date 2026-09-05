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
- [verified: `DhtStorageTierTest$Admission` (7 tests: pending-gate timeout and immediate-refusal
  cases for `get`/`put`/`delete`/`exists`, including two that assert directly against the raw DHT
  client that a gated write never persists, whether it times out or is refused) — 7/7;
  `StorageFactoryEncryptionTest#verifyDhtMarker_resolvesReadGate_withRefusalCause_onProductionFailurePath`
  (drives the real no-keyring-refusal path through `StorageFactory.verifyDhtMarker` and reads
  `readGate()` directly, without resolving it by hand) — 1/1;
  `aether/aether-storage` full module suite — 30/30;
  `aether/node` full module suite — 12900/12900 across the reactor;
  `mvn jbct:check -pl aether/aether-storage,aether/node` — 0 format issues, 0 lint errors on both
  touched modules]
- **What is NOT covered:** `AetherNode.start()`'s startup ordering is unchanged — `managementServer`/
  `appHttpServer` still come up before `verifyDhtMarkers()` resolves; protection now comes from gating
  every DHT-tier operation rather than delaying HTTP-surface startup, which was weighed and rejected
  because it would delay unrelated, non-DHT HTTP traffic (health checks, other endpoints) for up to the
  full 30 s bound. `Promise.allOfOrCancel` in `StorageFactory.verifyDhtMarkers` (plural) cancels
  sibling in-flight marker checks on the first failure; the exact cancellation cause a cancelled
  sibling's `readGate` now resolves with is a strict improvement over the pre-#875 hang but its precise
  type is unverified here.
