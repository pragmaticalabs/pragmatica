### Added (2026-09-14 — #683: the emergency gossip-key rotation can now be invoked — `POST /api/v1/cluster/gossip-key/rotate` and `aether cluster rotate-gossip-key`)
- **The consumer existed, the producer did not.** Every node subscribes to `GossipKeyRotationKey`
  (`GossipKeyRotationHandler` → `RotatingGossipEncryptor.rotate`, idempotent, replayed to a late joiner before its
  first SWIM datagram), and the design comment called it "the sole delivery path" — but nothing in production
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
  key reddens the fourth. `aether/node/src/test/java/org/pragmatica/aether/api/GossipKeyRotatePermissionTest.java` —
  exact route resolves ADMIN, `/junk` appended is a routing miss and never resolves weaker (#1101 shape)].
  Consumer side unchanged and already pinned: `GossipKeyRotationHandlerTest#rotation_deliveredViaReplay_lateJoinerDecryptsClusterTraffic`,
  `#liveRotationPut_adoptsRotatedKey`; `RotatingGossipEncryptorTest#rotate_dualKey_decryptsBothOldAndNew`.
- `aether cluster rotate-gossip-key` (`ClusterRotateGossipKeyCommand`, no options) posts to the route and prints the
  ids; documented in `cli.md`, the route in `management-api.md`.
- **SECURITY.md states two facts, not design notes:** the delivered key material lives in the consensus log and its
  snapshots, readable by any KV reader (accepted by the §5.8 design: every KV reader is already a full member, and a
  per-node out-of-band channel would need a second trust root); and after the first rotation the
  `cluster_secret`-derived daily key is permanently superseded on that cluster (a rebooted node uses its boot-day
  key only until the record replays to it).
- Not changed: the boot-only derivation of the "daily" key, filed as #1164.
  `[unverified: no multi-node run; the route is exercised over a mocked ManageableNode and an in-process KVStore]`
