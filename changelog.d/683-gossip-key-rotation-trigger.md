### Added (2026-09-14 — #683: the emergency gossip-key rotation can now be invoked — `POST /api/v1/cluster/gossip-key/rotate` and `aether cluster rotate-gossip-key`)
- **The consumer existed, the producer did not.** Every node subscribes to `GossipKeyRotationKey`
  (`GossipKeyRotationHandler` → `RotatingGossipEncryptor.rotate`, idempotent, replayed to a late joiner once the
  consensus engine activates), and the design comment called it "the sole delivery path" — but nothing in production
  wrote the record, no CLI command and no route existed. The only in-place mitigation for a leaked
  `cluster_secret`/gossip key was therefore unreachable [mechanism: producers of `GossipKeyRotationKey` outside
  tests: 0 (`aether/`, `integrations/`, `/test/` and `/target/` excluded); consumer wiring at `AetherNode` `.onPut`].
- `GossipKeyRoutes` (new `RouteSource`, registered in `ManagementServer`) serves `ManagementRoute.CLUSTER_GOSSIP_KEY_ROTATE`
  — LEADER-routed, an exact ADMIN row in `ManagementRoutePermissions.adminRoutes()`. One consensus Put per call: 32
  fresh `SecureRandom` bytes, `currentKeyId = previous + 1`, the previous key and id carried for the decrypt overlap;
  the response and the log carry ids only
  [verified: `aether/node/src/test/java/org/pragmatica/aether/api/routes/GossipKeyRoutesTest.java` — first rotation
  (id 1, 32 bytes, no previous), second rotation (id 2, previous = first key), exactly one Put each, and 0 log lines
  containing the key with the `keyId=` line as the positive control; removing the Put reddens three tests, logging the
  key reddens the fourth].
  Consumer side unchanged and already pinned: `GossipKeyRotationHandlerTest#rotation_deliveredViaReplay_lateJoinerDecryptsClusterTraffic`,
  `#liveRotationPut_adoptsRotatedKey`; `RotatingGossipEncryptorTest#rotate_dualKey_decryptsBothOldAndNew`.
- **The Put is fenced and confirmed (#683 round 2).** `GossipKeyRotationValue` is now `VersionFenced` on
  `currentKeyId`, and the route confirms the write LANDED by re-reading committed state. Without the fence, two
  concurrent ADMIN rotations — or one CLI retry after a client-side timeout, realistic on an emergency path — both
  derive `currentKeyId = N+1` from the same read with DIFFERENT key bytes; consensus orders them so the cluster
  converges, but in the window a peer holding key A under id N+1 receives a datagram encrypted with key B under the
  same id, so `resolveKey` SUCCEEDS and GCM tag verification then fails. The confirmation is semantic — comparing the
  key, not the id, because racing writers derive the same id — and a fenced-out rotation is reported rather than
  returned as a success for a write that did nothing
  [verified: `GossipKeyRoutesTest#concurrentRotation_isFencedOut_andReportedRatherThanSilentlyLost`; removing the
  `VersionFenced` marker or the confirmation reddens it. `SystemCodecPinningTest` (4 tests) confirms the marker does
  not disturb the pinned wire tag 1622].
- `aether cluster rotate-gossip-key` (`ClusterRotateGossipKeyCommand`, no options) posts to the route and prints the
  ids; documented in `cli.md`, the route in `management-api.md`.
- **SECURITY.md states two facts, not design notes:** the delivered key material lives in the consensus log and its
  snapshots, readable by any KV reader (accepted by the §5.8 design: every KV reader is already a full member, and a
  per-node out-of-band channel would need a second trust root); and after the first rotation the
  `cluster_secret`-derived daily key is permanently superseded on that cluster.
- **Two corrections to claims this PR made in round 1**, both traced to their producers:
  - The late-joiner replay does **not** precede the joiner's first SWIM datagram. SWIM starts on QUIC
    transport-ready (`clusterNode.network().whenReady(startSwimTrigger)`), deliberately before `startClusterAsync()`
    resolves, while the replay is reached only via restore → activate → replay inside the consensus engine. The claim
    is true of KV notification ordering and false of SWIM datagram ordering, and it was the safety argument for late
    joiners [mechanism: `AetherNode` boot ordering; `RabiaEngine.replayStateNotifications`].
  - The over-length permission row is held by `RoutePermissionRegistry`'s DENY-BY-DEFAULT, not by #1101:
    `/api/v1/cluster` appears in neither the ADMIN nor the OPERATOR prefix list (0 occurrences; positive control — 22
    quoted `/api/v1/` prefixes present in that file), so `resolveMutationPermission` returns `ADMIN_ONLY`. The
    protection is doubly held, but `GossipKeyRotatePermissionTest` stays green with #1101's arm removed and therefore
    cannot attest to it; `LengthenedPathPermissionMatrixTest` is the instrument that does.
- Not changed: the boot-only derivation of the "daily" key, filed as #1164.
  `[unverified: no multi-node run; the route is exercised over a mocked ManageableNode and an in-process KVStore]`
