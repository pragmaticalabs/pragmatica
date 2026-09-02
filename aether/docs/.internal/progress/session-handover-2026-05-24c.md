# Session Handover — 2026-05-24 (c)

**Branch:** `release-1.0.0-rc1` | **HEAD pushed:** `42da013d0` (origin up to date)
**Predecessor:** [session-handover-2026-05-24b.md](session-handover-2026-05-24b.md)
**Status:** in-flight — instrumented Docker S01 trace + Hetzner run launched; this doc to be UPDATED when they land.

## 0. TL;DR
The predecessor's NEXT#1 hypothesis (FSM-SOE fix resolves cluster-B) is **REFUTED**. Real story this session: (1) found + fixed the **cascade/total-collapse** root cause — committed + Docker-validated, cluster B no longer collapses; (2) **KSUID-ified** Docker NodeIds — committed + validated; (3) built an **in-process fast-loop substrate + black-hole fault** — committed; (4) developed two membership fixes (**SWIM bare-join** + **φ-accrual → decommission**) — **STAGED/HELD, NOT committed**, because the **forward-decommission axis still fails on Docker (S01 unchanged)** and the fast-loop black-hole repro may NOT faithfully model `docker kill`. Open root-cause question pending an instrumented trace.

## 1. Committed + PUSHED this session (release-1.0.0-rc1)
| Commit | What |
|---|---|
| `e6367986d` | fix(metrics): SWIM-ALIVE guard on ClusterSync ping-timeout eviction — stops live-peer eviction → self-drain cascade |
| `79944c517` | fix(consensus): idempotent QUIC REMOVE emission (disconnect/departurePermanent) |
| `486e85bca` | fix(deployment): right-size audit lifecycle stream off-heap budget (347→36.6 MiB, fits 128 MiB) |
| `fb81a94ea` | docs(changelog) |
| `d76c7c075` | fix(docker): mint NodeId via IdGenerator (KSUID), eliminate slot-ordinal reuse |
| `9e2546de2` | docs(changelog) |
| `0fc6a663e` | test(membership): in-process black-hole fault injection (reproduces transport-gated decommission gap) |
| `42da013d0` | docs(changelog) |

**Validated (Docker):** cascade ELIMINATED — 0 spurious self-drains, holds ≥ quorum, no total collapse, management plane responsive, off-heap WARN gone. KSUID replacements provision/join/count cleanly (15-delegation 2/2, 04-streaming 4/4).

## 2. STAGED / HELD (uncommitted — decide rollback vs push-forward)
- **SWIM bare-join fix** — `aether/node/.../health/fsm/SwimHealthState.java` + `integrations/swim/.../SwimProtocol.java` (+ 3 tests). Stops SWIM asserting HEALTHY on a bare gossip join/announce (HEALTHY now only from `PeerConnected`/probe-ack; `handleAnnounce` adds unknown as SUSPECT not ALIVE). Fixes the **resurrection** axis (dead node re-admitted via stale-gossip SwimHealthy). Unit-green (swim 80/80, node 34/34), formation-safe, in-process-clean. **Likely KEEP** (genuine correctness fix) but unproven on Docker S01.
- **φ-accrual → decommission fix** — NEW `aether/aether-deployment/.../membership/PhiTransportProbe.java` + `ClusterNetwork.setPeerInboundActivityListener` hook + `QuicClusterNetwork.onMessageReceived` listener + `ClusterSyncCollector.addPingListener` + `AetherNode` wiring. Wires φ-accrual silence (keyed on per-peer QUIC inbound activity — leaderless) into the transport-reachability plane → 2-plane co-confirmation (SWIM-faulty + φ-silence) → decommission. **Validated on the FAST LOOP** (black-hole spike FAIL→PASS, terminal-decommission 3.57s; clean-kill still PASS; 2-plane safety preserved). **BUT Docker S01 unchanged** → see §4.

## 3. The two membership axes (the structural problem)
Membership = N uncoordinated control loops (eviction, SWIM, FSM, CTM, manifest), no sovereign. Two distinct failure axes:
1. **Resurrection** — dead node re-admitted to OnDuty via stale-gossip `SwimHealthy` (no probe-ack). Root: `SwimHealthState.handlePeerJoined` asserted HEALTHY on bare join. → SWIM bare-join fix (held).
2. **Forward decommission-completion** — a killed node never gets a `NODE_FAILED`/`swim-faulty`/`transport-failure` domain event, never reaches terminal `STOPPED`/`DECOMMISSIONED`, lingers stale `ON_DUTY`. This is the **persistent, KSUID-independent** failure (S01, restore_baseline quiesce, scale-down-stuck-at-7, pick_non_leader stale candidates). → φ-accrual fix attempts this (held, unproven on Docker).

## 4. The fidelity question (THE open root cause — resolve first)
Decommission is **transport-disconnect-gated**: the only confirmed path to a decommission domain event is the QUIC channel physically closing (`reason=transport-failure`), feeding the 2-plane co-confirmation gate (SWIM-faulty AND aggregator-quorum UNREACHABLE; C7 CFT false-positive safety). φ-accrual was computed but never wired to lifecycle.

I built an in-process **black-hole** fault (silent, channel stays OPEN) ON THE ASSUMPTION that `docker kill` ≈ black-hole (connection lingers, QUIC idle-timeout disabled). The φ fix made that spike pass. **But Docker S01 is unchanged** (no domain event in 90s, `kv_state='<absent>'`) — identical to runs #1/#2 pre-fix. That S01 fails at the **SWIM-domain-event level too** suggests the assumption may be WRONG: `docker kill` likely **closes** the socket (OS reaps it → transport-disconnect SHOULD fire), making the real S01 bug *"transport-disconnect fires but produces no decommission event"* — a DIFFERENT bug than the *"silence on a live connection"* one φ fixes.

**If so, the fast-loop black-hole repro was not faithful to Docker S01, and the φ fix was validated against the wrong scenario.** This is the open question.

**THE TRACE MUST ANSWER:** on Docker `docker kill`, does the QUIC connection CLOSE (transport-disconnect fires) or LINGER (black-hole)? And where exactly does the detection→event→KV-write chain stop (φ engage? SWIM converge FAULTY? aggregator produce UNREACHABLE? gate confirm? FSM write DECOMMISSIONED?)?

## 5. Fast loop (the dev substrate — USE THIS, not 11-min Docker, for membership logic)
- `aether/forge/forge-tests/.../MembershipChaosSpikeTest.java` — clean-kill cycle, PASSES (~83s).
- `aether/forge/forge-tests/.../MembershipBlackHoleSpikeTest.java` — black-hole fault (committed `0fc6a663e`); the φ fix makes it PASS.
- Run recipe (HCLOUD_TOKEN MUST be unset): `unset HCLOUD_TOKEN; mvn -f aether/forge/forge-tests/pom.xml test-compile failsafe:integration-test -Dit.test=<Test> 2>&1 | tee /tmp/spike.log`
- **CAVEAT (the lesson):** a fast loop is only worth its fidelity. The black-hole spike reproduces *a* failure but maybe not Docker S01's. Confirm fidelity before trusting it.

## 6. Issues
- **#230** — FSM sovereignty / forward decommission-completion (3 design comments).
- **#231** — φ-accrual leaderless detector → lifecycle (this session's φ work + black-hole repro documented).
- **#232** — revive in-process Ember substrate as dev loop (DONE: substrate + black-hole committed).
- **#233** — harness ordinal→port coupling (KSUID replacements; filed, low-priority test-infra).

## 7. NEXT (in order)
1. **★ Instrumented Docker S01 trace** (DEBUG on membership FSM / ReachabilityAggregator / PhiTransportProbe / SWIM / QuicClusterNetwork; kill ONE node; read where the chain stops). Answer §4's question. (LAUNCHED.)
2. **★ Hetzner run** — real-VM timings/env for contrast (LAUNCHED; bail if cloud harness #73 isn't wired; ensure VM teardown — paid).
3. **DECIDE: rollback vs push-forward** the held SWIM + φ fixes, based on §4's answer:
   - If `docker kill` → black-hole (lingers): φ should help — debug why it didn't engage on Docker (DEBUG-level? σ-inflated/too-slow? co-confirmation quorum?). Push forward + tune.
   - If `docker kill` → clean-close: φ doesn't address S01 — the real fix is the transport-disconnect→decommission-event path. Likely KEEP the SWIM fix, rework/rollback φ.
4. Update this handover with results.

## 8. Gotchas
- **HCLOUD_TOKEN** must be unset for any failsafe/forge run (else real paid Hetzner server). Hetzner integration test path = paid VMs; ensure teardown.
- Single-line commits, no body/trailers/Co-Authored-By. Commit directly on release-1.0.0-rc1.
- Delegate Java/tests to jbct-coder, Maven to build-runner, log/Docker investigation to aether-investigator — keep main context for root-cause synthesis.
- Never `-Djbct.skip=true` for aether.
- φ false-positive risk: bursty traffic inflates φ's σ (suppresses false positives but slows true detection); SWIM co-confirmation is the false-positive backstop. Needs a φ-tuning test before any merge.

## 9. Honest validation status
- Cascade + off-heap + KSUID: **committed + Docker-validated.**
- SWIM bare-join fix: **held**, unit-green + in-process-clean, **not** Docker-proven.
- φ-accrual fix: **held**, fast-loop-validated against a **possibly-unfaithful** repro, **Docker S01 unchanged**.
- Cluster B: no longer collapses (real win); chaos/scaling suites still RED on the forward-decommission axis (§4).

## 10. TRACE VERDICT + DECISION (2026-05-24, UPDATE — supersedes §4's open question)

**Instrumented Docker S01 trace (killed node-3, leader=node-1) — DECISIVE:**
- `docker kill` **CLOSES** the connection (NOT black-hole). My black-hole model was WRONG. Transport detects fast: `PhiTransportProbe φ-edge → UNREACHABLE (φ=9.00)` +14s, SWIM FAULTY +31s.
- **φ-accrual ENGAGED on Docker and WORKED**; decommission DID fire: `OnDuty→Stopped` + `NODE_FAILED (reason=swim-faulty)` AND `(reason=transport-failure)`. The detection→decommission chain does NOT stop.
- **REAL S01 BUG = re-projection flapping.** CTM auto-heal re-claims the slot in ~2s; the leader **re-projects the dead node** via external KV write `<absent/UNTRACKED>→OnDuty` (+13s, +40s). Terminal `STOPPED` never STICKS → S01 terminal-state poll keeps seeing it flap → FAIL. **This is the FSM-owns-manifest-removal tombstone axis (the original #230 choice, deferred).**

**DECISION (rollback vs push-forward):**
- **φ-accrual fix → HOLD for #231, do NOT merge into RC1 S01 fix.** Correct + working but solves the wrong scenario for S01 (hung/silent-but-connected, not docker-kill-close) and is untuned (false-positive risk). Genuine future SENSE improvement; keep code staged/documented under #231, don't commit as the S01 fix.
- **SWIM bare-join fix → KEEP but BUNDLE (don't commit alone).** Complementary to the tombstone: HEALTHY only on real `PeerConnected` reachability (no gossip re-health) + tombstone blocks OnDuty re-projection from any source ⇒ terminal sticks, while a genuinely-reconnecting node still rejoins via PeerConnected.
- **BUILD the FSM-owns-manifest-removal tombstone = the real S01 fix.** Terminal `STOPPED+FORCED` that the reducer/external-write handler refuses to promote back to OnDuty. cluster-B `restart:"no"` ⇒ dead id stays dead (safe to tombstone); CTM replacements get fresh KSUIDs (not blocked).
- **Hetzner: NOT run** — trace was decisive; saved the paid PG VM. Invocation is `./run-tests.sh --env cloud --suites 02` but needs `PG_URL` from a paid PG VM (`tools/provision-test-pg.sh`) — only do it if a future question needs the real-VM contrast.

**FIDELITY CAVEAT (the recurring lesson):** the re-projection flapping is a Docker/CTM phenomenon the in-process clean-kill spike does NOT reproduce (MembershipChaosSpikeTest passes clean). The tombstone fix needs a FAITHFUL repro (model the leader/CTM re-projection of a dead id in-process) or careful Docker validation — confirm fidelity before building on it.

**NEXT (revised):** (1) Pin the re-projection DRIVER — is the `<absent>→OnDuty` re-write still `SwimHealthy` (SWIM fix incomplete?) or CTM desired-state reconcile? (tombstone blocks both, but confirm). (2) Build a faithful in-process repro of the re-projection flapping. (3) Implement the FSM tombstone (terminal STOPPED+FORCED un-promotable to OnDuty) on that loop. (4) Bundle SWIM + tombstone, validate fast-loop + Docker, commit. (5) φ → #231 backlog.
## 11. HELD WORK PRESERVED + NEXT-SESSION START

**Held SWIM bare-join + φ-accrual fixes are committed + pushed on branch `wip/membership-tombstone-base` (HEAD `12d815ab0`, off release `54ed3c902`).** 10 files (5 SWIM, 5 φ incl. new `PhiTransportProbe.java`). `release-1.0.0-rc1` working tree is CLEAN at `54ed3c902` (all session commits pushed).

**START NEXT SESSION HERE:**
1. Read this doc (esp. §10 — the decision) + GH #230/#231.
2. `git checkout wip/membership-tombstone-base` — the SWIM fix + φ work are there.
3. Build the **FSM-owns-manifest-removal tombstone** (§10) — the real S01 fix. Optional first: pin the re-projection driver (SwimHealthy vs CTM reconcile) — but the tombstone is driver-agnostic so not required.
4. Bundle SWIM + tombstone, validate (faithful in-process repro of the re-projection flapping + Docker), commit to release. Leave φ for #231 (don't merge as the S01 fix).
5. Fidelity discipline: the re-projection flapping does NOT reproduce in the in-process clean-kill spike — build a faithful repro before trusting the fast loop for this.

**Do NOT re-chase:** the cascade (fixed), off-heap (fixed), KSUID (fixed), the black-hole/φ-detection path (that was an off-target model of S01 — docker-kill CLOSES the connection; decommission DOES fire; the bug is re-projection flapping, not detection).
</content>
