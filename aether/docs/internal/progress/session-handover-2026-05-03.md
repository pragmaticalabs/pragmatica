# Session Handover — 2026-05-03

**Branch:** `release-1.0.0-rc1`  ·  **HEAD:** about-to-be-pushed; ~5 unpushed at handover-write time  ·  **Tag:** `v1.0.0-rc1-candidate` (will move to handover commit)

---

## ⚡ TL;DR — start the next session here

**The membership-architecture redesign is DONE and validated** (12/15 on remote Docker before remote-host loss, 13/15 reachable with deferred 12-network fix). All redesign work is committed and pushed.

**The next session's mission: implement production-grade cloud bootstrap machinery, then run the 15-suite integration test on Hetzner cloud.** This is the only path forward — the remote Docker host is no longer accessible. Forge/local Docker remains for unit tests but cannot validate the full cluster sweep.

We hit 11 separate cloud-bootstrap bugs in this session. **7 are fixed and committed**; **4 remain** and require non-trivial Java work. The session ended after burning through enough Hetzner trial-and-error to validate the diagnosis.

**Strongly recommend MAXIMAL DELEGATION for the next session.** Each fix below is a self-contained jbct-coder task. Do not load production source files into the main context unless absolutely necessary — dispatch focused agents per fix, commit between them, and let chore-runner / build-runner absorb the maven noise.

---

## 1 · State of the world

### 1a · What's running on Hetzner right now

```
$ curl -s -H "Authorization: Bearer $HCLOUD_TOKEN" https://api.hetzner.cloud/v1/servers | jq
```

| Resource | ID | Purpose | Cost |
|---|---|---|---|
| Server `aether-test-pg-acc2b9` | `128894483` | Test PostgreSQL VM (cx23, ubuntu-22.04, fsn1, public IPv4 `178.104.219.230`) | ~€0.0079/hr (≈€0.19/day) |
| Primary IP `129106324` (IPv4) | assigned to PG VM | required for psql access from host | included in server cost |
| Primary IP `129106325` (IPv6) | assigned to PG VM | (provisioned by reaper-style; harmless) | included |
| SSH key `aether-test-pg-key-9f5007` (id `111741620`) | uploaded for PG VM | required for `provision-test-pg.sh` reuse path | free |

**Decision needed at session start:** keep PG VM running for fast iteration, or destroy and re-provision when cloud testing resumes? The provisioner is idempotent — you can `tools/provision-test-pg.sh --destroy` to nuke and `tools/provision-test-pg.sh` to recreate (~60s). PG_URL stored at `/tmp/aether-test-pg.env` (mode 0600) — survives across shell sessions but NOT across reboots; re-run provisioner with `--print-only` if missing.

### 1b · Hetzner account quotas (post-increase)

User requested + Hetzner approved a quota increase during this session. Current effective quotas (verified by getting past prior failures):

| Quota | Value | Notes |
|---|---|---|
| Server count | ≥ 6 | Increased from 5. We ran 1 PG + 5 cluster successfully; precise upper bound unknown. |
| Primary IP | ~10 (default) | We use IPv4-only on cluster VMs (PR landed) so 1 IP per VM = 5 + PG's 2 = 7 → fits. |

Hetzner location: `fsn1` (Falkenstein). Datacenter `fsn1-dc14` is the specific data center inside fsn1; both are valid for Hetzner's `location` field but the cloud-provider integration sends `location` not `datacenter`. **Use `zone = "fsn1"` and `region = "fsn1"` in the bootstrap TOML, NOT `fsn1-dc14`.**

### 1c · Branch state

8 commits unpushed at handover-write time (all Phase-0.5/Phase-1 prep + diagnostic fixes). After this handover commit + tag move, plan to push: ~9 commits.

```
2e46b480d fix(test-infra): cloud harness uses direct public-IP addressing (Option A) via bootstrap-state.json + cx33
3e704f25b fix(cli): cluster destroy now terminates cloud VMs via BootstrapCleanup, adds --keep-resources flag
f44cd8906 feat(cli): add --cluster override flag for bootstrap command (CLI > TOML)
47e5af861 feat(tools): add Hetzner cloud-reaper safety-net script for aether-labeled resources
a5fc5e0a4 feat(tools): add provision-test-pg.sh for idempotent Hetzner PG VM provisioning
1ad3b807a fix(tools): provision-test-pg.sh — stderr logging, single-line docker run, root SSH for smoke test
+ 4 more from this session (Hetzner SDK PublicNetSpec, Hetzner provider hardening, label propagation, CLI shade transformer, TOML config)
+ this handover
```

### 1d · Membership redesign status (for context — completed in prior sessions)

R1–R10 + JBCT review rounds + bootstrap retry fix + leader-routing fix + CTM scale-up fix + 12-network event-bridge **all landed**. Last validated sweep on remote Docker: **12/15 (prior to remote going down).** The 12-network failure was traced to `ObservationAggregator.respectColdBoot` over-suppressing FAULTY for peers that joined directly HEALTHY; fix attempt `414bd361f` was REVERTED (`cdbb873e3`) because it destabilized other suites. **12-network is the lone remaining redesign-related test failure** and needs a different fix than the cold-boot guard relaxation. Out of scope for the next session unless cloud sweep also exhibits it.

---

## 2 · The 11 cloud-bootstrap bugs (full detail)

### ✅ FIXED

#### Bug 1: Cluster version must be strict `X.Y.Z` semver
- **Symptom:** `Failed to parse cluster config: CL-02: Cluster version '1.0.0-rc1' must be valid semver X.Y.Z`
- **Root cause:** `ClusterBootstrapConfigParser` uses a strict regex; SemVer 2.0 prerelease suffixes (`-rc1`) rejected.
- **Fix:** TOML edited — `version = "1.0.0-rc1"` → `version = "1.0.0"` in both `cloud-hetzner.toml` and `cloud-hetzner-b.toml`.
- **Commit:** in the TOML batch (commit 4 of bootstrap-fix series).
- **Follow-up consideration:** parser SHOULD accept full SemVer 2.0; current behavior contradicts the project's own `1.0.0-rc1` Maven version. Logging issue.

#### Bug 2: Credentials key mismatch (`api_token` vs `credentials_file`)
- **Symptom:** `Hetzner API error 401 (unauthorized): unable to authenticate` despite `HCLOUD_TOKEN` set in env and verified via direct curl.
- **Root cause:** `ProviderResolver.buildCloudConfig` stored the `${env:HCLOUD_TOKEN}`-resolved string under map key `"credentials_file"`, but `HetznerEnvironmentIntegrationFactory.buildEnvironmentConfig` reads `creds.getOrDefault("api_token", "")`. Empty string → 401.
- **Fix:** `ProviderResolver.buildCloudConfig` now writes the value under three aliases (`credentials_file`, `api_token`, `access_key`) so each provider factory finds its preferred key.
- **Commit:** part of "fix(bootstrap): propagate cluster labels to ProvisionSpec.tags() + multi-key credentials alias".
- **Follow-up consideration:** factory should declare its expected key via the SPI, not let the orchestrator guess. Architectural cleanup.

#### Bug 3: Empty `image` field (no default OS image)
- **Symptom:** `Hetzner API error 422 (invalid_input): invalid input in field 'image'`
- **Root cause:** `HetznerEnvironmentIntegrationFactory` reads `compute.getOrDefault("image", "")`. The bootstrap orchestrator (`ProviderResolver.buildCloudConfig`) doesn't populate the `image` key; TOML's `[runtime.default] image` is the **Docker image** (`ghcr.io/.../aether-node`), NOT the OS image.
- **Fix:** Added `DEFAULT_IMAGE = "ubuntu-22.04"` and `DEFAULT_SERVER_TYPE = "cx33"` constants in `HetznerEnvironmentIntegrationFactory`; uses `nonBlank(value, fallback)` helper so blank fields don't reach the API.
- **Commit:** "feat(hetzner): IPv4-only default + image/server-type fallbacks + spec.tags() label merge".
- **Follow-up consideration:** OS image and Docker image are conceptually distinct fields. The TOML schema should expose `[source.X.core] os_image` separately. Today the OS image is hardcoded in the factory. Real bootstrap UX should let operators choose (`debian-12`, `ubuntu-24.04`, etc.).

#### Bug 4: Region/zone semantics don't match Hetzner API
- **Symptom:** `Hetzner API error 404 (not_found): datacenter not found`
- **Root cause:** TOML originally had `region = "eu-central"` (AWS-style) and `zone = "fsn1-dc14"` (Hetzner datacenter notation). HetznerComputeProvider sends `config.region()` to Hetzner's `location` field. Hetzner expects `fsn1` (location) — `eu-central` and `fsn1-dc14` are both wrong.
- **Fix:** TOML edited — `zone = "fsn1"` and `region = "fsn1"` in both env files. Comments explain Hetzner's location-vs-datacenter distinction.
- **Commit:** TOML batch.
- **Follow-up consideration:** the `CloudProviderSupport.withZonePlacement` change (added during the label fix) creates a `ZoneHint` from TOML's `zone` field. `HetznerComputeProvider.locationFromHint` returns the zone name verbatim → would still 404 if zone has the `-dcN` suffix. Best practice: parser should validate region/zone against the provider's known values, OR provider should normalize (`fsn1-dc14` → `fsn1`).

#### Bug 5: Primary IP quota exceeded (Hetzner default ≈10)
- **Symptom:** `Hetzner API error 403 (resource_limit_exceeded): Primary IP limit exceeded` after creating ~3 cluster VMs.
- **Root cause:** Each Hetzner VM by default gets BOTH IPv4 and IPv6 Primary IPs. 5 cluster + 1 PG × 2 protocols = 12 IPs needed; default account quota ≈10. Failed bootstrap attempts also LEAKED IPs — 8 dangling IPs accumulated across retries until manually cleaned.
- **Fix (1):** Added `PublicNetSpec(enable_ipv4, enable_ipv6)` record to `CreateServerRequest` in the Hetzner SDK. New factory overload accepts the spec. `HetznerComputeProvider.buildCreateRequest` defaults all cluster VMs to `IPV4_ONLY` (`new PublicNetSpec(true, false)`). 5 cluster × 1 IP + PG × 2 = 7 IPs total → fits comfortably.
- **Fix (2):** `tools/cloud-reaper.sh --destroy` script handles cleanup of orphans by label selector.
- **Commits:** "feat(hetzner): IPv4-only default..." for the SDK + provider; reaper script committed earlier as `47e5af861`.
- **Follow-up consideration:** IPv6 should be a per-source TOML knob, not hardcoded. Cluster nodes probably want IPv4-only; PG VMs and bastions might want both. Add `[source.X] enable_ipv6 = true|false` to the schema.

#### Bug 6: Hetzner account server quota
- **Symptom:** `Hetzner API error 403 (resource_limit_exceeded): server limit reached` at the 5th cluster VM (with PG already running, total = 6).
- **Root cause:** Hetzner default account server quota was **5**. The bootstrap creates VMs in parallel; with 1 PG already up, the 5th cluster VM hit the limit. The 4 successful provisions then orphaned because BootstrapCleanup.cleanup() failed (see Bug 9).
- **Fix (1):** User requested + Hetzner approved quota increase. New limit ≥6 verified empirically. Probable new limit is 10–20; precise value unknown.
- **Fix (2):** None code-side. This is an account-level constraint; production users will face the same need.
- **Commit:** none (external resolution).
- **Follow-up consideration:** Bootstrap should validate against quota BEFORE attempting provision (Hetzner's API exposes quota via `/v1/pricing` or similar). Better: pre-flight check that totals proposed-VMs against `GET /v1/servers count`. Also: dual-cluster (test-a + test-b) needs 10 cluster VMs + PG, which may still exceed account quota; consider single-cluster cloud testing or sequential cluster runs.

#### Bug 7: Cluster labels show `aether-cluster=unknown`
- **Symptom:** Hetzner VMs got labels `{"aether-cluster": "unknown", "aether-role": "core"}` instead of the expected `{"aether-cluster": "test-a", "aether-source": "hetzner-eu", "aether-role": "core"}`.
- **Impact:** `tools/cloud-reaper.sh --cluster test-a --destroy` couldn't match orphaned VMs (selector miss). On every bootstrap failure, operator had to delete by `aether-cluster=unknown` filter manually.
- **Root cause:** `BootstrapPhaseProvision.provisionRoleGroup` correctly builds `labels = Map.of("aether-cluster", clusterName, "aether-source", sourceName, "aether-role", role.value())` (line 148), but the labels were stored on `NodeGroupConfig` and **never reached the `ProvisionSpec` passed into `compute.provision(spec)`**. `CloudProviderSupport.provisionSingle` was calling the tag-less `compute.provision(InstanceType.ON_DEMAND)` overload instead of the spec-taking overload.
- **Fix:** Refactored `CloudProviderSupport.provisionSingle` to build a `ProvisionSpec` (with tags + zone placement) and call `compute.provision(spec)`. New `buildProvisionSpec` and `withZonePlacement` helpers (extracted to package-private for testability). Combined with `HetznerComputeProvider.buildCreateRequest`'s `mergeLabels(buildLabels(), spec.tags())` which gives spec.tags() precedence. Verified end-to-end on Hetzner: VMs now show `aether-cluster=test-a` correctly.
- **Tests:** new `CloudProviderSupportTest` with 5 tests, including `provisionVia_passesTagsToComputeProvider` end-to-end via RecordingComputeProvider stub.
- **Commit:** "fix(bootstrap): propagate cluster labels to ProvisionSpec.tags() + multi-key credentials alias".
- **Follow-up consideration:** Same propagation gap probably exists in AWS/GCP/Azure providers (they all read tags from spec). They weren't tested in this session but should be audited.

### ❌ NOT FIXED — these are the next session's work

#### Bug 8: Cluster formation timeout — VMs provision but never reach quorum
- **Symptom:** Phase 5 of bootstrap reports `Quorum not established: 0/1 nodes healthy` after 300s timeout. Provisioning, address collection, and "deploy runtime" all succeed (cloud-init claims success). After timeout, bootstrap attempts auto-teardown which fails (Bug 9), leaving orphans.
- **What we know:**
  - The 5 cluster VMs ARE created and accessible via Hetzner API.
  - Port 22 (SSH) is open — but **NO SSH key is installed** on cluster VMs (Bug 10), so we cannot login as either `root` or `aether`.
  - Port 8080 (mgmt) is open at the TCP layer but `curl http://<ip>:8080/health/live` returns HTTP 000 (TCP accepted, no HTTP response).
  - Port 6000 (cluster QUIC consensus) is **closed/filtered** — Rabia consensus cannot establish.
  - Without SSH, cannot inspect cloud-init logs, docker container status, or aether-node logs.
- **Possible root causes (ranked by likelihood):**
  1. **Image not pullable.** `runtime.default.image = "ghcr.io/pragmaticalabs/aether-node:1.0.0-rc1"` may be private, unpublished, or behind auth. Cloud-init `docker pull` would silently fail; container never starts. **Probability: HIGH** — easy to verify by trying `docker pull ghcr.io/pragmaticalabs/aether-node:1.0.0-rc1` from any host (anonymous pull). If it fails, we need to either (a) make the image public, (b) push a test-only fork, (c) use a different image source for cloud testing, or (d) build the image inside cloud-init from a Dockerfile.
  2. **TLS cert generation failed.** `[operations] tls_auto_generate = true` — the bootstrap is supposed to generate self-signed certs for HTTPS mgmt. If cluster_secret derivation or cert mounting in the container fails, the mgmt server can't start its HTTPS listener (which would explain HTTP 000). **Probability: MEDIUM.**
  3. **Inter-node networking blocked at Hetzner level.** Default Hetzner VMs have no firewall, but maybe Phase 4 of bootstrap creates a firewall that blocks 6000? Or cloud-init configures `ufw`? **Probability: LOW** (Hetzner provider has no FirewallProvider integration today).
  4. **PG_URL connectivity from cluster nodes.** Each cluster VM tries to connect to `postgres://aether:...@178.104.219.230:5432/aether_forge`. If Hetzner inter-VM traffic is blocked OR if PG VM's firewall blocks port 5432 from arbitrary IPs, the aether-node container can't initialize Forge. **Probability: MEDIUM.**
  5. **Container runs but `aether-node` panics on startup.** Config interpolation issue, missing required env var, etc. **Probability: MEDIUM.**
- **Diagnosis blocker:** without SSH access (Bug 10), we cannot inspect ANY of the above. **Bug 10 must be fixed first** OR we use the Hetzner web console (interactive, not automation-friendly).
- **Recommendation:** Fix Bug 10 first; then re-bootstrap with `--keep-resources` (or whatever flag prevents teardown on failure — currently no such flag exists; bootstrap may need a `--keep-on-failure` analog to `cluster destroy --keep-resources`); SSH in and inspect.

#### Bug 9: BootstrapCleanup uses literal `"cloud"` instead of real provider name
- **Symptom:** When bootstrap fails mid-phase, the auto-cleanup reports `Provisioning failed for source 'cloud': No EnvironmentIntegrationFactory found for provider 'cloud'` for every provisioned VM. **All 5 cluster VMs orphan on every failed bootstrap.** Manual cleanup required via reaper or direct API DELETE.
- **Impact:** **Costs real money on every iteration.** Every failed bootstrap leaks 5 VMs running until reaper kills them. We had to clean up after ~5 attempts in this session.
- **Root cause:** `BootstrapCleanup.cleanup(state)` iterates `state.createdResources()` LIFO. Each `CreatedResource.ProvisionedVm` has a `provider` field that's set to the literal string `"cloud"` (the source TYPE) instead of the actual provider name (e.g., `"hetzner"`). When `BootstrapCleanup` calls `ProviderResolver.resolveCloudCompute(vm.provider())`, it tries to look up a `"cloud"` factory — there isn't one, only `hetzner`/`aws`/`gcp`/`azure`/`docker`.
- **Where the bug originates:** `BootstrapPhaseProvision` builds `ProvisionedVm` records when stamping resources into state. It passes the source's `type.value()` (= `"cloud"`) instead of `source.provider().map(p -> p.value()).or("cloud")` (= `"hetzner"` for Hetzner).
- **Recommended fix:** In `BootstrapPhaseProvision.recordProvisionedResources` (or wherever ProvisionedVm is constructed), pass the provider name, not the source type. Update `CreatedResource.ProvisionedVm` callers to receive `provider: String` correctly.
- **Test:** add unit test asserting `BootstrapCleanup.cleanup(state)` resolves the correct provider when state contains a Hetzner-provisioned VM.
- **Effort:** ~1-2 hours including tests. Self-contained fix.
- **Priority:** **CRITICAL.** Without this, every failed bootstrap iteration orphans VMs. Cloud testing iteration speed is gated by reaper-clean cycles.

#### Bug 10: No SSH key injection in `UserDataTemplate`
- **Symptom:** `ssh root@<cluster-vm-ip>` and `ssh aether@<cluster-vm-ip>` both fail with `Permission denied (publickey,password)`. Operator cannot debug failed cluster bootstraps.
- **Impact:** Bug 8 is undiagnosable without SSH. Production users will also need SSH for routine debugging.
- **Root cause:** `aether/cli/.../UserDataTemplate.java` renders cloud-init for cluster nodes. It does NOT include an `ssh_authorized_keys:` section or `users:` block with SSH keys. Hetzner SDK's `CreateServerRequest` accepts an `ssh_keys` field (List<Long> of Hetzner SSH key IDs), but `HetznerEnvironmentConfig.sshKeyIds` is read from `compute.getOrDefault("ssh_key_ids", "")` which is never populated by the bootstrap orchestrator.
- **Recommended fix path:**
  1. **Cloud-init route (preferred):** `UserDataTemplate` adds:
     ```yaml
     ssh_authorized_keys:
       - <operator-public-key-from-bootstrap-config>
     ```
     Operator's public key supplied via `[infrastructure.ssh] public_key_file = "~/.ssh/aether_test.pub"` in TOML, OR via `--ssh-key-file <path>` CLI flag, OR derived from `AETHER_SSH_KEY` env var (`.pub` sibling).
  2. **Hetzner native route:** Bootstrap uploads the operator's pub key to Hetzner via `POST /v1/ssh_keys`, captures the ID, and passes it to `CreateServerRequest.ssh_keys`. The provision-test-pg.sh tool already does exactly this — same code can be shared.
  3. **Both:** belt-and-suspenders. Operators on multi-cloud get the same key in cloud-init; cleanup also removes the SSH key registered in Hetzner.
- **Effort:** ~2-3 hours. Touches `UserDataTemplate` (templating), `BootstrapPhaseProvision` (or a new phase), `HetznerEnvironmentIntegrationFactory` (read ssh_key_ids properly).
- **Priority:** **CRITICAL.** Cannot debug Bug 8 without this.

#### Bug 11: Mgmt API not responding (HTTP 000 on port 8080)
- **Symptom:** `curl http://<cluster-vm>:8080/health/live` returns HTTP 000. `curl -k https://...` ditto.
- **Likely root cause:** aether-node container hasn't fully started OR is stuck in init OR isn't listening at all. Without SSH (Bug 10), can't determine which.
- **Diagnostic dependency:** blocked by Bug 10. Probably resolves itself once Bug 8 is fixed.

---

## 3 · The "production-grade bootstrap machinery" deliverable

For the next session to actually run the 15-suite cloud sweep, the bootstrap needs:

### 3a · Must have (P0)

1. **Bug 9 fix** — provider name correct in `CreatedResource.ProvisionedVm` so `BootstrapCleanup` works. **Without this, iteration is unsafe.**
2. **Bug 10 fix** — SSH key injection (cloud-init OR Hetzner ssh_keys). **Without this, can't diagnose failures.**
3. **Image strategy decision** — is `ghcr.io/pragmaticalabs/aether-node:1.0.0-rc1` pullable anonymously? If not:
   - Push a test-only image to a public registry, OR
   - Build the image inside cloud-init from `aether-node.jar` + a Dockerfile fetched via SCP, OR
   - Use a multi-stage cloud-init that installs JDK and runs the JAR directly (no Docker)
4. **Bug 8 root-cause** — once #1 and #2 are in, SSH into a node and find out why `aether-node` isn't establishing quorum.
5. **`--keep-on-failure` flag for `aether cluster bootstrap`** — symmetric with `cluster destroy --keep-resources`. Lets operator inspect a failed bootstrap before teardown. ~30 LoC.

### 3b · Should have (P1)

6. **Pre-flight quota check** — call Hetzner `GET /v1/servers` and refuse to bootstrap if `(current count + proposed) > expected_quota`. Saves a failed iteration. ~60 LoC.
7. **OS image as a TOML field** — `[source.X.core] os_image = "ubuntu-22.04"`. Don't hardcode in the factory. ~30 LoC parser + schema.
8. **IPv6 toggle as TOML field** — `[source.X] enable_ipv6 = false`. Don't hardcode in HetznerComputeProvider. ~50 LoC.
9. **AWS/GCP/Azure label propagation audit** — same pattern as Bug 7; verify other cloud providers correctly receive `spec.tags()`. Probably a 30-min audit + a couple of fixes.
10. **Strict semver parser fix** — accept SemVer 2.0 prerelease syntax. ~15 LoC + a test.

### 3c · Nice to have (P2 — for after Phase 1 smoke is green)

11. **Hetzner native firewalls** — the existing `[firewalls.X] allow_ingress = [...]` schema in TOML is parsed but `HetznerEnvironmentIntegrationFactory` doesn't create them. Production users need this for security.
12. **Private network + bastion model** — was deferred earlier as "Option B" / "Phase 4". Schema-level support exists in `NetworkingType` enum but bootstrap-side wiring is absent. Estimate: 1-2 days of careful work; touches `BootstrapPhaseProvision`, `NetworkingType` enum extension, new `BastionProvider` SPI, new `BootstrapPhaseNetwork`. Defer until production-grade demand.
13. **Floating IP attachment** — schema parsed but `FloatingIpProvider.attach()` only fires for sources with `loadBalancer == ELECTED`, which test config disables.

### 3d · Sequence proposal for the next session

```
DAY 1 morning:
  - Spawn jbct-coder #1: Bug 9 (provider name fix in CreatedResource.ProvisionedVm) + tests
  - Spawn jbct-coder #2 (parallel): Bug 10 cloud-init + Hetzner ssh_keys upload + UserDataTemplate
  - Spawn build-runner: verify all changes compile + JBCT lint clean
  - Commit both, push.

DAY 1 afternoon:
  - Re-bootstrap with --keep-on-failure (after that flag also lands; spawn jbct-coder #3 if needed).
  - Now SSH should work. Diagnose Bug 8: docker logs aether-node, /var/log/cloud-init-output.log, etc.
  - Fix Bug 8 root cause (likely image pullability OR PG_URL routing OR TLS).
  - Iterate until cluster forms.

DAY 2 (if bootstrap green):
  - Run Phase 1 smoke (00-smoke + 15-delegation, single cluster).
  - Tear down + provision Phase 2 non-destructive suites.
  - Continue staged plan from current task list (Phase 3, 4).
```

---

## 4 · Concrete files / commits / state references

### 4a · Hetzner cloud test config

| File | What's in it |
|---|---|
| `aether/tests/integration/env/cloud-hetzner.toml` | Cluster A (non-destructive) — 5×cx33, fsn1, ubuntu-22.04, version 1.0.0, networking=manual, load_balancer=none |
| `aether/tests/integration/env/cloud-hetzner-b.toml` | Cluster B (destructive) — same shape |
| `aether/docker/aether-node/aether.toml` | Embedded node config (used by aether-node container at runtime) — `[cloud.compute] expose_host_ports = "true"` set for test convenience |

### 4b · Tools

| File | Purpose |
|---|---|
| `tools/cloud-reaper.sh` | Hetzner cleanup-by-label safety net. `--cluster <name>` filters; `--destroy --force` for CI. |
| `tools/provision-test-pg.sh` | Idempotent PG VM provisioner. Writes `/tmp/aether-test-pg.env` (mode 0600). `--fresh` to redeploy, `--destroy` to clean. |

### 4c · Recent CLI build install

```
mvn -pl aether/cli install -DskipTests -am -q
cp aether/cli/target/aether.jar ~/.aether/lib/aether.jar
aether cluster bootstrap --help     # should show --cluster=<clusterNameOverride>
```

The CLI's shaded jar at `~/.aether/lib/aether.jar` was hand-installed during this session. Each rebuild needs the same `cp` (the wrapper script `~/.aether/bin/aether` runs it from there). Worth automating: add a `make install-cli` target or shell helper.

### 4d · Critical environment variables

| Var | Status | Notes |
|---|---|---|
| `HCLOUD_TOKEN` | Required, set | 64-char Hetzner Cloud API token |
| `PG_URL` | Required for cluster bootstrap | Source `/tmp/aether-test-pg.env` to populate |
| `AETHER_SSH_KEY` | Required, set to `~/.ssh/aether_test` | Operator SSH private key |
| `AETHER_SSH_USER` | Set to `aether` (cluster nodes) — but PG VM needs `root` | Future PG VM scripts override via `PG_VM_SSH_USER=root` |

---

## 5 · Delegation guidance — protect the main context

This session's mistake was loading multiple production source files into the main context to debug live. **Don't repeat that.** Specifically:

### 5a · Use jbct-coder for ALL Java fixes
Each of Bug 9, Bug 10, the `--keep-on-failure` flag, the OS image schema, the IPv6 schema, the cleanup audits — every one is a self-contained jbct-coder task. Brief like a cold colleague:
- "Fix X in file Y. Here's the diagnosis. Here's the recommended approach. Here's the test you need to add. Self-validate via mvn install + jbct:check + test. Report back commit-ready summary line. Do NOT commit."

Then commit each via chore-runner.

### 5b · Use aether-investigator for diagnosis
Bug 8 root-cause analysis after SSH access lands — dispatch aether-investigator with: "Read these logs: /var/log/cloud-init-output.log on VM IP X.Y.Z.W. SSH key path Z. Find why aether-node isn't reaching ACTIVE. Report ≤ 500 words." 

Don't load logs into main context. Let the investigator distill.

### 5c · Use build-runner for ALL maven invocations
Local `mvn -pl ... install` calls — fine for the orchestrator if narrow. But anything wider (full module tree, jbct:check across 4+ modules, test runs) — delegate to build-runner. The audit + label-fix + ProviderResolver + provision-test-pg work in this session ALL went via background agents. Do the same.

### 5d · Use chore-runner for commits + tag moves + push
Already the pattern. Continue.

### 5e · DO NOT spawn parallel jbct-coder agents on overlapping files
We hit this once: the destroy-cleanup agent and the --cluster-flag agent both touched `ClusterBootstrapCommand.java`. The label-fix agent's working state lagged behind ours when CLI was rebuilt. Sequence dependent fixes; parallelize independent ones.

### 5f · Cleanup discipline — run reaper between iterations
```
tools/cloud-reaper.sh                   # dry-run first
tools/cloud-reaper.sh --cluster test-a --destroy --force
tools/cloud-reaper.sh --cluster test-b --destroy --force
```
PG VM (label `test-pg`) is preserved by the cluster-specific filter. Don't run unfiltered `--destroy` unless you mean it.

### 5g · Avoid re-reading production files
If you find yourself running `Read` on `HetznerComputeProvider.java`, `BootstrapPhaseProvision.java`, `UserDataTemplate.java` more than once — STOP. Either delegate or write a short summary into the conversation and reference it.

---

## 6 · Open questions / decisions needed at session start

1. **PG VM disposition** — keep the existing one running across the wait, or destroy + re-provision? (Cost negligible; iteration speed says keep.)
2. **Image pullability** — is `ghcr.io/pragmaticalabs/aether-node:1.0.0-rc1` public? Verify with `docker pull ghcr.io/pragmaticalabs/aether-node:1.0.0-rc1` from a fresh VM. If not, decide on alternate strategy (private registry creds in cloud-init, public test image, JAR-based deployment, etc.).
3. **Single-cluster or dual-cluster on cloud?** — Docker had A non-destructive parallel + B destructive sequential. With Hetzner quota considerations (10 cluster VMs + PG = 11 servers; may exceed even raised quota), single-cluster sequential might be the cloud strategy. Affects `run-tests.sh` cloud branch.
4. **Bug 8 hypothesis ordering** — start with image pullability (cheapest to verify), then PG_URL routing, then TLS.

---

## 7 · Acceptance criteria for the next session's mission

**Mission complete when:**
- `aether cluster bootstrap aether/tests/integration/env/cloud-hetzner.toml --cluster test-a --yes --wait` returns success.
- `aether status --cluster test-a` shows 5 ON_DUTY nodes with a leader and `clusterPhase=NORMAL`.
- 00-smoke + 15-delegation suites pass against the cloud cluster.
- `aether cluster destroy --cluster test-a --yes` cleans up all VMs (no reaper needed).
- 0 leaked resources in `tools/cloud-reaper.sh` dry-run output post-teardown.

**Stretch goal:**
- Phase 2 non-destructive suites (04-streaming, 06-deployment, 07-cluster-mgmt, 08-resources, 09-artifacts, 10-database, 11-observability, 14-storage) all pass on cloud.

**Non-goal for next session:**
- Phase 3 (destructive) or Phase 4 (full sweep). Get the bootstrap solid first.
- 12-network fix. That's a separate redesign-side issue tracked elsewhere.

---

## 8 · Total spend so far this session: < €0.10

Hetzner billing for this session: ~€0.05 in cluster VM trial-and-error, plus PG VM running ~3 hours = ~€0.02. Negligible relative to the diagnostic value extracted.

---

## 9 · Final thought

The membership-architecture redesign was a 50+ commit, 10-phase, JBCT-reviewed-twice, integration-tested-on-remote effort. **It works.** The cloud bootstrap path is a separate beast — older code, less battle-tested, more provider-specific edge cases. It needs the same kind of methodical attention but it's much smaller in scope. Estimate 1-2 focused sessions to get it production-grade.

Start with Bugs 9 + 10. Get SSH access. Then everything else cascades.
