# Session Handover — 2026-06-10 (full-suite 12p/3f; dialer-side QUIC zombie fix + 11-obs #95-regression fix BOTH UNCOMMITTED — decisions pending)

**Branch:** `release-1.0.0-rc1` · **HEAD:** `75cb92688` (PUSHED, origin in sync, 0 ahead). · **⚠️ Working tree has UNCOMMITTED runtime changes that ARE half-built into the node JAR — read the "Uncommitted" section before doing anything.**

Continues `session-handover-2026-06-09d.md` (the committed/pushed work through `75cb92688`: #131 QUIC adopt-newer, Model C, PR#242 merge, #95, 03/13 harness fixes, formatter-disabled doc). This file covers the work AFTER that: the 12-network dialer-side zombie fix + the full-suite run that exposed a #95 regression.

## TL;DR
Implemented the **dialer-side** half of the QUIC zombie fix (#131's adopt-newer only fixed the acceptor side) — a periodic liveness sweep + a `closeFuture` listener. A full 15-suite run came back **12p/3f**: 12-network now **4p/0f** (dialer-fix = no regression), but the run exposed a **regression I introduced in the committed #95 fix** (11-observability) and a **chain-cascade in 03-scaling**. Both diagnosed; the 11-obs fix is coded. **Two things are uncommitted and one decision is open.**

## ⚠️ UNCOMMITTED working-tree state (do not lose this)
Modified (not committed):
- `integrations/consensus/.../net/quic/QuicClusterNetwork.java` — **QUIC dialer-side zombie fix** (Fix A liveness sweep + Fix C close listener). **IS built into the current node JAR** (built 00:02).
- `aether/node/.../api/routes/StatusRoutes.java` — **11-obs regression fix** (`expiresAt=""`/`lastRenewalAt=""` for `NOT_CONFIGURED`, both the `toCertificateStatus` guard and the `certificateStatus` placeholder). **NOT yet built** (edited after the 00:02 JAR) → JAR is stale w.r.t. this fix.
- `aether/node/src/test/.../StatusRoutesCertificatesTest.java` — updated the 3 NOT_CONFIGURED assertions `"N/A"`→`""`.

Untracked:
- `integrations/consensus/src/test/.../QuicClusterNetworkLivenessSweepTest.java` — 4 unit tests for the dialer-fix (sweep evicts dead, sweep no-ops on live, listener evicts bound, listener no-ops on superseded). All green.
- `aether/docs/internal/progress/session-handover-2026-06-09{b,c,d}.md` + this file (untracked progress docs).

**Node JAR (`aether/node/target/aether-node.jar`, 00:02) = QUIC dialer-fix YES, 11-obs fix NO.** Rebuild before any further validation.

## Full-suite run (HEAD 75cb92688 + both uncommitted fixes-as-of-00:02-JAR, i.e. WITHOUT the 11-obs StatusRoutes edit): 12p/3f
| Suite | Result | Note |
|---|---|---|
| 12-network | **4p/0f** | #131 partition-heal green + `connectedPeerCount=4` (`All_nodes_connected`) green. Dialer-fix = NO regression; **0 sweep/listener evictions** on cluster-B = no false-eviction of healthy links. |
| 05-security | 3p/0f | #95 holds (whoami + cert NOT_CONFIGURED) |
| 13-edge-cases | 3p/0f | drain harness fix holds |
| 02-chaos | 5p/1f | #94 `NODE_FAILED`-under-load flake (timing; was 6p/0f last run) |
| 11-observability | 5p/1f | **REGRESSION I introduced — see below. FIX CODED.** |
| 03-scaling | 2p/1f | **chain-cascade — echo slice not active — see below** |
| (9 cluster-A suites) | all 0f | — |

## Dialer-side QUIC zombie fix (UNCOMMITTED — DECISION PENDING)
**What:** #131's adopt-newer fixed the *acceptor* zombie (acceptor DUPLICATE-rejects a reconnect). The *dialer* twin: a `PeerState=CONNECTED` whose link is dead-but-`isActive()`-true (or genuinely closed with no write to notice) is (1) counted as connected, (2) skipped by the reconciler (it only dials NON-connected peers), (3) never evicted because the only eviction path is the 1Hz consensus write — and **nothing writes to a follower↔follower link** (ping is leader-only, pongs go only to the leader, Rabia is round-driven). So a dead follower↔follower link persists forever → `connectedPeerCount` drops and never self-heals. Confirmed mechanism (aether-investigator, all 3 sub-claims TRUE, with file:line).

**Fix (in `QuicClusterNetwork.java`):**
- **A — liveness sweep:** `sweepStaleConnectedLinks()` at the top of `reconcileMissingPeersUnsafe()` (BEFORE `connectedPeers()` is computed, so a just-evicted zombie re-dials the same tick): for each CONNECTED peer whose `activeConnection().isActive()==false`, `evictStaleConnection`. Catches the `isActive()==false` flavor.
- **C — event-driven close listener:** `registerCloseListener(peerId, connection)` in `onPeerConnected` (the ACCEPTED/RECONNECTED success path, on the just-bound connection) → `onChannelClosed` evicts on a genuine QUIC `closeFuture` — **identity-guarded** (`current == closed` reference equality) so an adopt-newer-superseded close does NOT evict the live replacement. Closes the death-DETECTION gap the layer never had (no `channelInactive`/`closeFuture` listener existed anywhere — we were polling `isActive()`).
- Residual NOT covered: the `isActive()`-lies-TRUE orphan (Flavor 2) — only a round-trip PING+timeout or a re-dial-from-the-other-side (adopt-newer) resolves it; deferred unless it reproduces.

**Validation status:** **no-regression = CONFIRMED** (12-network green, 0 false-evictions on cluster-B). **Live healing = NOT demonstrated** — the intermittent `connectedPeerCount=3` zombie didn't form this run, so the sweep/listener had nothing to heal (grep of cluster-B node logs for `Liveness sweep`/`evicting (event-driven)` = 0). Healing rests on the confirmed mechanism + 4 unit tests.

**OPEN DECISION:** commit the dialer-fix on (mechanism + unit-tests + no-regression), **or** hold until a `connectedPeerCount=3` repro is forced and the sweep/listener are watched healing it live. (User was asked; chose "prepare handover" instead of answering — so this is the first open item to resolve next session.) `connectedPeerCount=3` only reproduces in the destructive chain, intermittently — forcing it may take several runs or a fault-injection (`blackholed` flag at `QuicClusterNetwork:124-132`).

## 11-observability regression (MY fault, from committed #95 `51be401e8`) — FIX CODED, needs rebuild+commit
`expiresAt_field` test: *"TLS NOT_CONFIGURED but expiresAt='N/A' is set"*. Contract **C15**: `renewalStatus==NOT_CONFIGURED ⇒ expiresAt MUST be absent`. Before #95 the cert status was `HEALTHY` with the transport cert's real date (passed the "configured" branch); my A1 fix correctly flipped it to `NOT_CONFIGURED` but kept the `"N/A"` placeholder for `expiresAt`/`lastRenewalAt`, which C15 reads as "set" → fail. **Fix (coded, uncommitted):** `NOT_CONFIGURED` branch now returns `""` for `expiresAt`/`lastRenewalAt` (both the real `toCertificateStatus` guard and the unreachable `certificateStatus` placeholder); unit test assertions `"N/A"`→`""`. This is a clean committed-regression fix — **rebuild + run isolated 11-observability (cluster-A, ~5 min) to confirm, then commit.** 05-security C1 (NOT_CONFIGURED) is unaffected (it checks renewalStatus+tlsEnabled, not expiresAt).

## 03-scaling chain-cascade (NOT my change) — echo slice not active
`Scale_down_7_-_5_under_load` failed: `retarget: no ACTIVE owner found for test-echo; /api/slices: <empty>` → fell back to node-1:8080 → `success=400, failure=565` (58% error). The retarget fix (`8646c8caf`) ran correctly but cannot retarget to an owner that doesn't exist — **the whole cluster had no active slices** when 03 ran. Correlates with this run's rough 02-chaos (743s, hit the #94 flake, heavy churn) leaving 03's echo re-deploy unable to activate → chain-cascade. **Not the QUIC dialer-fix** (sweep/listener only evict `!isActive()`/closed links — a healthy link is untouched, 0 evictions observed — so they can't disrupt a deploy). Next-session check: a clean `02,03` re-run; if echo still doesn't activate after a churned 02, the deploy/activation path under post-chaos cluster state is the real (separate) bug, likely the same #126-family "degraded cluster-B state" the handovers keep noting.

## Recommended next steps (in order)
1. **Rebuild** the node JAR (picks up the 11-obs StatusRoutes fix). `env -u HCLOUD_TOKEN mvn -pl aether/node -am install -DskipTests`.
2. **11-obs fix:** run isolated `--suites 11` to confirm green, then commit (`fix(api): empty expiresAt/lastRenewalAt for NOT_CONFIGURED cert status (#95 follow-up, 11-obs C15)`) + push. Clean, low-risk.
3. **Dialer-fix decision** (the open question): commit (rec) or force-repro-first. If committing: `fix(consensus): liveness sweep + close-listener eviction for dead follower<->follower QUIC links (dialer-side zombie)` + the new unit-test file; update CHANGELOG.
4. **03 chain-cascade:** separate investigation (clean `02,03`), low priority — it's a known degraded-cluster-state family, not a new regression.
5. Optionally commit the untracked handover docs.

## Env / validation notes
- `$TARGET_HOST`=192.168.0.71; cluster B mgmt 5161-5165; `APP_ENDPOINT` default `TARGET_HOST:9090` is a DEAD port (LB removed) — app-load tests MUST `retarget_app_endpoint_to_active_slice` first, and that needs an ACTIVE slice to exist (03's failure = none existed).
- Validate: `cd aether/tests/integration && env -u HCLOUD_TOKEN ./run-tests.sh --env remote --suites N[,M] --skip-build`; `pgrep run-tests.sh` for orphans first; NEVER `mvn verify`/`./build.sh` with HCLOUD_TOKEN; build-runner owns maven. Editing an integration test shifts line numbers → may need `aether/tests/integration/lint-baseline.txt` updated (the run lints as a pre-flight and aborts on drift). 02-chaos is slow (~12-26 min); 11-observability/05/13 are fast.
- `connectedPeerCount=3` does NOT reproduce isolated — needs the destructive chain, intermittently. Cluster-B node-log greps for `Liveness sweep` / `evicting (event-driven)` confirm whether the dialer-fix fired.

## Key learnings (this segment)
- **The dialer-side zombie is the structural completion of #131:** the QUIC layer had NO channel-close listener — death was inferred by a 1Hz `isActive()` poll, and follower↔follower links carry no periodic traffic, so a dead one is immortal. The fix listens (event-driven) + sweeps. ("Stop polling QUIC liveness.")
- **A fix can be correct + unit-tested + no-regression yet still lack a live repro** when the bug is intermittent — be explicit about "no-regression validated" vs "healing demonstrated"; don't conflate a green suite (bug didn't occur) with a proven fix.
- **A correctness fix can satisfy one suite's contract and break another's** — #95 A1 satisfied 05-security C1 (NOT_CONFIGURED) but violated 11-obs C15 (expiresAt absent). Cross-check sibling contracts when changing a shared response shape.
