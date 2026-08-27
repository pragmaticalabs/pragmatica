# Session Handover — 2026-06-18

**Branch:** `release-1.0.0-rc2` · **HEAD:** `1d4ff31fe` (pushed) · **Candidate image:** `ghcr.io/pragmaticalabs/aether-node:1.0.0-rc2-candidate` built at `f18600b4c` (⚠️ **2 commits behind HEAD** — see "Candidate image gap"). · **Cloud:** CLEAN (0 servers, 0 firewalls). Nothing running.

## ⚡ TL;DR

The `/goal` was `#333 → #210 → remote 15/15 → Hetzner container 15/15 → Hetzner JVM 15/15`. The session resolved a 3-session **cloud auto-heal mystery** and then went deep on the cloud integration harness. Net:

1. **Cloud single-node auto-heal — FIXED + CLOUD-PROVEN + pushed.** Root cause: replacement nodes advertised an unroutable container/VM **hostname**; the leader's SWIM dial-in DNS-failed → DEAD → flap. Fixed at the source (boot-time routable-address resolution). See [[project_autoheal_advertise_host_fix]].
2. **Cloud chaos harness — rebuilt provider-agnostic + SSH-free + validated.** It was single-host-Docker/SSH-bound and couldn't address CTM ULID nodes. Now: IP-based resolver, power-off→**delete** kill, firewall partition, mgmt-API enumeration, cloud-aware poll fast-fail, cloud-sized windows, fail-fast active-recovery restore, leader-log capture. **Cluster-A 10/10 on cloud; cluster-B kills/partition/single-heal/JOINING-window all validated.** See [[project_cloud_clusterB_harness_and_gaps]].
3. **2-node-burst auto-heal stall — CONFIRMED a real, non-RC-blocking reconciler-under-load PRODUCT bug** (quota refuted). Root cause is NOT yet pinned — blocked on **observability** (can't read the leader's `provisionNode` failure on cluster-B). Two robustness fixes landed (`onNodeReady`, suppressed-success); the primary cause (why `provisionNode` fails) is **deferred**.

**Open decision (not yet answered):** Path 1/2/3 below — how far to push the 2-node-deficit. My recommendation: **Path 1**.

---

## ✅ Shipped + proven this session

### Product (cloud nodes — in the image)
- `f912a7903` **#333** backfill from HRW owner + SelfWatermark headOffset (from prior session, pushed this session's base).
- `69b8bea6f` / `475e5d5cb` **#210** NODE_FAILED from ungated FSM DEAD edge + publishSafely isolation.
- `1a7f6fad2` **#334** zone-rotate auto-heal provisionReplacement on capacity exhaustion.
- `9a0618459` self-heal NDM activation when the cold-start consensus-ACTIVE edge is dropped (F1 — fixes a latent dropped-edge bug, NOT the S20/membership-formation case).
- `698db9bb0` + `fbf01c6d2` + `29fe51d48` **the auto-heal advertise-host fix** (the headline): node resolves a routable advertise host at boot — chain `PEERS-self → AETHER_ADVERTISE_HOST → SWIM WhoAmI reflection → loud fallback` (`Main.parsePeers`); `SelfAddressResolver` + `SwimGossipEncryptors` (encryptor single-source); SWIM `WhoAmI`/`WhoAmIReply`; CTM user-data sets `AETHER_ADVERTISE_HOST` via `ip route`. **CLOUD-PROVEN**: replacement advertised `host=46.224.128.182` (IP not hostname), zero `UnknownHostException`, reached READY, cluster sustained 5/5; single-node heal ~6–8s across multiple runs.
- `77fbcaeb9` **wire `onNodeReady`** — reset the provisioning circuit breaker on a confirmed core join (`MembershipDecision.NodeJoined → handleNodeJoined`); was dead code. Necessary but not sufficient for the 2-node case.
- `f18600b4c` **operator SSH key on CTM replacement VMs** — bootstrap persists the operator key contents into the cluster TOML (`[infrastructure.ssh] authorized_keys`); CTM threads it into the replacement renderer. **SSH access works**; docker-log access does NOT on cluster-B (see observability gap).
- `4fe6f6fdc` **suppressed-success in-flight fix** (`ProvisionDisposition`) — a no-boot deferral (circuit-open / no-healthy-peers) no longer stamps a phantom in-flight placeholder that masked the deficit. Robustness fix (prevents a transient failure burst from becoming a *permanent* wedge); NOT the primary 2-node cause.

### Harness (integration tests — local, NOT in the image)
- `e064c7cb0` cloud-aware sustained auto-heal catch-window.
- `7209d69cc` provider-agnostic SSH-free chaos primitives: `cloud_kill_vm`/`cloud_revive_vm`, `cloud_partition_node`/`cloud_heal_partition` (Hetzner firewall, keeps mgmt:8080), `cloud_running_cores` (mgmt-API enum); `scale_cluster`→mgmt-API; `assert_cluster_healthy`→HTTP; compose-free cloud restore; teardown catches CTM orphans; `load.sh` `$$`-scoping. `CLOUD_PROVIDER` case-seam for AWS/GCP.
- `cc015133f` rewired 02-chaos/12-network/13-edge onto the primitives + relaxed Gap-A (drain-exit → membership-departure on cloud).
- `736c96f89` **resolver fix** — node→IP (bootstrap-state for seeds, mgmt-API `/api/nodes/endpoint/<id>` for CTM) → IP→server-id via `hcloud server list -o columns` (the raw-curl+grep choked on Hetzner's multi-line JSON, and seed node-ids are ambiguous across clusters).
- `89a530e1a` cloud-aware API poll fast-fail + endpoint rotation; 300s replacement-discovery window.
- `8a9b1c5f2` **`cloud_kill_vm` → `hcloud server delete`** (not power-off) for auto-heal kills (frees Hetzner quota; faithful "node gone") + **fail-fast active-recovery `restore_cluster_baseline`** (`scale_cluster`+`reset_provisioning_circuit`, bounded ~12min vs the old 1800s hang). Added `cloud_stop_vm` for any future same-node revive.
- `1d4ff31fe` `log-follower.sh` → `sudo docker logs`/journalctl/file with **loud-fail** (no more silent empty captures).

---

## ❌ The 2-node-deficit stall — CONFIRMED real, root cause UNPINNED (deferred)

**Symptom:** delete 2 cores at once (5→3), cluster sits at `actualCoreCount=3, state=RECONCILING` across two 720s windows, never recovers. Survives with quorum + leader (no data loss). Single-node deficits heal in seconds.

**Confirmed:** NOT quota (delete-on-kill kept quota free, breaker reset each subtest, still `priorFailureCount=49`). The deficit-follow-up loop fires and keeps calling `provisionReplacement`, but the actual `lifecycleManager.provisionNode` attempts **FAIL ~49×** → breaker trips → stuck.

**NOT yet pinned:** WHY `provisionNode` fails (capacity? rate-limit? user-data? a replacement-led-leader path?). Code-confirmed contributing defect (now fixed by `4fe6f6fdc`): suppressed-success masked the deficit, making any failure burst permanent. But the *primary* trigger is the provision attempts genuinely failing — unknown cause.

**Why unpinned — the observability wall:**
- Cluster-B operator SSH user is `aether` (NOT root, NOT in the `docker` group, sudo needs a password) → `docker logs`/`sudo docker logs` of the leader's container FAIL. (Cluster-A node logs *did* capture — its node config grants operator docker access; **cluster-B's does not** = the real capture fix.)
- No mgmt log endpoint (`/api/logs`, `/api/node/logs`, `/api/diagnostics` all 404).
- `/api/events` surfaces NODE_JOINED/FAILED/DEPLOYMENT, **not** CTM provision-attempt failures.

**Severity:** non-RC-blocking (single-node heals; quorum/leadership survive a double kill). Reconciler-under-load class — see [[project_reconciler_under_load_class]].

**Collateral:** the wedge leaves cluster-B unrecoverable at 3/5 → `restore_cluster_baseline` fails → **03-scaling / 05-security / 12-network / 13-edge-cases SKIPPED** (still unvalidated on cloud), and **Gap-C** (13 artifact-isolation) not reached (preliminary: deployed cleanly during bootstrap → likely churn-induced, not a hard bug).

---

## 🔀 OPEN DECISION (the user has not answered)

- **Path 1 (RECOMMENDED): bank + reorder + validate the rest.** Small harness reorder so the wedge-prone 2-node-kill runs **last** in cluster-B → rebuild image to HEAD → one run to finally validate 03/05/12/13 + Gap-C. Defer the 49-failures root-cause as a scheduled reconciler-under-load item.
- **Path 2: keep digging the 49 failures.** First build provision-failure observability (a mgmt endpoint / `/api/events` cause, OR align cluster-B operator docker access with cluster-A) → re-run → root-cause → fix the provisioning path. Deeper, multi-cycle.
- **Path 3: hard stop.** Bank committed work; defer all remaining cluster-B chaos validation + the 2-node-deficit.

---

## 🚨 Candidate image gap (do this before any next cloud run)
The candidate image is at `f18600b4c` — it does NOT include `4fe6f6fdc` (suppressed-success) or `1d4ff31fe` (log-follower; harness-only, doesn't need the image). Before the next cloud run that exercises auto-heal:
```
git tag -f v1.0.0-rc2-candidate HEAD && git push -f origin refs/tags/v1.0.0-rc2-candidate
```
→ `.github/workflows/release.yml` rebuilds the ghcr image + cuts a public WIP prerelease (~15-20min). Also **rebuild the local CLI** (`~/.aether/lib/aether.jar` goes stale) before bootstrap, or formation-phase fixes run stale code.

## ☁️ How to re-run cloud (cheap Hetzner — the iterate loop)
PG + firewall: `tools/provision-test-pg.sh --fresh` → `source /tmp/aether-test-pg.env` → `tools/pg-firewall.sh init && tools/pg-firewall.sh open`. `export AETHER_INSECURE_DEV_MODE=true`. Then `cd aether/tests/integration && ./run-tests.sh --env cloud --runtime container`. Teardown: `tools/cloud-reaper.sh --destroy --force` (bare — bootstrap VMs lack `aether-cluster` label) + destroy the PG VM + firewall. **Delegate cloud runs to a background `aether-investigator`** (they're ~1.5h; the agent backgrounds run-tests and may return early — resume it via SendMessage to block on completion + tear down). NEVER `mvn verify` with `HCLOUD_TOKEN` set (real paid server).

## 🔑 Key seams
- 2-node-deficit: `LeaderReconciler.java` (deficit/dispatch/in-flight/follow-up), `ClusterTopologyManagerRecord.java` (provisionReplacement :434 / circuit breaker :653-677), `MembershipFsm.java:750-752` (coreCountedMembers=MEMBER+SUSPECT). The unknown is inside `lifecycleManager.provisionNode` failures.
- Observability fix candidates: cluster-B node config docker-group grant (compare to cluster-A); a mgmt endpoint surfacing provision-failure cause.
- Suite reorder (Path 1): `aether/tests/integration/run-tests.sh` cluster-B suite ordering — move 02-chaos last.

## 📋 Remaining `/goal` status
`#333` ✅ · `#210` ✅ · remote 15/15 — **not revisited this session** · Hetzner container 15/15 — cluster-A 10/10, cluster-B blocked by the 2-node-deficit wedge (Path 1 would validate 03/05/12/13) · Hetzner JVM 15/15 — **not started**. Also open: `#7` reaper terminate-label (likely resolved as side-effect of the address fix — re-verify); the 60s-re-dial-grace-against-dead-peer loop (reconciler-under-load).
