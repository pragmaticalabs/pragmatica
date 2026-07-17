<!-- SPDX-License-Identifier: BUSL-1.1 -->
<!-- Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko -->
<!-- Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0. -->

# RFC-0016 — Full Major-Cloud Support

| Field | Value |
|---|---|
| Status | Draft (W1/W2/W10 landed, W6 gate checkpoint — see Implementation status) |
| Author | Sergiy Yevtushenko |
| Date | 2026-07-17 |
| Supersedes | — |
| Related | RFC-0012 (Resource Provisioning), RFC-0013 (Deployment Provider), RFC-0015 (Cluster-Label Scoping); epic #463; issues #442, #459, #444, #439, #434, #475 |

## Summary

The v1.0.0-rc3 headline is **full major-cloud support**: AWS becomes a
first-class, e2e-validated deployment target alongside Hetzner, and GCP/Azure
become code-complete with contract/smoke coverage. This RFC does **not** add new
provider code cell-by-cell. It fixes the *structural* reason cloud support is
uneven: the spec→provision mapping was an ad-hoc, per-provider, per-field
hand-pluming surface, and provisioning state (ssh keys, firewalls, credentials)
is reconstructed by divergent paths that disagree. It designs one spec→request
mapping surface so a missing field can never be "fixed for one provider only"
again (the #442/#459 defect class), folds a provider-agnostic, KV-reconstructible
`SourceProfile` for provisioning state (#444 + #439), defines a concrete per-tier
validation bar, and abstracts the Hetzner-only cloud e2e harness so AWS can be
gated the same way.

The mapping surface (§2), per-role image (§2.4), and the spot mechanism slice
(§2.5) are **landed** (W1/W2/W10). The Motivation sections are kept in the past
tense as the record of *why*.

## Implementation status (as-built, 2026-07-17)

**Landed** tonight (commits `b9ef6c475`, `e472a373f`): **W1** (both stages — the
spec→request mapping surface + AWS spot arm), **W2** (per-role image), **W10**
(spot loud-fail validation). **W6** (document-format gate) landed as a checkpoint
in the working tree. **Pending:** W3 (persisted `SourceProfile`, which deletes the
3B fallback), W4 (#439 cleanup unification), W5–W8 (LocalStack + AWS harness +
live gate), W9 (doc/catalog sweep).

As-built mechanism (this is the ground truth §2/§3.5 designed toward):

- `ComputeProvider.provision(ProvisionSpec)` (`ComputeProvider.java:53-58`) is the
  non-overridable boundary that calls the **static**
  `ProvisionRequest.resolve(spec, providerDefaults())`
  (`ProvisionRequest.java:59`) then `createFrom` — the single method each provider
  implements (`ComputeProvider.java:24`). No provider overrides `provision`.
- `resolve()` derives `market` from `context.role()`
  (`ProvisionRequest.java:68,144-148`), so the three producers' `ON_DEMAND`
  literals (`CloudProviderSupport.java:81`, `BootstrapPhaseProvision.java:456`,
  `ClusterTopologyManagerRecord.java:528`) are now an **inert field** — a `spot`
  role resolves to `SPOT` regardless of the literal (guarded by
  `ProvisionRequestTest.java:187` and `SpotRoleConstantDriftTest`).
- Every provider supplies its config-level fallbacks via `providerDefaults()`
  (Hetzner `:53`, AWS `:53`, GCP `:55`, Azure `:63`, Docker `:57`,
  `ProviderDefaults.none()` default at `ComputeProvider.java:45`) and consumes the
  resolved fields verbatim in `createFrom`. The per-provider
  `resolveServerType`/`resolveImage` methods moved **into**
  `ProvisionRequest.resolve` (`:77-122`); the loud stock-image fallback (#459) is
  now `ProvisionRequest.imageFallback` (`:115-122`) and the loud no-instance-size
  failure (#442) is `resolveInstanceSize` (`:77-85`).
- W2 per-role image: `BootstrapPhaseProvision.buildCloudProvisionSpec` computes
  `roleImage(source, role)` (`:440`) and applies it to `spec.imageId` via
  `applyImage` (`:461`) — the role's OWN image, never a cross-role fallback.
- W10: `ClusterBootstrapConfigValidator` (`:264-265`) encodes the post-W1 ground
  truth — only AWS has a real spot arm (its `createFrom` attaches EC2
  `InstanceMarketOptions`); Hetzner/GCP/Azure/Docker reject `SPOT` loudly at
  `createFrom`, and an unsupported spot sub-table fails validation.
- W6 (checkpoint, working tree): the document-format gate **reuses the existing
  top-level `config_version` field** — `ClusterBootstrapConfigParser.parseConfigVersion`
  (`:100-123`, `REQUIRED_CONFIG_VERSION = "1.0.0"` at `:31`), exact-match-gated
  with the Q1 named errors (absent/older → re-bootstrap; newer → restore
  pre-upgrade). W3 bumps the required version when `SourceProfile` joins the
  format. See §3.5.

## Motivation

### What "cloud support" looked like before W1

The SPI (`aether/environment-integration`) was already clean: a `ProvisionSpec`
carried a typed intent (`instanceType`, `instanceSize`, `pool`, `context`,
`imageId`, `userData`, `placement` — `ProvisionSpec.java:13-19`), each provider
translated it into its own native create request, and an `EnvironmentIntegration`
exposes seven optional facets (compute / secrets / loadBalancer / discovery /
certificate / dns / floatingIp — `EnvironmentIntegration.java:21-39`).

The problem was not the SPI. It was that **each provider's `provision(ProvisionSpec)`
override cherry-picked a different subset of spec fields**, and the base default
dropped everything (`provision(spec.instanceType())`). The result was a matrix of
what each provider *actually plumbed* that nobody could see from one place — the
exact condition under which #442 fixed `server_type` for Hetzner only and #459
showed `image` was fixed for nobody. W1 replaced those overrides with the single
`createFrom(ProvisionRequest)` surface (§Implementation status).

### The #442/#459 defect class, stated precisely (pre-W1)

`ProvisionSpec.imageId()` existed (`ProvisionSpec.java:17`, with `withImage()` at
`:33`) but was **read by no provider**. Each provider's `provision(ProvisionSpec)`
override cherry-picked a subset: Hetzner plumbed `instanceSize`→server_type but
not `imageId`; AWS/GCP/Azure dropped **both** `instanceSize` and `imageId`, using
`config.amiId()`/`config.sourceImage()`/`config.image()` for the image and
`config.instanceType()`/`config.machineType()`/`config.vmSize()` for the size.
W1 replaced these overrides with the shared `createFrom(ProvisionRequest)`;
`resolve()` now reads `spec.imageId()` and `spec.instanceSize()` for every
provider, so the fragmentation is gone (§Implementation status).

The field was also dropped *upstream of the provider* pre-#459: the config model
had no image field and the bootstrap never set it on the spec. **#459
(b1b9b2820)** added `image` to the config model (`RoleSubTable.java:16`) and made
Hetzner honor a source-level image through a *parallel config-overlay path*
(`config.image()` → the old `resolveImage`); **W2** then wired the spec field
itself (`buildCloudProvisionSpec.applyImage`, `:461`), and **W1** unified both
into `resolve()`'s single precedence — `spec.imageId()` → `providerDefaults().image()`
→ loud stock fallback (`ProvisionRequest.java:96-122`) — for every provider.

This history is the reason for the doc-reconciliation work (W9):
`vm-snapshot.md:84-85`/`:152-156` and `feature-catalog.md` row 204 claimed the
snapshot id was honored "through the same `image` field … No code change is
required" and "passed through to the provider's create-server API." That was
false as written — it took #459's code and, for a uniform path, W1/W2 — and W9
corrects the docs.

### Spot: a config surface that silently lied (pre-W1)

Before W1, the spot capability was configurable end-to-end and **silently
downgraded to on-demand**:

- `NodeRole.SPOT` parses and is validated: `ClusterBootstrapConfigValidator` rule
  **PF-16** (`:266`) and rule **PF-14** (`:284`). The CLI `scale` path accepts
  `role=spot`.
- All three spec-build sites hardcoded on-demand:
  `CloudProviderSupport.buildProvisionSpec` (`:81`),
  `BootstrapPhaseProvision.buildCloudProvisionSpec` (`:456`), and the CTM
  auto-heal path `ClusterTopologyManagerRecord.java:528`.
- And `CloudProvider.provisionSpot` (`CloudProvider.java:16`) had **zero
  production callers**.

So a validated `spot` role provisioned an on-demand instance, on the seed path
*and* the auto-heal path, with no operator-visible signal — while
`feature-catalog.md` row 4 markets auto-heal as "spot-first … Battle-tested".
**W1** made `resolve()` derive `market` from the role (so those literals are now
inert) and added the AWS spot arm; **W10** makes an unsupported spot sub-table
fail validation loudly and the non-AWS providers reject `SPOT` at `createFrom`.

### The provisioning-state reconstructibility gap (#444 + #439) — still open

This is unchanged by W1/W2/W10 (W3/W4 pending). Bootstrap-context provisioning
state lives only in the bootstrap CLI process and is not reconstructible from KV
by an arbitrary leader — violating the architectural invariant that cluster state
be reconstructible from the KV-Store. `#442` shipped option 3B for rc2 (Hetzner
re-derives ssh-key ids at provision time by listing account keys and filtering
the `aether-bootstrap` name prefix — still present in `HetznerComputeProvider`,
deleted by W3), which closed the loss mode with zero persisted state but is
provider-specific and account-wide-greedy, and leaves the *class* unfixed.
`firewall_ids` has the identical gap (rendered nowhere), and the ssh-key upload
name (`aether-bootstrap*`) matches **all** clusters on an account.

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
provisioned MUST be able to tear down everything it created. W3/W4 close this.

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
4. **The tactical Hetzner `image` fix (#459) landed outside this RFC.** This RFC
   owns the structural generalization (now landed as W1/W2).
5. **The persisted-cluster-TOML stored-format change is an OWNER SIGN-OFF item
   (now ruled — see the Owner-rulings-on-open-questions section, Q1).**

## Design

### 1. Capability matrix (code-verified, post-W1)

Status per cell against the `aether/environment-integration` SPI, at HEAD (W1/W2/W10
landed). Every cell carries file-level evidence; no cell is asserted from docs.
Legend: **C** = Complete (real implementation wired), **P** = Partial (works,
limited), **R** = present, deliberately rejects loudly (unsupported, W10), **S** =
Stub (facet present, non-functional), **—** = legitimately absent for that
provider.

| Capability | Hetzner | AWS | GCP | Azure | Docker |
|---|---|---|---|---|---|
| ComputeProvider (CRUD) | C | C | C | C | C |
| — spec→request image plumb | C¹ | C¹ | C¹ | C¹ | — |
| — spec→request instance-type plumb | C¹ | C¹ | C¹ | C¹ | — |
| SecretsProvider | P (env) | C (Secrets Mgr) | C (Secret Mgr) | C (Key Vault) | — |
| LoadBalancerProvider | C | C | C | C | — |
| DiscoveryProvider | C | C | C | C | — |
| FloatingIpProvider | C | — | — | — | S (Noop) |
| Spot provisioning | R² | C² | R² | R² | R² |

¹ **RESOLVED-by-W1.** All cloud providers now consume the effective
instance-size and image from the resolved `ProvisionRequest` in `createFrom`
(`AwsComputeProvider.java:68`, GCP `:74`, Azure `:83`, Hetzner `:70`), fed by
`resolve()`'s precedence (spec field → `providerDefaults()` → loud fallback).
Pre-W1 these were `S` for AWS/GCP/Azure (and image `S` for Hetzner too); that gap
is retained in Motivation as the historical *why*. Docker is single-image /
fixed-size (`providerDefaults(..., supportsImage=false)`, `:57-58`), hence `—`.

² **W1-ii + W10.** AWS has a real spot arm (`createFrom` attaches EC2
`InstanceMarketOptions`; `AwsComputeProviderTest.createFrom_spotMarket_*`).
Hetzner/GCP/Azure/Docker reject a `SPOT` market **loudly** at `createFrom`
(`*ComputeProviderTest.createFrom_spotRequest_rejectedLoud*`), and
`ClusterBootstrapConfigValidator` (`:264-266`) fails an unsupported spot
sub-table — replacing the pre-W1 silent on-demand downgrade.

Cell evidence (paths under `aether/environment/`):

- **Compute CRUD = C for all five**: each implements
  `createFrom`/`terminate`/`listInstances`/`instanceStatus`
  (+`restart`/`applyTags`) against a real client.
- **image / instance-type plumb = C for all cloud providers (W1)**: see footnote 1.
- **Secrets**: AWS `AwsSecretsProvider.java:23`, GCP `GcpSecretsProvider.java:19`,
  Azure `AzureSecretsProvider.java:22` implement `resolveSecret` against the
  managed secret store, wrapped in `CachingSecretsProvider`. Hetzner is **P**:
  env-only `EnvSecretsProvider` (`HetznerEnvironmentIntegration.java:76-79`) — a
  deliberate, documented posture (Q5): external secret managers integrate via
  env, no managed adapter until demand. Docker `empty()`.
- **LoadBalancer = C for four clouds**: real client calls, no
  `operationNotSupported` in method bodies — AWS `AwsLoadBalancerProvider.java:38-87`,
  Hetzner/GCP/Azure the same SPI trio; wired only when LB config is present.
- **Discovery = C for four clouds**: `discoverPeers` polls native tags/labels
  (`AwsDiscoveryProvider.java:59-63`); `registerSelf`/`deregisterSelf` are
  `operationNotSupported` **by design** (tag/label discovery needs no
  registration). Docker `empty()`.
- **FloatingIp**: Hetzner **C** (`HetznerFloatingIpProvider`); AWS/GCP/Azure
  `empty()` → `—`; Docker `NoopFloatingIpProvider` → **S**.
- **Spot**: see footnote 2.

**Post-W1 state:** the two plumbing sub-rows are Complete for every cloud
provider (RESOLVED-by-W1); spot is a real arm on AWS and a loud rejection
elsewhere (W10). The still-open cells are the Hetzner env-only Secrets (P, by
ruling) and the Docker Noop FloatingIp (S, harmless).

**Surprises (historical — recorded here as the why for §2):**

1. **The Tier-2 reference cloud (AWS) had *lower* provisioning fidelity than
   Tier-1 Hetzner** — AWS/GCP dropped both `instanceSize` and `imageId` while
   Hetzner at least plumbed `instanceSize`. Resolved by W1 (all consume the
   resolved request).
2. **`spec.imageId()` was dead code across the whole fleet**; #459 made
   `[source…] image` work for Hetzner only via a parallel overlay. Resolved by
   W1/W2 (one `resolve()` precedence for every provider).
3. **Spot didn't just stub — it silently downgraded** (validated spot →
   on-demand on both seed and heal paths). Resolved by W1 (market from role) +
   W10 (loud rejection / validation).
4. **The cleanup credential bridge is half-wired** (#439): VMs use the persisted
   handle, SSH-key reaping still reads raw `HCLOUD_TOKEN`. **Still open** (W4).
5. **Docker floating-IP is a Noop stub, not absent** — harmless. Unchanged.

### 2. Spec→provision plumbing parity — eliminate the class (LANDED: W1/W2)

**Root cause (pre-W1).** There was no single place that mapped a source/role spec
into a provider create-request. The mapping was smeared across the config parser,
the three spec-build producers (`CloudProviderSupport.buildProvisionSpec`,
`BootstrapPhaseProvision.buildCloudProvisionSpec`,
`ClusterTopologyManagerRecord:528` — the CTM heal path),
`ProviderResolver.buildCloudConfig`, and each provider's `provision(ProvisionSpec)`.
A field was "plumbed" only if all agreed; `server_type` accidentally did for
Hetzner, `image` reached only Hetzner via a second path, and `InstanceType.SPOT`
reached no one.

**Design (as landed): one total mapping surface.** A single provider-agnostic
`ProvisionRequest` that every provider consumes exhaustively.

- **2.1 — Config model (W2 landed).** #459 added `image` to `RoleSubTable`
  (`:16`); W2 uses it as tier-1 per-role image. (The follow-on typed
  `RoleProvisioning` refactor and source-level `image` remain a nicety, not a
  blocker; gated by the `config_version` document gate, §3.5.)
- **2.2 — The producers stay ON_DEMAND-literal; market is derived at the boundary
  (as-built).** `resolve()` computes `market(context.role())`
  (`ProvisionRequest.java:68,144-148`), so the three producers' `ON_DEMAND`
  literals (`CloudProviderSupport.java:81`, `BootstrapPhaseProvision.java:456`,
  `ClusterTopologyManagerRecord.java:528`) are an **inert field** — deliberately
  *not* removed, because the boundary ruling (2.3) made removing them
  unnecessary: `resolve()` covers all three producers including CTM heal. W2's
  `buildCloudProvisionSpec.applyImage` (`:461`) additionally sets `spec.imageId`
  per role. A `spot` role therefore resolves to `SPOT` regardless of the literal
  (`ProvisionRequestTest.java:187`).
- **2.3 — Providers consume the request at the SPI boundary (as-built).**
  `provision(ProvisionSpec)` is a **non-overridable** default on `ComputeProvider`
  (`:53-58`) that calls the **`static`** `ProvisionRequest.resolve(spec,
  providerDefaults())` (`:59`) then `createFrom` — the *only* method a provider
  implements (`:24`). `static` (not `default`) is load-bearing: a `default`
  `resolve` could be overridden and re-open the class. **Critically, `resolve()`
  runs at the `provision(spec)` boundary — the single choke every producer
  reaches (bootstrap seed, `CloudProviderSupport`, and the CTM heal path via
  `NodeLifecycleManager.provisionNode:60-69` → `provider.provision(spec)`).**
  Precedence: `spec` field → `providerDefaults()` → loud fallback; no-instance-size
  fails loud (#442, `ProvisionRequest.java:77-85`), stock-image fallback warns
  loud (#459, `:115-122`).
- **2.4 — CTM parity + per-role image (W2 landed).** A replacement inherits the
  effective image/instance-type of **its own role**: `resolve()` reads
  `spec.imageId()` (populated per-role by `buildCloudProvisionSpec.applyImage`,
  `:461`) with precedence spec → `providerDefaults().image()` → loud stock
  fallback, and **no cross-role fallback** (`ProvisionRequest.resolveImage`,
  `:96-122`; the doc at `BootstrapPhaseProvision.java:464-467` states "the role's
  OWN image … NEVER applies [cross-role]"). There is **no runtime-kind tag** on
  the snapshot id (Q6). The pre-W2 interim (core image stamps all roles) no longer
  applies.
- **2.5 — Spot mechanism slice (W1-ii + W10 landed).** (i) `role == SPOT →
  InstanceType.SPOT` at the boundary (`ProvisionRequest.market`); (ii) the **AWS
  spot arm** attaches EC2 `InstanceMarketOptions` in `createFrom`
  (`AwsComputeProvider`); (iii) Hetzner/GCP/Azure/Docker reject `SPOT` **loudly**
  at `createFrom`; (iv) `ClusterBootstrapConfigValidator` (`:264-266`) fails an
  unsupported spot sub-table (W10). **Reclamation is handled honestly as abrupt
  node failure via auto-heal — there is NO preemption-notice drain.** The policy
  layer (CTM spot preference, preemption drain, capacity fallback) is a **post-GA
  demand-gated ticket** *[reference placeholder: post-GA spot policy ticket]*.
- **2.6 — Mixed-architecture, multi-source clusters (Q6 extension).** A single
  cluster with heterogeneous sources — e.g. an x86 JVM source, an ARM container
  source, and an ARM JVM source — is expressible via the
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
  **loud at create** (a wrong-arch image fails the create call visibly).

**Acceptance for the class being eliminated (as-built).** A new provider added
after W1 cannot compile without implementing `createFrom(ProvisionRequest)` — a
total request in which image, instance-type, market, zone, and user-data are
already resolved — and cannot re-open resolution (the `static` `resolve` + the
non-overridable `provision(spec)` boundary). The three producers' `ON_DEMAND`
literals remain **inert** (market is derived from role at the boundary); removing
them is cosmetic and deferred.

### 3. Provider-agnostic SourceProfile (folds #444 + #439) — PENDING (W3/W4)

One coherent "provisioning-state reconstructibility" pass, per the architectural
invariant that cluster state be reconstructible from KV.

- **3.1 — Persist a provider-agnostic `SourceProfile` into distributed cluster
  config, atomically at bootstrap.** Covers ssh-key references, firewall
  references, and labels policy. **Bootstrap persists the profile atomically at
  cluster creation, so profile-absence is unreachable by construction.** A leader
  that finds no profile **fails loudly, naming the missing profile** — never
  account-wide prefix guessing. The Hetzner-only 3B prefix-listing fallback (still
  present at HEAD) is **deleted** by W3: with the profile always present, its only
  role (reconstruct-from-nothing) no longer exists, and its account-wide
  greediness is a liability.
- **3.2 — Prefer stable NAMES/selectors over provider-numeric ids.** Numeric ids
  go stale when account resources are recreated; the persisted profile stores
  selector *names* (cluster-scoped ssh-key name prefix, firewall names, label
  selectors) and resolves them to live ids at provision time.
- **3.3 — Cluster-scoped ssh-key naming (no dual-accept).** The upload name is
  `aether-bootstrap-<cluster>*` — cluster-scoped **only**. Because there are no
  pre-rc3 persisted clusters to preserve (Q1), there is **no dual-accept of the
  old bare `aether-bootstrap*` prefix**. `firewall_ids` has the identical gap and
  no firewall is attached at bootstrap today — fix the class in the profile
  **before** bootstrap grows a firewall.
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
  The document formatVersion is the **existing top-level `config_version` field**,
  reused deliberately (not a second version field), one migration ladder with the
  `SourceProfile` addition as **rung 1**. `config_version` was already REQUIRED
  and exact-match-gated on the same persisted document
  (`ClusterBootstrapConfigParser.parseConfigVersion`, `:100-123`;
  `REQUIRED_CONFIG_VERSION = "1.0.0"`, `:31`) — a second `formatVersion` field
  would have been redundant dual-versioning of one document. W6 upgraded its
  errors to the Q1 named forms; W3 bumps the required version when `SourceProfile`
  joins the format. Semantics (as-built, W6 checkpoint):
  - **Absent OR non-current `config_version` → named error** ("Persisted config
    has no config_version (document format version); this build requires N —
    re-bootstrap"; `ClusterBootstrapConfigParser.java:102,117-118`). No
    `absent = legacy-baseline`: no existing users, and an unversioned document is
    *malformed* per parse-don't-validate.
  - **Unknown-newer `config_version` → refuse loudly** (`:113-114`), covering the
    binary-rollback-after-migration failure mode: restore from pre-upgrade state;
    documented.
  - **The step-runner ladder is NOT built in rc3.** Design documented for the
    first real rung; rc3 ships only the reused version field, the **exact-match
    gate** (`:108`), and named errors in **both** directions.
  - **Release-notes obligation:** pre-rc3 persisted configs are refused;
    operators **re-bootstrap** (no pre-rc3 production clusters — a doc line, not a
    migration burden).

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
- **Pass criteria:** all non-skipped suites green; residual failures triaged
  HARNESS vs PRODUCT; only harness-side debt may remain open.

#### 4.2 Tier-2 — AWS (LocalStack now → live e2e when creds land)

- **Interim bar (no owner AWS creds):** **LocalStack contract tests** for the
  provisioning surface (EC2 run/describe/terminate/tags, ELBv2
  register/deregister/describe-target-health, Secrets Manager
  get/create-secret, Route53 upsert). Runs in CI on every rc3 commit touching
  `aether/environment/aws` or the shared SPI. Gate = contract suite green.
  **Spot is excluded from the contract suite** (LocalStack-Pro-only, §4.3);
  spot rides unit tests (the landed `createFrom_spotMarket_*` cases) plus a
  **live spot smoke** when creds land.
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
  `AzureComputeProviderTest`, incl. the landed `createFrom_*` cases) plus a
  build-time smoke that the factory wires all facets. No live cloud gate in rc3.
  The AWS/GCP/Azure `SecretsProvider`s are already Complete (§1); no new secrets
  scope (Q5).
- **Pass criteria:** compiles, facets wired, contract suites green, capability
  matrix cells hold at their §1 status.

### 5. Cloud e2e harness abstraction — PENDING (W5/W7/W8)

**Today the harness is Hetzner-only.** `deploy-cloud.sh` hardcodes `hcloud` for
ssh-key/network/server/LB creation, literals `LOCATION="fsn1"`,
`SERVER_TYPE="cx22"`, `IMAGE="ubuntu-24.04"` (`:19-21`), `python3` parsing of
`hcloud … -o json` for IPs, and directly provisions the PG VM and LB VM via
`hcloud server create` (`:164-172, 225-233`). `teardown-cloud.sh` reaps via
`hcloud … --selector aether-cluster=cloud-test` + `aether-core-*` name sweep.
`run-cloud-tests.sh` is provider-neutral already.

**Design: a thin provider driver interface behind the existing scripts**
(selected by `AETHER_CLOUD_PROVIDER`):

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
the idempotent `driver_init_network` step: public subnets + IGW, **NAT-free**
(public node IPs, $0 standing cost). Per-run resources are **instances + security
groups only**; the VPC is the reaper's scope boundary. Default VPC rejected
(reap-scoping risk); per-run VPC rejected (multi-step-teardown leak, #439-class).

**Support VM (Q3).** The AWS PG/support VM is **PG-on-EC2, per-run**, same
container-PG bootstrap shape as Hetzner — not RDS (zero fidelity gain, slower
teardown, more IAM/VPC surface) and not persistent (Tier-2 cadence is one e2e per
release). For LocalStack runs it is a PG container in the test network.

The label scheme is provider-portable (RFC-0015), so the reaper generalizes.
Serialized cluster-A/B policy and the `MAX_CLOUD_HOURS` guard stay in the outer
script.

**Scope for rc3:** extract the Hetzner driver (behavior-preserving) + the AWS
driver to the depth the Tier-2 live bar needs. GCP/Azure drivers out of rc3
harness scope.

## Work breakdown (risk-first)

Ordered by interaction-risk × blast-radius × observability-gap. Sizes S ≤ 1d,
M ≈ 2–3d, L ≈ 1w. **W1/W2/W10 landed 2026-07-17 (`b9ef6c475`, `e472a373f`); W6
gate checkpoint landed in the working tree.**

| # | Item | Size | Issue | Status |
|---|---|---|---|---|
| W1 | **Generalized spec→request mapping surface** (§2.3): `static` non-overridable `resolve()` at the `provision(spec)` boundary + `ProvisionRequest` (incl. market options); providers implement `createFrom` only; `role==SPOT`→`SPOT` + AWS spot arm (§2.5) | **L** | #459, #442 | ✅ **LANDED** — Hetzner-first migration proved the precedence; all five providers + Ember/Forge migrated. |
| W2 | **Per-role image** (§2.1/2.4): `RoleSubTable.image` (from #459) → `buildCloudProvisionSpec.applyImage` (own role, no cross-role fallback) → `resolve()` tier-1 | **M** | #459 | ✅ **LANDED.** |
| W3 | **Provider-agnostic persisted `SourceProfile`** (§3.1–3.3): atomic-at-bootstrap persistence, ssh-key/firewall selectors, cluster-scoped naming (no dual-accept), **delete 3B prefix fallback**; bumps `config_version` N | **L** | #444 | ⏳ pending; must precede any bootstrap firewall growth. |
| W4 | **Cleanup credential unification** (§3.4) + **timeout-path regression test** with non-default env-var name | **M** | #439 | ⏳ pending; money-leak path — build the test first. |
| W5 | **LocalStack AWS contract suite** (§4.2/4.3) for compute/LB/secrets/discovery (**spot excluded**) | **M** | new | ⏳ pending; AWS regression sensor before live creds. |
| W6 | **Document-level stored-format gate** (§3.5): **reuse the existing top-level `config_version`** (not a new field) + exact-match gate + Q1 named errors both directions (no ladder) | **S** | #434 | ✅ **checkpoint LANDED (working tree)** — `ClusterBootstrapConfigParser.parseConfigVersion:100-123`, Q1 named errors; W3 bumps N. |
| W7 | **Cloud e2e harness driver abstraction** (§5): Hetzner driver extract + AWS driver (VPC init, PG-on-EC2, ELBv2, reaper) | **L** | new | ⏳ pending; after W5. |
| W8 | **Live AWS e2e gate** (§4.2) + **live spot smoke** when creds land | **M** | new | ⏳ pending; final gate. |
| W9 | **Doc/catalog reconciliation**: `vm-snapshot.md` + catalog rows 4/204; spot wording; Hetzner-secrets wording; snapshot-matrix doc `aether-<ver>-<runtime>-<arch>` (§2.6) | **S** | — | ⏳ pending; additive. |
| W10 | **Spot loud-fail validation** (§2.5): unsupported spot sub-table fails validation; non-AWS providers reject `SPOT` at `createFrom` | **S** | new | ✅ **LANDED** (`ClusterBootstrapConfigValidator:264-266` + provider `createFrom_spotRequest_rejectedLoud` tests). |

**W9 catalog wording (code-verify before shipping):** spot →
*"opt-in per-role config, AWS (rc3); reclamation handled as node failure via
auto-heal; spot-aware placement planned, demand-gated"*; Hetzner secrets →
*"env-only; external secret managers integrate via env"*.

**New tickets referenced (being filed):** post-GA spot policy ticket (§2.5);
container multi-arch manifest check (§2.6).

## Alternatives considered

- **A1. Patch `image` per provider like #442 patched `server_type`.** Rejected:
  that *is* the defect class. #459 showed the trap (image for Hetzner only, via a
  second path). W1 paid the structural cost once.
- **A2. Keep provider-specific provisioning-state re-derivation (3B) as the
  general mechanism.** Rejected for the class; deleted by W3 (§3.1): Hetzner-only,
  prefix-guesses account-wide, cannot carry firewall refs or labels policy.
- **A3. Live AWS as the primary AWS gate from day one.** Rejected: LocalStack
  contract tests are the interim sensor; live e2e is one bounded run per release.
- **A4. Defer spot entirely to post-GA.** Rejected: the surface already existed
  and *silently lied*. W1 (market from role) + W10 (loud) shipped; only the
  *policy* layer is demand-gated post-GA.
- **A5. ~~Dual-accept old + new ssh-key names.~~ VOID** (Q1: no pre-rc3 clusters).
  §3.3 uses cluster-scoped names only.
- **A6. Account default VPC / per-run VPC.** Both rejected (Q4). §5 uses one
  dedicated persistent NAT-free VPC.
- **A7. Managed RDS for the AWS support DB.** Rejected (Q3). §5 uses PG-on-EC2
  per-run.
- **A8. Runtime-kind tag on the snapshot id.** Rejected (Q6). §2.4 uses per-role
  image resolution; §2.6 uses the `(instance_type, runtime, image)` triple.
- **A9. Remove the producers' `ON_DEMAND` literals (make §2.2 acceptance literal).**
  Rejected as-built: with `resolve()` deriving market from role at the boundary,
  the literals are inert; removing them is cosmetic churn across three producers
  and their tests for no behavior change. The boundary ruling (2.3) made removal
  unnecessary.
- **A10. A new `formatVersion` field distinct from `config_version`.** Rejected
  (W6): `config_version` was already required + exact-match-gated on the same
  persisted document; a second field is redundant dual-versioning. §3.5 reuses it.

## Migration

- **Config/parse format (W2/W3):** additive fields behind the **document-level
  `config_version` gate** (W6, §3.5). Absent or non-current → named error;
  operators **re-bootstrap** (no pre-rc3 production clusters). W2's per-role
  `image` reads the existing `RoleSubTable.image` (#459) at the current
  `config_version`, so no bump was needed for it; W3 bumps `config_version` when
  `SourceProfile` joins the format.
- **Providers (landed):** the `provision(ProvisionSpec)` override was replaced by
  `createFrom(ProvisionRequest)`; all five in-tree providers plus Ember/Forge
  test doubles migrated in W1 (Hetzner first). An SPI change within a BSL module —
  rebuild-together, no external contract.
- **Docs:** W9 corrects the false "no code change / passed through" claims, the
  spot wording, and the Hetzner-secrets wording; adds the snapshot-matrix doc.

## Owner rulings on open questions (2026-07-17)

- **Q1 — Persisted stored-format change. APPROVED, amended.** Document-level
  `config_version` (existing top-level field, reused; one ladder; `SourceProfile`
  = rung 1); absent/non-current → named error; unknown-newer → refuse loudly;
  step-runner ladder designed not built; W6 = reuse `config_version` + exact-match
  gate + named errors (landed, working tree). Cascade: §3.3 cluster-scoped names
  only (A5 void); §3.1 deletes 3B. → §3.1, §3.3, §3.5, W6, A5, A10, Migration.
- **Q2 — Spot. Option A + rc3 mechanism slice (LANDED W1-ii/W10).** market from
  role; AWS spot arm; non-AWS loud reject; validator loud-fail. Reclamation =
  node-failure via auto-heal, no preemption drain. Policy layer = post-GA ticket.
  → §2.5, §1, §4.2/4.3, W1, W10.
- **Q3 — AWS support DB. PG-on-EC2, per-run** (not RDS, not persistent). → §4.2,
  §5, A7.
- **Q4 — AWS networking. One dedicated persistent NAT-free VPC**; per-run
  instances + SGs only; VPC = reaper scope boundary. → §5, A6.
- **Q5 — Hetzner secrets stay env-only** (Partial cell + "integrate via env"
  wording); AWS/GCP/Azure Secrets already Complete. → §1, §4.4, W9.
- **Q6 — No runtime-kind tag.** Per-role image (W2, own role, no cross-role
  fallback); mixed-arch via the `(instance_type, runtime, image)` triple (§2.6)
  with named gaps. → §2.4, §2.6, W9, A8.

## References

### Internal — SPI & providers (as-built)
- `aether/environment-integration/…/ComputeProvider.java` (`:24` `createFrom`,
  `:45` `providerDefaults`, `:53-58` non-overridable `provision(spec)` boundary),
  `ProvisionRequest.java` (`:59` static `resolve`, `:68,144-148` market from role,
  `:77-85` loud no-instance-size (#442), `:96-122` image precedence + loud stock
  fallback (#459)), `ProviderDefaults.java`, `MarketOptions.java`,
  `ProvisionSpec.java` (`:17,33` imageId/withImage), `ProvisionContext.java`,
  `CloudProvider.java` (`:16` `provisionSpot`), `CloudProviderSupport.java`
  (`:81` inert `ON_DEMAND`), `CloudCredentials.java` (`:23-36`)
- `aether/environment/hetzner/…/HetznerComputeProvider.java` (`:53`
  `providerDefaults`, `:70` `createFrom`; 3B prefix fallback still present,
  deleted by W3), and `{aws,gcp,azure,docker}/…/*ComputeProvider.java`
  (`providerDefaults` + `createFrom`; AWS `createFrom` attaches spot
  `InstanceMarketOptions`), `*EnvironmentIntegration.java`, `*CloudProvider.java`,
  `*LoadBalancerProvider.java`, `*DiscoveryProvider.java`, `*SecretsProvider.java`
- Tests: `ProvisionRequestTest.java` (`:187` CTM-literal-inert),
  `SpotRoleConstantDriftTest.java`, `*ComputeProviderTest.createFrom_*` (per
  provider: resolved-fields carried; spot arm / loud reject)

### Internal — plumbing, config & lifecycle
- `aether/cli/…/cluster/BootstrapPhaseProvision.java` (`:430-461`
  `buildCloudProvisionSpec` — `:440` `roleImage`, `:456` inert `ON_DEMAND`, `:461`
  `applyImage`, `:464-467` per-role/no-cross-role doc),
  `ProviderResolver.java` (`:65-107` cleanup paths, `:146-178` `buildCloudConfig`),
  `BootstrapCleanup.java` (`:105-139` credential dual-path), `SourceCleanupHandle.java`
- `aether/aether-deployment/…/cluster/ClusterTopologyManagerRecord.java` (`:528`
  inert `ON_DEMAND`, CTM heal path), `NodeLifecycleManager.java` (`:60-69`
  `provisionNode` → `provider.provision(spec)` boundary)
- `aether/aether-config/…/cluster/ClusterBootstrapConfigParser.java` (`:31`
  `REQUIRED_CONFIG_VERSION`, `:100-123` `parseConfigVersion` W6 document-format
  gate + Q1 named errors), `RoleSubTable.java` (`:16` `image`, #459),
  `SourceProfile.java`, `ClusterBootstrapConfigValidator.java` (`:264-266` W10
  spot ground-truth, `:284` PF-14)

### Internal — harness & docs
- `aether/tests/cloud/{deploy-cloud.sh,run-cloud-tests.sh,teardown-cloud.sh}`
- `aether/docs/operator/vm-snapshot.md` (`:84-85,152-156` — false "no code change"
  claim, W9), `aether/docs/reference/feature-catalog.md` (rows 4, 178, 187, 200-204)

### Related RFCs
- RFC-0012 (Resource Provisioning), RFC-0013 (Deployment Provider),
  RFC-0015 (Cluster-Label Scoping — provider-portable label scheme this RFC's
  reaper reuses)
