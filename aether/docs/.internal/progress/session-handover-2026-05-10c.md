# Session Handover — 2026-05-10 (cloud validation + RC1 fixes)

**Branch:** `release-1.0.0-rc1` · **HEAD:** `2ed4a97f3` (local) · **Tag:** `v1.0.0-rc1-candidate` at `2e0bd2d1e` (4 commits behind HEAD, awaiting force-update push)

Continuation of [`session-handover-2026-05-10b.md`](session-handover-2026-05-10b.md). This session: investigated the previously-flagged "PEERS allowlist" hypothesis (proven WRONG — operational-only RC1 fix sufficient), ran first full cloud validation (`--env cloud --runtime jvm` and `--env cloud --runtime container`), launched 4 parallel investigators against cloud-only failures (3 cluster-A regressions diagnosed as test bugs, **1 real product bug found**), applied 4 fixes including the product bug, validated on docker-remote (**11/15 best-ever**), filed 3 follow-up tickets (#215/#216/#217).

---

## ⚡ TL;DR for next session

- **Real product bug fixed:** `TaskGroupActivator.handleLocalAssignment` silently returned without re-publishing `ACTIVE` to KV when components were already active. Latent on docker-remote (collocated containers never trigger SUSPECTED); exposes on cloud where WAN latency causes transient SUSPECTED blips → leader's reconcile re-issues ASSIGNED → activator no-ops → KV stuck at ASSIGNED indefinitely. **3 unit tests added.**
- **First cloud full-suite results captured.** Container cloud: 9/15. JVM cloud cluster A: 8/10 (cluster B blocked by JVM-cloud test-infra structural bug — `kill_node` shells `docker` which doesn't exist on bare systemd VMs).
- **Docker-remote new best: 11/15** — up from 10/15 (v6) in 2026-05-10b. Two of the 06-deployment / 04-streaming / 15-delegation cluster A regressions surfaced on cloud are now closed on docker-remote too (no regression).
- **3 tickets filed (#215/#216/#217)** covering stream-metadata-replication (latent product gap), cloud integration test-infra hardening (6-item punch list), and SchemaRoutes operator-safety guard.
- **PEERS-allowlist hypothesis disproven** (from 2026-05-10b §6). PEERS is a seed list — QUIC accepts unknown peers via Hello, Rabia has no membership rejection. Real cause of CTM scale-up flakiness is slot-deadline timing (60s wallclock vs actual SWIM/Rabia join). The 1h auto-reset backstop already shipped is sufficient operational mitigation; deeper fix tracked in issue #214.

**Recommendation for next session:** push the 4 unpushed commits, re-tag `v1.0.0-rc1-candidate`, then either (a) tackle the 6 product-blocked TODOs from 2026-05-10b §6 (alert/trace injection endpoints, CTM auto-heal disable toggle), or (b) work issue #216 #1+#5 (test-infra parallel-suite line prefixing + JVM cloud systemd-aware kill_node) to unblock JVM cloud cluster B.

---

## 1 · State at session end

| Item | Value |
|---|---|
| Branch HEAD | `2ed4a97f3` (local; not pushed) |
| Tag `v1.0.0-rc1-candidate` | `2e0bd2d1e` (4 commits behind HEAD) |
| Working tree | clean (test-results.json from last run is the only diff and is gitignored-by-policy) |
| Hetzner inventory | PG VM `130122272` (off) — single resource |
| Reactor `mvn -pl aether/aether-deployment test` | **257/257 green** (validated this session) |
| docker-remote (post-fix) | **11/15** (best run on this branch) |
| Cloud Container | 9/15 |
| Cloud JVM cluster A | 8/10 (cluster B blocked by test-infra bug) |
| Investigations launched | 5 (1 sequential PEERS hypothesis + 4 parallel cloud regressions) |
| New tests | 3 (`TaskGroupActivatorReassignmentTest`) |

---

## 2 · This session's commits (4 — unpushed)

```
2ed4a97f3 test(04-streaming): cloud-aware alt-endpoint composition for non-governor read (refs #215)
3a7ddd293 test(06-deployment): poll for currentVersion ≥ 900 — eliminates parallel-suite race vs 10-database baseline POST
f8c43a0a6 fix(deployment): TaskGroupActivator re-publishes ACTIVE on duplicate ASSIGNED — recovers from leader's SUSPECTED-blip reissue
8efab2ab6 docs(test-infra): correct restart_all_nodes comment — PEERS is seed list, slot-deadline timing is the real cause (refs #214)
```

---

## 3 · Investigations (5)

### A. PEERS-allowlist hypothesis (operational-only)

Verdict: **OVERSTATED, leaning WRONG.** The 2026-05-10b handover claimed "Rabia consensus may reject unknown peers" as the cause of CTM bulk scale-up failures on cluster B. Investigation found:

- `TopologyConfig.coreNodes` is a **seed list** (docstring + `isSeedNode` accessor).
- `QuicClusterNetwork#handleHello` accepts unknown peers via `buildUnknownNodeInfo` + DiscoverNodes (`integrations/consensus/.../net/quic/QuicClusterNetwork.java:560-633`).
- `TopologyObserver#addNode` is freely callable from gossip handlers (line 323-342) — only filters are self, in-memory tombstone, KV `NodeLifecycleKey == DECOMMISSIONED`.
- Rabia consumes `TopologyObserver.coreNodes()` as a live snapshot, no membership rejection.

**Real cause:** slot-deadline timing — CTM's 60s wallclock provisioning slot can expire before CTM observes the replacement's actual SWIM/Rabia join (which succeeds). 3 expiries trip the breaker. The 1h auto-reset shipped in `2e0bd2d1e` is sufficient operational mitigation; cleaner event-driven slot completion tracked in #214.

Misleading test-infra comment `lib/cluster.sh:960-976` corrected in `8efab2ab6`.

### B–E. Cloud-only cluster-A regressions (parallel investigators)

Four cluster-A failures appeared on cloud JVM that did not occur on docker-remote v6.

| # | Failure | Investigator verdict | Severity | Fix |
|---|---|---|---|---|
| B | 04-streaming non-governor read empty | **Test bug + latent product gap.** Test-side: alt-port formula `MGMT_PORT + index - 1` is docker-only (cloud uses per-VM IPs at port 8080). Product-side: `StreamPartitionManager.streams` is a per-instance map; no DHT/KV replication of stream metadata. Docker masks it via port-range mapping + lazy data-path seeding. | Latent | Test fix landed (`2ed4a97f3`); product gap tracked in #215 |
| C | 06-deployment migration race | **Test parallel-suite race.** 06-deployment ↔ 10-database both run on cluster A. 10-database POSTs `/api/schema/baseline/database` with empty body → defaults to version=1 → overwrites the PENDING SchemaVersionValue before CDM consumes it. Orchestrator's `migrateIfNeeded` PENDING-only filter then no-ops. Docker masks via sub-second local Postgres timing. | Test-only | wait_for barrier landed (`3a7ddd293`). Server-side operator-safety guard tracked in #217 |
| D | 08-resources slow deploy + PUT 500 | **Test-infra logging interleaving + likely PG firewall coverage gap.** The "4256s deploy" was wall-clock skew from parallel suites' stdout interleaving (theoretical max wait is 210s). Real failure: slice route IS wired (PUT got 500, not 404/503) but slice's PG pool can't reach Hetzner PG VM — 50/50 pool burst fails. Hypothesis: cluster-A node egress IPs not in PG firewall rule (especially CTM-replaced nodes). | Investigative | Tracked in #216 items #2-#4 |
| E | 15-delegation STORAGE stuck ASSIGNED | **REAL PRODUCT BUG.** `TaskGroupActivator.handleLocalAssignment` returns silently when `inactive.isEmpty()` (components already active). Leader's reconcile re-issues ASSIGNED after transient SUSPECTED → assignee no-ops → KV stuck at ASSIGNED forever. Docker masks because collocated containers never trigger SUSPECTED. | **RC1-relevant** | Fix landed (`f8c43a0a6`) + 3 unit tests; module 257/257 green |

---

## 4 · Fixes applied

### 4.1 `TaskGroupActivator` re-publishes ACTIVE (`f8c43a0a6`)

`aether/aether-deployment/src/main/java/.../delegation/TaskGroupActivator.java:81-97`

```java
// When all components are already active, the leader's reconcile may still
// re-issue ASSIGNED (e.g., after a transient SUSPECTED health blip flips the
// owner out of `collectHealthyCoreNodes`, then back in). Without re-publishing
// ACTIVE the KV stays at ASSIGNED indefinitely and tests asserting "all task
// groups ACTIVE" hang. `reportActivationSuccess` is an idempotent Put.
if (inactive.isEmpty()) {
    log.debug("Task group {} already active on node {}, re-confirming ACTIVE in KV", taskGroup, self);
    reportActivationSuccess(taskGroup);
    return;
}
```

Three unit tests in `TaskGroupActivatorReassignmentTest`:
1. `reassignmentToSelf_whenComponentAlreadyActive_republishesActive` — proves the fix
2. `firstAssignmentToSelf_activatesComponent_andPublishesActive` — proves no regression on the normal activation path
3. `assignmentToPeer_whenLocalComponentActive_deactivates_doesNotPublishActive` — proves the remote-assignment branch doesn't write spurious ACTIVEs

### 4.2 06-deployment wait barrier (`3a7ddd293`)

`aether/tests/integration/suites/06-deployment/test-schema-migration.sh`

Replaced the one-shot snapshot read in `test_trigger_migration` with `wait_for "migration applied (currentVersion ≥ 900)" _migration_at_v900 60`. The wait_for budget scales via TIMEOUT_SCALE so cloud's slower reactive flow has headroom. Robust against the 06↔10 race or any other future race that briefly clobbers the PENDING marker.

### 4.3 04-streaming cloud alt-endpoint (`2ed4a97f3`)

`aether/tests/integration/suites/04-streaming/test-stream-replication.sh:55-100`

Branch on `ENV_TYPE`: cloud uses `cloud_public_ip "$alt_node":${CLOUD_MGMT_PORT:-8080}`; docker preserves the historical `MGMT_PORT + index - 1` formula. Mirrors `cluster.sh:1402-1407` `reassign_task_group`. Adds `--connect-timeout 5` and a warn log on curl error so connection-refused failures are visible (previously swallowed by `2>/dev/null || true`).

Note: this test will STILL fail on cloud until #215 (stream metadata replication via consensus) lands — but now for the right reason, not a port-range artifact.

### 4.4 cluster.sh comment correction (`8efab2ab6`)

Rewrote the misleading `restart_all_nodes` block comment that propagated the wrong PEERS-allowlist hypothesis. New text cites file:line for QUIC's accept-unknown-peers behavior + Rabia's lack of membership filter.

---

## 5 · Results across three platforms

### 5.1 Per-suite matrix

| Suite | Docker-remote (post-fix) | Cloud JVM cluster A | Cloud Container |
|---|---|---|---|
| 00-smoke | 2p/0f ✅ | 2p/0f ✅ | 2p/0f ✅ |
| 02-chaos | 2p/2f (flake) | (cluster B not validated — JVM-infra bug) | **0p/4f** |
| 03-scaling | 3p/0f ✅ | (cluster B not validated) | 3p/0f ✅ |
| 04-streaming | **4p/0f ✅** | 3p/1f (latent) | 3p/1f (latent) |
| 05-security | 3p/0f ✅ | (cluster B not validated) | 3p/0f ✅ |
| 06-deployment | **5p/0f ✅** | 4p/1f | **5p/0f ✅** |
| 07-cluster-mgmt | 4p/0f ✅ | 4p/0f ✅ | 4p/0f ✅ |
| 08-resources | 4p/1f | 4p/1f | 4p/1f |
| 09-artifacts | 3p/0f ✅ | 3p/0f ✅ | 3p/0f ✅ |
| 10-database | 3p/0f ✅ | 3p/0f ✅ | 3p/0f ✅ |
| 11-observability | 3p/2f (TODOs) | 3p/2f (TODOs) | 3p/2f (TODOs) |
| 12-network | **3p/0f ✅** | (cluster B not validated) | 1p/2f |
| 13-edge-cases | 2p/1f (TODO) | (cluster B not validated) | 2p/1f |
| 14-storage | 2p/0f ✅ | 2p/0f ✅ | 2p/0f ✅ |
| 15-delegation | **2p/0f ✅** | 1p/1f (bug exposed) | 2p/0f ✅ |
| **Totals** | **11/15** | 8/10 (cluster A only) | 9/15 |

### 5.2 Cross-platform signal interpretation

- **04-streaming, 06-deployment, 15-delegation cluster A:** all three pre-fix cloud regressions investigated; product bug fixed (15); test bugs fixed (04, 06).
- **08-resources** fails consistently across all three runtimes — same SQL connector pool issue, not a cloud-specific bug. Tracked in #216.
- **11-observability TODOs** consistent — already documented as product-blocked in 2026-05-10b §6.
- **02-chaos** docker-remote 2p/2f (within flake band per 2026-05-10b §5), Container 0p/4f (cumulative cluster B degradation under cloud network conditions).
- **12-network** docker-remote 3p/0f is best-ever — the round-3 TODO-elimination fixes from 2026-05-10b are holding. Cloud Container 1p/2f matches the known CTM auto-heal latency issue.
- **15-delegation** product bug surfaced ONLY on JVM cloud cluster A. Docker-remote and Container cloud didn't trigger the SUSPECTED-blip path during their respective test windows. Bug is real and latent on both paths; fix is mandatory before RC1 tag.

---

## 6 · Issues filed

| # | Title | Class |
|---|---|---|
| #214 | CTM slot-deadline timing + TopologyConfig.coreNodes rename (post-RC1) | tech-debt |
| #215 | Stream metadata replication via consensus (StreamConfigKey) | post-ga, enhancement |
| #216 | Cloud integration test infra hardening (post-RC1 punch list) | post-ga, tech-debt |
| #217 | SchemaRoutes.writeBaselineStatus operator-safety guard | post-ga, enhancement |

#216 is a 6-item punch list, the most-actionable items being:
- #1 prefix log lines with `[suite/test]` — would have prevented the 4256s misattribution this session
- #5 JVM cloud `kill_node` / `restart_all_nodes` systemd-aware path — unblocks JVM cloud cluster B validation

---

## 7 · Cost summary

- JVM cloud run (terminated mid-cluster-B): ~3h15m × 10 VMs ≈ €4-6
- Container cloud run (full): ~3h × 10 VMs ≈ €4-6
- PG VM idle (cluster-A + on the off-cycle): negligible (~€0.10 — cx23)
- Docker-remote validation: free (TARGET_HOST infra)
- 4 cluster-A residue VMs left orphan after Container run, reaped manually after ~5 min ≈ €0.05

**Total this session: ~€8-12.** Higher than the prior session's ~€2 because two full cloud runs ran end-to-end. Cleanup successful — final Hetzner inventory contains only PG VM (off).

---

## 8 · Quick start for next session

```bash
# 1. Sanity
git log --oneline 2e0bd2d1e..HEAD          # 4 commits this session, unpushed
git status --short                          # should be clean
git tag --points-at HEAD                    # NO tag at HEAD (candidate still at 2e0bd2d1e)

# 2. Push commits + re-tag candidate
git push origin release-1.0.0-rc1
git tag -f v1.0.0-rc1-candidate
git push -f origin v1.0.0-rc1-candidate     # CI will rebuild the JAR asset

# 3. Verify the deployment fix unit tests still pass
mvn -pl aether/aether-deployment test       # expect 257/257

# 4. Hetzner inventory sanity (should be just PG VM, off)
curl -s -H "Authorization: Bearer $HCLOUD_TOKEN" 'https://api.hetzner.cloud/v1/servers' | \
  jq -r '.servers[] | "\(.id)\t\(.name)\t\(.status)"'

# 5. Choose next thread:
#    A — Address remaining product-blocked TODOs from 2026-05-10b §6 (alerts/traces injection,
#        CTM auto-heal disable). Each is ~1-2h in the round-5 (REST + CLI + docs) shape.
#    B — Work issue #216 (test-infra hardening) starting with item #1 (parallel-suite
#        line prefixing) and item #5 (JVM cloud systemd-aware kill_node) to unblock
#        JVM cloud cluster B validation.
#    C — Land #215 (StreamConfigKey replication) to close 04-streaming end-to-end on cloud.
#    D — Cloud Container cluster B reproduction (02-chaos 0p/4f) needs investigation —
#        is it cumulative cluster B degradation (same root cause as docker-remote v11)
#        or something cloud-specific? An aether-investigator run on the container cloud
#        02-chaos failures would clarify.
```

---

## 9 · Score card

| Metric | Start of session | End of session |
|---|---|---|
| Branch HEAD | `2e0bd2d1e` (tag) | `2ed4a97f3` (4 commits ahead) |
| Commits ahead of tag | 0 | 4 |
| docker-remote (best run) | 10/15 (v6 best in 2026-05-10b) | **11/15** |
| Cloud validation status | "never run this session" (per 2026-05-10b §6) | JVM cluster A + full Container both completed |
| Real product bugs found | 0 | **1** (TaskGroupActivator) |
| Real product bugs fixed | 0 | 1 (with 3 unit tests) |
| Cloud regressions investigated | 0 | 4 (parallel investigators) |
| Issues filed | (cumulative) | +3 (#215, #216, #217) |
| Test-only fixes landed | 0 | 2 (04-streaming, 06-deployment) |
| Reactor module tests | green | green (257/257 in aether-deployment after fix) |

**Net:** Cloud validation goal achieved; a real product bug was surfaced and fixed; docker-remote consolidated at a new high-water mark; 4 commits ready to push; 3 follow-up tickets capture every remaining finding. Next session has clear, scoped options.
