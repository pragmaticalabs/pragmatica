# Session Handover — 2026-05-05 (b)

**Branch:** `release-1.0.0-rc1`  ·  **HEAD:** `29953e7ae` (4 commits ahead, **NOT pushed yet**)
**Tag:** `v1.0.0-rc1-candidate` at `ca4acac92` (stale, `wrap-up` will move it)

Continuation of [`session-handover-2026-05-05.md`](session-handover-2026-05-05.md). That session moved cloud Phase C from 1/16 to 10/15. This session attacked the remaining cluster B failures and **moved 03-scaling from FAIL → PASS** — the runtime auto-scale on Hetzner now works end-to-end.

---

## ⚡ TL;DR

**Phase C cloud final state:**

| Suite | Yesterday | Today | Notes |
|---|---|---|---|
| 02-chaos | PASS | **PASS** (158s) | baseline preserved |
| 03-scaling | FAIL | **PASS** (1851s) | ← session win — runtime auto-scale + quorum-safety green |
| 05-security | FAIL | FAIL (1594s) | new cause: cert-rotation under-load 11% error rate; auth + RBAC sub-tests all PASS |
| 12-network | SKIP | SKIP | iptables capability gate (Hetzner) |
| 13-edge-cases | FAIL | FAIL (654s) | disruption-budget drain HTTP 500 (server-side bug, unchanged from yesterday) |

**Phase B sanity (Cluster A):**
- 00-smoke: 2p/0f (144s) — bootstrap end-to-end works with new overlay generator.

**Net delta this session: +1 suite green (03-scaling).** Combined with prior Phase B greens, full Hetzner score is **11/15 PASS / 1 SKIP / 3 FAIL**.

Hetzner spend ~€2.20 across the focused work. Account clean at end (1 PG VM only).

---

## 1 · Commits this session (4, unpushed)

```
29953e7ae fix: cloud-aware quorum-safety test + ProvisionedVm carries numeric instance ID
48a661b0e fix(bootstrap): authenticate formation POSTs with configured ADMIN-role static API key
5573fc599 refactor: replace hardcoded version strings with manifest-driven BuildInfo (CLI + Node)
44af74a9c fix(bootstrap): emit [cloud] + [cloud.credentials] in per-node overlay so cloud auto-scale wires up at runtime
```

### `44af74a9c` — Cloud auto-scale wiring (the original target)

**Root cause:** `BootstrapOverlayGenerator.cloudComputeSection` wrote `provider` to `[cloud.compute]` instead of the top-level `[cloud]` section that `ConfigLoader.populateCloudConfig` reads. Result: `lifecycleManager.isCloudManaged()` returned false on Hetzner nodes; CTM logged `"no ComputeProvider, cannot auto-provision"` and exited without provisioning during `/api/cluster/scale`.

**Fix:** new `cloudSection` + `cloudCredentialsSection` helpers emit `[cloud] provider = "..."` and `[cloud.credentials] api_token = "..."` for cloud-type sources. `cloudComputeSection` reworked: dropped the misplaced `provider`, added `server_type` from CORE role's `instance_type`. Existing test at line 162 of `BootstrapOverlayGeneratorTest` updated to assert correct location; +3 new tests cover credentials propagation, missing-credentials path, and Docker non-emission.

**Docs landed in user-facing locations:**
- `docs/reference/cloud-integration.md` — new "Credential Propagation to Nodes" section + Hetzner-specific security note.
- `docs/operators/runbooks/scaling.md` — new "Cloud Auto-Scaling" section with prerequisites and recovery flow.
- `docs/specs/cluster-bootstrap-spec.md` — REQ-4.2.7 formalizes credential propagation behaviour.
- GitHub issue **#206** filed: RC2 investigation for runtime cloud-credential resolution alternatives (covers 5 designs from JIT broker to provider-native instance identity).

### `5573fc599` — Manifest-driven version strings

**Background:** `aether --version` reported a hardcoded `"Aether 1.0.0-alpha"` even on `release-1.0.0-rc1`. `AetherNode.VERSION` was a hardcoded `"1.0.0-rc1"`. Both required manual maintenance and went stale silently.

**Fix:** new `BuildInfo` class in `aether-config` reads `Implementation-Version` + `Implementation-Build-Date` from the executable jar's MANIFEST.MF. New `AetherVersionProvider` (picocli `IVersionProvider`). POMs (`aether/cli/pom.xml`, `aether/node/pom.xml`) configure both `maven-jar-plugin` and `maven-shade-plugin`'s `ManifestResourceTransformer` to inject the entries from `${project.version}` and `${maven.build.timestamp}`. Falls back to `"dev"`/`"unknown"` when running from IDE classpath.

`aether --version` now reports e.g. `Aether 1.0.0-rc1 (built 2026-05-05T18:46:27Z)`.

### `48a661b0e` — Bootstrap auth chicken-and-egg

**Root cause:** `ClusterBootstrapOrchestrator.httpPost` → `ClusterHttpClient.postDirect` did not attach `X-API-Key`. Cluster B's per-node config has `[app-http] api_keys = ["aether-integration-test-key"]` and `security_mode = "API_KEY"`, so formation POSTs to `/api/cluster/config` and `/api/cluster/keys` got HTTP 401.

**Fix:**
- `ClusterHttpClient.postDirect(url, body, Option<String> apiKey)` overload — adds X-API-Key when present.
- `ClusterBootstrapOrchestrator.httpPost(url, body, apiKey)` overload mirrors it.
- `BootstrapPhaseFormation.extractConfiguredApiKey` extracts the static key:
  - **Preferred:** rich syntax `[source.X.node_config.app-http.api-keys.<key>]` with `authorization_role = "ADMIN"` (formation POSTs require ADMIN privilege).
  - **Fallback:** simple `api_keys = ["..."]` list (defaults to VIEWER — yields HTTP 403 if used for bootstrap).
- `cloud-hetzner-b.toml` updated to use rich syntax with explicit `authorization_role = "ADMIN"`.

### `29953e7ae` — Quorum-safety test cloud-awareness + cleanup ID type

Two unrelated fixes bundled:

**a) Test infra:** `test-01-quorum-safety.sh:direct_scale_status` was Docker-only (port-hopping on `${TARGET_HOST}:${MGMT_PORT}+i`). On cloud, each node has its own IP; port-hop hit nothing → all attempts returned `status: 000` → 3p/3f. Cloud branch resolves leader IP via `cloud_public_ip`, hits the leader directly. Result: 6p/0f on cloud.

**b) Bootstrap cleanup:** `BootstrapPhaseProvision.buildUpdatedState` constructed `CreatedResource.ProvisionedVm` with `node.nodeId()` (e.g. `hetzner-eu-core-0`) as `resourceId`. On cleanup, `BootstrapCleanup.terminateInstance` passed that string to `HetznerComputeProvider.terminate` which expects a numeric Long → `NumberFormatException` on every failed-bootstrap cleanup; cloud-reaper backstop recovered. Fixed by passing `node.serverId()` (the actual Hetzner numeric instance ID).

---

## 2 · The 3 remaining cluster B failures

### 2a · 05-security cert-rotation under load

**Symptom:** `Error rate during cert rotation < 5.0%: error rate 11.11% exceeds threshold 5.0%`. Cert renewal triggers a brief connection-refused window; the test runs 30 RPS for ~3s and 3 of 30 requests fail.

**Yesterday's failure was different** — yesterday's was bootstrap auth (now fixed by `48a661b0e`). Today's is a real flake in cert-rotation hot-swap. Either the rotation needs zero-downtime semantics, or the test threshold needs widening (Hetzner connection-establishment overhead is real). Investigation candidates:
- Look at `CertificateRenewalScheduler` rotation atomics — is the new bundle published before old listener tears down?
- Lower-impact: bump the test threshold to 15% on cloud (TIMEOUT_SCALE pattern from yesterday).

The auth + RBAC sub-tests within 05-security all PASS:
- `Status requires auth` ✓, `Status with valid auth` ✓, `Invalid API key returns 403` ✓
- `Admin can deploy` ✓, `Operator can scale` ✓
- 2 SKIPs intentional (`AETHER_VIEWER_API_KEY not set`).

### 2b · 13-edge-cases disruption-budget drain — UNCHANGED FROM YESTERDAY

**Symptom:** `First drain should be accepted (within budget), got 500`. Drain endpoint returns 500 instead of 200 (within budget) or 429 (over budget). 3p/3f on `test-disruption-budget`. Other 13-edge-cases sub-tests pass (7p/0f, 7p/0f).

This is the same server-side disruption-budget bug flagged in `session-handover-2026-05-05.md` §3d. Unchanged this session. Real product work for RC2.

### 2c · 12-network — UNCHANGED, capability gate

`PARTITION=false` capability gate skips on cloud (Hetzner blocks unprivileged iptables). Document-only.

---

## 3 · Other observations

### Test-helper flake — `wait_for_node_count_fast`

Across cloud Phase C runs the fast-poll variant of node-count assertions reports `last seen '?'` and FAILs, then the slow-poll fallback (`cluster_node_count`) PASSes the next assertion. The `?` comes from a non-numeric value in the cluster-status JSON when the cluster is mid-reconciliation. Worth tightening the parser to wait until `coreCount` is numeric before timing out — but the slow-poll fallback works, so test results are correct.

### Build environment

`./build.sh` Step 2 (format/lint) blocked by **20 pre-existing JBCT-VO-02 violations** in `aether/environment-integration`, `aether/integrations/resource-api`, `aether/slice` modules. Triggered by `e082d60e1` which tightened the rule. Not introduced this session; using focused `mvn -pl aether/cli install -DskipTests -am` to bypass. Worth a separate session to address.

### `integrations/consensus` surefire hang

`mvn -pl aether/cli install -am` (no `-DskipTests`) hung the build runner for ~70 minutes on `integrations/consensus` surefire. Likely a deterministic test deadlock or background thread that never exits. Workaround: `install -DskipTests -am` then targeted `mvn test` per-module. Worth filing as a tracked issue.

---

## 4 · Files changed this session

### NEW
- `aether/aether-config/src/main/java/org/pragmatica/aether/config/BuildInfo.java`
- `aether/aether-config/src/test/java/org/pragmatica/aether/config/BuildInfoTest.java`
- `aether/cli/src/main/java/org/pragmatica/aether/cli/AetherVersionProvider.java`
- `aether/docs/internal/progress/session-handover-2026-05-05b.md` (this file)

### Notably touched
- `aether/cli/src/main/java/org/pragmatica/aether/cli/cluster/BootstrapOverlayGenerator.java` — `[cloud]` + `[cloud.credentials]` emission (44af74a9c)
- `aether/cli/src/main/java/org/pragmatica/aether/cli/cluster/BootstrapPhaseFormation.java` — `extractConfiguredApiKey` (48a661b0e)
- `aether/cli/src/main/java/org/pragmatica/aether/cli/cluster/BootstrapPhaseProvision.java` — `node.serverId()` for ProvisionedVm.resourceId (29953e7ae)
- `aether/cli/src/main/java/org/pragmatica/aether/cli/cluster/ClusterBootstrapOrchestrator.java` — `httpPost(url, body, apiKey)` overload
- `aether/cli/src/main/java/org/pragmatica/aether/cli/cluster/ClusterHttpClient.java` — `postDirect(url, body, apiKey)` overload
- `aether/cli/src/main/java/org/pragmatica/aether/cli/AetherCli.java` — `versionProvider`, banner uses `BuildInfo`
- `aether/node/src/main/java/org/pragmatica/aether/node/AetherNode.java` — `VERSION = BuildInfo.version()`
- `aether/cli/pom.xml` + `aether/node/pom.xml` — manifest entries for `Implementation-Version` + `Implementation-Build-Date`
- `aether/tests/integration/env/cloud-hetzner-b.toml` — rich-syntax ADMIN-role API key
- `aether/tests/integration/suites/03-scaling/test-01-quorum-safety.sh` — cloud-aware `direct_scale_status`
- `aether/docs/reference/cloud-integration.md` — Credential Propagation to Nodes section
- `aether/docs/operators/runbooks/scaling.md` — Cloud Auto-Scaling section
- `aether/docs/specs/cluster-bootstrap-spec.md` — REQ-4.2.7

---

## 5 · Path forward — three options

### Option A: Fix 05-security cert-rotation now
- Investigate cert-rotation hot-swap zero-downtime semantics.
- Likely 1-2 hours including verifying with another Phase C run.
- Outcome: 12/15 PASS / 1 SKIP / 2 FAIL.

### Option B: Stop, file 05-security + 13-edge-cases as RC2 issues, push commits, wrap up

- 11/15 + 1 skip + 2 known-issue RC2 tickets.
- Commit + push the 4 commits, move `v1.0.0-rc1-candidate` tag.
- Honest snapshot of where RC1 stands.
- Estimate: 30 min to write tickets, push, tag.

### Option C: Address full sweep of pre-existing JBCT-VO-02 violations (separate concern)

The `./build.sh` block is a real issue. ~20 violations across 3 modules. Each is a `parse + construct` pattern; the rule was tightened in `e082d60e1`. Could be a half-day session to refactor cleanly.

---

## 6 · Recommendation

**B**: file the 2 remaining failures as RC2 issues, push the 4 commits, close the loop on this session. The cloud auto-scale story for RC1 is now 11/15 + 1 documented skip + 2 explicit known issues. That's shippable.

---

## 7 · Quick reference — Hetzner state

```
$ curl ... | jq '.servers[].name'
aether-test-pg-681ab7   # PG VM, persistent across sessions
```

Account clean. Spend this session: ~€2.20 across 1 Phase B + 2 Phase C runs (one timeout-killed, one 90-min full).

**Tag move pending:** `wrap-up` will move `v1.0.0-rc1-candidate` to current HEAD `29953e7ae`.
