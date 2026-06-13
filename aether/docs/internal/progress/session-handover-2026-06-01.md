<!--
SPDX-License-Identifier: BUSL-1.1
Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
-->

# Session Handover — 2026-06-01 — cold-start FAULTY-storm fixed (two-plane liveness); non-destructive 10/10 clean; clean destructive read pending

## ⚡ START HERE / TL;DR

This session continued the **terminal-removal rework** (transport-confirmed death) and found+fixed the regression it exposed: a **cold-start formation FAULTY-storm**. The fix (`markAliveFromTransport`) is the *dual* of the rework — transport confirms **life** as well as death, with the SWIM tombstone as the discriminator. Validation so far:

- ✅ **SWIM module tests 12/12** (incl. 2 new tombstone-gate cases).
- ✅ **Non-destructive Docker set 10/10 suites, 193 tests, 0 fail** (`00,04,06,07,08,09,10,11,14,15` on remote) — **proves the rework does not break normal operation.**
- ⚠️ **Clean destructive `02,12` re-run reproduced the auto-heal failure** — confirming a **genuine, pre-existing CONSENSUS wedge** (Rabia `BatchId` collision under node-5 backpressure), upstream of the reconciler. The membership rework is NOT implicated (§4b). This is RC1's new top destructive blocker, structurally separate from membership.

**Branch `release-1.0.0-rc1`. HEAD `6741d6c3a`. 18 commits unpushed (DO NOT push — RC1 not green). Large uncommitted working tree (17 files, the rework + cold-start fix) — NOT yet committed.**

## 1. The cold-start fix (this session's main deliverable)

**Symptom (the "massive errors"):** at cold-start, all followers complete their QUIC Hello handshake (consensus-ACTIVE) within ~1s, but SWIM never marks them HEALTHY → their ~10s SUSPECT windows expire ≈ when the first SWIM probe fires (startupDelay ~10s) → SUSPECT→FAULTY → (phase already NORMAL, never-HEALTHY ⇒ cold-boot suppression bypassed) → FaultyObserved → NTT evict → consensus REMOVE → `activePeerCount 4→0` → "Consensus apply timed out" forever; cluster never forms.

**Root:** `551f97f12` removed the QUIC-`PeerConnected`→HEALTHY fast-path for silent-death-resurrection safety, leaving SWIM probe-ack as the ONLY HEALTHY promotion — which races (and loses) against the suspect window at formation.

**Fix (two edits + test):**
- `integrations/swim/.../SwimProtocol.java`: new `@Contract public void markAliveFromTransport(NodeId)` delegating to the existing **tombstone-gated** `markAliveIfNeeded` (flips SUSPECT→ALIVE, clears suspect timestamp, bumps incarnation, emits HealthyObserved).
- `aether/node/.../health/fsm/SwimHealthState.java`: `Running.handlePeerConnected` now promotes a **known** SWIM member via `swim.markAliveFromTransport(peer)` (helper `promoteKnownMember`); genuinely-unknown peers keep the seed-add path. Removed redundant `readdUnknown` duplicate guard.
- `integrations/swim/.../SwimProtocolTombstoneTest.java` (untracked, new): `markAliveFromTransport_suspectNonTombstonedMember_promotesToAlive` + `markAliveFromTransport_provenHealthyThenFaultyTombstoned_refusesPromotion`.

**Why it's safe NOW (was not in `551f97f12`):** the terminal-removal rework tombstones a proven-healthy-then-dead id (`everSeenHealthy` gate). So `markAliveFromTransport` promotes a never-tombstoned (cold-start / live-flapping) member but is **refused** for a silently-dead tombstoned id off a stale/reopened channel (#231 — no resurrection). This is the **dual of two-plane death confirmation**: transport confirms life as well as death; tombstone is the discriminator.

## 2. The terminal-removal rework (uncommitted, validated for normal-op only)

All in the working tree (17 files, `git diff --stat` = 537+/249-). Map (from tasks #12–#15):
- **Rework A — SWIM FAULTY-edge terminal tombstone** (`SwimProtocol.java` + `SwimConfig.java`): tombstone set at the FAULTY edge for proven-healthy ids (`tombstoneIfProvenHealthy`); `blockedByTombstone` gates all ALIVE-promotion paths; `handleAnnounce` clears tombstone for partition-heal; deleted dead revival machinery (`markAlive`/`applyAliveRevival`/`revivalTimestamps`); removed `revivalGrace` from SwimConfig.
- **Rework B — QUIC terminal removal** (`QuicClusterNetwork.java` + `PeerState.java`): `isPeerRemoved` short-circuits consensus writes; `departurePermanent` keeps the peer REMOVED-resident (not re-dialed); deleted dead `expireEvicted`.
- **Rework C — co-confirmation terminal-set** (`LeaderReconciler.java`): `terminallyEvicted` set; `onSwimHealthy`/`onPeerRecovered` are no-ops for terminal ids (stale recovery cannot un-evict).
- **Level-readiness** (`AetherNode.java` + `NodeReportedStateHolder.java`): `metricsCollector.setNodeReportedStateSupplier` rewired from edge-cached holder to live `clusterNode::isActive`.
- Plus committed at HEAD: `4d6fc64e9` KV-backed `desiredCoreSize` (config-seed fallback), `6741d6c3a` leader-minted NodeId contract through all providers + cold-start provisioning grace/deficit-debounce + identity-match in-flight clear.

## 3. Validation status

| Layer | Result |
|---|---|
| SWIM unit (`SwimProtocolTombstoneTest`) | 12/12 ✅ |
| `aether/node install -DskipTests -am` | BUILD SUCCESS ✅ |
| Non-destructive Docker `00,04,06,07,08,09,10,11,14,15` | **10/10 suites, 193 tests, 0 fail, 2 skipped** ✅ (formation 7s) |
| Cold-start formation (clusters A+B) | clean, no FAULTY-storm, no apply-timeout ✅ |
| Destructive `02,12` | ⏳ clean re-run in progress (prior run contaminated — §4) |

## 4. CONTAMINATION INCIDENT — read this, it cost a root-cause

The first destructive `02,12` run this session shared the remote with an **orphaned `run-tests.sh` that survived session compaction** (the pre-compaction "terminal" run, still alive and manipulating the SAME cluster B `test-b` containers). **Two runs on one host = one forms the cluster while the other kills its nodes** → node-2/4 ADD/REMOVE view-flapping, kills landing inside the 60s bootstrap grace, consensus-commit stalls.

An `aether-investigator` then root-caused (off those logs) a "consensus can't commit the membership-departure during RECOVERING flapping → NODE_FAILED never fires → reconciler never invoked" bug. **That diagnosis is DISCARDED** — the flapping was dual-run interference, not a real single-run bug. Lesson saved to memory: `feedback_check_orphan_runs_before_docker.md`. **Always `pgrep -fl run-tests.sh` + clean remote + confirm single instance before any remote Docker run.** Formation-only results (cluster A cold-start) stayed valid (not kill-dependent).

## 4b. CLEAN destructive root-cause (verified single-run) — it's a CONSENSUS wedge, not membership

The clean `02,12` re-run reproduced the auto-heal-no-provision failure on a verified single instance, so it is a **genuine bug, not dual-run noise**. A read-only investigation of the clean leader logs found the real mechanism:

- **`RabiaEngine.mergeOrKeep ERROR — BatchId collision: batch-d5ad8b2 has different content`** on nodes 2/4/5 simultaneously (09:55:09) → divergent consensus log → cluster cannot advance.
- Preceded by a **`CONSENSUS stream backpressured or inactive for node-5`** storm that began ~24s BEFORE the kill (09:54:45). node-5 is the consensus bottleneck.
- Result: every `consensus apply` times out after 30000ms (×14 on the leader) → the leader can never commit the membership-departure → the CTM/`LeaderReconciler` is **never invoked** (`grep -ci 'deficit|provision|inFlight|armed|terminallyEvicted' leader.log` = **0**) → no replacement → 90s test window expires.
- **Responsible code:** `integrations/consensus/.../rabia/RabiaEngine.java:713-719` (`mergeOrKeep` / BatchId collision). Downstream symptom in `ClusterDeploymentState$Active` apply-timeout.

**The membership rework is NOT implicated — verified, not assumed:**
- The `LeaderReconciler` rework (grace/debounce/desiredCoreSize/minted-id/terminallyEvicted) never executes — consensus never delivers it a committed departure (grep=0).
- **Rework B (QUIC consensus transport) is cleared:** `isPeerRemoved` short-circuits `rawConsensusWrite`/`retryConsensusWrite` ONLY for terminal `REMOVED`-phase peers (set solely by `departurePermanent`). node-5 was **backpressured/inactive (`refuseBackpressured`, channel-not-writable), not REMOVED** — a pre-existing path the rework doesn't touch. The `BatchId` collision is in the Rabia engine, not the transport.
- The non-destructive run (193 tests, consensus-heavy formation/deployment) had **zero** wedge — the fault manifests only under kill-churn + node-5 backpressure, matching the project's known Rabia "V0-lock / batch-ordering" bug family (memory: 4 Rabia bugs fixed in 0.19.0 incl. batch ordering).

**⚠️ This means RC1's remaining destructive blocker is a CONSENSUS-layer fault, structurally separate from the membership work.** Triage = structural; needs its own focused session on Rabia batch-id assignment under backpressure (likely: two proposers minting the same `BatchId` with different payloads; and/or node-5 stream starvation as the trigger). Confirm node-5 GC/IO health and how `BatchId` is generated/deduped in `RabiaEngine`.

## 5. REMAINING WORK (next session)

1. **Consensus BatchId-collision wedge (NEW top blocker, §4b).** Structural, consensus-layer. Investigate `RabiaEngine` BatchId assignment + dedup under sustained backpressure, and node-5 stream starvation (why a peer goes `backpressured or inactive` at formation). This — not the membership rework — is what blocks the destructive suites now. The handover-2026-05-31b issues A/B/C/D are likely all downstream symptoms of this (no committed departure ⇒ no classification, no provision, no self-drain decision).
2. **Commit the rework** once the destructive read is acceptable (non-destructive green + module tests already justify a checkpoint commit if desired sooner). Suggested split mirrors tasks #13/#14/#15 + the cold-start fix as its own commit.
3. **Task #16 (Rework D docs):** document the **restart-disabled invariant** across guides/spec/cloud-init/compose/providers; fix `aether/forge/docker-compose.yml:26` `restart: unless-stopped`→`"no"`. (A dead NodeId never returns under the same id; recovery is always a NEW ULID — this is the model the terminal-removal rework enforces.)
4. **Task #11:** update the remaining stale SWIM unit tests to membership-v2 behavior.
5. **Tasks #6/#7:** generalize per-provider node-info preparation into shared modules (bootstrap + auto-heal one path); leader seeds `ClusterConfigKey.CURRENT` from config when KV empty (non-bootstrap path).

## 6. Process notes (hard-won, this session)

- **Docker is the authoritative oracle**, BUT only single-instance + clean-slate (§4). In-process spikes mislead.
- `run-tests.sh` does NOT call `mvn verify` — safe to launch with `HCLOUD_TOKEN` set. The Hetzner hazard is `mvn verify`/Failsafe only. Use `--skip-build` after a focused `mvn -pl aether/node install -DskipTests -am` (jar at `aether/node/target/aether-node.jar`); the run still pushes the jar + rebuilds the remote image.
- Each auto-heal-dependent destructive test burns ~30 min on 1200s/600s convergence timeouts — a wedged auto-heal makes the destructive run very slow.
- Java → jbct-coder; investigation → aether-investigator (read-only, background, protects context); maven → focused `install -DskipTests`.
