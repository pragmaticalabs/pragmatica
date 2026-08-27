# Design Session Transcript — Unified Security Subsystem (2026-06-23)

| Field | Value |
|-------|-------|
| Stream | **design-stream** (clone of `../pragmatica`, wired as git remote `upstream`) |
| Branch | `release-1.0.0-rc2` (tracks `upstream/release-1.0.0-rc2`, HEAD `97dae9e2a`) |
| Session type | **Design** (no code produced — analysis + architecture only) |
| Status | Design **converged**; formal spec **pending** (see §8) |
| Scope | Credential & secret management → one unified security subsystem |
| Related issues | #139, #119, #269, #206, #253, #287, #209, #88, #307, #313, #319, #23 |
| Next artifact | `aether/docs/specs/security-subsystem-spec.md` |

> **What this document is.** A faithful, detailed record of the design-stream session that
> produced the unified security-subsystem architecture. It captures the journey (ticket
> discovery → code-grounded analysis → reframe → research → decisions → deep technical
> dialogue), the **reasoning** behind each decision, and — importantly — a clear separation
> between **user-mandated constraints** (hard requirements) and **design recommendations**
> (mine, open to revision). The forthcoming spec is the normative artifact; this is the "how
> we got here / why" companion so the implementing stream inherits full context, not just
> conclusions.

---

## ⚡ TL;DR

- **It is ONE subsystem, not two.** "Keys/certificates" and "secrets management" are two faces
  of one problem: *protecting confidential material across its lifecycle (issue → distribute →
  use → rotate → revoke → audit), gated by identity, under one root of trust.* A cert, a secret,
  and a data-encryption key are the same shape; they differ only in backend and lifecycle timing.
- **The keystone is identity, and the core move is push → pull.** Today Aether *pushes* material
  outward (literal cloud token baked onto every node; trust-anyone-who-knows-`cluster_secret`).
  Best-in-class gives each node a verifiable **identity** and lets it *pull* exactly what it is
  entitled to, scoped and short-lived. Identity is what makes least-privilege, per-node
  revocation, and rotation possible on *all* planes.
- **Decisive Aether-specific constraint:** **Hetzner provides no signed instance identity**
  (metadata endpoint is unsigned HTTP; no TPM/Secure Boot on cloud VMs). Since all clouds —
  including Hetzner — are first-class targets, the identity baseline must work with **zero
  platform attestation** (one-time join tokens / TOFU), with cloud-IID attestation as a
  *strengthening upgrade* where the platform signs identity (AWS/GCP/Azure).
- **Four decisions locked (user-mandated):** (1) all clouds + on-prem first-class; (2) **complete,
  end-to-end GA** — no staging/deferral; (3) **minimal native + federation** (only combo that
  covers on-prem); (4) **credential-less slices** — the slice gets capabilities + a `Principal`,
  never keys/secrets/certs.
- **Runtime ↔ slice boundary mechanism (user-proposed, refined here):** per-slice classloader +
  **sibling security classloader**, hardened by **JPMS strong encapsulation** (named, non-`opens`
  security module) — because classloaders stop *symbolic linking* but not *reflection*, and the
  `SecurityManager` sandbox is gone (JEP 411 → JEP 486). The real invariant is *"no
  credential-bearing object is reachable from the slice"*; the strongest form is a **stub handle
  holding an opaque id**, so there is nothing to reflect.
- **Principal propagation (user-proposed, endorsed):** `ScopedValue` (like request-ID), with two
  hard security requirements: the binding key lives **behind** the boundary (slice can read, never
  rebind/forge), and propagation across the async Promise runtime and across nodes is the
  runtime's job (async snapshot/rebind; cross-node **signed assertion + verify** via the identity
  plane).
- **Next step:** write the full spec across **five planes** (root-of-trust/identity, certs,
  keys, secrets, policy+audit) + the runtime/slice boundary + credential-less consumption model,
  with detailed "why" per decision, reconciled to code (§9).

---

## 0. Session setup — design-stream provisioning

This session also bootstrapped the design-stream itself:

- **Cloned `../pragmatica` in place** (the empty target dir had a harness-created
  `.claude/settings.local.json`, so `git clone` refused — used `git init` + `git remote add
  upstream ../pragmatica` + `fetch` + checkout instead, which also named the remote `upstream`
  directly). Branch `release-1.0.0-rc2` tracks `upstream/release-1.0.0-rc2`.
- **Remote topology:** only `upstream` → `/Users/.../pragmatica`. No shared `origin` by design,
  to prevent accidental pushes of design WIP to the shared GitHub repo. Sync via
  `git fetch upstream && git rebase upstream/release-1.0.0-rc2`.
- **Config copied** (untracked in upstream, so not carried by clone): `.claude/skills/aether-coder`,
  `.claude/commands`, `cclsp.json`, `settings.local.json`. Agents are global (`~/.claude/agents`),
  already inherited.
- **`CLAUDE.md` adapted** for the design stream (added Design-Workstream framing + sync
  instructions; kept all project invariants verbatim). Both `CLAUDE.md` and `.claude/` are
  gitignored, as in upstream.

**Stream purpose:** design-heavy work here so the main stream stays focused on implementation
and testing. This security subsystem is the first such design effort.

---

## 1. Objective (and how it evolved)

1. *"find ticket related to secure credential management"* → surfaced the credential/secret cluster.
2. *"analyze all tickets to understand where we are and what needs to be done"* → ticket
   archaeology + code-grounded reality check.
3. *"compose everything in a single consistent and secure subsystem … look at industry best
   practices … how existing parts fit into an ideal best-in-class subsystem"* → the reframe +
   research + architecture.
4. Four design decisions resolved via Q&A, then a deep technical dialogue on the two hardest
   pieces (the runtime/slice boundary, and Principal propagation).
5. → produce this transcript, then the spec.

---

## 2. Phase 1 — Ticket discovery

Searched `pragmaticalabs/pragmatica` issues (`gh`, repo passed explicitly — the clone's remote is
a local path so `gh` can't auto-resolve it). The **credential & secret management cluster**:

- **Open:** #119 (HashiCorp Vault provider), #206 (runtime cloud-credential resolution), #253
  (storage encryption key management), #269 (slice-level `${secrets:}` not resolved), #307
  (DigitalOcean has spec, no module).
- **Closed / foundational:** #139 (pluggable `SecretsProvider` SPI), #88 (cloud cert adapters),
  #287 (cluster_secret at rest), #209 (cluster_secret-derived CA / TLS hardening).
- **Adjacent trust-model:** #290 (mgmt plane open by default), #285/#288 (TrustAll / symmetric
  trust — closed NOT_PLANNED as deliberate design), #313 (single-trust-domain doc), #319
  (SECURITY.md). #23 (audit trail, closed).

---

## 3. Phase 2 — Analysis: tickets vs. code reality

Ran three parallel investigations (one ticket-archaeology agent + two code-reality agents against
the clone). **Method note (per CLAUDE.md "tickets are hypotheses, not specs"): every ticket claim
was verified against code.** Several were stale. Condensed state map:

| Capability | Reality (verified) | Evidence |
|---|---|---|
| `SecretsProvider` SPI | **Built.** `Promise<String> resolveSecret(path)` + metadata/batch/`watchRotation`. **8** impls: Env, File, Composite, Caching, AWS-SM, GCP-SM, Azure-KV. Missing: Vault, k8s, local-encrypted. | `aether/environment-integration/.../SecretsProvider.java:17` |
| Node-side `${secrets:}` (node.toml) | **Built, SPI-backed.** | `SecretResolvingConfigurationProvider.java:27`; wired `AetherNode.java:4283-4288` via `ConfigurationProvider.withSecretResolution` |
| CLI bootstrap `${secrets:}` | **Partial — env-only in practice.** Resolver is pluggable but the only prod call site passes no provider → `Option.none()`. SPI fallback reachable only in tests. | `ConfigReferenceResolver.java`; `ClusterBootstrapCommand.java:120` |
| Slice `resources.toml` `${secrets:}` | **Missing (#269 CONFIRMED).** Slice config composed raw; placeholders stay literal. A slice's DB password ships as the string `${secrets:foo}`. Ticket's `AetherNode` line numbers were stale (real eager resolution ~4283-4288, not 3663-3667); slice-gap diagnosis correct. | `SliceStore.java:271-337` (`loadSliceIntrinsicProviderFromClassLoader`), `:236-256` (`assembleSliceComposite`), record `:160-167` (no secrets field) |
| Cloud-credential model | **Built-but-risky (#206 CONFIRMED).** Literal cloud API token fanned out into **every** node's `aether.toml` by design (any node may become leader → call provision). Any node compromise leaks a full-access token; Hetzner tokens are unscoped. Leader-gating is structural (`active.get()` reconciler guard), no explicit `isLeader` at the call site. | `BootstrapOverlayGenerator.java:125-134` (`[cloud.credentials] api_token`); `cluster-bootstrap-spec.md:268`; `ClusterTopologyManagerRecord.provisionReplacement:482-531` |
| Storage at-rest encryption | **Worse than #253 says.** `BlockEncryptor.aesGcm(key,keyId)` exists but production storage runs **unencrypted** — `Option<BlockEncryptor>` defaults to `none()`. No key source, no rotation, only test keys. | `BlockEncryptor.java:51`; `AetherNode.java:2537` (`none()`) |
| `cluster_secret` at rest | **Partial (#287).** chmod-600 via `SecureFiles` shipped, but compose path still injects `AETHER_CLUSTER_SECRET` as an **env var** (visible in `docker inspect`). | `SecureFiles.java:24-54`; `DockerComposeGenerator.java:80` |
| `cluster_secret`→CA derivation | **Claimed built (#209).** `ClusterTrust` derives CA from cluster_secret; item 1 of 5 confirmed; items 2–5 (curl -k, http:// hardcodes, VIEWER default-role) unverified. | `ClusterTrust`, commit `6293b206c` |
| Cloud cert adapters (#88) | **Unverified.** Closed with bare "Implemented in rc1", no commit, no per-adapter confirmation. Highest-uncertainty closure. | — |
| DigitalOcean (#307) | **Missing.** Spec only; `CloudCredentials.fromEnvironment` `default -> operationNotSupported`. Implemented: AWS/Azure/GCP/Hetzner/Docker. | `CloudCredentials.java:34`; `CloudProviderName.java` |

**Verdict that drove the rest of the session:** the gap is **not missing pieces** — most parts exist.
The gap is a **missing identity layer**. Every weakness (#206 fan-out, #287 env exposure, #269
literal placeholders, shared-secret full-trust) is a symptom of *"material is pushed/shared because
there is no identity to pull with."* Build the identity spine and these stop being separate tickets
and become consequences of the model.

---

## 4. Phase 3 — The reframe + industry research

### 4.1 The reframe

- **One subsystem, organized as planes:** Root-of-Trust → Identity → {Secrets | Keys | Certs} →
  Policy+Audit (cross-cutting). The three "material" planes share one architecture: an issuer, an
  identity allowed to obtain, a lifecycle, an audit trail.
- **The inversion:** push → pull. Identity-first (SPIFFE/SPIRE thesis). Without identity you can do
  least-privilege/revocation/rotation on *nothing*.

### 4.2 Research distilled (current industry practice, grounded — see §10 for sources)

- **Workload identity:** SPIFFE/SPIRE — per-workload SVID (X.509/JWT), short-lived, auto-rotated;
  mTLS everywhere; trust domains. Beats shared secrets because trust is per-identity and revocable.
- **Secret zero / "bottom turtle":** a fresh node needs one credential to get the rest. Solutions:
  platform attestation (cloud IID / TPM) where available; else **one-time join tokens** (SPIRE
  `join_token` TTL 600s, single-use; Consul `auto_config`; kubeadm bootstrap tokens) +
  **response-wrapping** for tamper-evident delivery.
- **DECISIVE FINDING — Hetzner has no signed instance identity.** Metadata at
  `169.254.169.254/hetzner/v1/metadata` is plain unsigned HTTP; cloud VMs have no TPM/Secure Boot.
  AWS/GCP/Azure all sign instance identity; Hetzner does not. ⇒ baseline must be join-token TOFU;
  cloud-IID is an upgrade.
- **Secrets — pull not push:** workload pulls JIT using its identity; never bake into env/argv/world-
  readable files (OWASP). Vault ideas to emulate: **dynamic secrets + leases/TTL**, **prefix
  revocation**, **policy least-privilege**, **response wrapping**, **audit devices**. Self-contained
  posture: minimal native broker + **federate** to Vault/cloud-SM via the existing SPI.
- **Cloud creds, worst→best:** literal fan-out (today) ✗ → leader-only scoped token → short-lived
  STS/assumed-role → **instance-profile / workload-identity (no static token at all)**. On Hetzner
  (no instance roles, unscoped tokens) best available is leader-only, JIT, never fanned out.
- **Keys — envelope encryption:** root → KEK → DEK (AWS/GCP KMS model). Never bulk-encrypt with the
  root. `keyId` selects the wrapping key + version; KEK rotation re-wraps the small DEK (cheap, no
  data re-encryption); keep old KEK versions for read-back. Crypto-shredding = destroy the key.
  `KeyProvider` SPI ≈ K8s **KMS v2** gRPC contract / AWS Encryption SDK keyring (local-file *or*
  cloud KMS behind one interface).
- **HKDF (RFC 5869 / NIST SP 800-108):** deriving many keys from one `cluster_secret` is **sound iff**
  each derivation uses a distinct `info`/label. **Limit:** no per-key revocation; rotating the root
  rotates *everything*. ⇒ data uses generated DEKs wrapped by a KEK, **not** keys derived directly
  from the root.
- **AEAD at scale (NIST SP 800-38D):** random 96-bit AES-GCM nonces cap near **2³² messages/key**
  before collision/forgery risk; a single reuse is catastrophic. ⇒ for fan-out scale use
  **AES-GCM-SIV (RFC 8452)** or **XChaCha20-Poly1305** (nonce-misuse-resistant), or strict per-DEK
  counters. And don't co-locate the KEK with the ciphertext or at-rest encryption is theater.
- **Rotation cadence (NIST SP 800-57):** DEK OUP <2yr, KEK <2yr, master/derivation ~1yr; KMS defaults
  365d (AWS) / 90d (GCP example).
- **PQC:** AES-256 at-rest is already quantum-safe (Grover only halves strength). Only *asymmetric*
  CA/handshake would need ML-KEM/ML-DSA (FIPS 203/204, finalized 2024-08-13), and only for data that
  must stay secret >10yr ⇒ make the CA swappable; not urgent.

---

## 5. Phase 4 — Design decisions (Q&A)

Four forks were put to the user. **These answers are hard requirements** for the spec.

### Decision 1 — Deployment targets: **ALL clouds first-class (+ on-prem)**
> User: *"ALL clouds will be targeted. Hetzner is just our cheapest test environment, but it also
> might be attractive to Aether users."*

**Implication:** `NodeAttestor` SPI needs real cloud-IID attestors (AWS/GCP/Azure) **and** a
production-grade one-time-join-token path as the universal baseline. Baseline must work with **zero**
platform attestation (Hetzner/on-prem). Hetzner being a real *production* target (not just test)
promotes join-token TOFU from "fallback" to first-class & hardened.

### Decision 2 — GA scope: **complete, end-to-end, no deferral**
> User: *"No rush to ship GA ASAP. But high desire to ship a high-quality product. So GA must
> contain full implementation, end-to-end."*

**Implication:** the identity plane ships *in* GA. No "design ideal, build subset." The spec
describes the **full target**; risk-first sequencing governs *order*, but the finish line is the
whole subsystem, **reconciled** (CLAUDE.md: `MISSING = STUB = SHORTCUT = OMISSION = SIMPLIFICATION = 0`).
*(This rejected my earlier staging recommendation — noted as superseded.)*

### Decision 3 — Native vs. federate: **minimal native + federation**
> User: *"Different deployment options, including on-prem … minimal native + federation is the only
> combination that covers all cases."*

**Implication:** every plane = native default impl (works air-gapped / on-prem) + federation SPI
(defer to Vault / cloud-KMS / cloud-SM when present). On-prem makes this **mandatory**, not optional.

### Decision 4 — Consumption & isolation: **credential-less slices**
> User: *"Cluster consumes it, but there are also user-provided secrets like DB credentials and
> user-managed secrets like management API RBAC and application-level RBAC. Everything should be
> covered. Clean separation: slice developer never sees and never has access to
> keys/security/certs/etc. At most it might get a `Principal` as part of the request, but there is
> no way to access credentials used by it to access a resource."*

**Identified pattern:** this is **capability injection / the credential-less workload** (Dapr
sidecar, Spring/Jakarta container-managed `DataSource`, cloud execution roles, SPIFFE Principal).
Endorsed as best-in-class for a platform. The slice receives **capabilities** (connected handles) +
**identity** (`Principal`), never **credentials**.

**Issues raised against Decision 4 (stress-test, since the user asked):**
1. **API boundary vs security boundary** — "never has access" is a *security* claim; only true if
   slice and key material are in different trust zones (→ resolved in §6).
2. **All authenticated egress must be runtime-mediated** — else the first un-adapted downstream
   leaks a credential. (Resolved by Decision/answer #2 in §6: runtime provisions *everything*.)
3. **On-behalf-of (OBO) delegation** — calling downstream *as* the end-user Principal needs runtime
   token-exchange (RFC 8693); slice still never sees the token. (Deferred — §6.)
4. **Principal propagation must be runtime-owned & unforgeable** — slice must not set/elevate its own
   Principal. (Resolved — §6.)
5. **Rotation under a live handle** — injected handle must be a managed pool that transparently
   re-authenticates on rotation (a *plus* of the capability model).
6. **Diagnosability tax** — runtime must surface *non-secret* failure diagnostics or slice devs are
   blind.
7. **Two RBAC domains, one engine** — management-plane RBAC (operators) ≠ application RBAC
   (end-users); share the Principal model + policy engine, keep scopes separate, or privileges bleed.
8. **The secrets *write*/management surface is itself privileged** — same authn/authz/audit rigor as
   the read path.

---

## 6. Phase 5 — Deep technical dialogue (the two hardest pieces)

### 6.1 Runtime ↔ slice boundary

**User proposal:** slices are user-hosted, single security domain, *not* malicious; the only real
risk is third-party libraries (*"almost entirely eliminated"*). Each slice runs in its **own
classloader inherited from the node classloader**; put security modules in a **sibling classloader**
so security classes are inaccessible to the slice but accessible to the node. Runtime provisions all
resources; the slice loads/accesses nothing itself.

**Assessment (mine) — right direction, two refinements:**

1. **In-process isolation is *proportionate*** for this threat model — do **not** escalate to
   per-slice processes (IPC/latency tax not worth it when the app trusts itself). Sibling-CL shape is
   correct.
2. **Classloaders stop *symbolic linking*, not *reflection*.** A sibling security CL means the slice
   can't `import`/`Class.forName` security classes — but the moment any security-loaded object is
   *reachable* (passed in, returned by a handle, reachable via a field), the slice can
   `getClass().getDeclaredFields()` + `setAccessible(true)` and read the credential.
3. **The actual fence is JPMS strong encapsulation, not the classloader.** Put security code in its
   own **named module** that `opens` nothing → cross-module `setAccessible(true)` throws
   `InaccessibleObjectException` (default since JDK 16, JEP 396). This matters *more* than usual
   because the **`SecurityManager` sandbox is gone** (JEP 411 deprecated; **JEP 486 permanently
   disabled** it in JDK 24) — the module system is the only language-level boundary left. *(Slices
   need not be modules; only the security code must be a named, non-`opens` module for encapsulation
   to bite.)*

**The invariant that actually matters:** *not* "slice can't name security classes" but **"no
credential-bearing object is reachable from the slice, and the security module opens nothing to it."**
Consequences:
- Slice CL is a *child* of the node CL ⇒ it sees everything the node CL can load by delegation. So
  credential-bearing classes live **only** in the sibling security CL (never loadable by the node CL),
  and node↔security calls go through **capability interfaces with no secret accessors** (a
  `DataSource.getPassword()` defeats everything — API hygiene is a precondition).
- **Strongest form:** the slice's handle is a **stub holding an opaque id** (`long`), not the
  credential. The real credential + authenticated client live behind the boundary; the node resolves
  the id on its side. Then there is *nothing to reflect*, and the secret's safety doesn't even depend
  on JPMS. Falls straight out of "runtime provisions everything."
- **Also a leak class:** `toString()` / serialization / logs. Redacting `toString()` on every
  credential type + exclusion from any slice-reachable serialization/error payload. (This is the
  earlier **R5** "logs config values at INFO" finding, generalized — `SliceStore.java:255-259`.)

**Documented assumption (must be written, not unstated):** against *actively malicious bytecode* this
is **not** a hard sandbox — `Unsafe`, JNI, deserialization gadgets, or any `opens` granted are
escapes. The user's trust model (single security domain, app trusts itself) puts that out of scope.
Ties to #313.

### 6.2 Principal propagation

**User proposal:** propagate `Principal` via **Scoped Values** (like request-ID); open to alternatives.

**Assessment (mine) — correct primitive, endorsed.** `ScopedValue` beats `ThreadLocal` (immutable, no
leak/cleanup, cheap on virtual threads, auto-inherited by `StructuredTaskScope` forks). No better
carrier to offer. Two **hard security requirements** (not ergonomics):

1. **Binding authority lives behind the boundary.** `ScopedValue.where(PRINCIPAL, x).run(...)` is
   callable by anyone holding the key. If the slice can see the `PRINCIPAL` key, it rebinds to a
   forged admin Principal and elevates. ⇒ the ScopedValue **key is one of the §6.1 "security
   classes"** — it lives in the security module; only the runtime binds it; the slice reads via a
   secret-free accessor (`SecurityContext.currentPrincipal()` → immutable value), never the key. *(This
   is the consistency check that §6.1 and §6.2 are the **same** boundary.)*
2. **Propagation across async + nodes is the runtime's job, not the carrier's.** `ScopedValue` covers
   a synchronous dynamic scope + `StructuredTaskScope` forks, but **not** a task handed to an
   unrelated executor or a Promise continuation resuming on another carrier thread. Aether is
   Promise-heavy ⇒ the async plumbing must **snapshot the security context at submission and rebind at
   execution** (same hook request-ID needs — *verify it actually survives the Promise hops*; that's
   where context carriers silently break). Cross-node: the Principal travels as a **signed assertion**
   (short-lived, bound to the call / under mTLS), **verified against the identity plane on the
   receiving node**, then re-bound locally — never trusted raw off the wire (issue #4 from §5). This is
   where in-process ScopedValue hands off to the cross-process identity plane.

`Principal` = immutable value object carrying everything an authz decision needs (id, roles/claims,
auth method, expiry) so downstream never re-fetches — JBCT parse-don't-validate.

**OBO/delegation (Decision-3 follow-up):** user has no case yet, *"but better be prepared."* ⇒ don't
build it now (nothing speculative); keep the **seam** — because every resource call already runs
inside the Principal context, adding "call downstream *as* this Principal via RFC 8693 token exchange"
later is additive, not a redesign.

---

## 7. Converged design (current state of truth)

### 7.1 The plane stack + two trust zones

```
   ┌───────────────────────────────────────────────────────────────┐
   │  POLICY (who may obtain what)  +  AUDIT (every access logged)      │  cross-cutting
   ├──────────────────┬──────────────────┬─────────────────────────┤
   │  SECRETS plane   │   KEYS plane     │   CERTS plane            │  "protected material"
   │  (broker / pull) │ (KeyProvider /   │  (cluster CA + per-node  │
   │                  │  envelope enc.)  │   identity certs)        │
   ├──────────────────┴──────────────────┴─────────────────────────┤
   │  IDENTITY — every node/workload has its own identity (SVID-equiv)  │  the foundation
   ├───────────────────────────────────────────────────────────────┤
   │  ROOT OF TRUST — cluster_secret (bootstrap anchor) → NodeAttestor   │  the bottom turtle
   └───────────────────────────────────────────────────────────────┘

   RUNTIME trust zone (holds ALL material) ── boundary (CL + JPMS) ──> SLICE zone
                                                                       (capabilities + Principal,
                                                                        NEVER credentials)
```

### 7.2 SPI inventory (the seams — all functional, `Promise`-returning, JBCT idiom)

| SPI | Role | Native default | Federation impls |
|---|---|---|---|
| `NodeAttestor` | verify a joining node's identity evidence | one-time join token (TOFU) | AWS/GCP/Azure signed IID; TPM quote |
| `IdentityIssuer` / cluster CA | issue short-lived per-node SVID-equiv certs | CA derived from `cluster_secret` | (external CA optional) |
| `CertificateProvider` (#88) | *external-facing* certs (mgmt API/ingress) | self-signed | ACM / GCP CM / Azure KV |
| `KeyProvider` | envelope wrap/unwrap/generate-DEK/current-keyId | `cluster_secret`-derived KEK | Vault transit; AWS/GCP/Azure KMS |
| `SecretsProvider` (#139, exists) | resolve/lease/revoke secrets | Env/File + native broker | AWS-SM/GCP-SM/Azure-KV/**Vault (#119)** |
| `PolicyEngine` | decide: Principal × Action × Resource | built-in RBAC | (external PDP optional) |
| `AuditSink` | record every material access | append-only local | SIEM/export |

### 7.3 How existing parts map (keep / evolve / replace)

- **Keep, demote:** `cluster_secret` → bootstrap anchor only (seed CA + authenticate first join);
  stop being a runtime authorizer.
- **Keep, constrained:** HKDF derivation (distinct `info` labels; document the no-revocation /
  root-rotation-cascades limit) — use it for the KEK seed, **not** for data DEKs.
- **Keep, promote:** `ClusterTrust` / CA-from-`cluster_secret` (#209) → issuer of per-node identities.
- **Keep, complete:** `SecretsProvider` SPI (#139) — add lease/revoke; unify resolution to run
  **everywhere** (CLI bootstrap, node.toml, slice `resources.toml`) ⇒ fixes #269 + the CLI env-only gap.
- **Keep as DEK layer, wire it:** `BlockEncryptor` — feed from `KeyProvider`; switch AEAD to
  nonce-misuse-resistant; fixes #253's `none()`-in-prod.
- **Replace:** cloud-token literal fan-out (#206) → identity-based JIT (AWS/GCP/Azure instance
  identity; Hetzner leader-only JIT scoped).
- **Finish:** #287 remaining compose-env vector; #307 DO module or clean error.
- **Produced by the spec:** #313 trust-domain doc, #319 SECURITY.md, #23 audit revival.

### 7.4 Locked vs. recommended (so the implementer knows what's negotiable)

- **Locked (user-mandated):** all clouds + on-prem; complete GA (no deferral); native + federate;
  credential-less slices; in-process classloader isolation; ScopedValue for Principal.
- **Recommended (mine, open to revision):** JPMS named-module hardening + stub/opaque-handle pattern;
  AES-GCM-SIV/XChaCha20 over AES-GCM; envelope KEK/DEK split; demoting `cluster_secret`; per-node
  SVID-style identity; two-RBAC-domains-one-engine; cloud-credential per-provider strategy.

---

## 8. Open questions & next steps

**Next artifact:** `aether/docs/specs/security-subsystem-spec.md` — full target across the five
planes + runtime/slice boundary + credential-less consumption, with detailed "why" per decision,
reconciled to the code anchors in §9. (Optionally a `#206` cloud-credential sub-spec, as that ticket
explicitly requests `docs/specs/cloud-credential-handling-spec.md`.)

**Still hand-wavy / to resolve in/with the spec:**
- **Principal async/cross-node propagation mechanics** — exact snapshot/rebind hook in the Promise
  runtime; the signed-assertion format and verification path. (The one piece flagged as not yet
  concrete.)
- **Intermediate CA** — does the cluster CA need an intermediate, or is a single root acceptable?
- **Native secrets broker storage** — where the built-in broker persists (encrypted via the keys
  plane → bootstrapping order with the keys plane needs care).
- **Policy language/model** — claims vs roles; how policy is authored and distributed.
- **Verification debt to hand to the main stream:** confirm #88 cloud cert adapters actually shipped;
  confirm #209 items 2–5; close the #287 compose-env vector.

**Build sequencing (risk-first, but all in GA):** (0) quick wins — slice `${secrets:}` unify (#269) +
R5 redaction + #287 compose-env + #307 clean error; (1) this spec + #206 sub-spec; (2) identity plane
(per-node identity from CA + mTLS + `NodeAttestor`); (3) keys plane (`KeyProvider` + envelope + wire
`BlockEncryptor` + AEAD-SIV); (4) secrets plane (unified pull + lease/revoke + cloud-token redesign +
federation); (5) hardening (policy, audit, SECURITY.md/#319, trust-domain doc/#313).

---

## 9. Code anchor map (reconciliation seeds for the spec & implementation)

| Area | Anchor |
|---|---|
| Secrets SPI | `aether/environment-integration/.../SecretsProvider.java:17` (+ 7 providers in same pkg) |
| Node-side resolution | `SecretResolvingConfigurationProvider.java:27`; wired `AetherNode.java:4283-4288` |
| CLI resolution (env-only gap) | `cli/.../ConfigReferenceResolver.java`; `ClusterBootstrapCommand.java:120` |
| Slice gap (#269) | `SliceStore.java:271-337`, `:236-256`, record `:160-167`; log-leak `:255-259` |
| Cloud token fan-out (#206) | `BootstrapOverlayGenerator.java:125-134`; `ClusterTopologyManagerRecord.provisionReplacement:482-531`; `HetznerEnvironmentIntegrationFactory.java:46,63` |
| Storage encryption (#253) | `integrations/storage/.../BlockEncryptor.java:51`; `AetherNode.java:2537` (`none()`) |
| Gossip key (separate, HKDF) | `swim/.../AesGcmGossipEncryptor`; `SelfSignedCertificateProvider.java:258-267` (salt `aether-ca-seed`), rotation `:152-157` |
| cluster_secret at rest (#287) | `SecureFiles.java:24-54`; `DockerComposeGenerator.java:80` |
| CA / trust (#209) | `ClusterTrust` (commit `6293b206c`) |
| DigitalOcean (#307) | `CloudCredentials.java:34`; `CloudProviderName.java`; `aether/docs/specs/cloud-provider-digitalocean.md` (Planned) |
| Envelope-version invariant | `ManifestGenerator.ENVELOPE_FORMAT_VERSION` (bump if slice-processor codegen changes) |

---

## 10. References (research sources, verified)

- **Workload identity:** SPIFFE/SPIRE concepts — https://spiffe.io/docs/latest/spire-about/spire-concepts/ ·
  join-token — https://spiffe.io/docs/latest/deploying/spire_server/
- **Secret zero / bootstrap:** Vault response wrapping — https://developer.hashicorp.com/vault/docs/concepts/response-wrapping ·
  AppRole — https://developer.hashicorp.com/vault/docs/auth/approle ·
  kubeadm bootstrap tokens — https://kubernetes.io/docs/reference/access-authn-authz/bootstrap-tokens/
- **Secrets management:** Vault leases — https://developer.hashicorp.com/vault/docs/concepts/lease ·
  OWASP Secrets Management Cheat Sheet — https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html
- **Cloud creds:** AWS beyond access keys — https://aws.amazon.com/blogs/security/beyond-iam-access-keys-modern-authentication-approaches-for-aws/
- **Envelope encryption / KMS:** GCP — https://cloud.google.com/kms/docs/envelope-encryption ·
  AWS GenerateDataKey — https://docs.aws.amazon.com/kms/latest/APIReference/API_GenerateDataKey.html ·
  Vault transit — https://developer.hashicorp.com/vault/docs/secrets/transit ·
  K8s KMS v2 — https://kubernetes.io/docs/tasks/administer-cluster/kms-provider/
- **Rotation:** AWS key rotation — https://docs.aws.amazon.com/kms/latest/developerguide/rotate-keys.html ·
  GCP key rotation — https://cloud.google.com/kms/docs/key-rotation
- **Standards:** HKDF RFC 5869 — https://datatracker.ietf.org/doc/html/rfc5869 ·
  NIST SP 800-108r1 (key separation) — https://nvlpubs.nist.gov/nistpubs/SpecialPublications/NIST.SP.800-108r1-upd1.pdf ·
  NIST SP 800-57 Pt1 r5 (cryptoperiods) — https://csrc.nist.gov/pubs/sp/800/57/pt1/r5/final ·
  NIST SP 800-38D (GCM/nonce) — https://csrc.nist.gov/pubs/sp/800/38/d/final ·
  AES-GCM-SIV RFC 8452 — https://datatracker.ietf.org/doc/html/rfc8452 ·
  XChaCha20-Poly1305 — https://doc.libsodium.org/secret-key_cryptography/aead/chacha20-poly1305/xchacha20-poly1305_construction
- **PQC:** NIST FIPS 203/204/205 finalized 2024-08-13 — https://csrc.nist.gov/news/2024/postquantum-cryptography-fips-approved
- **JDK platform:** JEP 396 (strong encapsulation default) · JEP 411 (SecurityManager deprecation) ·
  JEP 486 (SecurityManager permanently disabled, JDK 24) · `ScopedValue` (JEP 506, final) ·
  `StructuredTaskScope`
- **Delegation (future):** OAuth 2.0 Token Exchange RFC 8693 — https://datatracker.ietf.org/doc/html/rfc8693
- **Attestation (future):** RATS RFC 9334 — https://datatracker.ietf.org/doc/html/rfc9334

---

*End of transcript. Normative design follows in `security-subsystem-spec.md` (pending).*
