# Session Handover — 2026-06-20 (part 2)

**Branch:** `release-1.0.0-rc2` · **All work committed + pushed.** HEAD `9ff49779e`. **Cloud: CLEAN** (only the standing PG VM `aether-test-pg-e8db9b`). Tree clean.

## ⚡ TL;DR
Two milestones this session: (1) **#3 scale-down "100% error" cracked = a test-harness port bug** (load pinned to mgmt :8080 not app :8070), fixed + cloud-proven. (2) **02-chaos cloud-fragility STRUCTURAL OVERHAUL** landed — and it surfaced THE finding of the session: the long-blamed **"auto-heal recovery stall" was a `ready_core_count` lifecycle-lag, NOT a product defect** (the `/api/cluster/provisioning` poller proved `deficit=0`, 5 cores, CONVERGED throughout while READY read 3-4 for minutes). The overhaul re-gates all cloud recovery on the leader-authoritative `deficit=0`. Cloud-validated 4/5 suites, 0 skips, 02-chaos 5/6. The lone residual is a genuine **product** issue (SWIM under concurrent loss), now diagnosed.

## ✅ Shipped + pushed this session
| Commit | What |
|---|---|
| `c9859dcf2` | #3 fix: `retarget_app_endpoint_to_active_slice` resolves app port from `APP_PORT`, removed misuse-prone `port` arg (cloud was pinning app load to mgmt :8080 → 100% 404). 5 call sites. CLOUD-PROVEN 0%. |
| `fe90099c5` | `cloud_revive_vm` not-found → idempotent (log_warn+return 0), symmetric with cloud_kill_vm — stops poisoning passing recovery tests. |
| `9ff49779e` | **02-chaos overhaul (5 files):** deficit-based recovery gates + victim re-resolve + scoped assert. |
| `317f4cbf3`,`1fe13cb7c` | auto-format collateral + prior handover docs. |

### The overhaul (`9ff49779e`) — the deficit-based principle
ALL cloud cluster-B core counting must use the leader's `deficit=0` (GET /api/cluster/provisioning), NEVER `ready_core_count` (the SWIM-fed lifecycle READY projection lags by MINUTES on cloud). Applied in 4 places + 2 test fixes:
1. `restore_cluster_baseline` step-8 terminal gate → `cluster_no_deficit` (+ `provisioning_snapshot()` on DEGRADE).
2. `run-tests.sh:487` inter-suite quarantine → drop `ready < floor`, trust `restore_rc` + leader (was false-quarantining 12/03/02).
3. `cloud_running_cores` → enumerate `core.members[]` from `/api/cluster/generation` (was `--state READY` → Pick_3 "got 4").
4. `retarget` → bounded wait for an ACTIVE owner (facet-1; blueprint readiness is the test's job, not the generic gate).
5. test-kill-multiple (per-victim re-resolve) + test-self-drain (`_confirm_survivor_departure`, scoped S20 via `slices_*_for`).

### Validation
- **Cloud bchain3: 4/5 PASS, 0 SKIP, 02-chaos 5/6.** quarantine fix unblocked 12/03/02 (first e2e this session); kill-under-load 0% (was 100%); Change 2 Kill_2 ✓; Change 3 scoped S20 ✓.
- **Cloud bchain4: Pick_3 PASSED** (cloud_running_cores fix); 02-chaos S19 then wedged (the remaining product issue).
- Docker validation BLOCKED (remote host 192.168.0.71 degraded from repeated runs → cluster-B down). The build→observe→refine loop caught 3 gate-design flaws before cloud.

## 🔴 REMAINING ISSUE (product, intermittent, NOT harness)
**SWIM-under-concurrent-loss → no-self-drain → artifact-wedge.** Pick_3 kills 3-of-5 simultaneously → survivors' SWIM too slow to confirm the 3 departures → never recognize quorum loss → **don't self-drain** (S19; Change 2 correctly reports them still running) → keep trying to hold the cluster → **616× DEPLOYMENT_FAILED "Artifact not found ...test-echo-echo-slice"** (unresolvable w/o leader) → replacements also fail → **leaderless wedge (503)**. `Kill_node >60s` = single-kill form. Root = `#94`/reconciler-under-load SWIM latency under HEAVY concurrent loss. Evidence: `/api/events` on a live survivor. The overhaul made this HONEST (no longer masked).

## 🔭 NEXT STEP — SWIM/membership Management-API observability (issue-ready scope)
The SWIM-internal root is observability-BLOCKED on cloud cluster-B: root SSH password expired on candidate-image VMs, `aether` user has no docker access, no SWIM mgmt-log endpoint. **Add `GET /api/cluster/membership` (or `/swim`)** exposing, per node: suspicion state (ALIVE/SUSPECT/FAULTY), last-probe + last-ack times, the node's own quorum-view, and recent departure-confirmation latencies (kill→NODE_FAILED ms). Versioned, snapshot-read, no hot-path cost — same shape as the #336 `/api/cluster/provisioning` surface that was the hero of this session. Then the next concurrent-kill stall is diagnosable in minutes. Follow the REST→CLI→Docs triad. This unblocks fixing the product SWIM/self-drain timing.

## 🎯 GOAL STATE (15/15 container → JVM) — preserved
- **Container:** chain now runs end-to-end on cloud (quarantine false-skip fixed). cluster-B chain 4/5 (02-chaos gated on the product SWIM issue above). Full 15-suite container sweep NOT re-run this session (focus was cluster-B chain). With the overhaul, a full sweep should no longer false-quarantine.
- **JVM runtime sweep:** NOT started (`#9` rc2 milestone item).
- **Standing infra:** PG VM `aether-test-pg-e8db9b` (88.198.147.80) KEPT for future cloud runs (provision-test-pg.sh idempotent; pg-firewall init done). Remote docker host 192.168.0.71 DEGRADED — reset before docker validation.

## 🔓 Open threads / next session
1. Implement the SWIM/membership observability endpoint (scope above) → diagnose the SWIM-under-concurrent-loss product timing.
2. Re-run the full **15-suite container** cloud sweep (quarantine fix should prevent false skips) → confirm container 15/15 modulo the SWIM product issue.
3. Then the **JVM-runtime** sweep (#9).
4. Side: `cloud_revive_vm`/cluster-B VMs have an expired root password (candidate image) — blocks node-log forensics; worth fixing in the image.
5. `pick_non_leader` still `--state READY`-based but passed (has poll workaround); fold into the deficit principle only if a future run shows it blocking.
