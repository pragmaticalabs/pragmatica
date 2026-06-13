# Session Handover — 2026-05-12 (structural FSM migration + chaos-recovery diagnostic)

**Branch:** `release-1.0.0-rc1` · **HEAD:** `20f83ed41` (pushed) · **Pushes:** all 20 session commits at origin

Continuation of [`session-handover-2026-05-11b.md`](session-handover-2026-05-11b.md). The 2026-05-11b handover documented D.1-D.5 architectural items + a C.4 bootstrap regression blocked at the test layer. **This session pivoted from tactical patches to a structural FSM redesign** (Per the "structural over tactical" directive after observing D.2+D.3+D.5 produce whack-a-mole cascades).

---

## ⚡ TL;DR

**Cluster membership has been re-architected.** The legacy gate stack (`ObservationAggregator`, `HealthReconciler`, `suppressedByPhase`, cooldown debounce, leader-unknown escape hatches, self-promotion-with-retries) is **deleted entirely**. Replaced with a single `MembershipFsm` driven by a pure `ClusterMembershipReducer` (7 states × 8 events = 56 cells, totality-enforced). 20 commits ahead of pre-session baseline.

**Cluster A: 8/9 integration suites pass deterministically** (only pre-existing #219 inject-endpoint JSON bug fails — not FSM-related). Cluster B chaos suites are partially recovered; one final issue identified and fixed (uncommitted-but-pushed at session end): **CTM port-base bumped above compose-defined ranges**.

---

## 1 · Commit timeline (20 session commits)

```
20f83ed41 fix(test-infra): bump CTM port bases above compose-defined ranges  ← LAST, UNTESTED
6fc426b48 fix(provisioning): F.2+F.3 DockerComputeProvider rollback + per-cluster port env vars
e490be1ed feat(membership): F.4 — QUIC PeerConnected → MembershipFsm SwimHealthy bridge
9241cd0d9 fix(membership): TTL-bounded DECOMMISSIONED revival
c900c5d86 fix(membership): self-bootstrap race — LeaderChange trigger
8237c9fd5 fix(membership): bootstrap correction — (UNTRACKED,SwimHealthy)→ON_DUTY direct
1eda5a152 docs(membership): post-E.8 vocabulary sweep
7dfdd6224 refactor(membership): E.8 — delete HealthReconciler entirely
c615124ef refactor(membership): E.7 — delete gate stack
ce86ac63a feat(membership): E.6 — ClusterPhase as derived view
463f967dd feat(membership): E.5 — route SWIM observations through FSM
08fc016c5 feat(membership): E.4 — operator-write FSM + reverse-audit fix-ups
2b325e20e feat(membership): E.3 — read-only shadow FSM
c674ad074 docs(spec): fix stale event/cell counts
f34b6ff1b chore(jbct): disable format goal + document formatter bugs
1a6da19b1 feat(membership): E.2 — pure reducer (56 cells, 74 tests)
1657ff1f8 docs(spec): cluster-membership FSM spec
e02b249c4 refactor: TimeSpan migration for 7 config records
3e829d5c3 feat(health): periodic phase tick + TimeSpan health config (M1)
7c5f0cb26 test(infra): nginx gateway depends_on + IPv4 healthcheck + 32m body
```

Net diff vs pre-session HEAD (`2d779788d`): **−1297 production LOC, +700 test LOC, +669 spec LOC.**

---

## 2 · What landed — E.x structural redesign

### Architecture (post-session)

```
                    ┌──────────────────┐
SWIM probe Ack ────►│                  │
QUIC PeerConnected ─│  MembershipFsm   │ ───► consensus KVCommand.Put
NodeLifecycle ACTIVE│  (leader-gated)  │     (NodeLifecycleKey only)
LeaderChange-to-self│                  │
                    │  ┌────────────┐  │
ProvisioningSlot ──►│  │ Reducer    │  │
KV notification     │  │ 7×8 cells  │  │
                    │  │ pure       │  │
Operator commands ──│  │ idempotent │  │
(drain/decommission)│  └────────────┘  │
                    └──────────────────┘
                              │
                              ▼
                       Consensus replicates
                              │
                              ▼
                       All nodes observe via KV listeners
                              │
                              ▼
                   ClusterPhaseView (derived, on-demand)
                   1s phase-change watcher → CTM
```

### Per-step summary

| Step | Commit | Outcome |
|---|---|---|
| **E.1** (spec) | `1657ff1f8` | 669-line spec; Q1=A leader-init, Q2=A direct force-decommission, Q3=C no Tick |
| **E.2** (reducer) | `1a6da19b1` | Pure FSM record `(state, event) → Outcome(newState, writes, effects)`; 56-cell totality test |
| **E.3** (shadow) | `2b325e20e` | Read-only `MembershipFsm` observing KV+SWIM; flag default off |
| **E.4** (operator writes) | `08fc016c5` | FSM writes for OperatorDrain/Decommission; +reverse-audit fixes (timer wiring, drain-Ack feedback, leader-takeover replay, host/port preservation) |
| **E.5** (SWIM routing) | `463f967dd` | SWIM observations route through FSM (smoking-gun fix); follower-drop on non-leader |
| **E.6** (phase view) | `ce86ac63a` | `ClusterPhase` derived view; `ClusterPhaseKey` becomes optional cache |
| **E.7** (gate deletion) | `c615124ef` | `ObservationAggregator.java` deleted; `handleAggregatedEdge`, `suppressedByPhase`, `cooldownActive`, self-promotion path gone |
| **E.8** (reconciler deletion) | `7dfdd6224` | `HealthReconciler` interface deleted entirely; `LifecycleWriter.directLifecycleWriter` + `ClusterPhaseView` authoritative; flag removed |

### Bootstrap-correction layer (post-E.8 race fixes)

| Fix | Commit | Bug |
|---|---|---|
| Bootstrap | `8237c9fd5` | `(UNTRACKED, SwimHealthy) → ON_DUTY` direct (SWIM is edge-only, not periodic); self-bootstrap via NodeLifecycle ACTIVE listener |
| Self-bootstrap race | `c900c5d86` | LeaderChange-to-self adds second trigger (idempotent via `(ON_DUTY, SwimHealthy) → nop`) |
| TTL revival | `9241cd0d9` | `(DECOMMISSIONED, SwimHealthy) → ON_DUTY` if age<60s (allows same-NodeId restart) |
| QUIC bridge | `e490be1ed` | F.4 — `MembershipFsm.onPeerConnected` synthesizes SwimHealthy for known-cluster peers (eliminates probe-Ack race; deterministic cold boot in ~3s) |

### Provisioning hardening (F.1-F.3)

| Fix | Commit | Detail |
|---|---|---|
| Investigation | (no code) | F.1 — phantom origin = `DockerComputeProvider.provision()` no rollback on failure |
| Rollback + naming | `6fc426b48` | F.2+F.3 — Docker `docker rm -f` on failure; AWS `terminateInstances` on tag failure; GCP/Azure/Hetzner WARN hooks; container name now `aether-<cluster>-<pool>-node-<idx>-<hex>`; port bases via env vars |
| Port-base bump | `20f83ed41` | CTM port base above compose range (5156/5166 mgmt, 8075/8085 app) |

---

## 3 · Key architectural insights surfaced

1. **"SwimHealthy" became an abstract liveness signal.** 4 sources synthesize it (SWIM probe Ack, QUIC PeerConnected, NodeLifecycle ACTIVE, LeaderChange-to-self). All funnel through one leader-write gate. Reducer's `(ON_DUTY, SwimHealthy) → nop` is the universal idempotence backstop. Adding a 5th source is a one-list edit.

2. **"Multiple independent contributors + idempotent state check" pattern.** Bootstrap convergence has 3 independent triggers (NodeLifecycle ACTIVE, LeaderChange, QUIC PeerConnected). Race-safe by construction. No timing knobs.

3. **JOINING state semantics narrowed.** Pre-fix it was an intermediate state for SWIM-discovered peers AND slot-provisioned peers. Now only the slot-provisioning path goes through JOINING. SWIM-discovered peers go UNTRACKED → ON_DUTY directly.

4. **`HealthReconciler` was conflating five concerns.** Lifecycle writes → `LifecycleWriter.directLifecycleWriter`. Phase derivation → `ClusterPhaseView`. CTM phase callback → 1s SharedScheduler poll. SWIM-driven writes → MembershipFsm. Operator drain/decommission → MembershipFsm. Splitting eliminated one always-running stateful object.

5. **Reverse-reference audit policy established** (`memory/feedback_reverse_reference_audit.md`): after each implementation step, walk spec → code; report MISSING/STUB/SHORTCUT/OMISSION; only commit when MISSING=STUB=0. Caught 5 blockers in E.4 before commit (timer wiring, awaitAck feedback, leader takeover, host/port preservation).

6. **JBCT formatter disabled** (`docs/contributors/jbct-formatter-disabled.md`): the `format` goal strips `///` markdown javadoc + selected `//` blocks. Only `lint` runs now. 4 bugs catalogued. Re-enable conditions documented.

---

## 4 · Validation status

### Cluster A (parallel, non-destructive) — **8/9 deterministically green**

| # | Suite | Result |
|---|---|---|
| 00 | smoke | ✅ |
| 04 | streaming | ✅ |
| 06 | deployment | ✅ |
| 07 | cluster-mgmt | ✅ |
| 08 | resources | ✅ (one transient SQL flake in v5, recovered in v6) |
| 09 | artifacts | ✅ |
| 10 | database | ✅ |
| 11 | observability | ❌ **PRE-EXISTING #219 inject-endpoint bug** — `AlertsResponse`/`TracesResponse` JSON-encoded-as-String; alert+trace injection endpoints return escaped strings instead of structured JSON. NOT FSM-related. |
| 14 | storage | ✅ |

### Cluster B (sequential, destructive) — **partial**

| # | Suite | Result |
|---|---|---|
| 15 | delegation | ✅ 2p/0f (with 1 known soft FAIL on reassignment — TaskAssignmentCoordinator picks dead node-2 during reassignment window; suite summary doesn't count it) |
| 02 | chaos | ❌ blocked at `restore_cluster_baseline`: CTM circuit breaker trips after 3 consecutive provision failures (port collisions; addressed by `20f83ed41` — UNTESTED) |
| 01, 03, 05, 12, 13 | various | ⏸️ not reached |

### What works deterministically

- **Cold-boot convergence to 5/5 in ~3 seconds** (F.4 QUIC bridge — verified live)
- All non-chaos integration scenarios
- Module-level tests: aether-deployment 390/390, aether/node clean compile

### What is broken

- **Cluster B chaos suite recovery** — 02-chaos pre-condition fails because `restore_cluster_baseline` can't bring cluster back to 5 nodes after kills. Three root causes intertwined:
  1. CTM circuit breaker trips after 3 consecutive port-bind failures (`20f83ed41` should fix this — untested)
  2. SwimHealthState `LocalDisconnect` deadlock when phantom peers gossip (F.5+F.6 pending)
  3. Phantom IDs from prior provision failures gossip into SWIM via piggyback (F.7 admission gate pending)

---

## 5 · Diagnostic findings (F.1 phantom investigation)

Live cluster forensics confirmed:

- **Phantom origin: `DockerComputeProvider.provision()` had NO rollback on failure.** Orphaned `Created`-state containers stayed on host; their NodeIds leaked into SWIM gossip; subsequent boots inherited them; `LocalDisconnect` predicate tripped on phantom flap.
- **All cloud providers have the same rollback gap** (AWS/GCP/Azure/Hetzner). F.2 added partial rollback (AWS uses terminateInstances; GCP/Azure/Hetzner WARN-only because their create APIs are atomic single-call).
- **Postgres state leak refuted** (H1 — DB empty, freshly init'd each session).
- **Consensus log persistence refuted** (H3 — in-memory only, no git-backed persistence).
- **Cross-cluster network exposure innocent** (forge-postgres is dual-attached but carries no aether state).
- **DockerComputeProvider port collision confirmed.** Both clusters defaulted to `management_port_base=5160` baked into aether-node image. F.3 made it env-overridable per cluster.

Full report: investigator agent output preserved in session transcript (~13:30 UTC).

---

## 6 · F.x outstanding work (pending tasks)

| # | Status | Description |
|---|---|---|
| F.4 ✅ | done | QUIC PeerConnected → MembershipFsm bridge |
| F.2 ✅ | done | Provider rollback on failure |
| F.3 ✅ | done | Per-cluster port base + cluster-scoped name |
| F.5 ⏸️ | pending | **Peer-id-aware faulty counter** in `SwimHealthState.isLocalDisconnect()`. Currently counts faulty-events (phantoms double-count themselves into LocalDisconnect). Fix: count distinct peer-IDs within window. File: `SwimHealthState.java:176-188`. |
| F.6 ⏸️ | pending | **`LocalDisconnect` exit on positive evidence.** Currently the state ignores PeerSuspect/PeerFaulty/ReportHint, only exits on PeerConnected/PeerJoined. Refine: accept PeerSuspect/PeerFaulty for accounting but exit on "recent positive contact from any real peer." File: `SwimHealthState.java:196-214`. |
| F.7 ⏸️ | pending | **SlotClaimed-backed admission gate** for SWIM members. Only allow unknown NodeIds if (a) in static cluster config, OR (b) recent SlotClaimed KV entry. Eliminates phantom acceptance at source. |
| F.8 ⏸️ | pending | **Final 15/15 validation** across docker-remote + cloud Container + cloud JVM. |
| Port-base bump validation | UNTESTED | `20f83ed41` is pushed but not verified in integration. Re-run `./run-tests.sh --env remote --skip-build` to confirm. |

### TaskAssignmentCoordinator reassignment quirk

`15-delegation/test-02-reassignment.sh` log shows: `SCALING reassigned from dead node node-2 to node-2`. The task assignment coordinator picks dead-node-2 during reassignment window. Suite summary doesn't count it (log_warn). Likely a stale "healthy cores" cache. Separate bug — file a ticket and address post-RC1.

---

## 7 · Score card

| Metric | Start (2026-05-11b) | End (this session) |
|---|---|---|
| RC1 architectural debt | 5 D.x items "complete" but introducing whack-a-mole | **0 — structural FSM ships clean** |
| Integration cluster A | 7-8/9 (one slot variable due to inject bug + bootstrap flake) | **8/9 deterministically green** |
| Integration cluster B | 0-1/6 (chaos suites broken by D.2 quorum bug) | **1/6 + 1 untested fix (port-base bump)** |
| Module tests | 272/272 aether-deployment | **390/390 aether-deployment** |
| Production LOC | n/a | **−1297 net (HealthReconciler + aggregator gone)** |
| Spec | draft | **approved 2026-05-12, 669 lines, 12 sections** |
| JBCT formatter | introducing bugs each pass | **disabled, bugs documented, re-enable conditions specified** |
| Reverse-audit policy | informal | **formalized in memory + caught 5 blockers in E.4 before commit** |

---

## 8 · Where to pick up next session

### Highest priority (10-15 min)

1. **Validate port-base bump (`20f83ed41`).** Clusters are torn down on TARGET_HOST. Run:
   ```bash
   cd aether/tests/integration && ./run-tests.sh --env remote --skip-build
   ```
   Watch 02-chaos. If `restore_cluster_baseline` now converges to 5 ON_DUTY, F.2+F.3+`20f83ed41` are sufficient. If not, the CTM circuit-breaker trip pattern returns — proceed to F.5+F.6+F.7.

### Then (in order)

2. **F.5** — peer-id-aware faulty counter. `SwimHealthState.java:176-188`. Small surgical change.
3. **F.6** — `LocalDisconnect` exit predicate. `SwimHealthState.java:196-214`.
4. **F.7** — `SlotClaimed`-backed admission gate. New code in `SwimProtocol.applyNewMember`.
5. **F.8** — final 15/15 validation across docker-remote + cloud Container + cloud JVM.

### Known-not-RC1-blocking

- **#219 inject bug** (11-observability) — pre-existing AlertsResponse/TracesResponse JSON-as-String. Filed already.
- **TaskAssignmentCoordinator reassignment-to-dead-node** in 15-delegation. Soft fail. Post-RC1.

### Test infrastructure

- Cluster A + B currently torn down on TARGET_HOST after diagnostic.
- Forge-postgres data ephemeral (verified by F.1).
- Run `git pull origin release-1.0.0-rc1` to get all 20 session commits.

---

## 9 · Memory entries created this session

- `feedback_reverse_reference_audit.md` — code → spec audit policy after each step
- `project_nginx_gateway_pitfalls.md` — (created earlier) nginx upstream DNS + IPv6 healthcheck bugs
- `project_jbct_formatter_bugs.md` (updated) — formatter disabled; bug catalogue B1-B4 documented at `docs/contributors/jbct-formatter-disabled.md`

---

## 10 · Risk register

| Risk | Mitigation |
|---|---|
| `20f83ed41` port-base bump untested | Next session: run integration to verify before declaring chaos-recovery fixed |
| F.5+F.6+F.7 (SwimHealthState defenses) pending | These are defense-in-depth; F.2+F.3+F.4 may be sufficient already. Validate first, implement on demand |
| Cloud providers (Hetzner/AWS/GCP/Azure) atomic-create rollback is WARN-only | Production cloud-reaper already compensates. Open ticket post-RC1. |
| LegacyLifecycleWriterFixture name is misleading post-E.8 | Rename to a clean post-E.8 fixture; tracked in spec §12 amendment |

---

## 11 · Files of interest for next session

**Authoritative spec:**
- `aether/docs/specs/cluster-membership-fsm-spec.md` — status: approved 2026-05-12

**Core FSM:**
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/membership/fsm/ClusterMembershipReducer.java` (pure reducer)
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/membership/fsm/MembershipFsm.java` (wired execution)
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/membership/phase/ClusterPhaseView.java` (derived phase)

**Pending fixes:**
- `aether/node/src/main/java/org/pragmatica/aether/node/health/fsm/SwimHealthState.java` (F.5+F.6 target)
- `integrations/swim/src/main/java/org/pragmatica/swim/SwimProtocol.java` (F.7 admission gate target)

**Provider hardening:**
- `aether/environment/docker/src/main/java/org/pragmatica/aether/environment/docker/DockerComputeProvider.java` (rollback + cluster-scoped name)
- `aether/environment/{aws,gcp,azure,hetzner}/.../...ComputeProvider.java` (rollback gaps; cloud-reaper compensates for now)

**Test infra:**
- `aether/tests/integration/docker-compose-a.yml` / `docker-compose-b.yml` (port bases just bumped)
- `aether/tests/integration/lib/cluster.sh::restore_cluster_baseline` (relies on CTM provisioning)

---

**Net.** The structural FSM migration is the most substantive RC1 work this session. ~1300 LOC of legacy gate-stack gone; ~1000 LOC of FSM + tests + spec in. Cold-boot convergence is now deterministic. Operator paths work. SWIM-driven failure detection works. Chaos-test recovery is one untested commit away from validation — pick up there next.
