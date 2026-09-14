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
- **SECURITY.md states three facts, not design notes:** the delivered key material lives in the consensus log and its
  snapshots, readable by any KV reader (accepted by the §5.8 design: every KV reader is already a full member, and a
  per-node out-of-band channel would need a second trust root); the first rotation carries no decrypt overlap; and a
  rotated cluster cannot grow until an operator acts, auto-heal included.
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
- **A rotated cluster cannot GROW until an operator acts, and a boot that cannot join now refuses
  instead of sitting silent (#683 round 2).** Rotation replaces the live encryptor's accept set with
  the record's `{currentKeyId, previousKeyId}`, so the `cluster_secret`-derived key id leaves it. A
  node booting afterwards holds only the derived key: peers drop its SWIM datagrams and it cannot
  decrypt theirs, so SWIM discovers nobody, the QUIC dial set stays self-only (SWIM is its sole
  writer besides self), no quorum forms, and the consensus replay that carries the rotation record —
  the only source of the cluster key — never runs. The cycle cannot resolve itself.
  **Auto-heal replacements, scale-up and re-provisioned nodes are all included, so an emergency
  rotation leaves the cluster unable to self-heal.** Existing running nodes are unaffected. This cost
  is accepted for rc4 and disclosed in SECURITY.md, the CLI and the endpoint docs; a general
  key-delivery path for joiners is an architecture change (a second trust root, or a deliberately
  weakened revocation) and the choice is an open owner decision tracked as **#1200**.
  `GossipKeyDivergenceGuard` turns the detectable half into a refused boot: gossip arriving under one
  unheld key id, repeatedly, with no datagram ever having decrypted, is the divergence signature, and
  the node exits with a `FATAL` line naming the cause and the remedy rather than proceeding into a
  permanently unjoinable state
  [verified: `GossipKeyDivergenceGuardTest` — 7 tests: fires at the threshold, fires once not per
  datagram, one successful decrypt disarms it permanently, varied junk does not trip it, a differing
  key id resets the run, encrypt is passed through unchanged].
  **The exit path itself is measured, not assumed.** `refuse` runs on a Netty event-loop thread, and
  #838 proved by probe that `System.exit` from inside a shutdown HOOK parks the JVM forever, so the
  same call from an IO thread could not be taken on trust. Probed with a real `NettySwimTransport` fed
  real rotated-key datagrams: with no shutdown hook the process terminated in ~1s with **exit code 1**;
  with a hook shaped like `Main.shutdownNode` it still terminated with **exit code 1**, hook completing
  cleanly, port released, process gone. The arms differ only in the hook, attributing the extra ~10s to
  Netty's graceful-shutdown quiet period rather than to a deadlock. `System.exit` is kept over `halt`
  because it runs the node's own hooks, and `Main.shutdownNode` bounds those at 30s with `halt(3)`, so
  a wedged subsystem cannot hang the process
  `[unverified: the probe's hook stops the TRANSPORT; production's stops the whole node, a larger
  surface. What is established is that the Netty-thread exit does not self-deadlock and that hook
  machinery runs to completion; the 30s bound plus halt(3) is what caps the untested remainder]`.
  **Exposure this introduces, and the arming window that bounds it.** The refusal counts
  same-key-id-consecutive undecryptable datagrams, and an unencrypted datagram is NOT distinguishable
  from a rotated peer's by that signal alone. A delta review demonstrated the consequence: **eight
  16-byte junk UDP datagrams carrying one repeated arbitrary key id ended a booting process** —
  off-path and spoofable, since the SWIM listener decrypts from any sender with no source check and
  the default firewall preset opens SWIM UDP to `0.0.0.0/0`, and crash-looping under a restart
  supervisor. The gate is therefore ARMED only inside a window: it requires **60 seconds with no
  successful decrypt** before it will count anything, and the window **closes** afterwards — without
  an upper bound a node that never decrypts would stay armed for life, which is exactly the auto-heal
  replacement described above, making the most exposed node the one that stays killable longest.
  Out-of-window datagrams reset the run, so a burst cannot be banked up to the moment the window
  opens. **The protection here is the permanent disarm on first successful decrypt, NOT the arming
  delay** — a node that has decrypted even once is immune for the life of the process (measured: 500
  junk packets after one successful decrypt, no effect), while the delay merely bounds the
  pre-decrypt interval and costs an attacker **patience rather than bandwidth**: a one-per-second
  stream that simply crosses the 60s boundary trips the gate at 68 packets, needing no knowledge of
  boot time. Lengthening the delay would widen the only interval that is exposed, so it is not a
  hardening knob. **Both bounds are chosen, not measured:**
  60s is intended to clear a healthy node's first-decrypt latency (expected to be seconds after SWIM
  starts, itself unmeasured) by roughly an order of magnitude, and 10min to clear by a similar margin
  the time a probed restarted member needs to accumulate the threshold. Both margins were chosen
  deliberately wide; **whether the behaviour is sensitive to either number is unknown until those
  distributions are measured** (#1208), so this is not a reason to skip that work
  [verified: `GossipKeyDivergenceGuardTest` — 10 tests covering both bounds, the banking attack, the
  permanent disarm on one successful decrypt, varied-junk, differing-id reset, fire-once and
  encrypt-passthrough; plus an out-of-tree end-to-end probe over real UDP against a real
  `NettySwimTransport`: the burst that previously killed the process at +3.7s now **survives**, while
  the same datagrams delivered after the window opens still **refuse** with exit code 1].
  **Detection is partial by construction, and the operator instruction that follows from it is the
  most important line here: IF A NODE WILL NOT JOIN AFTER A ROTATION, CHECK THE SEED NODES' LOGS, NOT
  THE NEW NODE'S** — `Failed to decrypt gossip from <id>`, already logged by `NettySwimTransport`.
  This inverts where an operator will instinctively look. The guard fires only if the cluster SENDS to
  the node: a RESTARTED member is still in its peers' configured seed set, so it is probed and the
  divergence is detected precisely; a node the cluster has never heard of — the auto-heal case — is
  probed by nobody, receives nothing at all, and logs nothing about the cause. Worse than silent, it
  prints `Aether node <id> started, cluster forming...` and then stays quiet, because an unreachable
  quorum is retried and never exits. Naming divergence as a candidate in a no-quorum boot diagnostic
  was considered and rejected: no such diagnostic exists to extend, and inventing one is new machinery
  in a security-adjacent path — the gap is disclosed instead.
  Note also that CTM sets `AETHER_ADVERTISE_HOST` (`NodeUserDataRenderer`, via `ip route get`), so
  auto-heal and scale-up skip `SelfAddressResolver`'s reflection probe entirely — **that skips the
  SYMPTOM only; their live SWIM transport is still mute, so they still cannot join.** A reader who
  learns only "reflection breaks" would wrongly conclude auto-heal is safe.
  `[unverified: no multi-node run — the cycle is established from the code that enforces each step
  (`TopologyObserverTest.SwimOnlyDialSet` pins the self-only dial set; `NettySwimTransport` drops on
  decrypt failure) plus an in-process test of the key divergence itself, not from an observed cluster]`
- **The first rotation has no decrypt overlap**, and the docs no longer claim otherwise. With no prior
  record there is no previous key to carry, so the emergency rotation — the only one that runs during
  an actual leak response — replaces the boot key outright. Carrying the live derived key as
  `previousKey` was considered and rejected: it would extend the derived key's life by a rotation
  while fixing nothing, because a joiner still cannot decrypt the cluster's replies. The behaviour is
  unchanged and the CLI, endpoint docs and SECURITY.md now state it.
- Not changed: the boot-only derivation of the "daily" key, filed as #1164.
  `[unverified: no multi-node run; the route is exercised over a mocked ManageableNode and an in-process KVStore]`
