# Session Handover — 2026-04-23

**Branch:** `release-1.0.0-rc1`
**HEAD:** `92db75ef4`
**Tag:** `v1.0.0-rc1-candidate` (to move on push)

## Start-here summary

Today's session delivered Cluster A at 10/10 consistently (with the 08-resources STORAGE flake resolved), confirmed that the follower SWIM→`DisconnectNode` path fires correctly on dead-leader observation, and added quorum-stable counting so transient QUIC evictions no longer push quorum below threshold. The destructive-chain blocker is now narrowed to a single question: **why doesn't `LeaderManager.nodeRemoved` → `triggerElection` result in a proposal submission?** `onLeaderChange(none)` fires (leader was cleared), but the subsequent `RabiaNode.submitLeaderProposal` never logs — the path bails silently somewhere between `triggerElection` and `submitProposal`.

## What landed (commits, chronological)

- `92db75ef4` — **fix**: follower routes `DisconnectNode` on SWIM faulty when peer is current leader + `QuicClusterNetwork` counts `EVICTED` in quorum.
  - `CoreSwimHealthDetector.onMemberFaulty`/`onMemberLeft`: on follower, if the faulty peer is the `currentLeaderSupplier`, route `DisconnectNode` locally so `LeaderManager.nodeRemoved` fires. Other faulty peers still buffer upstream (single-writer rule preserved). Verified in live logs: `"follower needs local transport action (leader empty or faulty-is-leader), routing DisconnectNode to unblock re-election"` fires correctly at T+90s after kill.
  - `QuicClusterNetwork.activeConnectedCount`: now counts `CONNECTED` ∪ `EVICTED` phases (was `CONNECTED` only). Prevents transient QUIC handshake flaps from briefly pushing `activePeerCount` below quorum and triggering spurious `QuorumStateNotification.DISAPPEARED` → `LeaderManager.stop()`.
  - `AetherNode.java`: wires `swimHealthDetector.setCurrentLeaderSupplier(() -> clusterNode.leaderManager().leader())`.

## Integration tally (six runs today)

| Run | Cluster A | Cluster B | Notes |
|-----|-----------|-----------|-------|
| v0 (baseline, PeerState + harness-fix) | 10/10 + 08-resources flake | 2/18 | restart_all_nodes silent-stderr fixed |
| v1 (compose `restart: "no"`) | 10/10 | stuck at kill-leader | `docker kill` now authoritative |
| v2 (follower always routes DisconnectNode) | 10/10 | storm | over-aggressive, removed live peers |
| v3 (faulty==leader narrow) | 10/10 | stuck | leader empty too early |
| v4 (leader empty OR faulty==leader) | 10/10 | storm | same as v2 pattern |
| **v5 (narrow match + EVICTED quorum) — HEAD** | **10/10** | **stuck; `nodeRemoved` fires but no proposal** | Narrowed remaining path |

Cluster A stays consistently clean: `00, 04, 06, 07, 08, 09, 10, 11, 14, 15` all green. 08-resources STORAGE/KV flake is the only remaining occasional Cluster A issue (pre-existing).

## Root-cause narrowing — LeaderManager.nodeRemoved silently skips triggerElection

Confirmed sequence on node-3 (live log, current HEAD):

```
11:39:42  SWIM member suspected: node-1
11:39:57  SWIM member faulty: node-1 — follower needs local transport action, routing DisconnectNode
11:39:57.371  processViewChange: op=REMOVE, peer=node-1, activePeerCount=3, haveQuorum=true
11:39:57.371  Routing topology change: NodeRemoved[nodeId=node-1, topology=[node-2, 3, 4, 5]]
11:39:57.372  Node node-3 is not leader, deactivating task assignment coordinator    ← LeaderChange(none)
11:39:57.373  HealthReconciler stepping down — stopping reconciler
(5 minutes of silence — no "Submitting leader proposal")
11:44:50  SWIM member joined: node-1    ← compose up -d brought it back
```

Evidence:
- `haveQuorum=true` throughout: quorum never lost.
- `LeaderChange(none, false)` fires at 11:39:57.372: `currentLeader` was cleared.
- `RabiaNode.submitLeaderProposal` never logs — `triggerElection` either wasn't invoked, was invoked but `handleConsensusElection` bailed on an early-return, or proposal submission was suppressed.

Possible bailing points in `LeaderManager.handleConsensusElection`:
1. `topology.isEmpty()` — unlikely, `NodeRemoved` carried 4-node topology.
2. `currentLeader.get().isPresent()` — `getAndUpdate` should have cleared it; race?
3. `sortedCandidates.isEmpty()` — impossible with 4 candidates.
4. `!hasEverHadLeader.get() && !self.equals(candidate)` — shouldn't apply on re-election (`hasEverHadLeader=true` after first commit).

**Most likely:** `active.get()` is false at the moment `nodeRemoved` is invoked — LeaderManager was already stopped by a prior event. Or `proposalHandler.isEmpty()` — constructed in local mode somehow. A DEBUG log on `active.get()` value and `proposalHandler.isPresent()` in `triggerElection` entry would pin it in one test cycle.

## P0 for next session (blocker for 15/15 Docker)

**Add DEBUG/INFO logging at the very top of `LeaderManager.triggerElection`** and `handleConsensusElection` to surface the early-return reason. Files:
- `integrations/consensus/src/main/java/org/pragmatica/consensus/leader/LeaderManager.java`
  - Log `active.get()`, `proposalHandler.isPresent()`, `hasEverHadLeader.get()`, `currentTopology.get()`, `currentLeader.get()` on each `triggerElection` invocation.
  - Log the early-return branch taken in `handleConsensusElection`.
- Rebuild + kick off 15-suite. One run will reveal the bail reason.

Once the bail point is known, the fix is local (maybe 5 lines).

## P1 (nice-to-have)

- **Cluster A `await-quiesced` 500 warnings** on blueprint deploy (every run, both clusters) — cosmetic, blueprints still deploy. Investigate the server-side `/api/cluster/await-quiesced` handler for the 500 path.
- **08-resources STORAGE flake** — pre-existing, ~30% failure rate on the KV pool-burst test. Suspected activation-timing. Bump polling from 120s → 180s OR investigate activation path.
- **pg-tools JBCT lint errors** — 6 errors (JBCT-RET-01, RET-03, RET-07) in `pg-codegen`/`pg-schema` block `build.sh` Step 2. Workaround: `mvn -pl aether/node install -DskipTests -am` to rebuild the shaded JAR without running Step 2. Proper fix: add `@Contract` / method-level `@SuppressWarnings` and fix `firstSelectItem` null-returns.

## P2 (backlog)

- **NettyClusterNetwork sibling smear** (6 parallel maps) — QUIC is primary transport; Netty fallback. Same PeerState refactor pattern applies. Post-RC1.
- **Generic `integrations/statemachine` library adoption** — only consumer is its own test. `SliceState`, `NodeHealth`, `BootstrapPhase`, task-group string-state are candidates.

## File map (this session + prior)

| Path | Role |
|------|------|
| `integrations/consensus/src/main/java/org/pragmatica/consensus/net/quic/PeerState.java` | per-peer state machine (commit `58b33f5c3`) |
| `integrations/consensus/src/main/java/org/pragmatica/consensus/net/quic/QuicClusterNetwork.java` | PeerState delegation + `activeConnectedCount` counts `CONNECTED`∪`EVICTED` (today) |
| `aether/node/src/main/java/org/pragmatica/aether/node/health/CoreSwimHealthDetector.java` | follower-routes-DisconnectNode on faulty==leader (today) |
| `aether/node/src/main/java/org/pragmatica/aether/node/AetherNode.java` | wires `setCurrentLeaderSupplier` (today) |
| `aether/tests/integration/docker-compose-b.yml` | `restart: "no"` (prior `217db441e`) |
| `aether/tests/integration/lib/cluster.sh` | `restart_all_nodes` surfaces stderr (prior `b8073e6f1`) |

## Environment + conventions

Unchanged. Remote host `192.168.0.71`, user `aether`, key `~/.ssh/aether_test`. `HCLOUD_TOKEN` set — **never run `mvn verify`** (triggers HetznerCloudIT). Use `mvn -pl aether/node install -DskipTests -am` for focused rebuild that bypasses the pg-tools lint block.

## Architectural notes

- **Single-writer rule applies to KV membership atoms, not transport hygiene.** Followers routing `DisconnectNode` is safe and necessary for the dead-leader case — the fear that drove the original "leader-only routes" was correct for HealthReconciler atoms but over-applied.
- **Quorum counting must be stable across transient QUIC evictions.** Only `REMOVED` (authoritative via DisconnectNode → unregisterPeer) should drop from quorum count. `EVICTED` is a local-view transient state.
- **LeaderManager's re-election path is fragile.** Multiple entry points (`nodeRemoved`, `nodeDown`, `watchQuorumState`) and state flags (`active`, `proposalInFlight`, `hasEverHadLeader`, `electionRetryCount`, `needsReactivation`) create enough surface area that one race silently kills re-election. Candidate for rewrite using the `integrations/statemachine` library post-RC1.
