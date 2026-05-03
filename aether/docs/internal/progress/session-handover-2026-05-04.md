# Session Handover — 2026-05-04

**Branch:** `release-1.0.0-rc1`  ·  **HEAD:** `7a913e80a` (pushed)  ·  **18 commits this session**

---

## ⚡ TL;DR — start the next session here

**Cloud bootstrap is complete and validated end-to-end on Hetzner for both deployment paths.** The 11 cloud-bootstrap bugs from the 2026-05-03 handover are all closed; cascading work surfaced 9 additional bug classes (12–20a) which are also fixed. Both `aether cluster bootstrap` runs (container mode and JVM mode) report `Cluster is healthy.` and `state=CONVERGED` after `--wait`.

The next session can:
1. Run integration suites against cloud clusters (00-smoke, 15-delegation as Phase 1; then non-destructive Phase 2).
2. Investigate the flaky `CertificateRenewalSchedulerStaleTimerTest` that's been red on CI for several pushes (pre-existing, not from this session's work).
3. Optionally harden JVM mode with systemd supervision (currently `nohup … & disown`; a panic = node down forever, no auto-restart equivalent of container's `--restart unless-stopped`).

**Account state:** Hetzner clean except for the PG VM (`aether-test-pg-681ab7`, ~€0.008/hr). Cluster VMs reaped after each test.

---

## 1 · Bugs closed this session

### From the 2026-05-03 handover (the 4 P0 carryovers)

| # | Subject | Commit |
|---|---|---|
| 9 | `ProvisionedVm.provider` stores actual provider name (not `"cloud"`) | `74a2e231d` |
| 10 | Inject operator SSH keys into provisioned cloud VMs (cloud-init + Hetzner upload) | `0608fd42e` |
| — | `--keep-on-failure` flag for `aether cluster bootstrap` | `cc142390d` |
| 8 | (decomposed into Bugs 12–18 below; original "cluster never reaches quorum" hypothesis was layered) | n/a |
| 11 | (resolved as side-effect of Bug 12: mgmt API didn't respond because aether-node was never deployed) | n/a |

### Cascading bugs surfaced and fixed

| # | Subject | Commit |
|---|---|---|
| 12 | Wire `UserDataTemplate.render` per-node into cloud provisioning + JVM URL fix | `f9eb65d4d` |
| 13 | Bind-mount composed `aether.toml` over bundled `/app/aether.toml` (image entrypoint conflict) | `0ef5052eb` |
| 14 | Per-node identity via env vars (`NODE_ID`, `CLUSTER_PORT`, `MANAGEMENT_PORT`, `PEERS`, `AETHER_CLUSTER_SECRET`); drop dead `[node]`/`[cluster].peers` schema | `ea5aae7d8` |
| 15 | Cloud `DEPLOY_RUNTIME` SSHes nodes to restart with finalized PEERS | `0aa8479e1` |
| 16 | Cloud SSH-back uses `root`, image from `RuntimeProfile`, same port for all peers, SSH preflight | `6d387d800` |
| 17 | Preflight waits for cloud-init completion (`cloud-init status --wait`), not just SSH session | `d6659bb45` |
| 18 | Thread `[operations.tls].auto_generate` to `[cluster].tls` (defaults inherited mTLS-required) | `bf53f8456` |
| — | Three post-bootstrap warnings: TOML config payload, drop dead `/api/cluster/api-key` route, default `--timeout=300` when `--wait` set | `f399a431a` |
| 19 | `--wait` polls `/api/status` with apiKey, awaits `ClusterPhase=NORMAL` (first attempt — wrong endpoint) | `4c0b22ca0` |
| 19a | `--wait` polls `/api/cluster/status` with `state=CONVERGED` + JVM mode installs Temurin 25 | `95186d908` |
| 19b | `--wait` sets `ENDPOINT_OVERRIDE` from `BootstrapResult` before polling | `969f2025d` |
| 19c | `BootstrapResult.endpoint` URL includes mgmt port (was `http://<ip>` with no port) | `7a913e80a` |
| 20 | Cloud SSH-back is runtime-aware (container OR jvm) | `d61015f4c` |
| 20a | Anchor JVM pkill pattern (`^java -jar /opt/aether/aether-node.jar`) to avoid SSH self-kill | `0623ec443` |

### Side task

| # | Subject | Commit |
|---|---|---|
| — | Align envelope.version assertion with frozen `ENVELOPE_FORMAT_VERSION=1000` (no bumps until GA) | `77c1d7d24` |

---

## 2 · Verified end-to-end behavior

### Container path

`aether/tests/integration/env/cloud-hetzner.toml` (`type = "container"`, image `ghcr.io/pragmaticalabs/aether-node:1.0.0-rc1-candidate`).

```
[Phase 1/7: VALIDATE]
[Phase 2/7: UPLOAD_SSH_KEYS]
[Phase 3/7: PROVISION] core: provisioning 5 node(s)
[Phase 4/7: COLLECT_ADDRESSES]
[Phase 5/7: DEPLOY_RUNTIME]
  Waiting up to 180s for SSH to become reachable on 5 host(s)
  SSH reachable on all 5 host(s)
  Re-launching aether-node containers on 5 host(s) with finalized PEERS=…
  All 5 container(s) restarted with finalized PEERS
  Waiting for 5 node(s) to become healthy (timeout: 300s)
  All nodes reported healthy
[Phase 6/7: CLUSTER_FORMATION]
  Waiting for quorum at http://…:8080/health/ready (need 5 core(s), timeout: 600s)
  Quorum established (5 core(s) required)
  Cluster config stored in KV-Store
  API key stored
[Phase 7/7: POST_BOOTSTRAP]
Cluster "test-a" bootstrapped successfully.
Step 12/12: Done.
Waiting for cluster to become healthy (timeout: 600s)...
Cluster is healthy.
```

`/api/cluster/status` returns `state=CONVERGED`, `leaderId=hetzner-eu-core-0`, 5 nodes ON_DUTY, all task groups distributed (METRICS/SCALING/STRATEGIES/DEPLOYMENT/STORAGE).

### JVM path

`aether/tests/integration/env/cloud-hetzner-jvm.toml` (`type = "jvm"`, `[runtime.jvm] jar_url = "https://github.com/pragmaticalabs/pragmatica/releases/download/v1.0.0-rc1-candidate/aether-node.jar"`).

Same 7-phase output. JVM mode adds: Temurin 25 install via Adoptium apt repo (~60-90s), JAR download from GitHub releases (~30s), `nohup java -jar … & disown` startup.

---

## 3 · Architectural diagram of the bootstrap pipeline (post-fixes)

```
Phase 1: VALIDATE
   └─ generate clusterSecret (BootstrapPhaseValidate, persisted in BootstrapState; resume regenerates only if absent)

Phase 2: UPLOAD_SSH_KEYS
   └─ BootstrapPhaseSshKey: upload-or-reuse operator pubkey to Hetzner via SDK
   └─ stamp SshKeyResource in BootstrapState ONLY if newly created

Phase 3: PROVISION
   └─ BootstrapPhaseProvision.provisionCloudSource (per-node loop):
       1. NodeConfigBuilder.compose → composed aether.toml string
       2. UserDataTemplate.render (per-node):
          - SshAuthorizedKeysScript (SSH key install + aether user)
          - container: docker install + curl image (nothing started — see Phase 5)
            JVM: Temurin 25 install via Adoptium + curl JAR
          - chmod 644 the composed aether.toml
       3. ProvisionSpec(tags={aether-cluster, aether-source, aether-role},
                        zoneHint, userData=<rendered>)
          → compute.provision(spec) per node
       4. record ProvisionedVm{provider=source.provider().value()} in state

Phase 4: COLLECT_ADDRESSES
   └─ poll provider for each VM's public IP

Phase 5: DEPLOY_RUNTIME (no longer a no-op for cloud!)
   └─ BootstrapPhaseDeploy.deployCloudSource:
       1. SSH preflight: ssh ... 'cloud-init status --wait' on each node
          (180s budget, 5s poll, removes successful hosts each iteration)
       2. RUNTIME-AWARE restart loop:
          container: docker rm -f aether-node && docker run -d
            -v /opt/aether/config/aether.toml:/app/aether.toml:ro
            -e NODE_ID -e CLUSTER_PORT -e MANAGEMENT_PORT
            -e PEERS=<finalized 3-part list> -e AETHER_CLUSTER_SECRET
            <image-from-RuntimeProfile>
          JVM: pkill -f '^java -jar /opt/aether/aether-node.jar'; sleep 1;
                pkill -9 -f '^…'; sleep 1;
                AETHER_CLUSTER_SECRET=… nohup java -jar … --node-id= --port=
                  --management-port= --peers=<finalized> --config=… &
                  > /var/log/aether-node.log 2>&1 & disown
       3. Health-poll /health/live on each node

Phase 6: CLUSTER_FORMATION
   └─ wait for quorum at /health/ready (300s)
   └─ generate API key, POST to /api/cluster/keys
   └─ POST raw TOML to /api/cluster/config (expectedVersion=0 = initial-store)
   └─ persist API key to ~/.aether/clusters/<name>/api-key

Phase 7: POST_BOOTSTRAP
   └─ build BootstrapResult{endpoint=http://<first-ip>:<mgmt-port>, apiKey, …}
   └─ if --wait:
       - applyEndpointOverride(result) → ClusterHttpClient.ENDPOINT_OVERRIDE
       - applyApiKeyOverride(result) → ClusterHttpClient.API_KEY_OVERRIDE
       - poll /api/cluster/status, read state, until "CONVERGED"
   └─ exit 0 (or TIMEOUT)
```

---

## 4 · Important code locations

### CLI bootstrap (touched extensively)
- `aether/cli/.../cluster/ClusterBootstrapCommand.java` — `--wait` polling + endpoint override + apiKey override
- `aether/cli/.../cluster/ClusterBootstrapOrchestrator.java` — `BootstrapResult`, `BootstrapContext` (carries clusterSecret, sshPublicKeys, rawTomlContent)
- `aether/cli/.../cluster/BootstrapPhaseValidate.java` — clusterSecret generation
- `aether/cli/.../cluster/BootstrapPhaseSshKey.java` — Phase 2
- `aether/cli/.../cluster/BootstrapPhaseProvision.java` — Phase 3 (per-node user_data wiring; provider name fix)
- `aether/cli/.../cluster/BootstrapPhaseDeploy.java` — Phase 5 (SSH preflight, runtime-aware restart, health-poll)
- `aether/cli/.../cluster/BootstrapPhaseFormation.java` — Phase 6 (config storage, api-key)
- `aether/cli/.../cluster/BootstrapPhasePost.java` — Phase 7 (BootstrapResult build, includes mgmt port)
- `aether/cli/.../cluster/BootstrapOverlayGenerator.java` — composed config sections, threads `tls = config.operations().tls().autoGenerate()`
- `aether/cli/.../cluster/UserDataTemplate.java` — cloud-init rendering, container + JVM modes
- `aether/cli/.../cluster/SshAuthorizedKeysScript.java` — minimal ssh-key install (always emitted first)
- `aether/cli/.../cluster/SshKeyResolver.java` — operator key resolution (CLI > TOML > $AETHER_SSH_KEY.pub)
- `aether/cli/.../cluster/SshPublicKey.java` — value object
- `aether/cli/.../cluster/NodeConfigBuilder.java` — per-node TOML composer
- `aether/cli/.../cluster/CreatedResource.java` — sealed: ProvisionedVm, SshKeyResource
- `aether/cli/.../cluster/BootstrapCleanup.java` — runtime-aware cleanup
- `aether/cli/.../cluster/ClusterHttpClient.java` — `ENDPOINT_OVERRIDE`, `API_KEY_OVERRIDE`

### Provider integration
- `aether/environment-integration/.../CloudProviderSupport.java` — `provisionOne` per-node
- `aether/environment/hetzner/.../HetznerComputeProvider.java` — honors `spec.userData()`, `spec.tags()`, `spec.zoneHint()`
- (AWS/GCP analogues updated similarly; Azure userData wiring is a known gap, out of scope this session)

### Config schema
- `aether/aether-config/.../cluster/RuntimeProfile.java` — `image()`, `jarUrl()` accessors
- `aether/aether-config/.../cluster/ClusterBootstrapConfigParser.java` — parses `[runtime.X] type / image / jar_url`, `[infrastructure.ssh] public_key_files`, `[operations.tls] auto_generate`
- `aether/aether-config/.../cluster/SshDeploymentConfig.java` — operator SSH key schema

### Test configs
- `aether/tests/integration/env/cloud-hetzner.toml` (container, validated)
- `aether/tests/integration/env/cloud-hetzner-b.toml` (container, untested this session)
- `aether/tests/integration/env/cloud-hetzner-jvm.toml` (JVM, validated; NEW this session)

---

## 5 · Hetzner state and tooling

### Right now

```
$ curl -s -H "Authorization: Bearer $HCLOUD_TOKEN" https://api.hetzner.cloud/v1/servers \
    | python3 -c "import json,sys; d=json.load(sys.stdin); [print(s['name'], s.get('labels')) for s in d['servers']]"
aether-test-pg-681ab7 {'aether-cluster': 'test-pg', 'aether-role': 'postgres'}
```

PG VM `128911684` at `46.224.170.85`. PG_URL stored at `/tmp/aether-test-pg.env` (mode 0600). Re-source between iterations.

### Quotas

Hetzner-approved increases (from prior session): server limit ≥ 6, primary IP default ~10. Cluster VMs are IPv4-only (Bug 12+ era, `PublicNetSpec(true, false)` default), so 5 cluster + PG = 6 IPv4 + 0 IPv6 (PG has both, others IPv4 only) = comfortably within quota.

### Tooling

| Tool | Purpose |
|---|---|
| `tools/cloud-reaper.sh --cluster <name> --destroy --force` | Hetzner cleanup-by-label safety net. Run between iterations. |
| `tools/provision-test-pg.sh` | Idempotent PG VM provisioner. Writes `/tmp/aether-test-pg.env`. `--print-only` to recover URL when env file missing. `--destroy` to nuke. |
| `aether cluster destroy --yes` | CLI cleanup. NOTE: no `--cluster` flag on destroy (only on bootstrap). Sets active cluster only. Use the reaper for arbitrary clusters. |

### CLI install loop

After every CLI rebuild:
```
mvn -pl aether/cli install -DskipTests -am -q
cp aether/cli/target/aether.jar ~/.aether/lib/aether.jar
```
The wrapper at `~/.aether/bin/aether` runs from there. Worth automating into a make target.

---

## 6 · Known issues (not blocking this session's deliverable)

### CI flaky test

`org.pragmatica.net.tcp.security.CertificateRenewalSchedulerStaleTimerTest.immediateRenewalBranch_storesScheduledFutureForCancellation` failed on `4c0b22ca0`'s CI run; passed on `bf53f8456`'s. Pre-existing flake, unrelated to this session. The `mvn -pl aether/cli test` command pattern in MEMORY explicitly excludes it (`-Dtest='!CertificateRenewalSchedulerStaleTimerTest,...`). Should be either fixed or marked `@Disabled` until fixed.

### `aether cluster destroy --cluster <name>` doesn't exist

The CLI's destroy command operates on the **active** cluster only — there's no `--cluster` override (unlike `bootstrap`). For arbitrary cluster cleanup, use `tools/cloud-reaper.sh`. This is a UX gap; should be a Bug 21 in the next session: add `--cluster` to destroy, symmetric with bootstrap.

### JVM mode lacks process supervision

`nohup java -jar … & disown` means a JVM panic = node down forever. No equivalent of container's `--restart unless-stopped`. Mitigations to consider:
- (a) tiny systemd unit in `appendJvmInstall`
- (b) `until` loop wrapper in user_data
- (c) rely on aether's auto-heal to detect unresponsive node and re-provision

Acceptable for RC1 demo / smoke tests; harden before production.

### `BootstrapPhaseDeploy.startRuntimeViaSsh` (SSH-source path) hardcodes `:latest`

Out of scope for this session (we focused on cloud sources). The SSH source path runs `docker pull image:latest` instead of reading from RuntimeProfile.image(). File for next session.

### `Azure` user_data plumbing is incomplete

`AzureEnvironmentConfig.userData()` is read but never threaded into `CreateVmRequest.osProfile.customData`. Cloud-init for Azure won't run from this path even though Bug 12+ wired it for Hetzner/AWS/GCP. Out of scope.

### Pre-existing CI failures on prior pushes

`gh run list --workflow=ci.yml --branch=release-1.0.0-rc1` shows 5 of last 8 pushes red — some are mine (Bug 19's wrong-route attempt), some are pre-existing flakes. Worth one focused triage session to determine which are real regressions vs flakes.

---

## 7 · The 13 PRs awaiting rebase

User mentioned PRs #191–#204 (except #198) need to rebase onto current HEAD. The branch is now in a clean post-bootstrap-completion state with:
- 18 commits ahead of where they last rebased
- The Bug 19 → 19c chain is the most logically dense — if a PR's CI picks up an intermediate commit (e.g. `4c0b22ca0`), it'll see the wrong-route test failure; only `7a913e80a` is fully green-by-design.

Recommend rebasing onto `7a913e80a` (current HEAD). Do not rebase onto `4c0b22ca0` (broken intermediate state).

---

## 8 · Next-session priority order

1. **Verify CI green on `7a913e80a`** — currently in_progress at session-write time. Run `gh run view <id> --log-failed` if it fails; expect either the CertificateRenewal flake or zero failures.
2. **Rebase the 13 PRs** onto current HEAD (user's task; CI validates each).
3. **Run integration suites against cloud cluster.** `aether/tests/integration/run-tests.sh` has Docker support — needs a `--env cloud-hetzner` mode (or similar) that sources the JVM/container TOML, runs `cluster bootstrap`, executes 00-smoke + 15-delegation, tears down via reaper. This is the actual "production readiness" deliverable from the prior handover.
4. **Fix the flaky `CertificateRenewalSchedulerStaleTimerTest`** OR mark `@Disabled`. Stops red CI runs from masking real regressions.
5. **Add `--cluster` to `aether cluster destroy`** (symmetry with bootstrap; ~10 LoC).
6. **Optional: harden JVM mode with systemd unit** (~50 LoC in `appendJvmInstall`).

---

## 9 · Acceptance criteria — were they met?

From the 2026-05-03 handover:

> Mission complete when:
> - `aether cluster bootstrap …` returns success. ✅
> - `aether status` shows 5 ON_DUTY nodes with leader and `clusterPhase=NORMAL`. ✅ (verified via `/api/cluster/status` directly: state=CONVERGED, leaderId, 5 ON_DUTY)
> - `aether cluster destroy …` cleans up all VMs (no reaper needed). ⚠️ Partial — `cluster destroy` exists, but lacks `--cluster` so reaper is still the practical tool for multi-cluster scenarios. Bug 9's fix made the destroy work correctly when invoked via reaper or active cluster.
> - 0 leaked resources in `tools/cloud-reaper.sh` dry-run output post-teardown. ✅

> Stretch goal:
> - Phase 2 non-destructive suites pass on cloud. ❌ Not attempted (next session).

---

## 10 · Total spend

Hetzner billing for this session: ~€0.40 across multiple bootstrap iterations. 5 cluster VMs × cx33 (~€0.0079/hr each) × ~6 iterations × ~10 min average per iteration. Plus PG VM running ~6 hours. Negligible relative to the value delivered.

---

## 11 · Final thought

The cloud bootstrap pipeline is now structurally complete and substantially tested. Container mode is the production path; JVM mode is the no-Docker fallback. Both share the same KV-store-backed cluster config, the same Rabia consensus, the same SWIM membership — the runtime difference is purely about how the JVM gets started.

Next session can either tighten the cloud bootstrap (process supervision, destroy-by-cluster-flag, integration suite wiring) OR start running the actual integration test suites against Hetzner clusters. The latter is the more user-visible RC1 milestone.
