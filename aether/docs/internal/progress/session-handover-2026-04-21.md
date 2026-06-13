# Session Handover — 2026-04-21

**Branch:** `release-1.0.0-rc1`
**HEAD:** `2b2506947`
**Ahead of origin:** 85 commits

## Start-here summary

This session turned a 0/3 03-scaling regression into a full architectural pass on Aether's cluster-view single-source-of-truth (SSOT) + an end-to-end test-harness overhaul. Eight product bugs fixed with regression tests; 9 commits of helper/runner hardening; two CLI bugs found and fixed; a spec + RC1 ticket created for parallel multi-tenant testing (#184). The one-stop-short-of-15/15 remaining work is **consensus recovery after mass restart under a reconnect storm** — the Rabia vote-rebroadcast fix is shipped but timing evidence suggests a second vector remains in `QuicClusterNetwork` message buffering.

## Quick status

| Surface | State |
|---|---|
| 03-scaling (isolated) | **3/3 green, 244 s** |
| 02-chaos test-kill-leader (isolated) | **5/5 green** (prior: 0/4 under regression) |
| 15-suite sequential run (v4, in-flight) | 9/15 clean + 08-resources 4/5 + 02-chaos 1/4; currently in 03-scaling stall |
| CLI `cluster generation`/`await-quiesced` | **Fixed** — now honours `-c/--endpoint/--api-key` |
| SSOT + leader architecture | Designed + committed; snapshot-as-leader-bootstrap path live |
| Parallel multi-tenant testing | Specced (aether/docs/specs/parallel-multitenant-integration-test-spec.md), issue #184 |

## What landed (commits, chronological)

### Phase A — SSOT hardening

- `7f3961c85` **A2** — Serialized reconciler re-projection queue. Eliminates PUT-flood race where two listeners observed overlapping KV projections.
- `12dfb5329` **A1** — CTM ownership via `NodeLifecycleValue.provisioningSource` field (replaces `aether-core-*` prefix heuristic). Envelope bump 1001→1002.
- `3eab34d07` **A3/A4/A5** — Governor announcement re-projection + eviction-failure re-projection + CAS-loss logging in NodeReconcilerState.
- `bd23575b4` **A6** — `nodesWithoutSlices` projected into `ClusterGenerationSnapshot`. Envelope bump 1002→1003.
- `da5b8af41` **A6-fix** — `ClassCastException` on mixed KV keys (LeaderKey leaked into NodeArtifactKey iteration). Regression test both-way verified.

### Phase B — Leader-storm and stuck-reconcile

- `efcc9261a` **Bug Z** (later reverted) — Initial attempt to stop new-node leader storm by adopting kvStore LeaderKey on startup.
- `019648ba4` **Bug W** — CTM re-dispatches `reconcile` when target changes mid-`Reconciling`. Fixed scale-up-then-down stall.

### Phase C — Test harness B1–B4 + CLI fixes

- `8305da381` **B1-B3** — Pinned `MGMT_ENTRY_POINT` to node-1, stripped client-side port-hopping failover from `aether_failover`, `direct_api_get/post`, `start_mgmt_load`.
- `b313083d9` **B4** — `rotate_mgmt_entry_point` helper + `test-kill-leader` rotates after kill.
- `a365f7568` — Fixed bash `$?`-after-`fi` pitfall in `generation.sh` — caller was logging `rc=0` regardless of CLI exit code.
- `4189c9e7d` — CLI fix: `ClusterHttpClient` now honours `-c/--connect/--endpoint` and `--api-key` instead of silently falling back to local `ClusterRegistry`. Covers `cluster generation`, `cluster await-quiesced`, and any future `cluster <sub>` command.
- `3f7e91141` — `aether_failover` one-level health probe + rotation when pinned endpoint is down (for destructive suites).

### Phase D — Leader-as-SSOT experiment + revert

- `71502e833` + `58c8da91c` **(reverted)** — Made `LeaderKey` ephemeral (skip `KVStore.handlePut` storage + strip from `restoreSnapshot`). Caused consensus convergence stall on chained destructive tests (mass-restart deadlock).
- `cf1e8f10c` **Snapshot-leader** — New nodes learn leader from snapshot-bearing `ClusterSyncPing` (only the leader emits snapshots). `NodeSnapshotCache.leaderObserver` wires to `LeaderManager.onLeaderCommitted`. Guard in `AetherNode`: bootstrap only, never overrides a Rabia-committed leader.
- `87effe57b` — Snapshot-leader gated on ping advance (rejects stale buffered pings from a killed leader).
- `bef69acbc` **Revert** of ephemeral `KVStore.handlePut` / `stripEphemeralLeaderKey` — caused consensus convergence stall in chained destructive tests (user-directed revert after `bef69acbc` did real harm). Snapshot-leader bootstrap (above) kept for the scenario Bug Z was meant to solve.

### Phase E — Test harness strict assertions + Rabia recovery

- `429548a0c` — `api_get/api_post` probe+rotate when `CLUSTER_ENDPOINT` is down; `restart_all_nodes` waits for leader.
- `de8e52eea` — `restart_all_nodes` replaces `log_warn` with `log_fail` on recovery failures. No more broken-cluster hand-off to next suite.
- `473e1532d` — **Two fixes combined:** (a) `RabiaEngine.checkPhaseStall` re-broadcasts own `VoteRound1`/`VoteRound2` in addition to `Propose` (votes idempotent at receiver); (b) `restart_all_nodes` uses `docker compose -f docker-compose-b.yml down -v && up -d` instead of `docker start` — avoids identical-NodeId double-initiate QUIC race on mass restart.

### Phase F — Specs + documentation

- `2b2506947` — **Spec** for parallel multi-tenant integration testing + chaos-under-real-load (aether/docs/specs/parallel-multitenant-integration-test-spec.md). Filed as issue **#184** (RC1 label). The "proof-of-proofs" multi-tenant validation.

## What's validated (green)

- **03-scaling run in isolation**: 3/3 pass, 244 s.
- **02-chaos test-kill-leader in isolation**: 5/5 pass.
- **Non-destructive suites in sequential run**: 00, 04, 06, 07, 09, 10, 11, 14, 15 — all green.
- **08-resources**: 4/5 (1 flake: task-group STORAGE `ASSIGNED→ACTIVE` transition timing).
- **460/460 consensus unit tests** pass with Rabia vote-rebroadcast change.
- **329/329 node+deployment+cluster unit tests** pass with snapshot-leader bootstrap.

## What's still red (v4 in-flight as of this handover)

**In 15-suite sequential run:**
- 02-chaos **1/4** (was 0/4 → 2/4 at various points → 1/4 now with latest fix — regression likely from compose down/up timing OR new vote-rebroadcast unexpected interaction).
- 03-scaling — entered with bad cluster state from 02-chaos; stuck on `No leader available, falling back to direct_api_post`, node-1 submitting Rabia proposals without commit.
- 05-security, 12-network, 13-edge-cases — not yet reached; expected to inherit churn.

## Root-cause understanding (per aether-investigator report)

The consensus convergence stall on mass restart has two vectors:

1. **QUIC handshake storm** (PRIMARY, partial fix shipped): 5 nodes starting simultaneously re-use identical NodeIds/hostnames. Both sides of each QUIC link try to initiate → `peerLinks` flaps → `QuicClusterNetwork.broadcast` only sends to peers currently in `peerLinks` → consensus messages to transiently-evicted peers are dropped. **Fix shipped in `473e1532d`**: re-broadcast VoteRound1/VoteRound2 from the stall detector (votes are idempotent). **Fix operationally mitigated in `473e1532d`**: `docker compose down -v && up -d` in place of `docker start` for cleaner pre-stop.

2. **Broadcast drops consensus traffic when peer is transiently out of `peerLinks`** (REMAINING). Spec in investigator report §3 ("Secondary code"): `QuicClusterNetwork.broadcast` should enqueue into per-peer backpressure queue (`outboundQueues`) for peers still in topology but out of peerLinks, instead of silent drop. `cleanupPeerQueues` on eviction must skip for peers `topologyManager.get(peerId).isPresent()`. Not yet implemented this session. **This is the most likely remaining blocker for 15/15 on Docker.**

## Known issues + priorities for next session

### P0 (blocker for 15/15 Docker)

1. **Implement QUIC broadcast queue-on-evict** — the second vector identified by investigator. File: `integrations/consensus/src/main/java/org/pragmatica/consensus/net/quic/QuicClusterNetwork.java`:
   - `broadcast(...)` enqueues into `outboundQueues` when `peerLinks.get(peerId) == null` if `topologyManager.get(peerId).isPresent()`.
   - `evictStaleConnection(peerId)` / `cleanupPeerQueues(peerId)` skips queue deletion when peer is still in topology.
   - Guard against queue unbounded growth via existing `MAX_BACKPRESSURE_QUEUE_SIZE`.
   - Run `mvn test -pl integrations/consensus` to validate.

2. **Verify 02-chaos recovers** after above fix. Isolated + chained.

### P1 (blocker for Hetzner)

3. **Run 15-suite on Hetzner** — same scenarios, different network/latency profile. Expect QUIC handshake timing to differ.
4. **08-resources task-group STORAGE timing** — `ASSIGNED→ACTIVE` transition tight on remote under load. Likely just a timeout bump.

### P2 (RC1-labeled, post-stabilization)

5. **#184 — parallel multi-tenant testing + chaos-under-real-load.** 4–6 days. Real multi-tenant validation; replaces synthetic k6 load.

### P3 (backlog)

- Destructive-suite cleanup still mixes `docker rm -f` (CTM nodes) + `docker compose down -v` (b-nodes) — consolidate.
- Test-harness per-tenant log correlation with epoch markers (prereq for #184).

## In-flight at session end

- v4 full-suite run (`/tmp/full-v4.log`), PID 66416, currently in 13-edge-cases (last suite). All prior suites have reported; final tally below.

## Final v4 tally (Docker remote, HEAD `2b2506947`)

**Clean pass (9/15 suites):** 00-smoke (2/2), 04-streaming (4/4), 06-deployment (5/5), 07-cluster-mgmt (4/4), 09-artifacts (3/3), 10-database (3/3), 11-observability (5/5), 14-storage (2/2), 15-delegation (2/2).

**Partial (1):** 08-resources 4/5 — STORAGE task-group `ASSIGNED→ACTIVE` transition timeout under load (pre-existing flakiness).

**Destructive-chain failures (5):** 02-chaos 1/4, 03-scaling 0/3, 05-security 1/3, 12-network 1/3, 13-edge-cases in-flight at session end.

### Comparison to prior runs

| Run | 02-chaos | 03-scaling | Notes |
|---|---|---|---|
| Pre-session (stale image) | 3/4 | N/R | Client-side port-hopping masked broken-cluster state |
| Post-SSOT fixes (v2, revert path) | 3/4 (2/4 seen) | 3/3 isolated / 0/3 chained | Client failover stripped — exposed real issues |
| v3 (post ephemeral-LeaderKey revert) | 2/4 | 0/3 chained | |
| v4 (Rabia vote rebroadcast + compose down/up) | 1/4 | 0/3 chained | No observable improvement over v3 in destructive chain |

### Key takeaway

**The Rabia vote-rebroadcast fix (`473e1532d`) did not move the needle for destructive chains.** Votes weren't the bottleneck — the initial `Propose` broadcast drops to transiently-evicted peers is the dominant failure vector. The investigator's §3 "Secondary code" recommendation — QUIC broadcast queue-on-evict — is the real unblocker. Specific change:

- `integrations/consensus/src/main/java/org/pragmatica/consensus/net/quic/QuicClusterNetwork.java`:
  - `broadcast(...)` enqueues into `outboundQueues` when `peerLinks.get(peerId) == null` AND `topologyManager.get(peerId).isPresent()`.
  - `cleanupPeerQueues(peerId)` / `evictStaleConnection(peerId)` skips queue deletion when peer is still in topology.
  - Queue size still bounded by `MAX_BACKPRESSURE_QUEUE_SIZE`.

Expected outcome of that change: all 5 destructive-chain suites improve substantially; 15/15 achievable. Hetzner run after that confirms network-profile-independent.

### 08-resources STORAGE timing

Single-test flake in an otherwise green suite. Likely just needs the assertion timeout bumped from 60s → 120s, OR the STORAGE task group's post-ACTIVATING settle window extended. Low priority relative to the destructive-chain work.

## Artefacts and file map

| Path | Role |
|---|---|
| `aether/docs/specs/parallel-multitenant-integration-test-spec.md` | Spec for #184 |
| `aether/tests/integration/lib/cluster.sh` | `restart_all_nodes` strict recovery + compose down/up |
| `aether/tests/integration/lib/common.sh` | `aether_failover` probe+rotate; `api_get/api_post` rotation; `MGMT_ENTRY_POINT` pin |
| `aether/tests/integration/lib/generation.sh` | `await-quiesced` CLI exit-code capture fix |
| `aether/tests/integration/lib/load.sh` | `start_mgmt_load` via single `MGMT_ENTRY_POINT` |
| `integrations/consensus/src/main/java/org/pragmatica/consensus/rabia/RabiaEngine.java` | `checkPhaseStall` re-broadcasts VoteRound1/2 |
| `integrations/consensus/src/main/java/org/pragmatica/consensus/rabia/PhaseData.java` | `getRound1Vote`/`getRound2Vote` accessors |
| `aether/cli/src/main/java/org/pragmatica/aether/cli/cluster/ClusterHttpClient.java` | endpoint+api-key override |
| `aether/cli/src/main/java/org/pragmatica/aether/cli/AetherCli.java` | wires override at startup |
| `aether/node/src/main/java/org/pragmatica/aether/node/generation/NodeSnapshotCache.java` | leaderObserver via snapshot-bearing ping |
| `aether/node/src/main/java/org/pragmatica/aether/node/AetherNode.java` | leaderObserver guard (empty-only) |

## Environment + conventions

- **Remote test host**: `192.168.0.71`, user `aether`, key `~/.ssh/aether_test`. Containers: `aether-b-node-1..5` + `aether-a-node-1..5` + `forge-postgres`.
- **Compose files**: `aether/tests/integration/docker-compose-{a,b}.yml`.
- **Run command**: `cd aether/tests/integration && TARGET_HOST=192.168.0.71 AETHER_SSH_USER=aether AETHER_SSH_KEY=$HOME/.ssh/aether_test ./run-tests.sh --env remote [--suites N]`.
- **CLI**: `aether -c host:port --api-key $AETHER_API_KEY <cmd>` — `-c` and `--api-key` now work for all cluster subcommands (previously they didn't for `cluster generation`/`await-quiesced`).

## Open PRs and issues

- Merged this session: #181 (SQL validation), #183 (peglib 0.2.2).
- Closed this session: #182 (investigation-only, not prod).
- Filed this session: **#184** (parallel multi-tenant + chaos-under-real-load, RC1 label).

## Architectural notes (for future reference)

- **Leader identity is cluster-view SSOT**. Both Rabia LeaderKey commit (authoritative) and snapshot-bearing ping (bootstrap-only) converge into `LeaderManager.onLeaderCommitted`. Fix direction going forward: any new "leader-discovery path" must follow this pattern — authoritative consensus channel + bounded bootstrap shortcut, with the shortcut only firing when no leader is known.
- **Ephemeral-vs-persistent `LeaderKey`**: tried to make it ephemeral; consensus didn't tolerate it (convergence stall). Left persisted for now. If revisited, the fix is at the protocol layer (Rabia's sync response should carry current leader as metadata, not through KV commands).
- **Rabia vote idempotency** is now used by the stall detector. If future changes make votes non-idempotent (e.g., server-side deduplication changes), the re-broadcast logic in `checkPhaseStall` must be revised.
- **Test harness pinned-entry-point** conflict: strict entry-point enforces the forwarding contract; destructive suites need `rotate_mgmt_entry_point` after killing the pinned node. Currently one-shot in `test-kill-leader`; any new test that kills the pinned node must call `rotate_mgmt_entry_point` after the kill.
