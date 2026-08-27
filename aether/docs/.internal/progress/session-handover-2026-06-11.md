# Session Handover — 2026-06-11 (topology overhaul Waves 5–9, goal: 15/15)

**Goal (active /goal hook):** finish the cluster-topology-overhaul rework per spec and achieve a 15/15 full-suite integration run.
**Spec (keystone):** `aether/docs/specs/cluster-topology-overhaul-spec.md` — Waves 1–8 carry `> VALIDATED` annotations; **§5.8 AMENDED 2026-06-11** (user design: `sync → activate → replay`).
**Branch:** `release-1.0.0-rc1`. **HEAD `0c6b775b9`, PUSHED; tag `v1.0.0-rc1-candidate` at HEAD.**

## ✅ Committed + pushed this session (all Docker-gated)

| Commit | Wave | Content | Gate evidence |
|---|---|---|---|
| `bc1bc1b61` | 6 | Lifeguard LHM+dogpile; H8 residency + **FAULTY-edge departure decoupling**; incarnation-fabrication removal; Ack.from check; stale self-suspicion gate | 02 6p/0f, 12 4p/0f, detection 1s (transport-led) + ~12s SWIM backstop; swim 130/130 |
| `2b52dd8f3` | 5 | PeerState single emission chokepoint; receipt-evidence TTL + **CONTROL-lane KeepAlive** (SWIM rides own UDP socket — idle QUIC links get zero inbound); mandatory broadcast filter; H10 per-attempt dial timeout; reconnect provenance | 02 6p/0f **0.00% error rate** (first-ever zero), 12 4p/0f; idle evict/re-dial cycle journal-proven GONE (audit M7) |
| `571ad9141` | — | test(cdm): #124 register-only stale test | 30/30 |
| `d1b503bbf` | 7 | H2 DEPARTING exits; H3 flag symmetry; FSM-layer death-ward (`Suspect+PeerConnected→ignore`); PresenceSampler→sensor-only; M10 drain + **ghost-gated join-grace reaper** (first cut wedged formation — reaped ack-starved joiners on FSM-state-alone vs ratified #126 "readiness signal"; fixed = reap only never-healthy AND transport-disconnected, re-arm if connected); 21 LeaderReconcilerTest tests rewritten to #131 Model C | 02 6p/0f, 12 4p/0f, 13 4p/0f; formation 0–16s |
| `a362e6209` | 8 feat | **H4 LeaderKey viewSequence fence** (KV-applier compare-and-put, strictly-greater; baseline-anchored proposals = observed-committed+1); **M5 inverted activation replay** per amended §5.8 (silent sync → activate → replay-first-on-apply-path; diff-replay for mid-life installs; ActionLog + `replayFromStore` + per-consumer guards DELETED ~460 lines; invariant: *a KV notification implies ACTIVE*) | 02 5p/1f (residual = #325 pre-existing), 03 3p/0f, 13 4p/0f |
| `0c6b775b9` | 8 fix | **Sequence-gated leader adoption** (3 iterations: transport-vetoed → unconditional → Electing/ReElecting adopt only commits with viewSequence > entry-baseline; unconditional elsewhere); **dead higher-id grace-bypass dial fix** (`connectPeer` silently re-checked single-dialer guard after grace elapsed → higher-id node could never dial never-initiating lower-id peer — THE B5-facet-2 "READY-convergence 600s" root); all silent dial exits now log cause | re-election **2s** (was 300s wedge); both adoption directions proven |

Issues: **filed #284** (CDM deploy retry storm: 994 sub-ms DEPLOYMENT_FAILED, no backoff/blacklist + replacement artifact-distribution gap), **filed #325** (S20 ROUTING wedge, see below). #245/#257 closed earlier (Wave 4 `ff481e165`).

## 🔄 IN FLIGHT at handover time

1. **Wave 9 implementation** was running via a background jbct-coder when this handover was written. Work list (scout-verified, flags incorporated):
   - **One quorum denominator**: `handleSetClusterSize` (TopologyObserver:785) writes ONLY the `effectiveClusterSize` AtomicInteger — must write `ClusterConfigKey` (the single source); consensus-side atomic becomes a derived cell updated by the aether-side ClusterConfigKey subscription (module boundary: observer can't read aether KV). §3.1: trigger surface unchanged.
   - **One split timeout T**: collapse `quorumLossDrainThreshold` (8s) + `nttDepartureTimeout` (15s, LeaderReconciler multiples ×1/×1.5/×2/×3 at :260-275); no-double-active test required (minority measures from local-quorum-loss, majority from departure-verdict). Grep harness/compose for old knob names — config-breaking.
   - **Role vocab**: delete transport `NodeRole` (ACTIVE/PASSIVE) + 6 filter sites (TopologyObserver :334,:443,:593,:603,:740,:755) + `TopologyGrowthMessage` (zero callers); CAREFUL: check what marks PassiveNode peers passive (PeerState.isPassive/skipPassive in broadcast) before ripping.
   - **Dead-code strip**: CTM retired-slot loop (:254), CTM's unused `inQuorum` ctor param (NARROW — `TopologyObserver.inQuorum()` consumers are LIVE), `realActualStableSinceMs`, `PresenceSampler.evict`, **legacy QUIC gates** `considerPeerForReconcile`/`swimMembershipAllows`/`swimHealthAllows` (NOT dead — called at :1738/:1160; behavioral change, verify FSM-wired path covers cold-start), retire `NettyClusterNetwork` (no prod path, D6), split `ClusterDeploymentState.java` (2,244 lines, move-only).
   - **M4 sentinel**: AetherNode:1167 `cdmCoreCountedMembersSupplier` falls back `Set.of()` pre-wiring → CDM cleanup no-op sentinel.
   - **Spec banners** per §13 (7 files, all exist).
   **If the coder's work is in the worktree but unreported:** run all 4 module suites (consensus/cluster/aether-deployment/node, `env -u HCLOUD_TOKEN mvn -pl <m> test`), review against this list, then gate. **If the worktree is clean:** relaunch with this list.
2. **#325 fix — DESIGN READY, deliberately queued behind Wave 9** (same file: ClusterDeploymentState). RCA (95% confidence on defect 2):
   - Defect 1 (node-side): `HttpRoutePublisher.publishRoutesToCluster` (`aether/aether-invoke/.../http/HttpRoutePublisher.java:241`) — `cluster.apply(...)` is the ONLY lifecycle consensus write composed WITHOUT `.timeout()`/retry (logical await — a never-resolved Promise, no parked thread, nothing in jstack). Under consensus-lane backpressure the ROUTING→ACTIVE continuation never runs, silently.
   - Defect 2 (leader-side): `ClusterDeploymentState.executeStuckRemediation` (~:1770) has NO ROUTING arm (`default -> {}`) AND removes the timestamp BEFORE the switch → detected once ("Detected 1 slices stuck" WARN), abandoned forever.
   - Ratified fix: ROUTING joins the force-unload remediation arm; unhandled states stay tracked; `.timeout(CONSENSUS_OPERATION_TIMEOUT)`+retry on the publish apply (match `applyWithRetry` 30s×3 idiom); seed `registerExpectation` from existing `NodeRoutesKey` KV entries.

## ⏭ Remaining to goal, in order

1. Wave 9 lands → spot-check → 4 module suites → gate `--suites 02,03,12,13` → commit + push + move tag (+ CHANGELOG + spec Wave-9 annotation).
2. #325 fix (post-split file layout) → unit + gate 02 (S20 must go green) → commit `fix(deployment)`.
3. **Final full-15: RECREATE CLUSTER A FIRST** — a zombie `aether-a-node-4` (pre-Wave-6 false death, container alive, mgmt port 5154 503s every request) will otherwise re-fail 07-cluster-mgmt + 11-observability per-node probes (the only 2 failures in the 13/15 checkpoint). `cd aether/tests/integration && docker compose -f docker-compose-a.yml down -v` on the remote (or run without --skip-teardown). Spec Wave-9 bar: full-15 **×3** (incl. split no-double-active + worker-join regression).
4. Known 15/15 risks: #325 until fixed; 02 error-rate facet lottery (greatly improved — 0.00% measured post-Wave-5 — but H7-class + #284 can resurface on churned clusters); #284 storm unfixed (backoff/blacklist not yet built).

## Operational gotchas (hard-won this session)

- `env -u HCLOUD_TOKEN` on EVERY mvn invocation (HetznerCloudIT creates real paid servers via failsafe).
- Gate pattern: `mvn -pl <changed-modules> install -DskipTests` → `mvn -pl aether/node install -DskipTests` (NO -am) → `./run-tests.sh --env remote --skip-build --skip-teardown --suites N,N`.
- **Capture evidence the moment a FAIL fires** — suite transitions `compose down` cluster B and destroy container logs/journals. Rig pattern: background script watching the run log, snapshots journal+topology+slices via mgmt API (ports from `docker ps` 0.0.0.0:PORT->8080, header `X-API-Key: aether-integration-test-key`).
- The transition journal (`GET /api/cluster/journal`, WARN-class auto-flush to docker logs) root-caused every defect this week — read it before theorizing.
- Verify subagent claims empirically (one investigator fix proposal was journal-refuted; one scout claim — SWIM-over-QUIC — was wrong and would have shipped a broken fix).
- 02's Kill_node_during_active_load is a two-facet lottery (departure-event budget vs error-rate); attribute per-facet before re-running.
- Model dispatch pinned in `~/.claude/agents/*` frontmatter: builds/chore=sonnet, coding=opus, analysis/review/spec=fable; Explore scouts get `model:"sonnet"` per-launch.
- CHANGELOG editing trap: entries are single huge lines — an Edit old_string matching a line PREFIX silently merges entries; verify after every CHANGELOG edit.

## Deferred / RC2 ledger
inc-0 permanent-prune (Dead `rejoinIfNewer` strictly-higher fence + replacements boot at 0 — assessed RC2); Lifeguard buddy system; `DashboardMetricsPublisher` 1s forEach + `KvStoreApiKeyValidator` per-request scan; #256 (gossip-key day rollover), #258 (Rabia stall-detector), #259 (role display), #284; Wave 6b `admit()` collapse (optional, D5); boot-epoch ULID (§6.5d).
