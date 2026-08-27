# Session Handover — 2026-04-22

**Branch:** `release-1.0.0-rc1`
**HEAD:** `217db441e` (three commits of this session)
**Tag:** `v1.0.0-rc1-candidate` (to be moved after push)

## Start-here summary

This session replaced the reverted P0 broadcast queue-on-evict with a proper `PeerState` state machine for QUIC per-peer lifecycle (#185 closed), then unblocked the destructive-chain investigation by identifying two harness-level silent failures that had been masking the real bug. The real bug is now named and pinpointed to a specific code path — **leader re-election does not fire after the leader dies**, because follower nodes buffer SWIM FAULTY observations "upstream" to the dead leader.

## What landed (commits, chronological)

- `cb8ee3952` — **fix**: QuicClusterNetwork.broadcast queues for in-topology evicted peers (#185). **Net-negative**; reused the bounded Netty writability queue as the wrong primitive, dropped consensus messages under handshake storms. **Reverted.**
- `133c9f091` — **revert** of the above.
- `46b2c323f` — **refactor**: collapse `QuicClusterNetwork` per-peer state into `PeerState` machine (#185). Phases `INIT → CONNECTING → CONNECTED ⇄ EVICTED → REMOVED`. Separate 10k-entry offline buffer, sealed `OfferOutcome` for atomic SendNow/Queued/Dropped. Five parallel structures (`peerLinks`, `connectingInProgress`, `passivePeers`, `connectionEstablishedAt`, plus reconnect buffer) collapsed onto `Map<NodeId, PeerState>`. 21 unit tests. 481/481 consensus unit tests green.
- `ea4b9fd0b` — **docs**: changelog entry for the refactor.
- `b8073e6f1` — **fix**: `restart_all_nodes` surfaces stderr from compose down/up and fails loudly on non-zero rc. Prior form swallowed stderr via `2>/dev/null`, hiding silent failures of the compose cycle for many test runs.
- `217db441e` — **fix**: `docker-compose-b.yml` restart policy changed from `unless-stopped` to `"no"`. Root cause: `docker kill` sent by `kill_node` exits 137 → Docker's `unless-stopped` policy auto-restarts the "killed" container within seconds → Rabia sees the "dead" leader oscillating and cannot converge on re-election. Cluster A keeps `unless-stopped` (non-destructive, benefits from crash recovery).

## Integration results

### Run A (PeerState refactor only, pre-harness-fixes)
10/15 clean (+1 vs v4 baseline). Cluster A entirely green (9/10, one STORAGE flake). Cluster B destructive chain: 02-chaos 1/4, 03-scaling 0/3, 05-security 1/3, 12-network 1/3, 13-edge-cases 0/3 — all tracking v4 parity because harness was silently doing nothing on restart_all_nodes.

### Run B (PeerState + harness stderr fix + compose restart:"no")
Cluster A again clean (9/10 + 08-resources STORAGE flake). Destructive chain still stuck on the same leader-re-election gap, but now the pinpoint is unambiguous: `docker kill` is authoritative (container `Exited (137)` stays dead), yet remaining nodes still report the dead node as leader.

**Speedups from PeerState:**
- 04-streaming: 310s → 46s (7×)
- 08-resources: 370s → 57s (6.5×)
- 09-artifacts: 218s → 18s (12×)

## Root-cause breakthrough — leader re-election gap

### Evidence (after manual compose cycle with `restart: "no"`)

- Fresh 5-container compose-up converges cleanly: `leaderId = node-1`, epoch advances, QUIESCED.
- `docker kill aether-b-node-1`: container exits 137 and stays dead (RestartPolicy: no confirmed via `docker inspect`).
- `aether -c node-2:port status`: still reports `leaderId = node-1` after 80+ seconds.
- Node-2's log (follower):
  ```
  06:15:11 SWIM member suspected: NodeId[id=node-1]
  06:15:26 SWIM member faulty: NodeId[id=node-1] — follower sensor, buffering observation upstream
  ```
- SWIM detects dead leader in ~1m30s. But the follower buffers the observation "upstream" (per ClusterSync refactor commit 2 — followers are sensor-only, leader's HealthReconciler folds observations).
- **The leader that should process the observation is itself the dead node.** → `HealthReconciler` on the surviving nodes is stepped-down (they're not leader). No component runs to promote the observation to a membership atom change. `LeaderKey` in KV-Store stays pointing at the dead node.

### Concrete fix direction

The gap is in `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/generation/`:

- `PeerConnectivityReporter` (follower sensor) sends observations via `ClusterSyncPong` to the leader.
- `HealthReconciler` (leader) consumes observations and writes membership atoms via consensus.
- When observed peer == current leader: follower must **not** just buffer upstream; it must initiate a special "leader is dead" path that bypasses the dead leader and proposes a new `LeaderKey` value through Rabia directly.

Rough sketch:

```java
// In ClusterSync / PeerConnectivityReporter
if (faultyPeerId.equals(currentLeaderId)) {
    // Leader is dead — can't route observation through them.
    // Propose a new LeaderKey via Rabia consensus (idempotent — first to commit wins).
    leaderElectionTrigger.onLeaderFaulty(faultyPeerId);
}
```

`onLeaderFaulty` writes a `Put(LeaderKey, candidateNodeId)` through Rabia. Any candidate wins; Rabia's serial commit order resolves contention. `LeaderManager.onLeaderCommitted` on all nodes then re-activates coordinators under the new leader.

Candidate selection: simplest is lowest-NodeId-among-healthy-peers from the observer's local view. Rabia serializes; losing proposers see the winner's commit and no-op.

### Why v4 mostly didn't fail this (previous run snapshot claimed 12/15 Docker alpha)

Suspect: v4's `restart: unless-stopped` DEFEATED `docker kill`. The "killed" node auto-restarted, the cluster never actually lost a leader, and tests that *looked* like they were exercising destructive scenarios were only exercising "kill then auto-restart". The ClusterSync single-writer refactor (commit 2) landed AFTER v4's alpha snapshot, so the leader-observation-goes-upstream pattern hadn't yet broken the unmasked destructive path. Fix landing for restart policy + single-writer refactor combined to expose this.

## P0 for next session (blocker for 15/15 Docker)

1. **Leader-faulty direct proposal** — implement the "if faulty peer is current leader, propose new LeaderKey via Rabia" path in the follower sensor (`PeerConnectivityReporter` or equivalent). Files to touch:
   - `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/generation/PeerConnectivityReporter.java`
   - `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/generation/HealthReconciler.java` (may still want the fast path for single-writer correctness)
   - Expose `currentLeaderId()` from `LeaderManager` to the sensor layer
   - Unit tests in `aether/aether-deployment/src/test/java/...HealthReconciler*Test.java`
2. **Run 15-suite** — expect destructive chain to move from 2/18 to most-green once leader re-election fires within 10s of kill.

## P1 (nice-to-have)

- **08-resources STORAGE/KV flake** — 4/5 consistently. `all slice target instances ACTIVE (timed out after 120s)` on the KV test group. Timing-sensitive; likely just needs the activation polling bumped from 120s → 180s OR the root-cause activation-path investigated (pre-existing, not a regression).
- **Pinned MGMT_ENTRY_POINT + kill-non-leader**: `test-kill-non-leader` can kill node-1 (the pinned entry point). `rotate_mgmt_entry_point` runs after but some tests have one-shot-per-suite rotation. Audit.
- **Cluster A blueprint-deploy await-quiesced 500s** — benign warnings, blueprint deploy still works. Worth investigating separately (server-side 500 on the `await-quiesced` endpoint during high-churn windows).

## P2 (backlog)

- **PeerState for `NettyClusterNetwork`** — sibling smear with 6 parallel maps. QUIC is the primary transport; Netty is fallback. Same refactor pattern applies.
- **Generic `statemachine` library adoption** — infrastructure already exists at `integrations/statemachine/` (only consumer is its own test). `SliceState`, `NodeHealth`, `BootstrapPhase`, task-group string-state are all candidates. Post-RC1.

## Open PRs and issues

- Closed this session: **#185** (QUIC: refactor per-peer state into proper state machine).
- No new issues filed this session — the leader-faulty fix is the next concrete P0 but not yet ticketed (inline in this handover for now).

## Environment + conventions

Unchanged from previous session handovers. Remote test host: `192.168.0.71`, user `aether`, key `~/.ssh/aether_test`. Invoke 15-suite with `cd aether/tests/integration && ./run-tests.sh --env remote [--skip-build]` (env vars pre-exported — never reference their values inline).

## File map (this session)

| Path | Role |
|---|---|
| `integrations/consensus/src/main/java/org/pragmatica/consensus/net/quic/PeerState.java` | new — per-peer state machine |
| `integrations/consensus/src/main/java/org/pragmatica/consensus/net/quic/QuicClusterNetwork.java` | rewritten to delegate to PeerState |
| `integrations/consensus/src/test/java/org/pragmatica/consensus/net/quic/PeerStateTest.java` | new — 21 unit tests |
| `aether/tests/integration/lib/cluster.sh` | `restart_all_nodes` surfaces stderr + fails on nonzero rc |
| `aether/tests/integration/docker-compose-b.yml` | `restart: "no"` for destructive cluster |

## Architectural notes

- **Single-writer rule (ClusterSync refactor commit 2)** has a corner case when the single writer is the dead party being reported. Must be handled at the sensor layer with a "leader is the subject" special case.
- **`PeerState.offerOutbound` returns `SendNow(conn)` with the captured connection reference** inside the per-peer monitor — no race window between phase check and connection query. Sealed interface discrimination (SendNow/Queued/Dropped) keeps callers atomic.
- **Compose restart policy per cluster purpose**: `unless-stopped` for non-destructive clusters (resilience), `"no"` for destructive clusters (`kill_node` must be authoritative).
