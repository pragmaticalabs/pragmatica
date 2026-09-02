<!--
SPDX-License-Identifier: BUSL-1.1
Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
-->

# Session Handover — 2026-05-29d — Membership unification P2-b…P5 landed; chaos validation nailed a leader-kill cluster-dissolution to a KSUID-uppercase / Docker-DNS root cause; ULID fix + reconciler guard designed (NOT yet implemented)

**Branch:** `release-1.0.0-rc1`. **HEAD:** `b22b1e8a2`. **Working tree clean.** **3 commits LOCAL/unpushed** atop pushed `1dbaac4d6`.

## ⭐ START HERE
The membership-unification big-bang is **functionally complete and largely validated**: cold-start, slice placement, single-node-kill auto-heal, and the provisioning-storm fix are all Docker-proven. Chaos validation then exposed **one critical remaining bug**: killing the **leader** dissolves the whole cluster. We **root-caused it precisely** (it is NOT a consensus/membership-logic bug — it's a **Docker DNS case-sensitivity issue triggered by uppercase KSUID node names**, cascading through a reconciler over-count). The fix is **designed and agreed but NOT yet implemented**.

**Next actions, in order:**
1. **Implement the ID fix** (agreed): replace `KSUID` with a **ULID-style lowercase type** — see §4.
2. **Implement the defensive reconciler guard** (§4) — the actual safety net.
3. (optional) **QUIC connect null-address NPE guard** (§4).
4. **Clean cluster B** (label-scoped `docker rm`, see §6) then re-run `--suites 00,02`.
5. Push the 3 local commits + this handover.

## 1. What landed this session (commits atop pushed `1dbaac4d6`)
PUSHED (origin) through `1dbaac4d6`. **UNPUSHED (3):**
```
b22b1e8a2 fix(membership): P5 — fix auto-heal provisioning storm (asymmetric hysteresis fast-up ~1s / slow-down ~15s; inFlightProvisioning expiry → nttDepartureTimeout×3)
f45d8bc39 test(02-chaos): re-source decommission assertions to v2 membership-absence; retire transport-unreachable smoking-gun; refresh lint baseline
dcf3abf7b fix(membership): P5 — identity-aware reconciler arm-after-first-full-membership provisioning guard; wire reconciler leader-toggle into live router
```
Earlier this session (PUSHED): `2aecf81b6` P2-b, `232e52fe4`+`107491b1c`+`56253736f`+`6c579537a` P3, `1dbaac4d6` P4/Bug-B. Prior handover: `session-handover-2026-05-29c.md`. All build-green (`mvn -pl aether/node -am clean install -Dmaven.test.skip=true`) + unit suites green.

## 2. Validated on Docker (clean host)
- **00-smoke: fully GREEN** (formation 5+leader, quorum, liveness, **slice placement / app 200** — Bug B fixed). Reconciler now active; **no phantom provisioning at cold-start** (arm-latch holds).
- **02-chaos `test-joining-window-kill.sh`: 5/0** — single-node kill → CTM auto-heal provisions exactly one replacement, no storm, killed node removed from membership in ~1s. **Storm fix validated** (storm detector silent; previously 69 containers + host OOM, now ≤6).
- Stale-test fixes validated (the `test-joining-window-kill` assertions now use v2 membership-absence, not the deleted `NodeLifecycleKey` atom / FSM decommission events).

## 3. THE OPEN BUG — leader kill dissolves the cluster (root cause NAILED)
`test-kill-leader.sh` kills the leader; instead of re-election+heal, **all surviving cores self-drained (exit 2) and the cluster dissolved.** Full causal chain (evidence in node logs, saved to `/tmp/clusterb/*.log` — **ephemeral, may be gone**):

1. A node is killed → leader provisions a replacement → `inFlightProvisioning={<id>}`.
2. **ROOT CAUSE:** the replacement container is named from a **KSUID**, which is **base62 → contains UPPERCASE letters** (e.g. `aether-b-node-3EPXL4KZhNGmzvfZnIxPpVfMqUq`). **Docker's embedded DNS is case-sensitive** (long-standing bug, RFC-4343 violation — moby/moby #28689, #21169; moby/libnetwork #993). Static nodes are all-lowercase (`aether-b-node-1`) so they resolve; the uppercase replacement does **not**. Log: `NettySwimTransport.completeResolution: DNS resolution failed for aether-b-node-3EPXL… [A(1)]` (persistent ~30s on peers).
3. → replacement is SWIM-gossiped but **never completes the QUIC mesh** (peers can't dial it back) = a **phantom**. (Secondary: QUIC connect **NPEs on the unresolved address** — `SockaddrIn.setIPv4: address is null` — instead of failing cleanly.)
4. → `LeaderReconciler.runReconcileBody` over-counts: `effective = clusterMembershipCount + inFlightProvisioning.size()` **double-counts** the phantom (it's in the SWIM membership view AND in-flight), plus the just-killed node still lingers under the 15s down-hysteresis → `effective > configuredCoreCount(5)`.
5. → `computePeersToDrain` returns a "surplus" victim → leader **DRAIN-commands a healthy core** (`reason=OVERPROVISION_PARTITION_HEAL`). `quorumSafe` did not protect (it's computed from the same inflated count; **drain is not arm/floor-gated, only provisioning is**).
6. → cluster genuinely drops below quorum → the **now-live quorum-loss self-drain** (P5) correctly fires on the survivors → all exit(2) → **cluster dissolves**.

**Key insight:** the self-drain is behaving *correctly* (genuine sub-quorum isolation → dissolve). The leader *manufactured* the isolation by draining a healthy node off a phantom over-count, and the phantom exists only because **uppercase KSUID names break Docker DNS**. Earlier (pre-P5) this was survivable because the self-drain was dormant; activating it (P5) made it fatal — exactly why we chaos-validate.

## 4. AGREED FIX PLAN (designed, NOT implemented)
### (a) Replace KSUID with a ULID-style lowercase ID — ROOT-CAUSE FIX
- **Rename** away from `KSUID` (it will no longer be a standard KSUID). Suggested: `Tsid` / `Ulid` / `SortableId`.
- **Adopt ULID** rather than invent a bespoke bit layout: **48-bit ms timestamp + 80-bit random = 128 bits, lowercase Crockford base32** (`0123456789abcdefghjkmnpqrstvwxyz`, excludes i/l/o/u) → **26 chars**, ASCII-monotonic (lexicographic sort = chronological), **DNS-safe**. ms-resolution is the one justified upgrade over today's 1-second KSUID (high-volume invocation/request IDs currently sort randomly within a second). Optional **ULID monotonic variant** for strict intra-ms ordering.
- **CRITICAL:** this is a **radix/alphabet re-encode of the bytes**, NOT `.toLowerCase()` on the base62 output — naive lowercasing collapses A↔a → collisions + ~22 bits entropy loss, defeating uniqueness.
- **Do NOT** add speculative bit-splits (sub-ms, snowflake machine-id/sequence) — no demonstrated need; the driving requirement is DNS-safe sortable IDs. (Open question parked for the user: any field to *embed* in the ID — cluster/role/shard? If not, plain ULID.)
- Current impl: `integrations/utility/src/main/java/org/pragmatica/utility/KSUID.java` (base62 alphabet line 65, `STRING_LENGTH=27` line 62, encode/decode 178/208). **Verify before changing:** (1) no prod code decodes/parses a KSUID back to bytes/timestamp — grep found NONE (the `fromString` hits are UUID/TimeSpan/config, not KSUID); (2) `STRING_LENGTH`/`27` not hard-coded outside KSUID.java; (3) update `KSUIDTest` for new alphabet/length + add a **sortability** assertion. **12 non-test use sites** (CTM, DockerComputeProvider, InvocationContext, SliceInvoker, HttpForwarder, DeploymentManagerImpl, DHTRebalancer/AntiEntropy/DistributedDHTClient, IdGenerator) — all use IDs opaquely; old persisted base62 IDs coexist fine as opaque strings (no decode → no migration).

### (b) Defensive reconciler guard — the actual SAFETY net (do regardless of (a))
In `aether/aether-deployment/.../membership/ntt/LeaderReconciler.java`:
- **Count CONFIRMED capacity, not phantoms:** base provision/drain math on QUIC-confirmed members; **dedup in-flight vs membership** (`effective = |members ∪ inFlightPlaceholders|`, not a sum) so a provisioned node that also appears in the membership view is never counted twice.
- **Hard drain floor:** never drain below `configuredCoreCount` in a pass (mirror the provisioning arm-gate; `computePeersToDrain` at ~line 385 is currently only `quorumSafe`-gated, and `quorumSafe` uses the inflated count).
- Rationale: a node failing to join for ANY reason (DNS, slow boot, crash, real partition) must NEVER let the leader drain a healthy core / dissolve a quorate cluster. (a) removes today's trigger; (b) prevents the class.

### (c) QUIC connect null-address guard — robustness
`QuicClusterClient`/`QuicClusterClientInstance.connectQuicChannel` (~QuicClusterClient.java:234-245) NPEs when DNS returns null (`SockaddrIn.setIPv4: address is null`). Should treat an unresolved address as a clean retryable failure, not throw NPE.

## 5. Sequencing & validation
Implement (a)+(b) [+(c)], `mvn -pl aether/node -am clean install -Dmaven.test.skip=true`, then **clean cluster B (§6)** and re-run `cd aether/tests/integration && ./run-tests.sh --env remote --suites 00,02 --skip-build`. Expected: 00 green, 02 `test-joining-window-kill` 5/0, `test-kill-leader` now survives (replacement resolves+joins; no healthy-core drain). Watch the storm detector pattern (alert if cluster-b containers > 8). Delegate aether-deployment/swim/utility Java to `jbct-coder`; AetherNode wiring is direct (truncation magnet). NEVER `mvn verify` (HCLOUD failsafe).

## 6. Open items / traps
- **Cluster B is dissolved + leftover exited containers** on `$TARGET_HOST`. Cleanup is **classifier-blocked for me** — USER runs: `ssh -i "$AETHER_SSH_KEY" -o StrictHostKeyChecking=no "$AETHER_SSH_USER@$TARGET_HOST" "docker ps -aq --filter 'label=aether.cluster=b' | xargs -r docker rm -f"`. (Earlier this session a 3-node OOM was caused by **3 residual containers each hogging 15+ GB** — keep an eye on residuals; `free -h` / `docker stats` if OOM recurs.)
- **3 unpushed commits** (`dcf3abf7b`, `f45d8bc39`, `b22b1e8a2`) — push at a clean point.
- **Test endpoint caveat:** `test-kill-leader.sh` queries the cluster-B mgmt endpoint which can go dark when the cluster dissolves — the ConnectException / `[: -eq: unary operator expected` shell spam is a *symptom* of the dissolution, not a separate bug.
- The membership-unification stale-test sweep is **incomplete**: `test-kill-leader.sh` still has a stale `No NODE_LEFT/NODE_FAILED event` assertion (v2 `ClusterEventAggregator.onMembershipDecision` is a no-op — there is no v2 departure event; signal is membership-absence). Fix it (and re-audit remaining 02-chaos scripts) alongside the re-run — but FIRST fix the dissolution (a)/(b), or the leader-kill test can't get far enough to matter.
- **AetherNode** = ~3260-line single `assembleNode`, truncation magnet — direct Read+Edit only; the file uses an inner **record** `aetherNode` for the returned node (shutdown deps are record components).

## 7. References
- Spec: `aether/docs/specs/membership-unification-spec.md`
- Prior handovers: `session-handover-2026-05-29c.md`, `-29b.md`
- Docker DNS case-sensitivity: moby/moby #28689, #21169; moby/libnetwork #993
- Memory: `[[project_membership_v2_redesign]]`
