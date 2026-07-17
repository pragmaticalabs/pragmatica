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
| Related | RFC-0012 (Resource Provisioning), RFC-0013 (Deployment Provider), RFC-0015 (Cluster-Label Scoping); epic #463; issues #442, #459, #444, #439, #434 |

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

### Spot: a config surface that silently lies (code-verified)

The spot capability is not merely a stub at the provider — it is **configurable
end-to-end and silently downgrades to on-demand**. Verified:

- `NodeRole.SPOT` parses and is validated: `ClusterBootstrapConfigValidator`
  rule **PF-16** (`:266`) rejects a spot sub-table on providers without spot
  support (only Hetzner is named today), rule **PF-14** (`:284`) requires a
  non-spot sub-table when an elected LB is present. The CLI `scale` path accepts
  `role=spot`.
- But **both** spec-build paths hardcode on-demand:
  `CloudProviderSupport.buildProvisionSpec` (`:80-84`,
  `ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, …)`) and
  `BootstrapPhaseProvision.buildCloudProvisionSpec` (`:453`, same literal).
- And `ComputeProvider`/`CloudProvider.provisionSpot` (`CloudProvider.java:16`)
  has **zero production callers** — only the five provider impls (each returning
  `operationNotSupported`) and one Hetzner unit test invoke it.

Net: a configured `spot` role that passes validation is provisioned as an
on-demand instance, with no operator-visible signal. This RFC makes that
condition impossible (§2 spot slice) and corrects the marketing (feature-catalog
row 4 calls auto-heal "spot-first … Battle-tested").

### The provisioning-state reconstructibility gap (#444 + #439)

Bootstrap-context provisioning state lives only in the bootstrap CLI process and
is not reconstructible from KV by an arbitrary leader — violating the
architectural invariant that cluster state be reconstructible from the KV-Store.
`#442` shipped option 3B for rc2 (Hetzner re-derives ssh-key ids at provision
time by listing account keys and filtering the `aether-bootstrap` name prefix —
`HetznerComputeProvider.java:249-300`), which closed the loss mode with zero
persisted state but is provider-specific and account-wide-greedy, and leaves the
*class* unfixed. `firewall_ids` has the identical gap (rendered nowhere), and the
ssh-key upload name (`aether-bootstrap*`) matches **all** clusters on an account.

Cleanup makes it concrete and expensive. Two credential-resolution paths can
disagree (#439): provisioning reads `source.credentials()`
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

## Owner rulings — scope (FIXED 2026-07-17 — embedded, not re-opened)

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
5. **The persisted-cluster-TOML stored-format change is an OWNER SIGN-OFF item
   (now ruled — see the Owner-rulings-on-open-questions section, Q1).**

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
  secret store; this is a deliberate, documented posture (Q5): external secret
  managers integrate via env, no managed adapter until demand. Docker returns
  `empty()` (`DockerEnvironmentIntegration.java:38-40`).
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
  `HetznerCloudProvider.java:37`, `DockerCloudProvider.java:37` — and has no
  production caller (§Motivation "Spot silently lies").

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
3. **Spot doesn't just stub — it silently downgrades.** A validated `spot` role
   provisions on-demand (both spec-build paths hardcode `ON_DEMAND`;
   `provisionSpot` has zero callers) while `feature-catalog.md` row 4 markets
   auto-heal as "spot-first … Battle-tested". §2 (spot slice) makes the silent
   downgrade impossible; W9 corrects the wording.
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
Hetzner, `image` does for nobody, and `InstanceType.SPOT` for no one at all.

**Design: one total mapping surface.** Introduce a single provider-agnostic
`ProvisionRequest` shape and make every provider consume it exhaustively, so the
compiler forces every provider to say what it does with every field.

- **2.1 — Complete the config model.** Add `image` (source- and role-level, role
  overrides source) to the spec model so the documented key parses. Role-level
  fields become a small typed record (`RoleProvisioning`: `instanceType`,
  `image`, `count`, `hosts`) rather than the current flat `RoleSubTable`. This is
  a **persisted/parsed-format change** → gated by the document-level
  `formatVersion` (§3.5, Q1).
- **2.2 — Populate the whole `ProvisionSpec`.** `buildCloudProvisionSpec` calls
  `.withImage(role.image().or(source.image()))` whenever present, alongside the
  existing `.withUserData`/placement, and maps `role == SPOT` →
  `InstanceType.SPOT` (replacing the hardcoded `ON_DEMAND` at
  `CloudProviderSupport.java:81` and `BootstrapPhaseProvision.java:453`). The
  bootstrap-time spec becomes total: any field the operator can set is on the
  spec.
- **2.3 — Make providers consume it exhaustively.** Replace the free-form
  `provision(ProvisionSpec)` overrides with a shared, non-overridable resolution
  step in the SPI: a `ProvisionRequest resolve(ProvisionSpec, ProviderDefaults)`
  helper (default method on `ComputeProvider`) that computes the *effective*
  image/instance-type/zone/user-data/tags/market-options with a documented
  precedence (`spec` field → provider config default → **loud** fallback, never a
  silent hardcoded default). Each provider implements only
  `createFrom(ProvisionRequest)` and can no longer forget a field, because the
  request record has no optional-drop path. `imageId` unset resolves to the
  provider default *with an INFO line naming the fallback* (mirrors #442's
  `logCreateRequest` at `HetznerComputeProvider.java:110-116` and demotes the
  hardcoded `DEFAULT_IMAGE` at `HetznerEnvironmentIntegrationFactory.java:38`).
- **2.4 — CTM parity + per-role image resolution (Q6).** Auto-heal replacements
  must inherit the effective image/instance-type of *their own role*. There is
  **no runtime-kind tag** on the snapshot id (unverifiable metadata on an opaque
  id); instead a node's overlay renders **its own role's** image with **no
  implicit cross-role fallback**. Today `instance_type` rides the
  `config.serverType()` overlay derived from the CORE role only
  (`ProviderResolver.coreInstanceType`, `:190-192`); the generalized overlay is
  per-role. **Interim tactical behavior (until W2 lands), documented honestly:**
  the #459 tactical fix threads the *core* role's image into the config overlay,
  so the core image stamps all roles and a `worker`/`spot` role's image parses
  but is ignored. W2 replaces this with per-role resolution.
- **2.5 — Spot mechanism slice (Q2).** Two rc3 deliverables: (i) *standalone* —
  extend the PF-16 validator pattern so a spot sub-table on **any** provider
  without an implemented spot arm fails validation **loudly** (closes the silent
  on-demand downgrade for every provider at once); (ii) *riding W1* — the
  `role == SPOT → InstanceType.SPOT` mapping plus an **AWS spot arm**
  (`market-options` field on `ProvisionRequest`, spot-capacity error mapping).
  GCP/Azure spot arms only if they trivially ride the Tier-3 pass; otherwise they
  keep the loud rejection from (i). **Reclamation is handled honestly as abrupt
  node failure via auto-heal — there is NO preemption-notice drain** in rc3. The
  policy layer (CTM spot preference, preemption-notice drain, capacity fallback)
  is a **post-GA demand-gated ticket** *[reference placeholder: post-GA spot
  policy ticket]*.
- **2.6 — Mixed-architecture, multi-source clusters (Q6 extension).** A single
  cluster with heterogeneous sources — e.g. an x86 JVM source, an ARM container
  source, and an ARM JVM source — is expressible today via the
  **`(instance_type, runtime, image)` triple per source×role**. Example:

  ```toml
  [source.x86-jvm.core]
  instance_type = "cx22"          # x86
  image = "aether-1.0.0-jvm-amd64"

  [source.arm-container.worker]
  instance_type = "cax21"         # ARM
  image = "aether-1.0.0-container-arm64"

  [source.arm-jvm.spot]
  instance_type = "cax21"         # ARM
  image = "aether-1.0.0-jvm-arm64"
  ```

  Confirmed expressible; the gaps are: (a) a snapshot-matrix doc with the naming
  convention `aether-<version>-<runtime>-<arch>` (rides W9); (b) a container
  multi-arch manifest check *[new rc3 ticket — being filed]*; (c) one Hetzner
  `cx`/`cax` mixed validation run; (d) provider-side arch-mismatch errors are
  **loud at create** (no preflight check needed — a wrong-arch image fails the
  create call visibly).

**Acceptance for the class being eliminated:** a new provider added after this
RFC cannot compile without deciding what it does with image, instance-type
(incl. spot market-options), zone, user-data, and tags — there is no
`provision(spec.instanceType())` fall-through left to inherit, and no
`ON_DEMAND` literal on the spec-build path.

### 3. Provider-agnostic SourceProfile (folds #444 + #439)

One coherent "provisioning-state reconstructibility" pass, per the architectural
invariant that cluster state be reconstructible from KV.

- **3.1 — Persist a provider-agnostic `SourceProfile` into distributed cluster
  config, atomically at bootstrap.** Covers ssh-key references, firewall
  references, and labels policy — everything a leader needs to provision a
  replacement without the bootstrap CLI process. **Bootstrap persists the profile
  atomically at cluster creation, so profile-absence is unreachable by
  construction.** A leader that finds no profile for a source **fails loudly,
  naming the missing profile** — it never falls back to account-wide prefix
  guessing. The Hetzner-only 3B prefix-listing fallback
  (`HetznerComputeProvider.java:249-300`) is **deleted**: with the profile always
  present, its only role (reconstruct-from-nothing) no longer exists, and its
  account-wide greediness is a liability.
- **3.2 — Prefer stable NAMES/selectors over provider-numeric ids.** Numeric ids
  go stale when account resources are recreated (the reason 3B won for rc2); the
  persisted profile stores selector *names* (cluster-scoped ssh-key name prefix,
  firewall names, label selectors) and resolves them to live ids at provision
  time.
- **3.3 — Cluster-scoped ssh-key naming (no dual-accept).** The upload name is
  `aether-bootstrap-<cluster>*` — cluster-scoped **only**. Because there are no
  pre-rc3 persisted clusters to preserve (Q1: pre-rc3 configs are refused, not
  migrated), there is **no dual-accept of the old bare `aether-bootstrap*`
  prefix**. `firewall_ids` has the identical distribution gap and no firewall is
  attached at bootstrap today — fix the class in the profile **before** bootstrap
  grows a firewall so we never ship the stale-state version.
- **3.4 — Cleanup reuses the provisioning credential path (#439).** All cleanup
  credential resolution routes through the persisted profile's
  `credentialEnvVars` (the `SourceCleanupHandle` bridge,
  `ProviderResolver.java:71-107`). Concretely: (a) `BootstrapCleanup` SSH-key
  reaping (`defaultHetznerClient:105-117`) stops reading raw `HCLOUD_TOKEN` and
  resolves via the handle like VM reaping already does (`:129-139`); (b) the
  raw-env fallback `resolveCloudComputeForCleanup` (`:65-69`) is removed from the
  normal path (a handle always exists post-rc3). **A token that provisioned MUST
  be able to tear down.**
- **3.5 — Document-level stored-format versioning (Q1 APPROVED; relates #434).**
  A single **`formatVersion` on the whole persisted cluster-TOML document** — one
  migration ladder for all persisted config, of which the `SourceProfile`
  addition is **rung 1**. Semantics:
  - **Absent OR non-current `formatVersion` → named error** ("persisted config
    has no/unsupported formatVersion; this build requires N — re-bootstrap").
    There is no `absent = legacy-baseline` special case: there are no existing
    users and nothing to migrate, and an unversioned document is *malformed* per
    parse-don't-validate.
  - **Unknown-newer `formatVersion` → refuse loudly** (named error), covering the
    binary-rollback-after-migration failure mode: the operator restores from the
    pre-upgrade persisted state; this is documented.
  - **The step-runner ladder is NOT built in rc3.** Its design — ordered,
    idempotent migration steps applied through the leader write path — is
    documented here for the first real rung, implementation deferred. rc3 ships
    only: the version field, an **exact-match gate**, and named errors in **both**
    directions (too-old and too-new).
  - **Release-notes obligation:** pre-rc3 persisted configs are refused;
    operators must **re-bootstrap** (there are no pre-rc3 production clusters, so
    this is a documentation line, not a migration burden).

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
  **Spot is excluded from the contract suite** (LocalStack-Pro-only, §4.3);
  spot is covered by unit tests plus a **live spot smoke** when creds land.
- **Live bar (creds landed, expected mid-rc3):** exactly **one full cloud e2e
  per release** — the Hetzner 15-suite set retargeted to AWS via the abstracted
  harness (§5), one 5-node cluster, torn down within the run. Gate = smoke +
  chaos + scaling + one streaming-failover pass green, plus the spot smoke.
- **Cost profile:** LocalStack = $0. Live AWS ≈ 5× t3.small + 1 ALB +
  **PG-on-EC2 per-run** (Q3 — not RDS; see §5) inside **one dedicated,
  NAT-free, $0-standing-cost VPC** (Q4 — see §5); same `MAX_CLOUD_HOURS` guard,
  label-scoped reaper.

#### 4.3 LocalStack free-tier vs Pro (flagged per owner ruling)

| Provisioning surface | LocalStack Community (free) | Requires Pro |
|---|---|---|
| EC2 run/describe/terminate/tags | ✅ | — |
| Security groups, subnets, key pairs | ✅ | — |
| ELBv2 target group register/deregister/health | ✅ | — |
| Secrets Manager get/create | ✅ | — |
| Route53 record upsert | ✅ | — |
| EC2 **spot** requests | — | ⚠️ Pro → **excluded from contract suite**; unit + live smoke instead |
| ACM certificate issuance | — | ⚠️ Pro (cert adapter is contract-mockable) |
| IAM fine-grained policy eval | partial | ⚠️ Pro |

The entire rc3 AWS provisioning surface (compute + LB + secrets + discovery via
EC2 tags) is **Community-tier**. Only spot and full ACM issuance would need Pro;
spot rides unit tests + a live smoke, ACM is contract-mockable. **No LocalStack
Pro dependency for the rc3 AWS bar.**

#### 4.4 Tier-3 — GCP / Azure (code-complete + contract/smoke)

- **Bar:** existing unit + provider contract tests (`GcpComputeProviderTest`,
  `AzureComputeProviderTest`, and siblings — already present) plus a
  build-time smoke that the factory wires all facets. No live cloud gate in rc3.
  The AWS/GCP/Azure `SecretsProvider`s are already Complete (§1) and validated
  under these bars — no new secrets scope (Q5).
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
the provider-specific operations `deploy-cloud.sh`/`teardown-cloud.sh` need into a
`cloud-driver` contract (shell functions, selected by `AETHER_CLOUD_PROVIDER`):

| Driver op | Hetzner impl (exists) | AWS impl (new) |
|---|---|---|
| `driver_create_sshkey` | `hcloud ssh-key create` | `aws ec2 import-key-pair` |
| `driver_init_network` | `hcloud network create/add-subnet` | **one dedicated persistent VPC** — public subnets + IGW, NAT-free, idempotent create-once (Q4) |
| `driver_provider_toml` | `aether-cloud.toml` (hetzner source) | `aether-cloud-aws.toml` (aws source) |
| `driver_create_support_vm` | `hcloud server create` (PG, LB) | **PG-on-EC2 per-run** — same container-PG bootstrap shape as Hetzner (Q3) |
| `driver_node_public_ip` | `hcloud server describe -o json` | `aws ec2 describe-instances` (public IPs — NAT-free) |
| `driver_managed_lb` | `hcloud load-balancer …` | ELBv2 target group + listener |
| `driver_reap_by_label` | `hcloud … --selector aether-cluster=` | `aws ec2 …--filter tag:aether-cluster` (VPC is the reaper's scope boundary) |
| `driver_cost_estimate` | `$0.071/hr` literal | per-instance-type table |

**Networking (Q4).** AWS uses **one dedicated, persistent VPC** created once via
the idempotent `driver_init_network` step: public subnets + Internet Gateway,
**NAT-free** (public node IPs, matching the Hetzner networking model, $0 standing
cost). Per-run resources are **instances + security groups only**; the VPC is the
reaper's scope boundary. The account **default VPC was rejected** (reap-scoping
risk) and a **per-run VPC was rejected** (multi-step teardown is a #439-class leak
surface); one persistent dedicated VPC threads between them.

**Support VM (Q3).** The AWS PG/support VM is **PG-on-EC2, per-run** (torn down
with the cluster), using the same container-PG bootstrap shape as Hetzner for
driver symmetry — not managed RDS (zero fidelity gain, slower teardown, more
IAM/VPC surface) and not persistent (Tier-2 cadence is one e2e per release,
unlike Hetzner's repeated sweeps). For LocalStack runs it is a PG container in
the test network.

The label scheme is already provider-portable (RFC-0015: `aether-cluster` /
`aether-node-id` on every provider), so the reaper generalizes cleanly.
Serialized cluster-A/B policy and the `MAX_CLOUD_HOURS` guard are
provider-neutral and stay in the outer script.

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
| W1 | **Generalized spec→request mapping surface** (§2.3): non-overridable resolve + `ProvisionRequest` (incl. `market-options`); providers implement `createFrom` only; `role==SPOT`→`InstanceType.SPOT` + AWS spot arm (§2.5-ii) | **L** | #459 (structural half), #442 | Highest blast-radius: every provision path (bootstrap seed + CTM heal) funnels through it; eliminates the class incl. the silent-spot downgrade. Do first. |
| W2 | **Complete config model** (§2.1): `image` + typed `RoleProvisioning`; `buildCloudProvisionSpec` populates image; **per-role image resolution** + CTM overlay parity (§2.4) | **M** | #459 | Unblocks documented `[source…] image`; replaces the interim core-stamps-all behavior; touches persisted parse format (gated by W6). |
| W3 | **Provider-agnostic persisted `SourceProfile`** (§3.1–3.3): atomic-at-bootstrap persistence, ssh-key/firewall selectors, cluster-scoped naming (no dual-accept), **delete 3B prefix fallback** | **L** | #444 | KV-reconstructibility invariant; must precede any bootstrap firewall growth. Interaction-heavy (bootstrap + CTM + reaper). |
| W4 | **Cleanup credential unification** (§3.4) + **timeout-path regression test** with non-default env-var name | **M** | #439 | Money-leak path. Small code, but the test is the observability surface that proves it — build the test first, then unify. |
| W5 | **LocalStack AWS contract suite** (§4.2/4.3) for compute/LB/secrets/discovery (**spot excluded**) | **M** | new (harness) | AWS regression sensor before live creds; cheap, unblocks W1/W2 verification for AWS without a bill. |
| W6 | **Document-level stored-format versioning** (§3.5): `formatVersion` field + **exact-match gate** + named errors both directions. **No step-runner ladder** (deferred to first real rung) | **S** | #434-related | Gates W2/W3 persisted changes; owner-approved (Q1). Shrunk from the original ladder scope. |
| W7 | **Cloud e2e harness driver abstraction** (§5): extract Hetzner driver (behavior-preserving) + AWS driver (VPC init, PG-on-EC2, ELBv2, reaper) | **L** | new (harness) | Enables the Tier-2 live gate; sequenced after W5 so contract-level AWS is already green. |
| W8 | **Live AWS e2e gate activation** (§4.2 live bar) + **live spot smoke** when creds land | **M** | new | Final gate; depends on W1–W3 + W7. Expected mid-rc3. |
| W9 | **Doc/catalog reconciliation**: fix `vm-snapshot.md` + catalog rows 4/204; spot wording (below); Hetzner-secrets wording; snapshot-matrix doc `aether-<ver>-<runtime>-<arch>` (§2.6) | **S** | — | Additive, independent, batch freely. |
| W10 | **Spot loud-fail validation** (§2.5-i): extend PF-16 so a spot sub-table on any provider without an implemented arm fails validation loudly | **S** | new (spot slice) | Standalone, closes the silent on-demand downgrade for all providers at once; independent of W1. |

**W9 catalog wording (code-verify before shipping):** spot →
*"opt-in per-role config, AWS (rc3); reclamation handled as node failure via
auto-heal; spot-aware placement planned, demand-gated"*; Hetzner secrets →
*"env-only; external secret managers integrate via env"* (matrix cell stays
Partial with the reason stated).

**New tickets referenced (being filed):** post-GA spot policy ticket (CTM
preference / preemption drain / capacity fallback — §2.5); container multi-arch
manifest check (§2.6).

Validation gate between foundational changes: W1–W4/W10 each land behind a fast
in-JVM proof (provider unit/contract + the #439 timeout test) before the
LocalStack suite (W5), with the live AWS sweep (W8) as the **final** gate, never
the primary debug surface.

## Alternatives considered

- **A1. Patch `image` per provider like #442 patched `server_type`.** Rejected:
  that *is* the defect class. It leaves the next field (firewall, spot, placement
  detail) to be rediscovered as a field-specific outage. W1 pays the structural
  cost once.
- **A2. Keep provider-specific provisioning-state re-derivation (3B) as the
  general mechanism.** Rejected for the class *and now deleted entirely* (§3.1):
  it is Hetzner-only, prefix-guesses account-wide, and cannot carry firewall refs
  or labels policy. With the profile persisted atomically at bootstrap,
  profile-absence is unreachable, so the fallback has no remaining role.
- **A3. Live AWS as the primary AWS gate from day one.** Rejected per owner
  ruling and cost/latency: LocalStack contract tests are the interim sensor; live
  e2e is one bounded run per release once creds exist.
- **A4. Defer spot entirely to post-GA.** Rejected: the config surface already
  exists and *silently lies* (validated spot → on-demand). rc3 must at minimum
  fail loudly (W10) and ship the AWS arm riding W1 (§2.5); only the *policy* layer
  is demand-gated post-GA.
- **A5. ~~Dual-accept old + new ssh-key names for back-compat.~~ VOID.** The
  original rejection of cluster-scoped-only naming assumed existing clusters to
  preserve. Per Q1 there are no pre-rc3 persisted clusters (they are refused, not
  migrated), so §3.3 uses **cluster-scoped names only** with no dual-accept.
- **A6. Account default VPC / per-run VPC for the AWS harness.** Both rejected
  (Q4): default VPC has reap-scoping risk; per-run VPC is a multi-step-teardown
  leak surface. §5 uses one dedicated persistent NAT-free VPC.
- **A7. Managed RDS for the AWS support DB.** Rejected (Q3): zero fidelity gain
  over PG-on-EC2, slower teardown, more IAM/VPC surface. §5 uses PG-on-EC2 per-run.
- **A8. Runtime-kind tag on the snapshot id (container vs JVM).** Rejected (Q6):
  unverifiable metadata on an opaque id. §2.4 uses per-role image resolution;
  §2.6 expresses runtime/arch via the `(instance_type, runtime, image)` triple.

## Migration

- **Config/parse format (W2/W3):** additive fields (`image`, typed role table,
  persisted `SourceProfile`) behind the **document-level `formatVersion`** (W6,
  §3.5). Absent or non-current version → named error; operators **re-bootstrap**
  (release-notes obligation — there are no pre-rc3 production clusters, so this is
  a doc line, not a migration burden). The step-runner ladder is designed but not
  built in rc3.
- **Providers:** the `provision(ProvisionSpec)` override signature changes to
  `createFrom(ProvisionRequest)`; all five in-tree providers migrate in W1. This
  is an SPI change within a BSL module — rebuild-together, no external contract.
- **Docs:** W9 corrects the three false "no code change / passed through" claims,
  the spot wording, and the Hetzner-secrets wording; adds the snapshot-matrix
  naming doc.

## Owner rulings on open questions (2026-07-17)

All six questions raised in the first draft are ruled. Each is propagated into the
sections cited.

- **Q1 — Persisted stored-format change. APPROVED, amended.** Document-level
  `formatVersion` on the whole persisted cluster-TOML (one migration ladder;
  `SourceProfile` = rung 1). Absent OR non-current version → **named error**
  (no `absent=legacy-baseline`: no existing users, nothing to migrate, and an
  unversioned doc is malformed per parse-don't-validate). Unknown-newer →
  refuse loudly (binary-rollback failure mode: named error, restore from
  pre-upgrade state, documented). The step-runner ladder is **designed but not
  built in rc3**; W6 shrinks to field + exact-match gate + named errors both
  directions. **Cascade:** §3.3 uses cluster-scoped ssh-key names **only**
  (dual-accept dropped, A5 voided); §3.1 **deletes** the 3B prefix-listing
  fallback (profile persisted atomically at bootstrap → absence unreachable →
  fail loudly, never guess). Release-notes: pre-rc3 configs refused; re-bootstrap.
  → §3.1, §3.3, §3.5, §2.1, W6, A5, Migration.
- **Q2 — Spot. Option A + an rc3 mechanism slice.** Recorded the code-verified
  fact that the spot surface exists but silently downgrades to on-demand
  (§Motivation, §1 surprise 3). rc3: (i) loud-fail validation on unimplemented
  arms (W10); (ii) `role==SPOT`→`InstanceType.SPOT` + AWS spot arm riding W1
  (§2.5); GCP/Azure arms only if trivially riding Tier-3. Spot is
  LocalStack-Pro-only → excluded from contract tests, covered by unit + live
  smoke (§4.2/4.3). Reclamation = node-failure via auto-heal, **no preemption
  drain** — stated honestly. Policy layer = post-GA demand-gated ticket
  (placeholder). W9 catalog wording, code-verified. → §2.5, §4.2, §4.3, W1, W9,
  W10.
- **Q3 — AWS support DB. PG-on-EC2, per-run** (not RDS, not persistent); same
  container-PG bootstrap shape as Hetzner. → §4.2, §5, A7.
- **Q4 — AWS networking. One dedicated persistent VPC**, created once via an
  idempotent `driver_init_network`; NAT-free (public subnets + IGW + public node
  IPs, $0 standing cost); per-run resources = instances + security groups only;
  the VPC is the reaper's scope boundary. Default VPC and per-run VPC both
  rejected. → §5, A6.
- **Q5 — Hetzner secrets stay env-only.** Matrix cell stays Partial with the
  reason stated + "external secret managers integrate via env" (W9 wording); no
  ticket until demand. AWS/GCP/Azure SecretsProviders confirmed already Complete,
  validated under W5/Tier-3. → §1, §4.4, W9.
- **Q6 — No runtime-kind tag.** Per-role image resolution in W2 (own role's
  image, no cross-role fallback; interim: core stamps all, worker image
  parsed-but-ignored). Mixed-arch multi-source is expressible via the
  `(instance_type, runtime, image)` triple per source×role (§2.6) with named
  gaps: snapshot-matrix doc `aether-<ver>-<runtime>-<arch>` (W9), container
  multi-arch manifest check (new ticket), one `cx`/`cax` mixed validation run,
  provider-side arch-mismatch errors loud at create. → §2.4, §2.6, W9, A8.

## References

### Internal — SPI & providers
- `aether/environment-integration/…/ComputeProvider.java` (`:35-37` default drop),
  `ProvisionSpec.java` (`:17,33` imageId/withImage), `ProvisionContext.java`,
  `EnvironmentIntegration.java` (`:21-39` facets), `CloudProvider.java` (`:16`
  `provisionSpot` — zero callers), `CloudProviderSupport.java` (`:80-84`
  hardcoded `ON_DEMAND`), `CloudCredentials.java` (`:23-36` env resolution)
- `aether/environment/{hetzner,aws,gcp,azure,docker}/…/*ComputeProvider.java`,
  `*EnvironmentIntegration.java`, `*CloudProvider.java` (spot stubs),
  `*LoadBalancerProvider.java`, `*DiscoveryProvider.java`, `*SecretsProvider.java`

### Internal — plumbing, config & lifecycle
- `aether/cli/…/cluster/BootstrapPhaseProvision.java` (`:429-458`
  `buildCloudProvisionSpec` — no `withImage`, `:453` hardcoded `ON_DEMAND`),
  `ProviderResolver.java` (`:65-107` cleanup paths, `:146-178` `buildCloudConfig`,
  `:190-192` `coreInstanceType` overlay), `BootstrapCleanup.java` (`:105-139`
  credential dual-path), `SourceCleanupHandle.java`
- `aether/aether-config/…/cluster/SourceProfile.java`, `RoleSubTable.java`
  (`:12-16` — no image field), `ClusterBootstrapConfigValidator.java` (`:266`
  PF-16 spot, `:284` PF-14 non-spot-with-LB)

### Internal — harness & docs
- `aether/tests/cloud/{deploy-cloud.sh,run-cloud-tests.sh,teardown-cloud.sh}`
- `aether/docs/operator/vm-snapshot.md` (`:84-85,152-156` — false "no code change"
  claim), `aether/docs/reference/feature-catalog.md` (rows 4, 178, 187, 200-204)

### Related RFCs
- RFC-0012 (Resource Provisioning), RFC-0013 (Deployment Provider),
  RFC-0015 (Cluster-Label Scoping — provider-portable label scheme this RFC's
  reaper reuses)
