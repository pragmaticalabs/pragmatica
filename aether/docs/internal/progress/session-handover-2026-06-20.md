# Session Handover — 2026-06-20

**Branch:** `release-1.0.0-rc2` · **2 new commits (LOCAL, NOT pushed):** `14cb25ed9`, `30e84b0bd` · **Cloud:** CLEAN (0 servers/snapshots/firewalls). · **#336 scale-UP: FIXED + cloud-proven.**

## ⚡ TL;DR
Cracked #336's *scale-up* failure end-to-end after peeling back **three layered confounders**. It was a **dual root cause** — a test-harness config-clobber + a real product placeholder bug — both now fixed and **cloud-proven together** (scale 5→7 converges in 58s, healthy, no leak; was 900s-timeout + 14-VM leak). The separate **scale-DOWN-under-load error-rate bug** (flaky, long-standing) was narrowed to two suspects with a one-line experiment to decide it.

## ✅ #336 scale-up — FIXED (dual root cause)
1. **HARNESS (commit `30e84b0bd`):** `seed_cluster_config` (lib/cluster.sh) reconcile-path re-applied the hardcoded **Docker** `cluster-config.toml`, clobbering the real cloud config in KV (verified: persisted KV config was a 378-byte `[source.docker]` stub named "b"). CTM then rendered cloud scale-up nodes from a config with **no cloud source / credentials / database / ssh** → broken nodes → never join → over-provision spiral → Hetzner 403. Fix: skip `seed_cluster_config` on `ENV_TYPE=cloud` (bootstrap already planted the authoritative config; coreMax=9 ≥ scale target 7).
2. **PRODUCT (commit `14cb25ed9`):** CTM renders scale-up/replacement nodes from the **unresolved** persisted KV TOML (REQ-KV-03 keeps `${env:}` raw by design), with NO resolution step → placeholders leak into the node overlay → node crashes on config load. Fix: new `PlaceholderConfigResolver` (aether-config) does position-based substitution from the **leader's own resolved config**; `Main.publishConfigPath` publishes `--config` path to `aether.config.path` sysprop; `AetherNode` re-parses it (memoized) and threads `Supplier<Option<TomlDocument>>` into `ClusterTopologyManagerRecord.renderFromSource`. Restores REQ-4.2.7 for the runtime path. No KV change, no secrets in KV/env.

**Verification:** `UnresolvedEnvReplacementProvisioningTest` 5/5 (covers `[database]` + `cloud.credentials.api_token`, compose + render); full `./build.sh` 6/6 green; cloud suite-03 `Scale_up_to_7` PASS (7 nodes, 58s, healthy), final VM count 5 (no leak), provisioning endpoint showed deficit→0, breaker never tripped.

> NOTE: the earlier (06-19) "#336 = not-a-logic-bug, fix = observability endpoint" verdict was the *diagnostic that cracked it*; the **actual scale-up fix is these two commits**. The "49× server-limit 403" was the downstream symptom of the orphan leak, not the cause.

## 🧱 Three confounders (each masked the next) — BUILD-TOOLCHAIN finding
1. Harness docker-stub clobber (above).
2. **Local arm64→amd64 cross-build (colima+qemu) silently drops the `aether` user** (`usermod -l aether ubuntu` doesn't persist under qemu) → `docker run` fails "unable to find user aether" → container never starts. CI/native images (e.g. `rc1`) are clean.
3. The probe VM (booted from my bad snapshot) couldn't run ANY image's JVM (`libjvm.so: file too short`).
→ **Candidate cloud images MUST be built native/CI, never via Mac qemu cross-build.** The proven image was built natively on a throwaway fresh ubuntu VM, pushed to GHCR, then snapshotted.

## 🔎 Scale-DOWN-under-load error-rate bug — NARROWED (separate, OPEN)
`03-scaling/test_scale_down_under_load` flaky/100% for a long time. **Port-bug RULED OUT** (mgmt 8080 serves slice routes via `SliceRoutes`). A **drain guard exists** (`LeaderReconciler.selectDrainVictims` + `SliceOwnershipQuery`, added for the prior "96%-error incident") that shields slice owners + drains ephemeral CTM first — yet 100% persists. Two suspects:
1. **Guard TOCTOU (lead):** generation-advance slice-state churn → drain pass reads owner as "owns nothing" → drains it → `000` connection-refused = 100%. (one stale `000` forensic fits.)
2. **Rebalance off surviving owner** → `404`/`503`.
**Decisive experiment (cheap, no cloud):** docker/colima repro of suite-03 scale-down → read `/tmp/load_failures_$$.txt` (`000` vs `4xx/5xx`) + log drained nodes + owner. Full plan in memory `project_scaledown_error_rate_bug.md`.

## 📌 Side findings (not actioned)
- **#290** closed/fixed (security default-ON); README/docs still say "OFF" → stale, cleanup pending.
- **Cluster-key→VIEWER** root-caused: `BootstrapPhaseFormation.storeApiKey` omits `authorizationRole` → server defaults VIEWER. Structural fix tied to **#206** (rc3 runtime-credential-resolution investigation); decision pending.

## 🔓 Open threads / next
1. **PUSH** `14cb25ed9` + `30e84b0bd` (currently local only).
2. **Auto-format collateral**: 11 files reformatted by build.sh (LeaderReconciler, BootstrapPhaseFormation/Provision, ClusterProvisioningCommand, SshAuthorizedKeysToml, EmberCluster, HetznerComputeProvider, ClusterConfigRoutes, ManageableNode, ProvisioningDiagnostics, SliceStore) left UNCOMMITTED — commit as `chore` or discard.
3. **Error-rate bug**: run the docker repro to pin guard-TOCTOU vs rebalance (memory has it).
4. Update **#336** GitHub issue with the dual-fix (harness + product) + reclassify (scale-up fixed; was rc3).
5. Cluster-key→VIEWER fix (#206/#290 decision).
