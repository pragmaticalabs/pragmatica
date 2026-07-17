<!-- SPDX-License-Identifier: BUSL-1.1 -->
<!-- Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko -->
<!-- Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0. -->

# RFC-0016 — Full Major-Cloud Support

| Field | Value |
|---|---|
| Status | Draft |
| Author | Sergiy Yevtushenko |
| Date | 2026-07-17 |
| Supersedes | — |
| Related | RFC-0012 (Resource Provisioning), RFC-0013 (Deployment Provider), RFC-0015 (Cluster-Label Scoping); issues #442, #459, #444, #439, #434 |

## Summary

The v1.0.0-rc3 headline is **full major-cloud support**: AWS becomes a
first-class, e2e-validated deployment target alongside Hetzner, and GCP/Azure
become code-complete with contract/smoke coverage. This RFC does **not** add new
provider code cell-by-cell. It fixes the *structural* reason cloud support is
uneven today: the spec→provision mapping is an ad-hoc, per-provider,
per-field hand-pluming surface, and provisioning state (ssh keys, firewalls,
credentials) is reconstructed by divergent paths that disagree. It designs one
spec→request mapping surface so a missing field can never be "fixed for one
provider only" again (the #442/#459 defect class), folds a provider-agnostic,
KV-reconstructible `SourceProfile` for provisioning state (#444 + #439), defines
a concrete per-tier validation bar, and abstracts the Hetzner-only cloud e2e
harness so AWS can be gated the same way.

The tactical Hetzner `image` fix (#459) lands **now, outside this RFC**; this RFC
owns the generalization that makes it the last field ever fixed one-off.

## Motivation

### What "cloud support" actually looks like in the code today

The SPI (`aether/environment-integration`) is clean: a `ProvisionSpec` carries a
typed intent (`instanceType`, `instanceSize`, `pool`, `context`, `imageId`,
`userData`, `placement` — `ProvisionSpec.java:13-19`), each provider translates
it into its own native create request, and an `EnvironmentIntegration` exposes
seven optional facets (compute / secrets / loadBalancer / discovery /
certificate / dns / floatingIp — `EnvironmentIntegration.java:21-39`).

The problem is not the SPI. It is that **each provider's `provision(ProvisionSpec)`
override cherry-picks a different subset of spec fields**, and the base default
drops everything:

```java
// ComputeProvider.java:35-37 — the default silently discards imageId/userData/placement
default Promise<InstanceInfo> provision(ProvisionSpec spec) {
    return provision(spec.instanceType());
}
```

The result is a matrix of what each provider *actually plumbs* that nobody can
see from one place — the exact condition under which #442 fixed `server_type`
for Hetzner only and #459 shows `image` was fixed for nobody. This RFC makes
that surface single and total.

### The #442/#459 defect class, stated precisely

`ProvisionSpec.imageId()` exists (`ProvisionSpec.java:17`, with `withImage()` at
`:33`) but is **read by no provider**. Verified: Hetzner
(`HetznerComputeProvider.provision(ProvisionSpec)` at `:66-72` reads
`instanceSize`, `userData`, `placement` — never `imageId`; image comes from
`config.image()` at `:308`), AWS (`AwsComputeProvider.java:63-73` reads
`placement`, `userData`, `context` — image is `config.amiId()` at `:191`,
instance type is `config.instanceType()` at `:192`), GCP
(`GcpComputeProvider.java:68-79` — image is `config.sourceImage()` at `:224`,
machine type is `config.machineType()` at `:150`), Azure
(`AzureComputeProvider.java:76-87` — image is `config.image()` at `:141`).

Worse, the field is dropped **upstream of the provider too**: the bootstrap
builds the spec in `BootstrapPhaseProvision.buildCloudProvisionSpec`
(`:429-458`) and calls `.withUserData(...)` and `applyZone(...)` but **never
`.withImage(...)`** — and the config model itself has no image field
(`RoleSubTable.java:12-16` is `role/count/hosts/instanceType/runtimeRef`;
`SourceProfile.java:14-30` has no image). So the operator-documented
`[source.<provider>.<role>] image` key is discarded at parse, long before any
provider could honor it.

This directly contradicts shipped docs: `vm-snapshot.md:84-85` and `:152-156`
claim the snapshot id is honored "through the same `image` field … No code
change is required" and "passed through to the provider's create-server API";
`feature-catalog.md` row 204 repeats "consumable via every cloud provider's
existing `image`/`amiId`/`sourceImage` field — no schema change." All three are
false for the source-level path. (They are true only for the *node-runtime*
`[cloud.compute] image`, a different key that reaches `config.image()` via
`HetznerEnvironmentIntegrationFactory.java:69-70`.)

### The provisioning-state reconstructibility gap (#444 + #439)

Bootstrap-context provisioning state lives only in the bootstrap CLI process and
is not reconstructible from KV by an arbitrary leader — violating the
architectural invariant that cluster state be reconstructible from the KV-Store.
`#442` shipped option 3B for rc2 (Hetzner re-derives ssh-key ids at provision
time by listing account keys and filtering the `aether-bootstrap` name prefix —
`HetznerComputeProvider.java:249-300`), which closes the loss mode with zero
persisted state but is provider-specific and leaves the *class* unfixed.
`firewall_ids` has the identical gap (rendered nowhere), and the ssh-key upload
name (`aether-bootstrap*`) matches **all** clusters on an account.

Cleanup makes it concrete and expensive. There are two credential-resolution
paths that can disagree (#439): provisioning reads `source.credentials()`
(TOML `${env:HCLOUD_TOKEN}`, interpolated at parse) via
`ProviderResolver.buildCloudConfig` (`:146-178`); cleanup has a
persisted-handle bridge (`resolveCloudComputeFromHandle` → `buildHandleConfig`,
`ProviderResolver.java:71-107`, reads `SourceCleanupHandle.credentialEnvVars`)
**and** a raw-env fallback (`resolveCloudComputeForCleanup` →
`CloudCredentials.fromEnvironment` → `System.getenv`, `:65-69`). At HEAD the VM
cleanup path prefers the handle (`BootstrapCleanup.java:129-139`), but **SSH-key
cleanup still reads raw `HCLOUD_TOKEN`** (`defaultHetznerClient`,
`BootstrapCleanup.java:105-117`) — so a token supplied as `HCLOUD_TOKEN_PROD`
provisions successfully then fails to reap its own SSH key. A token that
provisioned MUST be able to tear down everything it created.

## Owner rulings (FIXED 2026-07-17 — embedded, not re-opened)

1. **rc3 headline = full major-cloud support.**
2. **Validation tiers:**
   - **Tier-1 Hetzner** — full cloud gate (existing 15-suite), unchanged.
   - **Tier-2 AWS** — one full cloud e2e per release. Until owner AWS
     credentials arrive (expected mid-rc3) the operative bar is **LocalStack
     contract tests** for the provisioning surface; the live e2e gate activates
     when credentials land.
   - **Tier-3 GCP/Azure** — code-complete + contract/smoke tests.
3. **AWS is the first Tier-2 cloud.** LocalStack contract tests for the
   provisioning surface; the RFC flags which features are LocalStack free-tier vs
   Pro (§4.3).
4. **The tactical Hetzner `image` fix (#459) lands NOW, outside this RFC.** This
   RFC owns the structural generalization.
5. **The persisted-cluster-TOML / `SourceProfile` stored-format change is an
   OWNER SIGN-OFF item** (hard-stop class; relates #434). See §3.5 and Open
   Questions.

## Design

### 1. Capability matrix (code-verified)

Status per cell against the `aether/environment-integration` SPI. Every cell
carries file-level evidence; no cell is asserted from docs. Legend: **C** =
Complete (real implementation wired), **P** = Partial (works, limited), **S** =
Stub (facet present, non-functional), **—** = legitimately absent for that
provider.

| Capability | Hetzner | AWS | GCP | Azure | Docker |
|---|---|---|---|---|---|
| ComputeProvider (CRUD) | C | C | C | C | C |
| — spec→request image plumb | **S** | **S** | **S** | **S** | — |
| — spec→request instance-type plumb | **C** | **S** | **S** | **S** | — |
| SecretsProvider | P (env) | C (Secrets Mgr) | C (Secret Mgr) | C (Key Vault) | — |
| LoadBalancerProvider | C | C | C | C | — |
| DiscoveryProvider | C | C | C | C | — |
| FloatingIpProvider | C | — | — | — | S (Noop) |
| Spot provisioning | **S** | **S** | **S** | **S** | — |

Cell evidence (paths under `aether/environment/`):

- **Compute CRUD = C for all five**: `hetzner/…/HetznerComputeProvider.java`,
  `aws/…/AwsComputeProvider.java`, `gcp/…/GcpComputeProvider.java`,
  `azure/…/AzureComputeProvider.java`, `docker/…/DockerComputeProvider.java` —
  each implements `provision`/`terminate`/`listInstances`/`instanceStatus`
  (+`restart`/`applyTags`) against a real client.
- **image plumb = S everywhere**: no `provision(ProvisionSpec)` reads
  `spec.imageId()` (see Motivation for the four `:66/:63/:68/:76` sites); Docker
  is single-image so `—`.
- **instance-type plumb**: Hetzner **C** (`resolveServerType(spec.instanceSize)`,
  `HetznerComputeProvider.java:82-89, 231-241`); AWS/GCP/Azure **S** (they read
  `config.instanceType()` / `config.machineType()` / `config.vmSize()` and ignore
  `spec.instanceSize()`).
- **Secrets**: AWS `AwsSecretsProvider.java:23`, GCP `GcpSecretsProvider.java:19`,
  Azure `AzureSecretsProvider.java:22` all implement `resolveSecret` against the
  managed secret store, wrapped in `CachingSecretsProvider`
  (`AwsEnvironmentIntegration.java:77-80`, etc.). Hetzner is **P**: env-only
  `EnvSecretsProvider` (`HetznerEnvironmentIntegration.java:76-79`) — no managed
  secret store. Docker returns `empty()` (`DockerEnvironmentIntegration.java:38-40`).
- **LoadBalancer = C for four clouds**: real client calls, no
  `operationNotSupported` in method bodies — verified for AWS
  (`AwsLoadBalancerProvider.java:38-87`, register/deregister/reconcile-diff
  against `AwsClient`), Hetzner/GCP/Azure implement the same SPI trio. Each is
  wired only when its LB config is present (`AwsEnvironmentIntegration.java:56-61`,
  etc.). Docker `empty()`.
- **Discovery = C for four clouds**: `discoverPeers` polls native tags/labels
  (`AwsDiscoveryProvider.java:59-63`, GCP/Azure/Hetzner analogous);
  `registerSelf`/`deregisterSelf` are `operationNotSupported` **by design**
  (tag/label-based discovery needs no registration —
  `AwsDiscoveryProvider.java:82-89`). Docker `empty()`.
- **FloatingIp**: Hetzner **C** (`HetznerFloatingIpProvider`, wired at
  `HetznerEnvironmentIntegration.java:106-108`); AWS/GCP/Azure return `empty()`
  (`AwsEnvironmentIntegration.java:107-110`, etc.) → `—`; Docker wires
  `NoopFloatingIpProvider` (`DockerEnvironmentIntegration.java:52-55`) → **S**.
- **Spot = S for all**: every `provisionSpot` returns `operationNotSupported`
  ("not implemented in v1") — `AwsCloudProvider.java:37-38`,
  `GcpCloudProvider.java:37-38`, `AzureCloudProvider.java:37-38`,
  `HetznerCloudProvider.java:37`, `DockerCloudProvider.java:37`.

**Headline counts** (30-cell grid, excluding the two plumbing sub-rows):
**17 Complete, 1 Partial, 6 Stub, 6 legitimately-absent.**

**Surprises / discrepancies vs docs & catalog:**

1. **The Tier-2 reference cloud (AWS) has *lower* provisioning fidelity than
   Tier-1 Hetzner.** Hetzner is the only provider that plumbs `instanceSize`;
   AWS and GCP drop both `instanceSize` and `imageId`. "AWS-first" starts from
   behind on the exact axis this RFC generalizes.
2. **`spec.imageId()` is dead code across the whole fleet**, and the config
   model has no `image` field — so the operator-facing `[source…] image` is a
   silent no-op, directly contradicting `vm-snapshot.md` and catalog row 204.
3. **Spot is 100% stub** while `feature-catalog.md` row 4 markets auto-heal as
   "spot-first … provisioning" (Battle-tested). The *scheduler* can prefer
   spot-labelled capacity, but no provider can request a spot/preemptible
   instance. The claim should be scoped to "spot-preferring placement" until
   provisioning exists.
4. **The cleanup credential bridge is half-wired**: VMs use the persisted handle,
   SSH-key reaping still reads raw `HCLOUD_TOKEN` (#439).
5. **Docker floating-IP is a Noop stub, not absent** — harmless, but it reports a
   present facet that does nothing.

### 2. Spec→provision plumbing parity — eliminate the class

**Root cause.** There is no single place that maps a source/role spec into a
provider create-request. The mapping is smeared across (a) the config parser
(which fields even reach `SourceProfile`/`RoleSubTable`), (b)
`BootstrapPhaseProvision.buildCloudProvisionSpec` (which `with*` calls it makes),
(c) `ProviderResolver.buildCloudConfig` (which `[cloud.compute]` keys it sets),
and (d) each provider's `provision(ProvisionSpec)` (which spec fields it reads).
A field is "plumbed" only if all four agree; `server_type` accidentally does for
Hetzner, `image` does for nobody.

**Design: one total mapping surface.** Introduce a single provider-agnostic
`ProvisionRequest` shape and make every provider consume it exhaustively, so the
compiler forces every provider to say what it does with every field.

- **2.1 — Complete the config model.** Add `image` (source- and role-level, role
  overrides source) to the spec model so the documented key parses. Role-level
  fields become a small typed record (`RoleProvisioning`: `instanceType`,
  `image`, `count`, `hosts`) rather than the current flat `RoleSubTable`. This is
  a **persisted/parsed-format change** → see §3.5 + Open Questions.
- **2.2 — Populate the whole `ProvisionSpec`.** `buildCloudProvisionSpec` calls
  `.withImage(role.image().or(source.image()))` whenever present, alongside the
  existing `.withUserData`/placement. The bootstrap-time spec becomes total: any
  field the operator can set is on the spec.
- **2.3 — Make providers consume it exhaustively.** Replace the free-form
  `provision(ProvisionSpec)` overrides with a shared, non-overridable resolution
  step in the SPI: a `ProvisionRequest resolve(ProvisionSpec, ProviderDefaults)`
  helper (default method on `ComputeProvider`) that computes the *effective*
  image/instance-type/zone/user-data/tags with a documented precedence
  (`spec` field → provider config default → **loud** fallback, never a silent
  hardcoded default). Each provider implements only
  `createFrom(ProvisionRequest)` and can no longer forget a field, because the
  request record has no optional-drop path. `imageId` unset resolves to the
  provider default *with an INFO line naming the fallback* (mirrors #442's
  `logCreateRequest` at `HetznerComputeProvider.java:110-116` and demotes the
  hardcoded `DEFAULT_IMAGE` at `HetznerEnvironmentIntegrationFactory.java:38`).
- **2.4 — CTM parity.** Auto-heal replacements must inherit the same effective
  image/instance-type as their seeds. Today `instance_type` rides the
  `config.serverType()` overlay (`ProviderResolver.buildCloudConfig:162`); `image`
  gets the identical overlay so a replacement is byte-identical to a seed. This
  is the CTM half of #459 ("replacements inherit via node overlay like
  ssh_key_ids").

**Acceptance for the class being eliminated:** a new provider added after this
RFC cannot compile without deciding what it does with image, instance-type,
zone, user-data, and tags — there is no `provision(spec.instanceType())`
fall-through left to inherit.

### 3. Provider-agnostic SourceProfile (folds #444 + #439)

One coherent "provisioning-state reconstructibility" pass, per the architectural
invariant that cluster state be reconstructible from KV.

- **3.1 — Persist a provider-agnostic `SourceProfile` into distributed cluster
  config.** Covers ssh-key references, firewall references, and labels policy —
  everything a leader needs to provision a replacement without the bootstrap CLI
  process. Reconstructible from KV by any leader; supersedes the Hetzner-only 3B
  re-derivation as the *general* mechanism (3B stays as the Hetzner fallback for
  clusters bootstrapped before this RFC).
- **3.2 — Prefer stable NAMES/selectors over provider-numeric ids.** Numeric ids
  go stale when account resources are recreated (the reason 3B won for rc2); the
  persisted profile stores selector *names* (`aether-bootstrap-<cluster>` key
  prefix, firewall names, label selectors) and resolves them to live ids at
  provision time. This keeps the provider re-derivation self-healing while making
  it KV-driven rather than prefix-guessing.
- **3.3 — Cluster-scoped ssh-key naming with back-compat lookup.** Tighten the
  upload name from `aether-bootstrap*` (matches every cluster on the account) to
  `aether-bootstrap-<cluster>*`. The 3B lookup
  (`HetznerComputeProvider.BOOTSTRAP_KEY_NAME_PREFIX`, `:225`, `isBootstrapKey`
  at `:270-273`) must accept **both** the old bare prefix and the new
  cluster-scoped name so existing clusters keep healing. `firewall_ids` has the
  identical distribution gap and no firewall is attached at bootstrap today —
  fix the class in the profile **before** bootstrap grows a firewall so we never
  ship the stale-state version.
- **3.4 — Cleanup reuses the provisioning credential path (#439).** All cleanup
  credential resolution routes through the persisted profile's
  `credentialEnvVars` (the `SourceCleanupHandle` bridge,
  `ProviderResolver.java:71-107`). Concretely: (a) `BootstrapCleanup` SSH-key
  reaping (`defaultHetznerClient:105-117`) stops reading raw `HCLOUD_TOKEN` and
  resolves via the handle like VM reaping already does (`:129-139`); (b) the
  raw-env fallback `resolveCloudComputeForCleanup` (`:65-69`) is demoted to a
  last resort only when no handle exists (pre-RFC clusters). **A token that
  provisioned MUST be able to tear down.**
- **3.5 — Stored-format versioning (relates #434).** The persisted
  `SourceProfile` gets an explicit `formatVersion` field and a forward-only
  migration step, so a leader reading an older persisted cluster-TOML upgrades it
  deterministically (mirrors the internally-versioned schema-history pattern,
  catalog row 128). Per RFC-0015's precedent, wire messages are INTERNAL and
  rebuild-together; **persisted** formats are not — this is the hard-stop,
  owner-sign-off surface (see Open Questions Q1).

**Test the money path explicitly (#439).** The regression suite must drive the
**timeout-triggered** cleanup path (the one that leaked 5 orphan VMs on
2026-07-11) with a credential env var whose name differs from the provider
default (`HCLOUD_TOKEN_PROD`), asserting VMs *and* SSH keys are all reaped.

### 4. Per-tier validation bar (concrete gates)

#### 4.1 Tier-1 — Hetzner (unchanged, full gate)

- **Suites:** existing 15-suite cloud set (`aether/tests/cloud/run-cloud-tests.sh`
  `ALL_SUITES`, minus `01-stability` soak and `07-cluster-mgmt` by default).
- **Environment:** real Hetzner project via `deploy-cloud.sh` (5 core + Aether LB
  VM + Postgres VM + managed LB), container and JVM runtime TOMLs.
- **Cost profile:** ~$0.07/hr, `MAX_CLOUD_HOURS` guard (default 6h),
  `teardown-cloud.sh` reaps by `aether-cluster=cloud-test` label + `aether-core-*`
  name sweep.
- **Pass criteria:** all non-skipped suites green; residual failures must be
  triaged HARNESS vs PRODUCT and only harness-side debt (e.g. #459/#460) may
  remain open.

#### 4.2 Tier-2 — AWS (LocalStack now → live e2e when creds land)

- **Interim bar (no owner AWS creds):** **LocalStack contract tests** for the
  provisioning surface (EC2 run/describe/terminate/tags, ELBv2
  register/deregister/describe-target-health, Secrets Manager
  get/create-secret, Route53 upsert). Runs in CI on every rc3 commit touching
  `aether/environment/aws` or the shared SPI. Gate = contract suite green.
- **Live bar (creds landed, expected mid-rc3):** exactly **one full cloud e2e
  per release** — the Hetzner 15-suite set retargeted to AWS via the abstracted
  harness (§5), one 5-node cluster, torn down within the run. Gate = smoke +
  chaos + scaling + one streaming-failover pass green.
- **Cost profile:** LocalStack = $0. Live AWS ≈ 5× t3.small + 1 ALB + RDS/PG-on-EC2
  for a bounded window; same `MAX_CLOUD_HOURS` guard, label-scoped reaper.

#### 4.3 LocalStack free-tier vs Pro (flagged per owner ruling)

| Provisioning surface | LocalStack Community (free) | Requires Pro |
|---|---|---|
| EC2 run/describe/terminate/tags | ✅ | — |
| Security groups, subnets, key pairs | ✅ | — |
| ELBv2 target group register/deregister/health | ✅ | — |
| Secrets Manager get/create | ✅ | — |
| Route53 record upsert | ✅ | — |
| EC2 **spot** requests | — | ⚠️ Pro (moot — spot is Stub §1) |
| ACM certificate issuance | — | ⚠️ Pro (cert adapter is contract-mockable) |
| IAM fine-grained policy eval | partial | ⚠️ Pro |

The entire rc3 AWS provisioning surface (compute + LB + secrets + discovery via
EC2 tags) is **Community-tier**. Only spot (already Stub) and full ACM issuance
would need Pro; both are out of the rc3 gate. **No LocalStack Pro dependency for
the rc3 AWS bar.**

#### 4.4 Tier-3 — GCP / Azure (code-complete + contract/smoke)

- **Bar:** existing unit + provider contract tests (`GcpComputeProviderTest`,
  `AzureComputeProviderTest`, and siblings — already present) plus a
  build-time smoke that the factory wires all facets. No live cloud gate in rc3.
- **Pass criteria:** compiles, facets wired, contract suites green, capability
  matrix cells hold at their §1 status.

### 5. Cloud e2e harness abstraction

**Today the harness is Hetzner-only.** `deploy-cloud.sh` hardcodes `hcloud` for
ssh-key/network/server/LB creation, literals `LOCATION="fsn1"`,
`SERVER_TYPE="cx22"`, `IMAGE="ubuntu-24.04"` (`:19-21`), `python3` parsing of
`hcloud … -o json` for IPs, and directly provisions the PG VM and LB VM via
`hcloud server create` (`:164-172, 225-233`). `teardown-cloud.sh` reaps via
`hcloud … --selector aether-cluster=cloud-test` + `aether-core-*` name sweep.
`run-cloud-tests.sh` is provider-neutral already (it drives
`run-tests.sh --env remote --skip-deploy --skip-teardown`).

**Design: a thin provider driver interface behind the existing scripts.** Extract
the ~8 provider-specific operations `deploy-cloud.sh`/`teardown-cloud.sh` need
into a `cloud-driver` contract (shell functions, selected by
`AETHER_CLOUD_PROVIDER`):

| Driver op | Hetzner impl (exists) | AWS impl (new) |
|---|---|---|
| `driver_create_sshkey` | `hcloud ssh-key create` | `aws ec2 import-key-pair` |
| `driver_create_network` | `hcloud network create/add-subnet` | VPC + subnet (or default VPC) |
| `driver_provider_toml` | `aether-cloud.toml` (hetzner source) | `aether-cloud-aws.toml` (aws source) |
| `driver_create_support_vm` | `hcloud server create` (PG, LB) | `aws ec2 run-instances` |
| `driver_node_public_ip` | `hcloud server describe -o json` | `aws ec2 describe-instances` |
| `driver_managed_lb` | `hcloud load-balancer …` | ELBv2 target group + listener |
| `driver_reap_by_label` | `hcloud … --selector aether-cluster=` | `aws ec2 …--filter tag:aether-cluster` |
| `driver_cost_estimate` | `$0.071/hr` literal | per-instance-type table |

The label scheme is already provider-portable (RFC-0015: `aether-cluster` /
`aether-node-id` on every provider), so the reaper generalizes cleanly. The PG
and support-VM provisioning is the largest new surface; for LocalStack runs it is
stubbed (PG-on-container in-network). Serialized cluster-A/B policy and the
`MAX_CLOUD_HOURS` guard are provider-neutral and stay in the outer script.

**Scope for rc3:** implement the Hetzner driver by extracting current code
(behavior-preserving), plus the AWS driver to the depth the Tier-2 live bar needs
(§4.2). GCP/Azure drivers are out of rc3 harness scope (Tier-3 is contract/smoke,
no live e2e).

## Work breakdown (risk-first)

Ordered by interaction-risk × blast-radius × observability-gap (project
methodology: foundation before features, observability before hardening).
Sizes are rough (S ≤ 1d, M ≈ 2–3d, L ≈ 1w).

| # | Item | Size | Issue | Why here |
|---|---|---|---|---|
| W1 | **Generalized spec→request mapping surface** (§2.3): non-overridable resolve + `ProvisionRequest`; providers implement `createFrom` only | **L** | #459 (structural half) | Highest blast-radius: every provision path (bootstrap seed + CTM heal) funnels through it; eliminates the class. Do first — features build on it. |
| W2 | **Complete config model** (§2.1): `image` + typed `RoleProvisioning`; `buildCloudProvisionSpec` populates image; CTM overlay parity (§2.4) | **M** | #459 | Unblocks the documented `[source…] image`; small but touches persisted parse format (gate with W6). |
| W3 | **Provider-agnostic persisted `SourceProfile`** (§3.1–3.3): ssh-key/firewall selectors, cluster-scoped naming + back-compat lookup | **L** | #444 | KV-reconstructibility invariant; must precede any bootstrap firewall growth. Interaction-heavy (bootstrap + CTM + reaper). |
| W4 | **Cleanup credential unification** (§3.4) + **timeout-path regression test** with non-default env-var name | **M** | #439 | Money-leak path. Small code, but the test is the observability surface that proves it — build the test first, then unify. |
| W5 | **LocalStack AWS contract suite** (§4.2/4.3) for compute/LB/secrets/discovery | **M** | new (harness) | Becomes the AWS regression sensor before live creds; cheap, unblocks W1/W2 verification for AWS without a bill. |
| W6 | **Stored-format versioning for `SourceProfile`** (§3.5) — `formatVersion` + forward migration | **S** | #434-related | Gates W2/W3 persisted changes; **owner sign-off** (Q1). |
| W7 | **Cloud e2e harness driver abstraction** (§5): extract Hetzner driver (behavior-preserving) + AWS driver | **L** | new (harness) | Enables the Tier-2 live gate; sequenced after W5 so contract-level AWS is already green. |
| W8 | **Live AWS e2e gate activation** (§4.2 live bar) when creds land | **M** | new | Final gate; depends on W1–W3 + W7. Expected mid-rc3. |
| W9 | **Doc/catalog reconciliation**: fix `vm-snapshot.md` + catalog rows 4/204 to match code; scope "spot-first" → "spot-preferring placement" | **S** | — | Additive, independent, batch freely. |

Validation gate between foundational changes: W1–W4 each land behind a fast
in-JVM proof (provider unit/contract + the #439 timeout test) before the
LocalStack suite (W5), with the live AWS sweep (W8) as the **final** gate, never
the primary debug surface.

## Alternatives considered

- **A1. Patch `image` per provider like #442 patched `server_type`.** Rejected:
  that *is* the defect class. It leaves the next field (firewall, spot, placement
  detail) to be rediscovered as a field-specific outage. W1 pays the structural
  cost once.
- **A2. Keep provider-specific provisioning-state re-derivation (3B) as the
  general mechanism.** Rejected for the class: it is Hetzner-only, prefix-guesses
  across all clusters on an account, and cannot carry firewall refs or labels
  policy. Retained only as the pre-RFC Hetzner fallback (§3.1).
- **A3. Live AWS as the primary AWS gate from day one.** Rejected per owner
  ruling and cost/latency: LocalStack contract tests are the interim sensor; live
  e2e is one bounded run per release once creds exist.
- **A4. Add spot provisioning in rc3.** Rejected: out of headline scope, 100%
  stub today, and LocalStack spot needs Pro. Tracked separately; the matrix and
  catalog wording are corrected instead (W9).
- **A5. Cluster-prefixed ssh-key naming without back-compat.** Rejected: breaks
  healing for clusters bootstrapped before rc3. §3.3 dual-accepts old + new names.

## Migration

- **Config/parse format (W2/W3):** additive fields (`image`, typed role table,
  persisted `SourceProfile`) behind `formatVersion` (W6). Older persisted
  cluster-TOMLs upgrade forward on first leader read; no operator action for
  existing clusters (ssh-key lookup dual-accepts old names).
- **Providers:** the `provision(ProvisionSpec)` override signature changes to
  `createFrom(ProvisionRequest)`; all five in-tree providers migrate in W1. This
  is an SPI change within a BSL module — rebuild-together, no external contract.
- **Docs:** W9 corrects the three false "no code change / passed through" claims.

## Open questions for owner

1. **[HARD-STOP / SIGN-OFF] Persisted `SourceProfile` / cluster-TOML stored-format
   change (§3.5, #434).** Approve adding `formatVersion` + forward-only migration
   to the persisted cluster config? This is the owner-sign-off gate; W2/W3/W6 are
   blocked on it. Confirm the versioning approach (meta-version + ordered steps,
   mirroring schema-history) is acceptable.
2. **Spot in v1 wording.** OK to re-label catalog row 4 auto-heal from
   "spot-first … provisioning" to "spot-preferring placement (provisioning
   deferred)", given all `provisionSpot` are `operationNotSupported`? Or should
   spot provisioning enter rc3 scope (adds LocalStack Pro dependency + real spot
   e2e)?
3. **AWS support-VM strategy for the live e2e (§5).** For the AWS PG/support VM,
   prefer managed RDS (bill + slower teardown) or PG-on-EC2 (matches the Hetzner
   PG-VM shape)? Affects the `driver_create_support_vm` impl and cost profile.
4. **AWS default networking.** Use the account's default VPC/subnet for the live
   e2e (simplest) or create+reap a dedicated VPC per run (cleaner isolation,
   more teardown surface)? Impacts `driver_create_network`.
5. **Hetzner secrets (§1, Partial).** Leave Hetzner secrets as env-only, or is a
   managed-secret story (e.g. file/KV-backed) in scope? Not required for the
   headline; flagged because it is the one non-cloud-native Partial cell.
6. **CTM `image` overlay for JVM runtime.** The snapshot doc distinguishes
   container vs JVM snapshots; should the persisted image overlay carry the
   runtime kind so a JVM-runtime replacement inherits the JVM snapshot, not the
   container one? (Edge of §2.4.)

## References

### Internal — SPI & providers
- `aether/environment-integration/…/ComputeProvider.java` (`:35-37` default drop),
  `ProvisionSpec.java` (`:17,33` imageId/withImage), `ProvisionContext.java`,
  `EnvironmentIntegration.java` (`:21-39` facets), `CloudCredentials.java`
  (`:23-36` env resolution)
- `aether/environment/{hetzner,aws,gcp,azure,docker}/…/*ComputeProvider.java`,
  `*EnvironmentIntegration.java`, `*CloudProvider.java` (spot stubs),
  `*LoadBalancerProvider.java`, `*DiscoveryProvider.java`, `*SecretsProvider.java`

### Internal — plumbing & lifecycle
- `aether/cli/…/cluster/BootstrapPhaseProvision.java` (`:429-458`
  `buildCloudProvisionSpec` — no `withImage`), `ProviderResolver.java`
  (`:65-107` cleanup paths, `:146-178` `buildCloudConfig`),
  `BootstrapCleanup.java` (`:105-139` credential dual-path),
  `SourceCleanupHandle.java`
- `aether/aether-config/…/cluster/SourceProfile.java`, `RoleSubTable.java`
  (`:12-16` — no image field)

### Internal — harness & docs
- `aether/tests/cloud/{deploy-cloud.sh,run-cloud-tests.sh,teardown-cloud.sh}`
- `aether/docs/operator/vm-snapshot.md` (`:84-85,152-156` — false "no code change"
  claim), `aether/docs/reference/feature-catalog.md` (rows 4, 178, 187, 200-204)

### Related RFCs
- RFC-0012 (Resource Provisioning), RFC-0013 (Deployment Provider),
  RFC-0015 (Cluster-Label Scoping — provider-portable label scheme this RFC's
  reaper reuses)
