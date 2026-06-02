<!--
SPDX-License-Identifier: BUSL-1.1
Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
-->

# Session Handover — 2026-06-02 — ROOT CAUSE of membership-convergence churn found + fixed: SHA-256 BatchId (consensus divergence) + WriteTimeout removal (stream churn); batch-oriented StateMachine refactor

## ⚡⚡⚡ LATEST (06-02 session-3) — QUIC ACCEPTOR RECEIVE-WEDGE root-caused + FIXED (committed `b90ac4728`)

The dominant RC1 blocker behind the residual readiness/erosion was a **QUIC acceptor receive-wedge**: under churn an acceptor keeps a stale "zombie" connection (its `isActive()` stays true — `maxIdleTimeout` disabled, no acceptor close-listener) and `PeerState.resolveDuplicate` returned **DUPLICATE for a same-initiator re-dial**, closing the peer's fresh live stream and never draining it → writer backpressures (4776×) → `evictStaleConnection` → 5s redial → ∞. The wedged replacement never reached the leader's `reportedStates` → 600s READY timeout → cluster erosion. Root-caused via live Docker forensics (acceptor re-accepts every 5s, 0 inbound consensus drained, evict-stale only on that pair).

**FIX (committed `b90ac4728`, 11 files, all 586 consensus unit tests green):** restored natural establishment (`0b7f39905`: drop dial-gate + grace-dial, `prefersInitiator` tiebreak, `AttachOutcome.REPLACED`, `connectionInitiatorId`, `clientInitiated` plumbing) **+ THE FIX** — `PeerState.resolveDuplicate` now ADOPTS the new connection on a same-initiator re-handshake (REPLACED, isolated `closeDroppedConnection`, no REMOVE cascade) instead of trusting the zombie incumbent's `isActive()`.

**Docker-validated (00-smoke + 02-chaos):** evict-stale **764→0**, 600s READY timeouts **multiple→0**, formation intact, generation quiesce 9/3, **no connection churn**. Natural establishment alone was REFUTED first (churned identically) — the adopt-on-re-handshake was the missing piece; both committed together.

**RESIDUAL TRIAGE (investigator ae34b9b, verdict): the remaining 02-chaos 4p/2f is ~75% TEST-SIDE; product behavior is CORRECT.** (1) `generation did not quiesce 90s` ×3 — quiesce is correctly DEGRADED while any core has a non-HEALTHY SWIM hint; under 2 concurrent fresh replacements, all-HEALTHY-within-90s is legitimately tight (dominant cost = consensus catch-up to `isActive`). (2) `pick_non_leader 1/2` (Kill_2) — test-side structural cap: among 3 survivors, leader + pinned-MGMT-entry-point exclusion + brief re-election READY flicker. (3) `No-NODE_LEFT` never-HEALTHY victim — test-side: event assertion on a victim with no `coreMemberIds`-delta; membership-absence is the right signal. Also: `swim_hints_ttl` is effectively **15s at the node** (`AutoHealConfig.DEFAULT`) but the TOML value never reaches the node — a real but benign `AutoHealSpec`↔`AutoHealConfig` wiring gap (no conversion; `Main.java` never calls `.autoHeal(...)`).

**HARNESS TUNING (user-approved test-side close) — UNCOMMITTED, Docker-validation IN FLIGHT:** (A) baseline-restore quiesce gate `90s→180s` (`cluster.sh` restore step 6 ~:1965); (B) `PICK_NON_LEADER_TIMEOUT 60→120` (`cluster.sh` ~:303, ride out re-election flicker); (C) dual-signal departure in `test-kill-multiple.sh` (~:51,59) + `test-kill-node.sh` (~:49): pass if `wait_for_node_departure` (event) OR `wait_for_node_removed` (membership-absence) confirms gone. `bash -n` clean; lint 0-new (edited files carry no baseline findings). A 02-chaos validation run is in progress (`/tmp/harness-val.log`) — **confirm result; if green (~6p/0f), commit the harness changes** (chore: single-line, e.g. `test(02-chaos): realistic readiness windows + dual-signal departure`).

**NEXT STEPS:** (1) finish + read the in-flight harness-tuning validation; commit if green. (2) **TIMING COLLECTION (user request):** run 02 with `--skip-teardown` and extract per-stage replacement timelines from node logs — container-start→JVM→`NodeDeploymentManager activated`→first `QUIC Hello complete`→SWIM-HEALTHY→`RabiaEngine.activate()` (isActive)→`signalling self-ready`→READY-in-leader-`reportedStates`; plus generation DEGRADED→QUIESCED and leader re-election durations — to quantify where the readiness gaps actually are (decide if a consensus-catch-up speedup is worth it). (3) optional product hardening: bridge `AutoHealSpec`→`AutoHealConfig` (call `.autoHeal(...)` in `Main.java`) so TOML `swim_hints_ttl` reaches the node + align `decommissionedRetention` 60s-vs-24h. (4) full 15-suite run for RC1 status (cluster A was 10/10 green earlier this session).

**Commits this session (all on `release-1.0.0-rc1`, unpushed ~58 ahead):** `cd5193f3c` naming · `d3cbed9f8` swim-resurrection-tombstone · `435ce8e07` clear-on-recovery+TTL15s · `5d259b15d` handover · `b90ac4728` QUIC natural-establishment+adopt-on-re-handshake · `508f53b10` handover. **Uncommitted:** harness tuning (3 files above) — pending validation.

---

## ⚡⚡ SESSION-2 UPDATE (06-02 continued) — A-cascade root-caused + largely fixed

Continued from the TL;DR below. This session **root-caused and fixed the (A) cascade**, validated on Docker (two suite-02 runs). **Net: 02-chaos went 3p/3f → 4p/2f, runtime halved (1875s→946s), BatchId collisions 0, error-rate-under-load 80.87%→0.00%.** All remaining failures trace to ONE residual root (replacement readiness-latency).

**Three fixes landed this session (UNCOMMITTED — hold for review; 9 files, 2 cohesive groups):**
1. **Node names carry the cluster name** (`aether-<cluster>-node-<ulid>`, was bare `node-<ulid>`). Root: `LeaderReconciler.computePeersToProvision` minted `NodeId.randomNodeId()` (=`node-<ulid>`) with no cluster-name access. Fix: thread a `clusterNameSupplier` (KV `ClusterConfigValue.clusterName`, sourced in `AetherNode` like CTM does) into `LeaderReconciler`; mint via `NodeId.randomNodeId(prefix)` where prefix = `ProvisionContext.coreNodeNamePrefix(cluster)` (centralizes the `aether-<cluster>-node` convention previously duplicated in ProvisionContext/Docker/Hetzner providers). **Docker-confirmed:** replacements now named `aether-b-node-<ulid>`, one even became leader. Files: `NodeId.java`, `ProvisionContext.java`, `DockerComputeProvider.java`, `HetznerComputeProvider.java`, `LeaderReconciler.java`, `AetherNode.java`, `LeaderReconcilerTest.java`.
2. **SWIM resurrection-loop fix (THE A-ready dominant root)** — a node killed while NEVER-HEALTHY (JOINING/SYNCING, e.g. the S01 scenario) was never tombstoned (the #231 tombstone is gated on `everSeenHealthy`), so its stale QUIC Hello re-completed → `addSeedMember` re-admitted it → SUSPECT→FAULTY→REMOVE→repeat at ~1 Hz; each FAULTY edge republished a generation snapshot → counter climbed unbounded (to 1:3608) and `quiescence=DEGRADED` forever → `generation-not-quiesce` + (turbulence) `pick_non_leader 1/2` + (forwarding disruption) 80.87% error-rate. Fix: `SwimProtocol.tombstoneOnFaultyEdge` (renamed from `tombstoneIfProvenHealthy`) tombstones a FAULTY id when `(!isBooting) OR everSeenHealthy` — i.e. in NORMAL/RECOVERING tombstone even never-HEALTHY ids; COLD_BOOT keeps the `everSeenHealthy` gate so slow formation seeds are NOT tombstoned (the invariant a prior non-gated attempt broke). Sweep backstop relaxed identically. Files: `SwimProtocol.java`, `SwimProtocolTombstoneTest.java` (added NORMAL-phase test `neverHealthyId_faultyInNormalPhase_isTombstoned_reAddRefused`; re-scoped 2 existing tests to explicit COLD_BOOT). 101 SWIM tests pass. **Docker-confirmed:** generation now quiesces (7 PASS vs unbounded churn before); error-rate 0%.
3. **A-enum harness REVERTED to prefix-based** — the membership-based `running_core_containers`/`core_node_ids` workaround (uncommitted last session) was REVERTED to HEAD. Reason: under churn the health-filtered `coreNodes` array collapses to 1 (only the leader stays consistently healthy), so membership-enumeration is fragile; the prefix-based `docker ps --filter name=aether-b-node-` is health-INDEPENDENT and — now that fix #1 makes replacements carry the prefix — catches all 5. **Docker-confirmed:** `Pick_3_victims` enumeration `Pre-kill: 5` (incl. 2 ULID replacements), self-drain file 7p/0f (was 4p/3f). lint-baseline also reverted (no drift).

**RESIDUAL ROOT (root-caused + fix #3 implemented, Docker-validation in flight): replacement READINESS-LATENCY = stale SUSPECTED hint not cleared on recovery.** Investigator (a6a3f1c) found it is NOT consensus catch-up (eager/cheap) nor NTT up-hysteresis (~2s) — it is the **60s SUSPECTED-hint TTL**: during kill turbulence a node is briefly SWIM-SUSPECTED → `SwimHealthState.handle` routes `PeerSuspect→reportHint(SUSPECTED)` → leader `SwimHintsRegistry` stores it w/ TTL; `ClusterGenerationProjector.deriveClusterQuiescence` returns DEGRADED while ANY core member has a SUSPECTED/FAULTY hint (`:334`). The `Running` (steady-state) FSM lost the `reportHint(HEALTHY)` on the recovery edge that the two other states have (it routes `PeerConnected→markAliveFromTransport` only), so a recovered node's stale SUSPECTED hint lives the full TTL → quiesce misses 90s, pick_non_leader misses 60s. `No NODE_LEFT` for a never-HEALTHY victim is EXPECTED (NodeRemoved is a `coreMemberIds` delta; never-core node = no removal event) → test-side victim-selection, NOT a SWIM-fix regression.

**Fix #3 (user-approved: clear-on-recovery + configurable TTL reduction), implemented + unit-tested, Docker-validation pending:** (A) `SwimHealthState.Running.promoteKnownMember` now emits `ctx.reportHint(peer, HEALTHY)` after `markAliveFromTransport` (restores symmetry; safe — current-member branch only, tombstone-refused nodes can't reach it) → clears the stale SUSPECTED hint immediately (chain confirmed: reportHint(HEALTHY)→PeerHealthObservation→`SwimHintsRegistry.onPeerHealth`→clear). (B) `DEFAULT_SWIM_HINTS_TTL` 60s→**15s** (= `nttDepartureTimeout`; > `suspectTimeout`=10s). Files: `SwimHealthState.java`, `AutoHealSpec.java`, `AutoHealConfig.java`, `SwimHealthFsmTest.java`, `SwimHintsRegistryTest.java`. Tests: FSM 13/13, registry 12/12, config 282/282.
> **LATENT CONFIG DISCONNECT found (separate, flag to maintainer):** `swim_hints_ttl` parsed from TOML lands in `AutoHealSpec`, but the running node builds `SwimHintsRegistry` from `AutoHealConfig.DEFAULT` (`AetherNode:1716`); `AutoHealSpec`↔`AutoHealConfig` are parallel records with NO conversion site, so the TOML knob never reaches the node (affects ALL auto-heal fields, not just TTL). Fix #3-B lowers BOTH defaults so the runtime backstop is 15s; plumbing TOML→node is a separate decision.

**Commit plan (HOLD for user review), 3 cohesive groups:** (a) `fix(membership): replacement node ids carry cluster name` (7 files) — VALIDATED; (b) `fix(swim): tombstone never-HEALTHY FAULTY nodes in NORMAL phase to stop resurrection loop` (2 files) — VALIDATED; (c) `fix(membership): clear SWIM SUSPECTED hint on recovery + reduce swim-hints TTL default to 15s` (5 files) — correct but suite-NEUTRAL (see runs). HEAD still `c7bc1cad4`.

### Three Docker runs + the DEEP RESIDUAL (consensus sync-completion / isActive-stuck)
- **run1** (naming only): 02-chaos 3p/3f, 1875s.
- **run2** (+SWIM resurrection fix): **4p/2f, 946s — CLEAN**; error-rate-under-load 80.87%→0%; generation quiesces (was unbounded→3608); self-drain file 7p/0f (A-enum fixed).
- **run3** (+clear-on-recovery+TTL): 3p/3f, 1873s. The regression vs run2 is **VARIANCE in the consensus-readiness erosion, NOT fix #3** (max epoch 184 < run2's 198; no churn). Fix #3 is architecturally correct + surfaced the config disconnect, but its suite effect is in the noise.
- **DEEP ROOT (run3 smoking gun):** a baseline restore logged `4+ healthy cores present (0s)` PASS but `4+ cores reporting READY (target=5) timed out after 600s`, then `only 3 cores READY within 300s`. **Cores are SWIM-HEALTHY but `RabiaEngine.isActive()==false` (stuck Syncing) for >300–600s → never READY → READY-count erodes to 3–4** → `pick_non_leader 1/2`, intermittent `generation did not quiesce`, and the self-drain precondition `5 healthy cores: got 4`. This contradicts the "catch-up is eager/cheap" assumption → a joining node SyncRequests but **never COMPLETES sync** (insufficient/missing SyncResponses when multiple replacements churn, or `activate()` never reached). VARIABLE run-to-run (run2 dodged it). This is the real remaining RC1 blocker — a CONSENSUS-layer issue, independent of SWIM/naming.
- **NEXT:** dedicated consensus investigation — trace `RabiaEngine` sync (SyncRequest → SyncResponse collection `syncQuorumSize` → `restoreState` → `activate()`); repro with `--skip-teardown` and capture a stuck node's engine state + sync logs (why isActive stays false for minutes). Likely a product fix in consensus sync, possibly + test-side (baseline wait strict-5 READY, widen `pick_non_leader` budget, victim-selection avoid never-HEALTHY). The `No NODE_LEFT`-for-never-HEALTHY case is confirmed EXPECTED → test-side only.
- **Recommendation:** commit (a)+(b) (validated by run2); hold (c) for a confirmatory re-run OR commit as a low-risk correctness fix (no churn, restores FSM symmetry) — user's call. Then pivot to the consensus sync-completion root.

---

## ⚡ START HERE / TL;DR (session-1, 06-02 morning)

This session **found and fixed the real root causes** of the long-standing cluster-B membership-convergence failure, after the connection-direction/SWIM investigation turned out to be chasing **symptoms**. The breakthrough came from the user's directive to **"note exceptions in the log"** — which surfaced two genuine roots that pre-date all the membership work:

1. **`RabiaEngine.mergeOrKeep` BatchId collision** — `BatchId = "batch-" + Integer.toHexString(commands.hashCode())` (a 32-bit, JVM-non-deterministic hash). Different command batches collided on one id → `mergeOrKeep` dropped one → **consensus divergence**. **FIXED** with a SHA-256 content id, via a batch-oriented StateMachine refactor.
2. **`WriteTimeoutHandler` killing live-but-backpressured CONSENSUS streams** → `ClosedChannelException` → evict-stale → **redial churn**. **FIXED** by removing the handler (the retry + Rabia retransmit + SWIM-removal is the intended non-destructive backstop).

**Docker-validated (suite-02, single-instance, clean slate): `BatchId collision` = 0, `Consensus apply timed out` = 0, `evicted stale` = 0, cluster stable at 5.** The consensus divergence + churn are gone at the root.

- **Branch `release-1.0.0-rc1`. HEAD `c7bc1cad4`. 52 commits unpushed (DO NOT push — RC1 not green).**
- **Uncommitted:** the (A-enum) harness fix (2 files: `lib/cluster.sh`, `suites/02-chaos/test-self-drain-quorum-loss.sh`) — implemented + `bash -n` clean, **pending lint re-baseline + suite re-run** (see §4).
- **Tag `natural-establishment-wip` → `0b7f39905`** preserves the (reverted) natural-establishment work for later restore (§5).
- Docker oracle: single-instance + clean slate only (`pgrep -fl run-tests.sh` + `docker rm -f` + confirm count=1). Exit-137 death-watcher still armed on `$TARGET_HOST` (`/tmp/aether-deaths.log`).

## 1. What shipped this session (committed)

In commit order (newest first):
- `c7bc1cad4` **fix(quic): drop WriteTimeoutHandler** — removes the 10s handler from both consensus stream pipelines (`QuicClusterClient`/`QuicClusterServer`); `exceptionCaught` still closes on genuine I/O errors. Stops tearing down backpressured-but-live streams.
- `28a5a81f3` **test(swim): reconcile hint-emission/resurrection-guard tests** — 3 stale tests (`CoreSwimHealthDetectorHintEmissionTest` ×2, `SwimHealthFsmTest$ResurrectionGuard` ×1) updated to two-plane liveness (HEALTHY-on-connect now flows via SwimProtocol/`markAliveFromTransport`, not the detector sink). Test-only; **#231 anti-resurrection invariant confirmed intact** (tombstone-gated promotion is *stronger* than the old sink path).
- `75f08507a` **refactor(consensus): batch-oriented StateMachine + SHA-256 BatchId** — the core fix (§2).
- `acbc3c5a6` **revert(quic): restore dial-gate ordering** — reverted natural-establishment (§5).
- `0b7f39905` **fix(quic): natural connection establishment** — [REVERTED; preserved at tag `natural-establishment-wip`].
- `1e1019140` **fix(swim): self-ANNOUNCE→ALIVE (non-tombstoned) + source-IP probe target** — ID-order-independent join; a node introduces a self-ANNOUNCE'd non-tombstoned peer as ALIVE (not SUSPECT), and probes via the ANNOUNCE source-IP (drops the fragile DNS path).
- `b6b03d9d3` **test(integration): pick_non_leader waits for convergence** — distinguishes transient undercount from genuine non-convergence (60s wait).
- (earlier, also this session) `72caa5262` docs spec §12.5-12.8 terminal-removal reconcile; `ae6123814` --restart no; `2caed210b` ProvisionContext unify; `a70e3f7a0` restart-disabled invariant; `07db9b7f4` ClusterConfigKey.CURRENT seed.

## 2. The BatchId / StateMachine refactor (`75f08507a`)

**StateMachine is now batch-oriented** (`integrations/consensus/.../StateMachine.java`):
- `process(C)` / `process(List<C>)` → single `<R> List<R> process(Batch<C> batch)`.
- `Batch` is **nested in `StateMachine`**; `BatchId` → nested **`Batch.Id`** (both `@Codec`; generate `StateMachine_BatchCodec` / `StateMachine_Batch_IdCodec`).
- `createBatch(List<C>)` (default) — single creation point; `merge(Batch,Batch)` (default, throws on id-mismatch) replaces `Batch.mergeWith`; `RabiaEngine.mergeOrKeep` deleted (calls `stateMachine.merge` directly — no more peeking at batch internals).
- **SHA-256 id:** `createBatch` → `"batch-" + sha256hex(serializer().encode(commands))`. The `serializer()` accessor is **intrinsic to the StateMachine** (it already serializes for `makeSnapshot`/`restoreSnapshot`); `KVStore` returns its existing snapshot serializer — no new wiring. Old `hashCode` path deleted.
- `Batch.compareTo` reordered **timestamp-first** → batches processed roughly in submission order (deterministic across nodes: the proposer's timestamp travels in the batch).
- **`StreamConsensusCommand` gained `@Codec`** (was missing — needed for encode).
- Old `rabia/Batch.java`, `rabia/BatchId.java`, and dead `aether-storage/KVStoreMetadataStore.java` deleted. New `TestSerializers.java` test helper.
- **Validated:** consensus 581/0, cluster 46/0, aether-storage/stream/deployment (330/0) green, full reactor compiles (110 modules, jbct lint clean), + 3 SHA-256 determinism tests.

## 3. The investigation arc (so it isn't re-litigated)

The chain of misdirection → root, documented so future sessions don't repeat it:
- The **acceptor-never-reads** hypothesis was **REFUTED** — the acceptor's inbound consensus counter climbs into the thousands; consensus *flows*. The "backpressured/not writable" storm (5-6k lines) is **benign retry noise** once divergence is removed (apply-timeout 0, churn 0).
- The **late-join deadlock** (highest-ULID-never-dials + `coreNodes()`/`swimMembershipAllows` HEALTHY-only gate) is real but was a *convergence-latency* concern, not the divergence root.
- **Natural establishment** (drop the dial-gate, make NodeId a duplicate-resolution tiebreak) is the architecturally-correct fix for that gate (the dual-dial cascade it guarded was already removed — REMOVE is SWIM-authoritative now). But the implementation **regressed** (REPLACE-path left PeerState holding an inactive connection → dial→Hello→evict-stale→redial churn every 5s), so it was reverted and preserved at the tag (§5).
- The **actual roots** were the two exceptions (§TL;DR), found only when the user said "note exceptions." Both pre-date the membership work.
- **Process win:** revert-to-known-state + add disambiguating logging + reproduce on one suite (the user's method) cut through the symptom layers. The enriched backpressure log (`active=…/writable=…/bytesBeforeWritable=…`) + per-role inbound-frame counters were decisive (added then reverted as scaffolding).

## 4. REMAINING — the (A) cascade (in progress)

Suite-02 with the BatchId+WriteTimeout fixes: **02-chaos 4p/2f**. The 2 fails are the **(A) cascade**, two distinct sub-issues:

- **(A-enum) — FIX IMPLEMENTED, uncommitted, pending validation.** `running_core_containers` (`suites/02-chaos/test-self-drain-quorum-loss.sh`) enumerated cores by docker-name prefix `aether-b-node-` → **missed ULID replacements** (`node-<ulid>`) → "Pre-kill: 5 core containers … got 3" → Survivors empty → S19 cascade. Fixed to enumerate by **cluster membership** (`/api/cluster/topology` `coreNodes`, via new `core_node_ids()` in `lib/cluster.sh`, resolving NodeId→running-container — recognizes seeds AND ULID replacements). `bash -n` clean.
  - **BLOCKER to validate:** the edit shifted line numbers in `test-self-drain-quorum-loss.sh`, so the pre-existing R1 `warn-then-pass-demotion` findings (lines ~434-481) no longer match `aether/tests/integration/lint-baseline.txt` → `run-tests.sh` aborts at the lint step ("Total: 47 findings (baseline allows 47)" but positions drifted). **NEXT: re-baseline those R1 line numbers** (regenerate/append to `lint-baseline.txt`), then re-run suite-02 to validate (A-enum) → commit it.
- **(A-ready) — needs investigation.** `pick_non_leader: only 1/2 live non-leader candidates after 60s` (Kill_2_nodes) + `generation did not quiesce within 90s` (late blocks, cluster degraded to 3). `pick_non_leader` is already membership/NodeId-based (not the enum bug), so 1/2 = ULID replacements **not reaching lifecycle-READY within the window** after repeated kills, and the generation (CTM slice-placement) not settling — a residual **readiness/erosion issue (issue-D class)**, NOT consensus churn (that's fixed: apply-timeout 0, evict-stale 0). Needs a fresh run with live evidence: do replacements reach `NodeReportedState=READY`, how fast, and why the generation churns when consensus is healthy.

## 5. Natural establishment — preserved for restore (`natural-establishment-wip` / `0b7f39905`)

Architecturally the right fix for the late-join dial-gate (the `shouldInitiate` rule is **vestigial** — the dual-dial `CONNECTION_CLOSE` cascade it guarded was already eliminated when REMOVE became SWIM-authoritative; `grep channelInactive|closeFuture` in the quic package = none). The reverted commit drops the dial-gate and repurposes NodeId as a **duplicate-resolution tiebreak**, so any node dials naturally (ID-order-independent — critical for multi-cloud non-monotonic IDs). **BUG to fix before restoring:** the REPLACE path (keeping the lower-initiator connection, closing the loser) left `PeerState` holding an **inactive** connection → `evict-stale → redial` churn every 5s. Fix the REPLACE/connection-swap so the survivor is retained active, then restore from the tag. (#26)

## 6. Other open items
- **#26 natural establishment** — restore after fixing the REPLACE-path churn (§5).
- **(A-ready)** readiness/generation-quiesce erosion (§4) — the residual issue-D-class convergence gap, now isolated from consensus churn.
- **Exit-137 sporadic node death** — durable docker-events watcher still armed on `$TARGET_HOST` (`/tmp/aether-deaths.log`); no spontaneous death observed this session. See memory `reference_exit137_death_watcher`.

## 7. Process notes
- Java → jbct-coder (self-validates compile + module tests); shell/harness → general-purpose; investigation → aether-investigator (read-only, background); git → chore-runner (single-line msgs, no trailers). Maven: focused `install -DskipTests` / `-pl <m> test` only (HCLOUD-safe); NEVER `mvn verify` / `build.sh`.
- Commits were held for user review then landed in **cohesive batches** (refactor / swim-tests / quic) — keep this rhythm; user reviews before commit and authorizes explicitly.
- `run-tests.sh` lints integration tests first and **aborts on baseline drift** — after editing a test file, re-baseline `lint-baseline.txt` if line numbers shift (bit us on A-enum, §4).
