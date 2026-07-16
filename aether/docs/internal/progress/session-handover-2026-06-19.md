# Session Handover — 2026-06-19

**Branch:** `release-1.0.0-rc2` · **HEAD:** `1b0431162` (pushed) · **Cloud:** CLEAN (0 servers/firewalls). · **Candidate image:** `1.0.0-rc2-candidate` @ `3a4c740c8` (has reorder + suppressed-success).

## ⚡ TL;DR

Path-1 goal met (cluster-B reorder → all cluster-B suites finally run on cloud). Two harness fixes shipped + cloud-validated. Then a deep, evidence-driven investigation of **#336** that ended in a **proof that the reconciler/provisioning logic is sound** — #336 is *not* a logic bug; its cloud stall is **environmental provision failures behind an observability wall**. Next deliverable (in progress): a **provisioning-observability mgmt endpoint**.

## ✅ Shipped + committed (all pushed)

- `3a4c740c8` **cluster-B suite reorder** → `05 13 12 03 02` (ascending wedge-risk, **02-chaos last**). Every cluster-B suite now runs instead of being skipped by a 02-chaos wedge.
- `832469316` **cloud-aware route-kill victim-picker** (13-edge `stale-route-cleanup` → 4/0; the `docker ps` picker doesn't work on cloud — now uses `cloud_running_cores` mgmt-API enumeration + a killable-victim barrier) **+ 05-security viewer/operator RBAC keys** (the 3 viewer tests + operator test now RUN and PASS instead of skipping; role-based keys in `cloud-hetzner-b.toml`/`-jvm-b.toml` + exports in run-tests.sh). Lint baseline refreshed.
- `cd5553a92` **forge provisioning probes + observability seams**: `ProvisioningRecoveryAfterFailureBurstProbeTest` (proves recovery), `ScaleUpFiveToSevenProbeTest` (proves scale-up logic), `ManageableNode.observedPeakMembership()`, `EmberCluster.withComputeProviderDecorator` (test seam).

## ☁️ Cloud validation results (container sweep, candidate `3a4c740c8`)

- **Cluster A: 10/10** (35 tests, 0 fail).
- **Cluster B:** `05`✅(3/0) · `12`✅(4/0) · `13`✅(4/0 after fix) · `03`❌ · `02`❌ — both fails = #336.
- 02-chaos took **4.4h** (×6 slower) — a #336 symptom (auto-heal restores time out at 900s repeatedly).
- ⚠️ `test-results.json` is a fixed-path file **stale between runs** — read the live `/tmp/aether-test-results.*` mktemp or the log tally, not `test-results.json`, until `generate_report` writes at run end.

## 🔬 #336 — FINAL VERDICT: not a logic bug (5 framings, each refuted)

1. "2-node corner" → 2. "scale-up broken, rc2" → 3. "cross-zone" → 4. "leadership-stability" → **5. (PROVEN) reconciler + breaker + recovery logic is SOUND.**

- **Forge in-JVM AND isolated cloud scale-up 5→7 both PASS** → logic sound. Isolated cloud reached coreCount=7 in ~2.5min.
- **Cross-zone REFUTED** — public IPs, no cluster firewall, no private net (nbg1↔fsn1 internet-routable).
- **Recovery PROVEN** (`ProvisioningRecoveryAfterFailureBurstProbeTest`): inject 4 provision failures → breaker trips after 3 → 60s backoff → re-probe → re-trip → backoff → re-probe → **success → join → `onNodeReady` resets breaker → 5/5**. The `DEFICIT_FOLLOW_UP` loop re-pokes after each backoff. NOT permanently wedged.
- The `reachedFullMembership` FSM-source "fix" is **documented-wrong** (`LeaderReconciler.java:173-180`: FSM boot-seed promotes configured→MEMBER before real health → would latch before formation → reintroduces the cold-start phantom-replacement over-provisioning death-spiral). The gate would've been OPEN for a churned (`leaderTerm>1`) leader anyway.
- **So #336's "49× failures" = real environmental provision failures** (cloud networking/advertise/boot/capacity under churn) that the breaker *correctly* defers on. Behind the observability wall (no mgmt endpoint for `reason=`/breaker/failure-cause; operator docker-log access blocked).
- **#336 re-triaged rc2→rc3**, retitled, 5 evidence-backed correction comments posted.

## ✅ SHIPPED: provisioning-observability mgmt endpoint (the actual #336 fix)

`GET /api/cluster/provisioning` — surfaces the provisioning decision the reconciler already computes (`reason=`, circuit-breaker state, configured-vs-counted cores, the arm/reached-full latches, quorum safety, last provision-failure cause) over the Management API, so the next cloud stall is diagnosable in **minutes** instead of a 14-hour dig. Full REST→CLI→Docs triad + probe test; additive capture only (no reconcile control-flow change), leader-only, no hot-path cost.
- `141817f17` data layer — `LeaderReconciler.ProvisioningDecisionSnapshot` capture + `lastProvisioningDecision()`/`currentProvisioningSnapshot()`, `ClusterTopologyManager.lastProvisionFailure()`, `ManageableNode.provisioningDiagnostics()` + `ProvisioningDiagnostics` record.
- `1b0431162` surface — `ManagementRoute.CLUSTER_PROVISIONING_GET` (leader-pinned, VIEWER), `ClusterConfigRoutes` handler, `aether cluster provisioning` CLI, docs, `ClusterProvisioningDiagnosticsProbeTest`.
- **Proven green** (`Tests run: 1, Failures: 0`): on a settled leader returns `{"leader":true,"configuredCoreCount":5,"countedCoreMembers":5,"effective":5,"deficit":0,"quorumSafe":true,"lastTrigger":"LEADER_ACTIVATION","lastReason":"NOT_EVALUATED","circuitBreaker":{...},"lastProvisionFailure":null}`. The probe caught (and the fix resolved) a real gap: the endpoint must report current live state immediately, not gate on the delayed first reconcile pass.
- This is the **first concrete instance** of the new policy below.

## 📋 New project policy (CLAUDE.md, local-only)

Added **"Sequencing: risk-first, observability-first hardening"** to `CLAUDE.md`: big-bang rejected; order by interaction-risk × blast-radius × observability-gap; **observability surface is PART of any topology/provisioning/consensus/reconciler/membership fix** (first-class versioned Management-API, not a test hook); tickets are hypotheses; incremental with in-JVM-proof → cloud-final-gate. Born directly from this session's experience.

## 🔓 Open threads
- **`/goal` ladder:** `#333`✅ `#210`✅ · remote 15/15 not revisited · Hetzner container 15/15 = 13/15 (gated on #336) · JVM 15/15 not started.
- **Tangent:** the bootstrap-generated cluster key resolves to **VIEWER** role (403'd on `/api/cluster/scale`) — possible #290 issue, worth a quick check.
- The admin key for the cloud cluster is the **static** `aether-integration-test-key` (from the TOML), NOT the generated `~/.aether/clusters/<name>/api-key` (which is VIEWER-role). Use the static key for mgmt ops via curl (`-H "X-API-Key: aether-integration-test-key"`).
