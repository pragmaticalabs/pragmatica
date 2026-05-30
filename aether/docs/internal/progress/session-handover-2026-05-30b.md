<!--
SPDX-License-Identifier: BUSL-1.1
Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
-->

# Session Handover — 2026-05-30b — Cold-start chain FIXED end-to-end; full-suite triage; LocalDisconnect is next

## ⚡ START HERE / TL;DR

**Membership v2 went from "does not cold-start at all" to "9/15 integration suites green, formation+catch-up+auto-heal all Docker-validated."** Five formation/recovery layers were peeled and fixed this session (4 commits). The remaining 6 failing suites are **dominated by ONE root cause — `LocalDisconnect` suppressing decommission — which is the next fix.** Zero regressions from any change this session.

- **Branch:** `release-1.0.0-rc1`, **HEAD `45d76da2f`**, working tree clean.
- **14 commits unpushed** (origin at `3233a92eb`). **DO NOT push yet** — RC1 not green; 6 suites still fail.
- **This session's commits (all validated where applicable):**
  - `e8d61ecc7` fix(membership): cold-start formation (whenReady + MemberDiscovered + seed-listener order + resolved-address)
  - `62ac4e30d` fix(consensus): late-joiner catch-up (eager SyncRequest broadcast)
  - `e258579b9` fix(membership): auto-heal arm-at-quorum
  - `45d76da2f` refactor(metrics): remove dead AggregatedReachabilitySnapshot pipeline (P4)

## 1. What was fixed (the root-cause chain — each verified EMPIRICALLY on Docker, not from code review)

The §5.4 change (`a97a9e753`, prior session) made SWIM the sole source of the QUIC dial set and EXPOSED a stack of latent deadlocks. Peeled in order:

1. **Boot-order deadlock** → `startSwim` was gated behind `clusterNode.start()` resolving, which only resolves on consensus quorum, which needs the peers SWIM discovers. **FIX:** `ClusterNetwork.whenReady(Runnable)` (default + `QuicClusterNetwork` override firing after `server.start()` bind); `AetherNode` registers `startSwimTrigger` on it BEFORE `startClusterAsync()`. SWIM now starts at transport-ready. (`e8d61ecc7`)
2. **Gossip-learned peers never dialed** → only the `JoinAnnounced` (direct-ANNOUNCE) edge fed the dial set. **FIX:** new `SwimObservation.MemberDiscovered` emitted on every membership-join (`SwimProtocol.notifyMemberJoined`); `AetherNode` routes it to `handleDiscoveredNodes` like `JoinAnnounced`. (`e8d61ecc7`)
3. **THE minor detail (the actual cold-start wedge)** → `CoreSwimHealthDetector.seedAndWrap` seeded members BEFORE registering observation listeners → seed `MemberDiscovered` fired into an empty listener list and were lost → a last-joining highest-NodeId node never populated its dial set, never initiated. **FIX:** register listeners+emitters BEFORE `seedMembers`. (`e8d61ecc7`)
4. **Resolved-address dialing** (orthogonal robustness) → `NodeInfo.resolvedAddress` (excluded from equals/hashCode); SWIM threads the ANNOUNCE datagram source IP; `connectPeer` dials resolved address (DNS-free). (`e8d61ecc7`)
5. **Late-joiner catch-up** → a provisioned replacement joined a cluster ~230 phases ahead, but `doClusterConnected` deferred the first `SyncRequest` to a timer → the node sat silent in `Syncing` and was killed before any sync round started. (snapshot-install itself works: `restoreState`/`applyRestoredState` jumps phase 0→N.) **FIX:** broadcast the first `SyncRequest` immediately in `doClusterConnected`. (`62ac4e30d`)
6. **Auto-heal wedge after multi-kill** → `LeaderReconciler.armedForProvisioning` armed only at FULL `configuredCoreCount` (5); a baseline-restore of survivors never re-observes 5 → latch never arms → zero provisioning. **FIX:** arm at `quorumThreshold(configuredCoreCount)` (`LeaderReconciler:337`). Provisioning stays split-brain-safe via the existing `quorumSafe` gate. (`e258579b9`)

**Validation (Docker, remote):** both 5-node clusters cold-start instantly (5 nodes 0s, leader 0s); 00-smoke + 02-chaos baseline restores reconverge in 0s (were wedging 1200s); arm-quorum provisions exactly 2 replacements (no phantom storm — Bug-C did NOT re-open).

**P4 (`45d76da2f`):** removed the dead `AggregatedReachabilitySnapshot` per-ping pipeline (producer-dead + decision-dead, behavior-preserving). **PRESERVED the live `PeerConnectivityObservation` failure-detector** (`ClusterSyncPongSignalFan` → SWIM/HealthReconciler) — do not confuse them. 18 files, build-clean, full-suite confirmed zero route/wire regressions.

## 2. Full-suite result (remote, all 15 suites) — 9 pass / 6 fail

**✅ PASS (9):** 00-smoke, 04-streaming, 06-deployment, 07-cluster-mgmt, 09-artifacts, 10-database, 11-observability, 14-storage, 15-delegation. (No regressions from formation/catch-up/auto-heal/P4.)

**❌ FAIL (6) — by root cause:**

| Root cause | Failures | Where |
|---|---|---|
| **#23 `LocalDisconnect` → decommission never fires** (killed nodes emit no `NODE_LEFT`/`NODE_FAILED`; downstream phantom-membership: `pick_non_leader` short, `docker kill: container not running`, self-drain S19/S20 doesn't exit) | ~10 | 02-chaos (4), 12-network (3 + phantom), 13-edge-cases (phantom) |
| **Scale-down drain** (data-loss `marker 404`, 75% error rate under load) — likely #23-adjacent (drain blocked) | 2 | 03-scaling |
| **Drain API/budget** (`500 "Node lifecycle not found"` on /api/nodes/drain; 3rd drain not refused) — #23-adjacent + drain-budget logic | ~3 | 13-edge-cases |
| **Pre-existing, UNRELATED to this session** | 4 | 05-security `whoami=anonymous`/dev-mode auth (2) + TLS renewal status; 08-resources `Pause_task` readback; 12-network partition gossip-TLS handshake |

**Key point:** these destructive-suite failures were **never reachable before** (prior runs wedged at formation/baseline). They are newly-EXPOSED, not regressions. The dominant ~10 trace to a single cause: **#23**.

## 3. NEXT FIX — #23 LocalDisconnect false-positive (DOMINANT blocker)

**Diagnosis (investigated, characterized — NOT yet implemented):** When several nodes are killed, survivors enter `SwimHealthState.LocalDisconnect` because a majority of their SWIM *peers* are FAULTY — even though the node is the elected leader WITH consensus quorum (3/5, NORMAL phase). `LocalDisconnect` short-circuits the per-peer topology *drain* (`SwimHealthState.java:131-138`), so killed nodes are never decommissioned → no `NODE_LEFT`/`NODE_FAILED` events → failure-detection tests time out and phantom (undecommissioned) IDs linger in membership, breaking `pick_non_leader` and `docker kill`.

- **Trigger:** `SwimHealthState.isLocalDisconnect()` (`:184-198`) = `count > totalMembers/2` over the LOCAL SWIM view, using a MONOTONIC per-event faulty counter that includes dead replacements. NOT a quorum signal.
- **Fix shape (user-approved direction):** gate `LocalDisconnect` ENTRY on actual quorum-loss / inability-to-see-a-leader (consult `TopologyObserver.inQuorum()`) rather than peer-faulty-majority; and/or count DISTINCT peer-IDs within the window excluding decommissioned/replacement (prior F.5 double-count note). The node is the elected leader with quorum → it is NOT partitioned → must NOT suppress drain.
- **Code:** `aether/node/src/main/java/org/pragmatica/aether/node/health/fsm/SwimHealthState.java`. Delegate Java to jbct-coder. Validate on Docker `--suites 02,12` (the decommission/self-drain tests).
- **Expected payoff:** clears the bulk of 02-chaos + most of 12-network + the phantom-membership downstream in 03-scaling/13-edge-cases.

## 4. Remaining work (after #23)

- **#23 LocalDisconnect** (above) — highest leverage.
- **03-scaling scale-down** — re-test after #23; if data-loss 404 / error-rate persists, it's a separate scale-down drain/migration issue.
- **13-edge-cases drain budget** — `/api/nodes/drain` 500 "lifecycle not found" + budget (3rd drain must 409). Partly #23 (phantom lifecycle), partly drain-budget enforcement logic.
- **Pre-existing / separate (not this session's regressions):** 05-security auth (`whoami` anonymous in dev-mode — likely test-env expectation vs `AETHER_INSECURE_DEV_MODE`) + TLS renewal status; 08-resources `Pause_task`; 12-network partition gossip-TLS handshake. Triage these independently.
- **P4 leftover (minor, task #18):** stale `ConsensusBridge`/`RabiaEngine` "QUIC path removed" comments + gutted `AetherNode.membershipView()` comment + `LocalQuorumWatcher` refs in 2 internal design docs. Comment-only, non-load-bearing.

## 5. Process notes
- Verify-on-Docker was essential: I was wrong TWICE diagnosing cold-start from code review alone; only live node logs settled it. Every fix here was confirmed by re-deploying and reading node logs, not by reasoning.
- The cluster-B baseline-restore + decommission machinery only became testable once formation+catch-up+auto-heal worked — fixing the early layers is what surfaced #23.
- jbct-coder shipped one JBCT violation (a raw `== null`) while reporting "no forbidden patterns" — scan subagent code edits before relaying.

## 6. Task list state
- #20 cold-start formation — DONE
- #21 catch-up — DONE
- #22 auto-heal arm-latch — DONE
- #18 P4 — reachability DONE; minor comment/doc tidy remaining
- **#23 LocalDisconnect — PENDING, NEXT (dominant blocker)**
