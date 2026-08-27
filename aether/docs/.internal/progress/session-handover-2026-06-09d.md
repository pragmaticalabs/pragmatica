# Session Handover — 2026-06-09d (#131 CLOSED via QUIC adopt-newer; PR#242 merged; 03/05/13 green; 12 dialer-side zombie remains)

**Branch:** `release-1.0.0-rc1` · **HEAD:** `75cb92688` · **PUSHED, origin in sync (0 ahead).** Working tree clean except two untracked handover `.md` files (this one + `-09c`).

## TL;DR
Closed **#131** with the real runtime fix (QUIC acceptor adopt-newer), merged **PR#242** (formatter — still disabled, bugs narrowed), fixed **#95** (two real management-API defects), and fixed two **test-harness** bugs (03-scaling, 13-edge-cases) that *looked* like runtime failures but were stale-identity assumptions after destructive-chain churn. Integration suite went from many reds to mostly green. Remaining: **#94** `NODE_FAILED`-under-load flake, and **12-network `connectedPeerCount=3`** — the dialer-side facet of the same QUIC zombie class #131's fix only half-closed.

## Shipped + pushed this session (oldest→newest)
- `6d4f36c67` — **refactor(consensus):** quorum signal flow made strictly unidirectional `TopologyObserver → RabiaEngine → everyone`; `ConsensusBridge` sole shared-bus `ClusterStateNotification` emitter (consumers upgraded simple-majority→active-consensus).
- `d660d5d0d` — **fix(membership): #131 Model C** — co-confirmed-dead member's terminal eviction DEFERRED behind an 8s backstop (`quorumLossDrainThreshold`); stays SUSPECT (counted, recoverable); un-fences a brief-partition rejoin. *Necessary but not sufficient for #131.*
- `122f18771` — **Merge PR #242** (`fix(jbct): formatter content/blank-line/lambda fixes + qualified-super parsing`). jbct-only; reviewed merge-safe (lexer diff = regenerated artifact; grammar change additive; content-preservation guards present; red CI = the 3 known unrelated SWIM awaitility flakes).
- `f71d54921` — **fix(consensus): #131 the real fix — QUIC acceptor adopt-newer.** See below.
- `5761bc525` — **docs(jbct):** recorded the 5 residual formatter comment-deletion bugs (post-PR#242) in `jbct-formatter-disabled.md`.
- `8646c8caf` — **test(03-scaling):** retarget `APP_ENDPOINT` to active echo owner before scale-down load (harness fix).
- `51be401e8` — **fix(api): #95** — `whoami` surfaces authenticated principal + TLS `NOT_CONFIGURED` when app-TLS off.
- `75cb92688` — **test(13-edge-cases):** select live READY non-leader drain targets dynamically (harness fix).

## #131 — the centerpiece (QUIC acceptor adopt-newer)
**Symptom:** after a brief partition heals, a rejoined node reaches local READY but never publishes `NodeReportedState=READY` through consensus → 12-network partition-heal `4+ cores reporting READY` gate timed out 600s.

**Root (nailed by live probe instrumentation, since reverted):** the leader (lower-id, the designated dialer) re-dials the rejoined node and the handshake *completes*, but the **acceptor** still holds its **pre-partition** QUIC connection to the leader as `isActive()==true` **forever** — because `maxIdleTimeout=0` (disabled) + no QUIC keepalive means a connection whose peer closed it *during* the partition (the `CONNECTION_CLOSE` never traversed the dead path) becomes immortal. `PeerState.attach` then DUPLICATE-rejected every reconnect and closed the fresh link → leader looped connect→evict every ~5s.

**Fix (`f71d54921`):** in `PeerState.attach` CONNECTED branch, when the incumbent reports `isActive()` **but is older than `SUPERSEDE_MIN_AGE_NANOS` (3s)**, ADOPT the fresh connection (RECONNECTED) and hand the displaced old connection back via a new `AttachOutcome(result, superseded)` record; `QuicClusterNetwork.onPeerConnected` closes the superseded one. Rationale: a completed Hello handshake is a *current* liveness proof; `isActive()` lies indefinitely on a partition-orphaned link; `ConnectionDirection.shouldInitiate` guarantees one dialer per pair, so a fresh inbound handshake = the dialer detected death and re-dialed → defer to it. The 3s guard preserves the sub-ms dual-dial-race protection (young active incumbent still wins as DUPLICATE). Also enabled `activeMigration(true)` on both QUIC codecs (free soft-partition path survival).

**Validated:** 12-network 2p/2f → **4p/0f isolated**; the READY gate converges in 0s; acceptor logs `reconnect=true` (RECONNECTED) on the leader's first post-heal redial. Unit `PeerStateTest` 28/28.

**Why keepalive/idle-timeout was REJECTED as the fix:** investigation proved the cluster's only periodic per-pair traffic is the **leader-centric** 1Hz ClusterSync star (Rabia is idle-silent; SWIM rides a *separate* UDP socket). So a finite `maxIdleTimeout` alone would idle-out **follower↔follower** QUIC links (silent indefinitely) and flap them. Netty's GA QUIC codec exposes **no keepalive knob** (verified via `javap`). So the event-driven adopt-newer (zero new traffic) was the correct, minimal fix.

## Formatter (PR#242) — merged, still DISABLED
PR#242 fixed operator spacing, if-indentation, qualified-super parsing, and most comment positions — but a whole-codebase format pass still **deletes comments in 5 syntactic positions** (50 lines / 10 files; verified by a content-equivalence check, NOT just idempotency — a deleted comment is idempotently stable). Reverted; format stays disabled. The 5 trigger signatures (S1 `///` after enum-`;`; S2 `///` before annotated member across blank line; S3 `//` first-in-switch before `case`; S4 `//` between `case` arms; S5 `//` mid fluent-chain), verbatim repros, root-cause hypothesis (orphan-trivia sweep needed), and re-enable status are in **`docs/contributors/jbct-formatter-disabled.md`** ("Update 2026-06-09"). The jbct toolchain IS built with PR#242 in `~/.m2` (rebuild via `mvn -f jbct/pom.xml install -DskipTests`).

## Integration suite scorecard (this session)
| Suite | Before | After | Root class |
|---|---|---|---|
| 02-chaos | 5p/1f | 6p/0f* | #94 edge-trigger (prior session) |
| 03-scaling | 2p/1f | **3p/0f** | HARNESS: `APP_ENDPOINT` dead LB port 9090 (no retarget) |
| 05-security | 1p/2f | **3p/0f** | RUNTIME #95: whoami scope-binding + TLS NOT_CONFIGURED |
| 12-network | 2p/2f | partition-heal **fixed** (4p/0f isolated) | RUNTIME #131 (acceptor); `connectedPeerCount=3` OPEN (dialer) |
| 13-edge-cases | 0p/3f→2p/1f | **3p/0f** | HARNESS: hardcoded `node-5/4/3` gone after churn (live selection) |

\* `NODE_FAILED within 60s under load` is a timing flake (6p/0f full-suite, 5p/1f in a `02,13` re-run).

**Key pattern:** the suites that *looked* like runtime failures (03, 13) were **harness bugs assuming stale node identities after destructive-chain churn** — both fixed by discovering live state (`retarget_app_endpoint_to_active_slice`, `pick_non_leader`). 05-security and #131 were the genuine runtime defects. **Lesson reinforced: read the test's transport/exit codes and reproduce in isolation before believing a runtime-failure narrative** (03's `0/573` and 13's `404` were both env, not product).

## #95 (`51be401e8`) — two real management-API defects
1. **`whoami` returned anonymous for a valid admin key.** `ManagementServer.validateManagementSecurity` resolved the `SecurityContext` for the allow/deny decision then collapsed it to `.isSuccess()`; the management dispatch never bound `SecurityContextHolder`'s ScopedValue (only `AppHttpServer` did). Fixed by returning `Result<SecurityContext>` and dispatching authenticated requests inside `ScopedValue.where(SecurityContextHolder.scopedValue(), sc)`. Completes the principal-injection contract for ALL management routes.
2. **`/api/certificates` reported HEALTHY with TLS off.** The QUIC transport's self-signed cert keeps the renewal scheduler healthy; `StatusRoutes.toCertificateStatus` ignored `tlsEnabled`. Guard: `!tlsEnabled ⇒ NOT_CONFIGURED`.

## REMAINING OPEN

### 12-network `connectedPeerCount=3` — the DIALER-SIDE QUIC zombie (NEXT TARGET)
The #131 adopt-newer fixed the **acceptor** side (acceptor holds zombie → DUPLICATE-rejects the dialer's reconnect). The **dialer** side is unaddressed: a node that holds a connection it *believes* active (`isActive()` lies, idle-timeout disabled, no keepalive) and whose reconciler only dials peers it considers **disconnected** will **never re-dial** → `connectedPeerCount` stays low. This is the residual 12-network in-chain failure (`connectedPeerCount=3`, expected ≥4). It does NOT reproduce cleanly in isolation (12-network was 4p/0f isolated incl. `All_nodes_connected` at connectedPeerCount=4); it surfaces under the destructive chain. **Hypothesis to confirm with live instrumentation (mirror the #131 probe):** on a node stuck at connectedPeerCount=3, is the missing peer's `PeerState` CONNECTED-with-a-dead-but-`isActive()`-true zombie (so the reconciler skips it)? If so, the fix is symmetric to adopt-newer: detect a dialer-side stale-active link and force a re-dial — e.g. on the FSM "peer should be connected but link has carried no inbound for > threshold" edge, or by having the 1Hz ClusterSync write-failure path mark the peer reconcile-eligible even when `isActive()` reads true. The earlier-rejected idle-timeout path is OFF the table (follower↔follower silence). See `QuicClusterNetwork` reconciler (`reconcileAgainstTopology`/`reconcileAgainstDesired` ~1300) and `evictStaleConnection`/`writeToStream` (~1020/1165).

### #94 `NODE_FAILED`-within-60s under load
SWIM detection latency for a replacement death under active load (>60s sometimes). Timing-flaky (passes/fails across runs). Separate from the #94 edge-trigger (which IS fixed). Lower priority (observability, not correctness).

## Env / validation notes
- `$TARGET_HOST`=192.168.0.71, `$AETHER_SSH_KEY`/`$AETHER_SSH_USER`/`$AETHER_API_KEY` set; reference by name. Cluster B mgmt 5161-5165; `APP_ENDPOINT` default `TARGET_HOST:LB_PORT(9090)` is a **dead port** (LB module removed) — tests that load app endpoints MUST `retarget_app_endpoint_to_active_slice` first (02-chaos/03/13 do).
- Validate: `cd aether/tests/integration && env -u HCLOUD_TOKEN ./run-tests.sh --env remote --suites N[,M] --skip-build`. `pgrep run-tests.sh` for orphans first. NEVER `mvn verify`/`./build.sh` with HCLOUD_TOKEN set; build-runner owns maven. Editing an integration test shifts line numbers → the integration **lint baseline** (`aether/tests/integration/lint-baseline.txt`) may need the line updated (hit twice this session) — the run lints as a pre-flight and aborts on drift.
- Node JAR `aether/node/target/aether-node.jar` currently = #95 build (committed). `--skip-build` pushes it + rebuilds the remote image.
- **Reproduction notes:** 03/05/13 reproduce/validate as single suites (or `02,13` for 13's cascade); 12-network's `connectedPeerCount=3` needs the destructive chain (does NOT reproduce isolated). 02-chaos is slow (~14-26 min).

## Key learnings
- **QUIC liveness is event-driven; we were polling it.** No `channelInactive`/`closeFuture` listener exists in the QUIC layer — death is inferred from a 1Hz `isActive()` poll. With `maxIdleTimeout=0` + no keepalive, a partition-orphaned connection is immortal on whichever side didn't receive the `CONNECTION_CLOSE`. The fix treats a fresh handshake as authoritative over a stale `isActive()`. The dialer-side analog is still open (see 12 above).
- **Harness assumes pristine identities; the chain doesn't provide them.** Destructive suites terminal-remove seeds and CTM replaces them with ULID nodes — any downstream test hardcoding `node-N` or pinning `APP_ENDPOINT` will hit gone/dead targets. Discover live state.
- **Idempotency ≠ content preservation.** A formatter that consistently drops a comment is perfectly idempotent. The whole-codebase reformat gate must assert `output_comments ⊇ input_comments`, not just `format(format(x))==format(x)`.
- **Verify against a live run.** Two agents gave opposite answers on QUIC re-dial; the live probe settled it. 03/13 "runtime failures" were harness artifacts only an isolated run could reveal.
