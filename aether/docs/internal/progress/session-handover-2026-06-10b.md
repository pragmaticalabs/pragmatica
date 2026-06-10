# Session Handover — 2026-06-10b (RC1 push: formatter re-enabled, 03 + 12-network green, #94 split into flap-variant-FIXED + under-load-OPEN; 14/15)

**Branch:** `release-1.0.0-rc1` · **HEAD:** `c6b88e27a` (PUSHED, origin in sync, candidate tag at HEAD). **Tree CLEAN.**

Continues `session-handover-2026-06-10.md` (which ended at `75cb92688` with the dialer-side QUIC zombie fix + 11-obs C15 fix UNCOMMITTED). This session committed/pushed all of that and went much further.

## TL;DR
Started 12/15 integration suites, ended effectively **14/15** — only the **#94 under-load variant** remains (now definitively root-caused to QUIC, tracked in **#245**). Ten commits pushed. Two GitHub issues filed (#244 lint debt, #245 #94 under-load). Two wrong-layer SWIM fixes for #94-under-load were tried and **reverted** after the real (QUIC) root was found — an honest whack-a-mole correction.

## What shipped this session (10 commits, all pushed, `75cb92688..c6b88e27a`)
1. **`17e2d9c6a` fix(api)** — 11-obs C15: empty `expiresAt`/`lastRenewalAt` for `NOT_CONFIGURED` cert (was the committed #95 regression). 11-observability green.
2. **`3d0a5afcf` fix(consensus)** — dialer-side QUIC zombie: liveness sweep + close-listener eviction for dead follower↔follower links. (from the prior session's uncommitted work.)
3. **`bc71673c1` docs** — handovers 2026-06-09b/c/d + 2026-06-10.
4. **Formatter re-enabled (3 commits):** `58850dcfa` merged **PR#243** (orphan-trivia sweep — 0 comment deletions across 2667 files); `685ec0aff` fixed the one pre-existing `JBCT-RET-07` (discarded Result in `SystemStreamBootstrap`); `727fb96ca` **reformatted 843 files + switched `build.sh` Step 2 to the `format` goal**. Lint is DECOUPLED (format-only) because the combined `process` goal surfaces **33 pre-existing lint errors** → tracked in **#244**.
5. **`b824c97cf` test(02-chaos)** — S20 echo-baseline fix: 02-chaos's `test-self-drain-quorum-loss` does `docker compose down -v` (full volume wipe), so the cluster returns at generation 1:1 with NO slices; the harness now redeploys echo + asserts all instances ACTIVE after S20. **Fixed 03-scaling: 74% error → 0.00%.**
6. **12-network structural QUIC hardening (2 commits):** `de1adb9c1` (CONNECTING staleness + per-dial timeout — stuck-dial wedge) + `2b2fff2d4` (dial-time DNS re-resolution + inbound-TTL zombie eviction + `MembershipFsm` descriptor address-downgrade guard). **Fixed 12-network: connectedPeerCount 3→4, READY 600s→8s.** Validated live through the S05/S06 partition-heal sequence.
7. **`c6b88e27a` fix(swim)** — LRP (least-recently-probed) scheduling: `selectNextProbeTarget` used `probeIndex++ mod size` over a `ConcurrentHashMap` rebuilt each tick; a flapping peer reshuffled it, starving a dead peer's probe. Now identity-keyed least-recently-probed (`lastProbedAt` + monotonic `probeOrdinal`). **Fixed the #94 FLAP variant: 12-network detection 129s→4s.**

## Open issues filed
- **#244** — clear 33 pre-existing JBCT lint errors (aether-deployment 19 / node 12 / aether-stream 1 / aether-invoke 1; RET-01/RET-03/EX-01), then switch `build.sh` Step 2 back to the `process` goal to re-couple lint. Most RET-01 are intentional side-effect voids wanting `@Contract`; 3 RET-03 + 2 EX-01 are real refactors; `ReplicaSetController.close()` (AutoCloseable) needs a suppression.
- **#245** — #94 **under-load variant** (see below).

## #94 is MULTI-MECHANISM — flap FIXED, under-load OPEN
NODE_FAILED-within-60s is NOT one bug:
- **Flap variant (12-network)** — FIXED + validated (`c6b88e27a`, LRP). A partition-healed flapping peer starved probe scheduling.
- **Under-load variant (02-chaos `Kill_node_during_active_load`)** — **OPEN, #245. DEFINITIVE root = QUIC, not SWIM.** Live leader log (captured before S20 wipe): `QuicClusterNetwork.processViewChange` re-emits `PeerJoined[nodeId=<victim>, topology=[...victim...], source=QUIC]` **every ~10s for ~120s** after the kill — the transport plane keeps re-asserting the DEAD victim as connected, so SWIM correctly never suspects it; when the re-emission stops (~T+120s) SUSPECT→FAULTY follows in ~10s. The ~126s is how long QUIC holds the dead node in its topology. Load/churn-specific (non-load + settled-cluster kills detect ~7s). **Prime suspect: the structural QUIC fix `2b2fff2d4`** (re-dial + re-resolution re-attaches the dead node, resetting the inbound clock so the `pingInterval×8` zombie-TTL sweep never evicts it).

### ⚠️ Hard lesson (do not repeat) — 3 mis-diagnoses, all SWIM-layer, all missed
The under-load variant was mis-fixed THREE times before the QUIC root was found by capturing the live leader log:
1. LRP — fixed the DIFFERENT flap variant (kept).
2. `applyAliveFromAck` no-fabricated-incarnation — real correctness fix, wrong layer — **REVERTED**.
3. `markAliveFromTransport` suspect-clock-reset shield — guard never engaged (victim is *seed-birth* SUSPECT not *probe-failure* SUSPECT) — **REVERTED**.
The victim never reaches the SWIM paths those patched; QUIC holds it "connected" the whole time. **The remarkably CONSTANT ~130s timing across every run was the tell that all three missed.** Both reverts were `git reset --hard c6b88e27a` (commits were unpushed). **Next step is a TRANSPORT-layer investigation, NOT another SWIM patch** — instrument why a killed node persists in the QUIC topology + re-emits `PeerJoined` ~120s under churn, and whether `2b2fff2d4`'s re-dial/re-resolution drives it.

## Integration status (last full run before the #94-under-load loop: 13/15)
GREEN: 00,04,05,06,07,08,09,10,11,12,13,14,15 + 03 (after the S20 fix). RED: 02-chaos (under-load #94 only) + occasionally a churn-carryover (S20 echo 1/3, 12-network "got 4") downstream of the lingering under-load ghost. SWIM detection itself is fast (`SWIM_detection_time` 1s, `Kill_node_and_detect_drop` 4s).

## Env / validation notes
- `$TARGET_HOST`=192.168.0.71; cluster B mgmt 5161-5165. Run: `cd aether/tests/integration && env -u HCLOUD_TOKEN ./run-tests.sh --env remote --suites N[,M] --skip-build --skip-teardown`; `pgrep run-tests.sh` for orphans first. `--skip-build` skips build.sh but STILL pushes the JAR + rebuilds the remote image (that's gated by the separate `--skip-image-push`). Rebuild node JAR before a `--skip-build` run: `env -u HCLOUD_TOKEN mvn -pl integrations/swim,aether/node -am install -DskipTests` (add the module you changed).
- **build.sh now FORMATS (Step 2 = `format` goal, idempotent)** and is UNBLOCKED (decoupling lint removed the pre-existing-lint-error blocker). Re-couple to `process` after #244.
- **02-chaos S20 does `docker compose down -v`** — a full wipe mid-suite; it wipes the kill-under-load failure's node logs. To diagnose #245 you must capture cluster-B node logs DURING the kill-under-load window (before self-drain S20 runs), or run a dedicated repro on a fresh cluster.
- The cluster preserved at session end is churned (heavy ULID-replacement state); reset before the next run.

## Key learnings
- **`connectedPeerCount=3` and `#94` are BOTH multi-mechanism** in the membership/transport layer — single fixes look "necessary-but-insufficient" until each distinct mechanism is closed. See `memory/project_12network_connectedpeercount_and_94_roots.md`.
- **Capture the live failing-test logs, don't trace plausible code paths.** Three rigorous-looking SWIM code traces all produced wrong fixes; one `docker logs` grep of the actual victim's timeline gave the real (QUIC) root in minutes. A CONSTANT failure timing ⇒ a fixed timer you haven't identified, not the variable mechanism you're theorizing.
- A `down -v` in a destructive suite wipes deployments AND logs — design assertions (and diagnostics) around that.
