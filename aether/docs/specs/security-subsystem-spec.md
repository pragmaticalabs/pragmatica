# Aether Unified Security Subsystem Specification

**Version:** 0.1
**Status:** Draft
**Date:** 2026-06-23
**Author:** security design-stream
**Related issues:** #139, #119, #269, #206, #253, #287, #209, #88, #307, #313, #319, #23
**Companion documents:** `aether/docs/.internal/progress/security-subsystem-spec-plan.md` (work order),
`aether/docs/.internal/progress/session-transcript-2026-06-23-security-subsystem-design.md` (rationale/"why"),
`aether/docs/specs/rbac-spec.md` (request-time RBAC), `aether/docs/specs/cloud-integration-spi-spec.md` (cloud SPIs)

---

## Table of Contents

1. [Overview & Goals](#1-overview--goals)
2. [Architecture Overview](#2-architecture-overview)
3. [Plane 0 — Root of Trust & Identity](#3-plane-0--root-of-trust--identity)
4. [Plane 1 — Certificates / PKI](#4-plane-1--certificates--pki)
5. [Plane 2 — Keys / Encryption](#5-plane-2--keys--encryption)
6. [Plane 3 — Secrets Management](#6-plane-3--secrets-management)
7. [Plane 4 — Cloud Credential Handling (#206)](#7-plane-4--cloud-credential-handling-206)
8. [Runtime ↔ Slice Boundary & Credential-less Consumption](#8-runtime--slice-boundary--credential-less-consumption)
9. [Policy & Audit (cross-cutting)](#9-policy--audit-cross-cutting)
10. [Configuration Model](#10-configuration-model)
11. [Error Model](#11-error-model)
12. [Implementation Plan](#12-implementation-plan)
13. [Reconciliation to Existing Code](#13-reconciliation-to-existing-code)
14. [Open Questions](#14-open-questions)
15. [References](#15-references)

---

## 1. Overview & Goals

### 1.1 Purpose

This specification defines Aether's **unified security subsystem**: a single, coherent
architecture for protecting confidential material — certificates, encryption keys, and secrets —
across its entire lifecycle (issue → distribute → use → rotate → revoke → audit), gated by
**identity**, under one **root of trust**.

### 1.2 The unifying thesis

Keys/certificates and secrets are **not two subsystems but one**. A certificate, a secret, and a
data-encryption key are the same shape: confidential material that must be issued to an authorized
identity, delivered safely, used without leaking, rotated, revoked, and audited. They differ only
in backend and lifecycle timing. Treating them as one subsystem removes duplicated trust
plumbing and a class of inconsistency bugs (today there are three divergent secret resolvers;
storage encryption is wired off; a cloud token is copied onto every node).

**The core architectural move is push → pull.** Today Aether *pushes* material outward — a literal
cloud API token is baked into every node's config (#206); membership trusts anyone who knows the
symmetric `cluster_secret`. Best-in-class practice gives each node a verifiable **identity** and lets
it *pull* exactly what it is entitled to — scoped and short-lived. Identity is the keystone: without
it, least-privilege, per-node revocation, and rotation are impossible on every plane.

### 1.3 Goals

- **G-1 — One subsystem, five planes.** Root-of-trust/identity, certificates, keys, secrets, and
  policy+audit organized as cooperating planes with a shared SPI shape (issuer, authorized identity,
  lifecycle, audit).
- **G-2 — Identity-first.** Every node/workload has its own short-lived, revocable identity. All
  node↔node traffic is mutually authenticated against it.
- **G-3 — Native + federate on every plane (Decision-3).** Each plane ships a native default that
  works air-gapped/on-prem, plus a federation SPI that defers to Vault / cloud-KMS / cloud-SM when
  present.
- **G-4 — Credential-less slices (Decision-4).** A slice receives **capabilities** (connected
  resource handles) and an identity (`Principal`), **never** keys/secrets/certs.
- **G-5 — Complete, end-to-end GA (Decision-2).** The spec describes the *full* target. Risk-first
  sequencing governs *order*, not scope: `MISSING = STUB = SHORTCUT = OMISSION = SIMPLIFICATION = 0`.
- **G-6 — All clouds + on-prem first-class (Decision-1).** AWS/GCP/Azure/Hetzner + bare-metal. The
  identity baseline must work with **zero** platform attestation; cloud-IID attestation is an upgrade.
- **G-7 — JBCT throughout.** `Promise<T>`/`Result<T>`/`Option<T>`, records, `sealed interface`,
  `Cause`, parse-don't-validate at every boundary.

### 1.4 Non-Goals

- A hard sandbox against actively malicious in-process bytecode (see §1.5 — explicitly out of scope).
- On-behalf-of / delegation token exchange (RFC 8693) — seam preserved, not built (§8.7, §14).
- Post-quantum cryptography *implementation* now — the CA algorithm is made swappable, PQC deferred
  (§4.5; full inventory + posture §5.7).
- Multi-region trust federation across distinct trust domains — single trust domain per application
  (#313).

### 1.5 Threat model & trust assumptions (load-bearing — read before any plane)

The entire design rests on these assumptions. They are stated explicitly because several design
decisions are only correct *given* them.

- **Single application / single security domain (#313).** Slices belong to one application and are
  **not mutually hostile**; the application trusts itself. There is no multi-tenant adversary *inside*
  a cluster.
- **The realistic in-domain threat is buggy or compromised third-party libraries.** In this stack
  that surface is largely (not entirely) eliminated, but it is the threat the runtime/slice boundary
  is built to contain.
- **The runtime/slice boundary is an accident- and blast-radius boundary**, hardened against
  reflection and casual access — **NOT a hard sandbox against actively malicious bytecode.** `Unsafe`,
  JNI, deserialization gadgets, and any `opens` directive granted to a slice are acknowledged escapes
  and are **out of scope**. This is restated where it matters (§8.8).

  > **Why state this in writing.** An unstated "slices can't access credentials" claim invites
  > someone to treat it as a security guarantee against malicious code, which it is not. Naming the
  > boundary honestly is what lets the rest of the design choose *proportionate* mechanisms
  > (in-process isolation, JPMS, opaque handles) instead of over-engineering per-slice processes.

- **Cross-node trust is a real adversary boundary (the network).** Material crossing nodes must be
  signed and verified; nothing is trusted raw off the wire. This is why identity assertions and
  cross-node `Principal` propagation are signed and verified against the identity plane (§8.6).

### 1.6 Design principles

- **Identity-first** — every actor has a verifiable identity before it can obtain anything.
- **Least privilege** — scope and TTL on everything; no standing full-access credential.
- **Native + federate** — air-gapped default, external backend when present, behind one SPI.
- **Capability injection** — the runtime provisions resources; the slice loads/holds nothing.
- **Pull, not push** — material is pulled JIT by an identity, not pushed onto nodes.
- **Observability/audit** — every material access is recorded; append-only.
- **Parse, don't validate (JBCT)** — every boundary parses raw input into a typed value object once.

### 1.7 Ticket → section map

| Ticket | Topic | Addressed in | Status (see §13) |
|---|---|---|---|
| #139 | Pluggable `SecretsProvider` SPI | §6 | DONE (extended) |
| #119 | HashiCorp Vault provider | §6.4 | MISSING (federation target) |
| #269 | Slice-level `${secrets:}` not resolved | §6.5 | STUB → fix |
| #206 | Runtime cloud-credential resolution | §7 | STUB/risky → redesign |
| #253 | Storage encryption key management | §5 | STUB → wire |
| #287 | `cluster_secret` at rest | §6.6, §10 | PARTIAL → close compose vector |
| #209 | `cluster_secret`-derived CA / TLS hardening | §3, §4 | PARTIAL (CA done; residual hardening → Phase 5) |
| #88 | Cloud certificate adapters | §4 | **STUB (CA-echo; real issuance unbuilt)** |
| #307 | DigitalOcean provider | §7.4 | MISSING (clean error today) |
| #313 | Single-trust-domain documentation | §1.5, §8 | produced here |
| #319 | SECURITY.md | §9.4, §12 | Phase-5 deliverable (skeleton + pointer) |
| #23 | Audit trail | §9.3 | revived |

---

## 2. Architecture Overview

### 2.1 The plane stack & two trust zones

```
   ┌───────────────────────────────────────────────────────────────┐
   │  POLICY (who may obtain what)  +  AUDIT (every access logged)   │  cross-cutting
   ├──────────────────┬──────────────────┬─────────────────────────┤
   │  SECRETS plane   │   KEYS plane     │   CERTS plane            │  "protected material"
   │  (broker / pull) │ (KeyProvider /   │  (cluster CA + per-node  │
   │                  │  envelope enc.)  │   identity certs; #88)   │
   ├──────────────────┴──────────────────┴─────────────────────────┤
   │  IDENTITY — every node/workload has its own identity (SVID-eq.) │  the foundation
   ├───────────────────────────────────────────────────────────────┤
   │  ROOT OF TRUST — cluster_secret (bootstrap anchor) → NodeAttestor│  the bottom turtle
   └───────────────────────────────────────────────────────────────┘

   RUNTIME trust zone (holds ALL material) ── boundary (CL + JPMS) ──> SLICE zone
                                                                       (capabilities + Principal,
                                                                        NEVER credentials)
```

**Two trust zones.** The **runtime** holds *all* material (keys, secrets, private keys, the
`ScopedValue` binding key). The **slice** receives only capabilities (connected handles) and a
`Principal`. The boundary between them is a classloader topology hardened by JPMS strong
encapsulation, with the strongest form being an opaque-id handle that holds nothing to reflect (§8).

### 2.2 SPI inventory

| SPI | Role | Native default | Federation impls | New/Existing |
|---|---|---|---|---|
| `NodeAttestor` | verify a joining node's identity evidence | one-time join token (TOFU) | AWS/GCP/Azure signed IID; TPM quote | **New** |
| `IdentityIssuer` | issue short-lived per-node SVID-equiv certs (cluster CA) | CA derived from `cluster_secret` | external CA (optional) | **New** (wraps existing CA) |
| `CertificateProvider` | node mTLS certs + gossip keys; external-facing certs (#88) | `SelfSignedCertificateProvider` | `Aws/Gcp/Azure CertificateProvider` | **Existing** (reuse) |
| `KeyProvider` | envelope wrap/unwrap/generate-DEK/current-keyId | `cluster_secret`-derived KEK | Vault transit; AWS/GCP/Azure KMS | **New** |
| `SecretsProvider` | resolve/lease/revoke secrets | Env/File + native broker | AWS-SM/GCP-SM/Azure-KV/**Vault (#119)** | **Existing** (extend) |
| `CloudCredentialResolver` | obtain cloud provisioning creds | leader-JIT broker | instance-identity / STS / WIF | **New** |
| `PolicyEngine` | decide Principal × Action × Resource | built-in RBAC (existing `AuthorizationRole`) | external PDP (optional) | **New** (over existing RBAC) |
| `AuditSink` | record every material access | append-only local (+ hash-chain) | SIEM export | **New** |
| slice `SecurityContext` accessor | secret-free `currentPrincipal()` | reads the `ScopedValue` | — | **New** |

> **Reconciliation note (verified).** A `CertificateProvider` SPI already exists at
> `integrations/net/tcp/src/main/java/org/pragmatica/net/tcp/security/CertificateProvider.java`
> with `SelfSignedCertificateProvider` (native) and `Aws/Gcp/Azure CertificateProvider`
> federation impls, plus a `CertificateRenewalScheduler`. The cert plane **reuses and extends**
> this rather than introducing a parallel SPI (see §4, §13). A request-time RBAC model also exists
> (`Principal`, `SecurityContext`, `Role`, `AuthorizationRole` in
> `aether/http-handler-api/.../security/`); §8–§9 build on it, they do not replace it.

### 2.3 End-to-end request flow

```
inbound request
  → runtime authenticates caller (mTLS cert OR API key OR external IdP)   [identity plane]
  → runtime constructs/loads the Principal + SecurityContext
  → runtime binds the Principal into the ScopedValue (binding key behind the boundary)  [§8.6]
  → slice handler runs; reads SecurityContext.currentPrincipal() (immutable value)
  → slice uses an INJECTED resource handle (e.g. a DataSource stub holding an opaque id)
  → runtime resolves the opaque id → real authenticated client → performs the downstream call
  → PolicyEngine.evaluate(principal, action, resource) gates the call                  [§9.1]
  → AuditSink.record(event) logs the access (append-only)                              [§9.3]
```

The slice never sees a credential at any step; it sees a `Principal` value and capability handles.

### 2.4 Bootstrapping order (chicken-and-egg)

See §3.5 and §6.7. Summary: `cluster_secret` seeds the CA *and* the KEK; the CA must exist before a
node can get identity; the KeyProvider must exist before the native secrets broker can encrypt its
store; secrets resolution must exist before cloud-credential JIT. The native path must stand up with
zero external dependencies (air-gapped); federation backends slot in after identity exists.

---

## 3. Plane 0 — Root of Trust & Identity

### 3.1 `cluster_secret` demotion

> **Decision.** Demote `cluster_secret` from a *runtime authorizer* to a **bootstrap anchor only**:
> it seeds the cluster CA and the KEK, and authenticates the *first* join handshake. It is never
> consulted to authorize a running node's actions.
>
> **Why.** A symmetric shared secret grants full membership to anyone who holds it (#288/#285,
> closed NOT_PLANNED as deliberate-but-acknowledged), cannot revoke a single node, and is the
> blast-radius root. Demoting it removes the standing liability while keeping the one thing it is
> good for: a "bottom turtle" on infrastructure with no platform identity (Hetzner — verified to
> provide no signed instance identity). Today `cluster_secret` already seeds the CA
> (`SelfSignedCertificateProvider.java:64`, salt `aether-ca-seed`), so this is a *demotion of role*,
> not a removal.
>
> **Rejected alternative.** *Keep it as runtime trust* — the status quo and the exact #288 problem
> (trust-anyone-who-knows-the-secret, no revocation). *Replace it entirely* — impossible on Hetzner
> and bare-metal, which have no signed IID to bootstrap from; there must be a bottom turtle.

### 3.2 Three-tier identity model

| Tier | What | Lifetime | Verified anchor / target |
|---|---|---|---|
| 0 — Bootstrap anchor | `cluster_secret` (HKDF seeds CA + KEK) | install-time | `ClusterTrust`, `SelfSignedCertificateProvider.java:64,148-157` |
| 1 — Per-node identity | own keypair + short-lived X.509 SVID-equivalent issued by cluster CA, carrying node-id + role + trust-domain (SPIFFE-style URI SAN) | short (hours) | target — new `IdentityIssuer` over existing CA |
| 2 — Attestation (upgrade) | platform-signed evidence strengthens the join | per-join | new `NodeAttestor` |

> **Decision.** Every node gets a **per-node identity** (Tier 1): its own keypair plus a short-lived
> certificate issued by the cluster CA. All node↔node mTLS uses these certs, not `cluster_secret`.
>
> **Why.** Per-node identity is the *precondition* for the two properties `cluster_secret` cannot
> provide: **per-node revocation** (revoke one cert, not the whole cluster) and **least privilege**
> (the cert carries role + trust-domain, so authz can differ per node). Short TTL bounds the
> exposure of any leaked key without a revocation-distribution mechanism. This is the SPIFFE/SPIRE
> SVID thesis (§15).
>
> **Rejected alternative.** *Shared `cluster_secret` for node auth* (status quo) — no revocation, no
> per-node scoping. *Long-lived per-node certs* — removes the TTL safety net and forces a CRL/OCSP
> dependency (see §3.6).

### 3.3 `NodeAttestor` SPI

```java
// issuer side (control plane / leader)
sealed interface AttestationEvidence
        permits JoinTokenEvidence, CloudIidEvidence, TpmQuoteEvidence {}

record JoinTokenEvidence(String token) implements AttestationEvidence {}
record CloudIidEvidence(CloudProviderName provider, byte[] signedDocument) implements AttestationEvidence {}
record TpmQuoteEvidence(byte[] quote, byte[] pcrs) implements AttestationEvidence {}

record AttestedClaims(String nodeId, String role, String trustDomain, Instant attestedAt) {}

interface NodeAttestor {
    Promise<AttestedClaims> attest(AttestationEvidence evidence);
}
```

Per-provider attestation strategy:

| Provider | Mechanism | Native/Federation |
|---|---|---|
| Baseline (all) | one-time join token, TOFU | native |
| AWS | EC2 instance identity document + IMDSv2 | federation upgrade |
| GCP | instance identity token (signed JWT) | federation upgrade |
| Azure | attested metadata document | federation upgrade |
| **Hetzner** | **join-token only — no signed IID** | native (no upgrade) |
| Bare-metal w/ TPM | TPM quote | federation upgrade |

> **Decision.** The **baseline `NodeAttestor` is the one-time join token (TOFU)**; cloud-IID and TPM
> attestors are strengthening upgrades selected by config.
>
> **Why.** Decision-1 makes Hetzner and bare-metal first-class, and it was **verified this session**
> that Hetzner's metadata endpoint (`169.254.169.254/hetzner/v1/metadata`) is plain unsigned HTTP
> with no TPM/Secure Boot on cloud VMs — so there is *no* platform attestation to rely on. The only
> mechanism that works everywhere is a join token. Where the platform *does* sign identity
> (AWS/GCP/Azure), attestation removes the TOFU window and is offered as an upgrade.
>
> **Rejected alternative.** *Require cloud-IID attestation* — excludes Hetzner/on-prem, violating
> Decision-1. *Join token as a permanent fallback only* — under-hardens what is, per Decision-1, a
> first-class production path.

### 3.4 Bootstrap / secret-zero flow

```
1. Operator mints a join token: short TTL (~600s, per SPIRE join_token), single-use.
2. Token delivered RESPONSE-WRAPPED (tamper-evident; per Vault response-wrapping).
3. Joining node presents evidence → NodeAttestor.attest(...) → AttestedClaims.
4. Node generates a keypair LOCALLY (private key never leaves the node).
5. Node submits a CSR → IdentityIssuer.issue(csr, claims) → short-lived SVID.
6. Node now has identity; all further traffic is mTLS with that cert.
```

> **Decision.** The node's private key is generated locally and never transmitted; only a CSR
> crosses the wire; the CA returns a signed certificate.
>
> **Why.** This is the standard PKI property: a private key that never leaves its host cannot be
> intercepted in transit or at the issuer. Response-wrapping the join token makes single-use
> tamper-evident — a token opened in transit is detectably burned. (Cites SPIRE join_token, Vault
> response-wrapping, kubeadm bootstrap tokens — §15.)
>
> **Rejected alternative.** *CA generates and ships the keypair* — puts the private key on the wire
> and in the CA's memory, defeating the purpose. *Plain (unwrapped) join token* — no tamper evidence.

### 3.5 HKDF usage & limits

> **Decision.** Keep HKDF (RFC 5869 / NIST SP 800-108) to derive operational seeds from
> `cluster_secret`, each with a **distinct `info` label** — but derive **only the CA seed and the KEK
> seed** this way, never data DEKs.
>
> **Why.** Key separation by distinct `info` labels is sound (NIST SP 800-108). The existing code
> already does this correctly: `SelfSignedCertificateProvider.java:64-65` uses salt `aether-ca-seed`
> and info `aether-ca-key-v1`. The **limit** is that HKDF gives no per-key revocation and rotating
> the root rotates *everything* derived from it. Therefore data is encrypted with *generated* DEKs
> wrapped by a KEK (§5), not with keys derived directly from the root.
>
> **Rejected alternative.** *Derive data DEKs directly from `cluster_secret`* — every data key then
> depends on the root; rotating the root forces re-encrypting all data, and there is no per-DEK
> revocation. *Random unrelated seeds with no labels* — risks cross-purpose key reuse.

### 3.6 Revocation model

> **Decision.** Revoke a node by revoking its certificate, relying primarily on **short TTL + frequent
> re-issue** rather than CRL/OCSP distribution. A revocation *signal* (deny-list pushed over the
> existing gossip/control plane) is the fast-path; expiry is the backstop.
>
> **Why.** Short-lived certs bound the exposure window to the TTL even with no active revocation
> channel — the simplest correct mechanism for a cluster that already has a control plane to push a
> small deny-list. CRL/OCSP adds a distribution and availability dependency for a marginal latency
> gain over a short TTL.
>
> **Rejected alternative.** *CRL/OCSP as the primary mechanism* — heavier, adds an availability
> dependency (OCSP responder) and stale-CRL windows. Kept as an *open question* for very-long-TTL
> external certs (§14, Q5).

---

## 4. Plane 1 — Certificates / PKI

### 4.1 Reuse the existing `CertificateProvider` (verified)

> **Decision.** Build the certificate plane on the **existing** `CertificateProvider` SPI
> (`integrations/net/tcp/.../security/CertificateProvider.java`), not a new parallel interface. The
> new `IdentityIssuer` (§3) is a thin issuer-side wrapper that produces SVID-style identity certs;
> the cert *material* type stays `CertificateBundle`.
>
> **Why.** The SPI already exists: `SelfSignedCertificateProvider` (native, CA-from-`cluster_secret`,
> the one genuine per-node issuer), the `Aws/Gcp/Azure CertificateProvider` federation impls (present
> with tests, but currently **CA-echo stubs** — see §4.4), a `CloudCertificateProvider` orchestrator,
> and a `CertificateRenewalScheduler` (renews at ~40% of remaining validity, exponential backoff).
> CLAUDE.md invariant "extend, don't replace" and the sibling `cloud-integration-spi-spec.md` both
> mandate reuse: keep the SPI shape and the native issuer, and replace the cloud impls' CA-echo bodies
> with real issuance (§4.4). Inventing a parallel SPI would duplicate the working native path.
>
> **Rejected alternative.** *A fresh `Promise`-returning `CertificateProvider`* — the existing one is
> synchronous (`Result<CertificateBundle> issueCertificate(...)`). Introducing an async twin would
> fork the implementations and the renewal scheduler. If async issuance is needed for a remote CA,
> wrap the call in a `Promise` at the call site rather than changing the SPI.

Existing surface (verified):

```java
public interface CertificateProvider {                          // org.pragmatica.net.tcp.security
    Result<CertificateBundle> issueCertificate(String nodeId, String hostname);
    Result<CertificateBundle> caCertificate();
    Result<GossipKey>        currentGossipKey();
    Option<GossipKey>        previousGossipKey();
    default Option<GossipKey> nextGossipKey() { return Option.none(); }  // #256 rollover overlap
}

record CertificateBundle(byte[] certificatePem, byte[] privateKeyPem,
                         byte[] caCertificatePem, Instant notAfter) {}
```

### 4.2 `IdentityIssuer` (issuer-side wrapper)

```java
record Csr(byte[] csrPem) {}
record SpiffeId(String trustDomain, String path) {}        // URI SAN: spiffe://<domain>/<path>
record NodeIdentity(CertificateBundle bundle, SpiffeId id, Instant notAfter) {}

interface IdentityIssuer {
    Result<NodeIdentity> issue(Csr csr, AttestedClaims claims);   // wraps CertificateProvider + CA
    Result<NodeIdentity> renew(NodeIdentity current);
}
```

The native `IdentityIssuer` issues against the same CA `SelfSignedCertificateProvider` derives from
`cluster_secret`; it adds the SPIFFE-style URI SAN (node-id + role + trust-domain) onto the cert.

### 4.3 Internal vs external-facing certs

> **Decision.** Split **internal identity certs** (cluster CA, node↔node mTLS) from
> **external-facing certs** (management API / ingress, #88). Internal trust chains to the cluster's
> own root; external certs may use public/managed CAs (ACM / GCP Certificate Manager / Azure Key
> Vault).
>
> **Why.** Internal mTLS must trust *only* the cluster's own root (so no public CA can mint a cert
> that impersonates a node), which is exactly what `ClusterTrust` does today (trust store contains
> only the derived CA). External ingress/mgmt endpoints are consumed by clients (browsers, operators)
> that expect publicly-trusted certs, so those want managed/public CAs.
>
> **Rejected alternative.** *One CA for both* — either forces external clients to trust the private
> cluster root, or forces internal mTLS to trust a public CA (widening the set of certs that can
> impersonate a node).

### 4.4 #88 — cloud cert adapters are CA-echo stubs, NOT issuers (verified correction)

> **Verified correction (supersedes an earlier optimistic reading).** Adapter *classes* exist
> (`AwsCertificateProvider:35-37` and `GcpCertificateProvider:34-36` delegate to
> `CloudCertificateProvider`; `AzureCertificateProvider:73-78` inlines the same), but they do **NOT**
> issue per-node certificates and call **no** cloud certificate API. `CloudCertificateProvider.issueCertificate(nodeId, hostname)`
> (`:70-75`) **returns the pre-seeded CA certificate verbatim** as the leaf and **ignores `nodeId`/`hostname`**;
> the Azure variant fetches the CA material from the Key Vault *secrets* API, not its certificate API.
> `AwsClient` (`:43`) exposes only `getSecretValue` (`:78`) — there is **no ACM** method anywhere in the
> tree. The only genuine per-node issuance is `SelfSignedCertificateProvider` (`:106-187` — real BCST
> keypair, `CN=nodeId`, SAN from hostname), which is *not* a cloud adapter. `CertificateRenewalScheduler`
> (`:36`) is real, but with a cloud provider it merely re-stamps a fresh `notAfter` on the same CA cert.
>
> **Therefore #88 is STUB, not DONE** (§13): the SPI and a CA-distribution path exist, but real
> cloud-issued or per-node external certs are **unbuilt**. The work is to implement genuine issuance
> (ACM `RequestCertificate` / GCP Certificate Manager / Azure Key Vault *certificates*), **or** to
> explicitly scope external-facing certs to the self-signed cluster CA plus a documented BYO-cert path.

### 4.5 Rotation & PQC posture

Short TTL + auto-rotation is handled by `CertificateRenewalScheduler` (renew at ~40% of remaining
validity; NIST SP 800-57 cadence). Gossip keys rotate daily with previous/next-day overlap (#256,
verified in `AesGcmGossipEncryptor` / `SwimGossipEncryptors`).

> **Decision.** Make the CA/handshake algorithm **swappable**; do **not** implement PQC now.
>
> **Why.** AES-256 at-rest is already quantum-resistant (Grover only halves effective strength).
> Only the *asymmetric* CA/handshake would need ML-KEM/ML-DSA (FIPS 203/204, finalized 2024-08-13),
> and only for material that must stay confidential >10 years. A swappable algorithm preserves the
> upgrade path without paying immature-tooling cost now.
>
> **Rejected alternative.** *Implement PQC now* — premature: tooling/library maturity and interop
> are still settling, and there is no >10-yr-secret requirement on the table. *Ignore PQC entirely* —
> leaves no migration seam; rejected in favor of swappability.
>
> The full algorithm inventory and the symmetric-vs-asymmetric posture are consolidated in **§5.7**.

---

## 5. Plane 2 — Keys / Encryption

### 5.1 Envelope hierarchy

> **Decision.** Use envelope encryption: **root → KEK → per-tier/stream DEK**. Bulk data is encrypted
> with a generated DEK; the DEK is wrapped by a KEK; the KEK is derived from / held by the root.
>
> **Why.** Never bulk-encrypt with the root (rotating it would force re-encrypting all data). KEK
> rotation re-wraps the *small* DEK — cheap, no data re-encryption — while old KEK versions are kept
> for read-back. Destroying a key is **crypto-shredding** (the data becomes unrecoverable without
> re-touching it). This is the AWS/GCP KMS and NIST SP 800-57 model.
>
> **Rejected alternative.** *Single key for all data* — rotation re-encrypts everything; one key
> compromise loses everything. *Per-object keys derived from the root via HKDF* — no per-key
> revocation, root rotation cascades (§3.5).

### 5.2 `KeyProvider` SPI

```java
record KeyId(String logical, int version) {}
record WrappedKey(KeyId kekId, byte[] ciphertext) {}
record DataKey(byte[] plaintext, WrappedKey wrapped) {}

interface KeyProvider {                              // modeled on K8s KMS v2 / AWS Encryption SDK keyring
    Promise<DataKey>    generateDataKey(KeyId kekId);   // returns plaintext DEK + wrapped DEK
    Promise<byte[]>     unwrap(WrappedKey wrapped);     // KEK unwraps DEK
    Promise<WrappedKey> wrap(byte[] dek, KeyId kekId);
    KeyId               currentKekId();
}
```

| Impl | Native/Federation | Backed by |
|---|---|---|
| `ClusterSecretKeyProvider` | native default | KEK derived from `cluster_secret` (HKDF, distinct `info`) |
| `VaultTransitKeyProvider` | federation | Vault transit engine |
| `Aws/Gcp/Azure KmsKeyProvider` | federation | cloud KMS |

> **Decision.** Model `KeyProvider` on **K8s KMS v2 / AWS Encryption SDK keyring** — one contract
> covering cluster-local file and external KMS identically.
>
> **Why.** It is a proven abstraction that hides whether wrapping happens locally or in an external
> KMS, which is exactly what Decision-3 (native + federate) needs. `generateDataKey` returning both
> the plaintext and wrapped DEK mirrors AWS `GenerateDataKey` and lets the sink discard the plaintext
> after use.
>
> **Rejected alternative.** *Expose raw key bytes to callers* — defeats the federation case (an
> external KMS never releases the KEK) and the credential-less invariant. *Separate native vs
> federation interfaces* — forces call sites to branch on backend.

### 5.3 Wire `BlockEncryptor` to `KeyProvider` (stream/segment path — #253 did NOT close this; see update)

> **2026-09-04 update — #253 shipped a narrower mechanism than this section specifies; the gap named
> below is UNCHANGED and still open.** #253 resolved as: `EncryptingStorageTier` wraps the generic
> `StorageTier` framework (`LocalDiskTier`/`DhtStorageTier`, used by the `artifacts`/`streams`
> `StorageInstance`s created via `StorageFactory.createAll`/`createOne`), keyed by a static
> `EncryptionKeyring` resolved once at boot from `${secrets:<path>}` references through the existing
> `SecretsProvider` SPI (`StorageEncryption.resolveKeyring`) — no `KeyProvider`, no `generateDataKey`,
> no per-segment `WrappedKey` in a header, no KEK/DEK wrapping tier. This satisfies the owner ruling
> that #253 ride the existing secrets SPI (Vault/#119 deferred) and gives boot-time keyring resolution,
> versioned+authenticated per-block headers (AES-GCM AAD), and loud boot/read failure on any
> unresolvable or legacy-plaintext block — but it does **not** touch the specific gap this section
> targets: `SegmentReader.java:43` and the `AetherNode.java:2537` call site still construct with
> `none()`, so the stream/segment payload pipeline (distinct from the generic storage-tier block path
> #253 wraps) remains **unencrypted, exactly as described below**. Key rotation here also has no
> re-encryption-on-rotate step (old keys stay resolvable for reads) rather than this section's rewrap-
> without-rewrite model. See `CHANGELOG.md` (#253 entry, 2026-09-04) and
> [`configuration.md`](../reference/configuration.md#storage-encryption-configuration-253) for what
> actually shipped; treat the `KeyProvider`/`WrappedKey` design below as still-open future work, not
> superseded.
>
> **Decision.** Keep `BlockEncryptor` as the DEK/AEAD layer and feed it from `KeyProvider`. At
> segment/tier creation the storage sink calls `generateDataKey`, stores the `WrappedKey` in the
> segment header, and encrypts with the plaintext DEK; on read it `unwrap`s via the header. `keyId`
> carries `(logical, version)`.
>
> **Why (verified gap).** `BlockEncryptor.aesGcm(key, keyId)` exists
> (`integrations/storage/.../BlockEncryptor.java:51`), but production storage runs **unencrypted**: the
> stream path defaults to an **empty `Option<BlockEncryptor>`** — `SegmentReader.java:43` constructs the
> reader with `none()`, and `AetherNode.java:2537` is the 2-arg call site that takes that default — so
> segments are written/read in the clear, with no key source or rotation (only test keys). (The separate
> no-op constant `BlockEncryptor.NONE` at `BlockEncryptor.java:48` is a *different* mechanism and is not
> what the prod path uses.) The infrastructure is present; the missing piece is a key source, which is
> exactly what `KeyProvider` supplies. Storing the `WrappedKey` in the segment header (not co-located key
> *material*) means KEK rotation re-wraps without rewriting data.
>
> **Rejected alternative.** *Leave `none()` in prod* — at-rest encryption absent (the #253 reality).
> *Derive the DEK from `cluster_secret` per segment* — no per-segment revocation, root rotation
> cascades (§3.5).

### 5.4 AEAD choice

> **Decision.** Use a **nonce-misuse-resistant AEAD** for tier/stream DEKs: **AES-GCM-SIV (RFC 8452)**
> or **XChaCha20-Poly1305**.
>
> **Why (verified).** The current `AesGcmBlockEncryptor` uses plain AES-GCM with a **random 12-byte
> IV** (`AesGcmBlockEncryptor.java:19-21`). NIST SP 800-38D caps random 96-bit nonces at **2³²
> invocations per key** — a *birthday* bound driven by nonce-**collision** probability (≈2⁻³³ at 2³²
> messages). The collision is what breaks GCM: two messages under the same (key, nonce) leak the GHASH
> authentication key and enable forgery, so the collision bound — not plaintext length — is the real
> limit, and a *single* reuse is catastrophic. A fan-out cluster writing many segments per key
> approaches that scale; a nonce-misuse-resistant AEAD (where reuse reveals at most plaintext equality,
> never the key) degrades gracefully instead of failing catastrophically.
>
> **Rejected alternative.** *Plain AES-GCM with random nonces* (status quo) — unsafe at cluster scale.
> *Strict per-DEK nonce counters* — workable but fragile across restarts/replicas (a counter reset
> reuses a nonce); the misuse-resistant AEAD removes that footgun.

### 5.5 Rotation, crypto-shredding, KEK placement

- **Cadence (NIST SP 800-57):** DEK < 2yr, KEK < 2yr, root/derivation ~1yr; align defaults with KMS
  conventions (AWS 365d, GCP 90d example).
- **Crypto-shredding:** destroy the wrapping key ⇒ wrapped DEKs become unrecoverable.

> **Decision.** The KEK is **never co-located with the ciphertext it protects**. The native default
> KEK is `cluster_secret`-derived; federation uses Vault transit / cloud KMS.
>
> **Why.** If the KEK lives next to the data it wraps, an attacker who reads the storage also reads
> the key — at-rest encryption becomes theater. Keeping the KEK in a separate trust domain (derived
> in-process from `cluster_secret`, or held in an external KMS) is what gives the encryption value.
>
> **Rejected alternative.** *Store the KEK in the segment header* — co-location; defeats the purpose.
> (Only the *wrapped* DEK goes in the header.)

### 5.6 Worked example — storage segment round-trip

```
WRITE (StorageSegmentSink, new segment):
  DataKey dk = keyProvider.generateDataKey(keyProvider.currentKekId()).await();
  header.wrappedKey = dk.wrapped();                       // (kekId, ciphertext) → segment header
  blockEncryptor = BlockEncryptor.aesGcmSiv(dk.plaintext(), dk.wrapped().kekId().toString());
  writeEncrypted(blockEncryptor, payload);               // plaintext DEK discarded after segment seal

READ (SegmentReader):
  byte[] dek = keyProvider.unwrap(header.wrappedKey).await();   // KEK (current or old version) unwraps
  blockEncryptor = BlockEncryptor.aesGcmSiv(dek, header.wrappedKey.kekId().toString());
  return decrypt(blockEncryptor, ciphertext);
```

### 5.7 Cryptographic algorithm inventory & quantum-resistance posture

Consolidated reference for every cryptographic algorithm the subsystem relies on, with the
post-quantum (PQC) posture for each. Complements the CA-algorithm decision in §4.5.

| Use | Algorithm (verified) | Class | Anchor | Quantum status |
|---|---|---|---|---|
| Storage at-rest | AES-256-GCM, random 96-bit IV (prod defaults to clear today, §5.3/#253) | symmetric AEAD | `AesGcmBlockEncryptor.java:19-21` | ✅ resistant |
| Gossip / SWIM transport | AES-256-GCM, daily-rotated | symmetric AEAD | `swim/.../AesGcmGossipEncryptor.java:25` | ✅ resistant |
| Key derivation (CA + KEK seeds) | HKDF-HMAC-SHA-2 (RFC 5869) | symmetric / hash | `SelfSignedCertificateProvider.java:64`; §3.5, §5.1 | ✅ resistant |
| CA / node-cert signatures | **ECDSA — `SHA256withECDSA`, EC keys** | **asymmetric** | `SelfSignedCertificateProvider.java:61,273` | ⚠️ exposed |
| mTLS handshake | EC-based key exchange (negotiated by the TLS stack) | **asymmetric** | `ClusterTrust`; the EC certs above | ⚠️ exposed |

**Why this split is the whole story:**

- **Symmetric primitives are already quantum-resistant.** Grover's algorithm yields only a quadratic
  speedup, halving effective strength: AES-256 → ~128-bit post-quantum security; HMAC-SHA-2 is
  similarly unaffected at these sizes. So **at-rest, gossip, and all key derivation need no PQC
  migration.** (The §5.4 move to a nonce-misuse-resistant AEAD is orthogonal — AES-GCM-SIV /
  XChaCha20-Poly1305 are equally quantum-safe; that change addresses nonce reuse, not quantum.)
- **Only the asymmetric layer is exposed.** ECDSA signatures and EC(DH) key agreement fall to Shor's
  algorithm on a cryptographically-relevant quantum computer (CRQC) — affecting exactly the **mTLS
  handshake** and **certificate signatures**, nothing else.

> **Decision.** Treat **symmetric crypto as PQC-complete today**; keep the **asymmetric CA/handshake
> algorithm swappable** (§4.1, §4.5) and migrate it to NIST PQC — **ML-KEM (FIPS 203)** for key
> establishment and **ML-DSA (FIPS 204)** for signatures, in a **hybrid** construction (e.g.
> X25519+ML-KEM) — **only when** the JDK/TLS + certificate tooling ship interoperable support, **or** a
> concrete >10-year in-transit-confidentiality requirement appears. Do **not** implement PQC now.
>
> **Why.** The only realistic near-term quantum threat here is **harvest-now-decrypt-later (HNDL)**: an
> adversary recording mTLS traffic today to decrypt once a CRQC exists. That matters only for data that
> must stay confidential past the CRQC horizon (commonly modeled >10 years); ephemeral intra-cluster
> control/data traffic does not meet that bar, and the data with genuine long-lived confidentiality —
> at rest — is already AES-256-protected. So there is no material exposure worth paying immature-tooling
> and interop cost for today, and the swappable-algorithm seam (one `IdentityIssuer`/`CertificateProvider`
> SPI, §4.1) keeps the eventual migration a contained change, not a rewrite. Hybrid KEX ensures a flaw
> in the young PQC primitive cannot regress classical security in the interim.
>
> **Rejected alternative.** *Migrate to PQC now* — premature: no >10-yr in-transit requirement on the
> table and PQC library/interop maturity still settling; shipping a less-battle-tested primitive as the
> sole defense is its own risk. *Ignore PQC entirely* — leaves no migration seam and exposes long-lived
> HNDL data later; rejected in favor of swappability + symmetric-already-safe.

---

## 6. Plane 3 — Secrets Management

### 6.1 Unified resolution pipeline

> **Decision.** One `${secrets:}` resolution engine, used at **all three** layers: CLI bootstrap,
> `node.toml`, and slice `resources.toml`.
>
> **Why (verified).** Today there are three divergent paths: node-side resolution is SPI-backed and
> works (`AetherNode.java:4283-4288` via `ConfigurationProvider.withSecretResolution`); CLI bootstrap
> is **env-var-only in practice** (`ClusterBootstrapCommand.java:120` calls the 1-arg
> `ConfigReferenceResolver.resolveAll` → `Option.none()`, so SPI providers are unreachable in prod);
> and the **slice layer is not resolved at all** (#269 — `SliceStore` never wraps the slice config in
> a resolver). One pipeline removes the inconsistency and the silent-literal footgun (a slice's DB
> password shipping as the literal string `${secrets:foo}`).
>
> **Rejected alternative.** *Three separate resolvers* (status quo) — divergent behavior, two
> already-broken paths, and a third (slice) missing.

### 6.2 Pull, not push

> **Decision.** Slices and nodes resolve secrets **at use-time** via the provider chain, authenticated
> by node identity — never baked into env/argv/world-readable files.
>
> **Why.** OWASP Secrets Management lists secrets-in-env/argv/world-readable-files as anti-patterns
> (visible via `docker inspect`, `/proc`, process listing). Pull-at-use-time scopes exposure and
> shrinks blast radius. This also fixes the #287 residual (§6.6).
>
> **Rejected alternative.** *Push secrets into the environment at launch* — `DockerComposeGenerator.java:80`
> used to bake `AETHER_CLUSTER_SECRET` as a literal env var into the generated compose file; deleted
> 2026-09-04 and replaced by `DockerComposeTemplate.java:84`'s `${AETHER_CLUSTER_SECRET:?...}`
> shell-substitution reference (#684), so the generated file itself carries no secret value. The
> resolved container env is still visible via `docker inspect` post-`docker compose up` — env-var
> delivery's inherent residual, distinct from the literal-in-file defect #684 closed, and still open
> both on this path and on the live bootstrap/redeploy `docker run -e`/SSH-inline-JVM-env paths in
> `BootstrapPhaseDeploy.java`.

### 6.3 `SecretsProvider` extension

The existing SPI is kept verbatim (back-compat) and extended with optional lease/revoke defaults.

```java
public interface SecretsProvider {                          // org.pragmatica.aether.environment
    Promise<String> resolveSecret(String secretPath);                              // existing
    default Promise<SecretValue> resolveSecretWithMetadata(String secretPath) {…}   // existing
    default Promise<Map<String,String>> resolveSecrets(List<String> paths) {…}      // existing
    default Promise<Unit> watchRotation(String path, SecretRotationCallback cb) {…} // existing

    // --- NEW (dynamic secrets; default = unsupported so the 7 static impls stay valid) ---
    default Promise<Lease> lease(String path)        { return Promise.failure(SecretsError.leasingUnsupported()); }
    default Promise<Unit>  renew(LeaseId id)         { return Promise.failure(SecretsError.leasingUnsupported()); }
    default Promise<Unit>  revoke(LeaseId id)        { return Promise.failure(SecretsError.leasingUnsupported()); }
    default Promise<Unit>  revokePrefix(String prefix){ return Promise.failure(SecretsError.leasingUnsupported()); }
}
record Lease(LeaseId id, String value, Duration ttl, Instant expiry) {}
record LeaseId(String value) {}
```

| Impl | Native/Federation | State (verified) |
|---|---|---|
| Env, File, Composite, Caching | native | DONE — the 4 local impls |
| native encrypted broker | native | MISSING — new (encrypted via the keys plane) |
| AWS-SM, GCP-SM, Azure-KV | federation | DONE |
| **Vault (#119)** | federation | MISSING — new |
| k8s | federation | MISSING — optional |

> **Decision.** Extend with **leases/TTL + renew + revoke + revokePrefix** (Vault dynamic-secret
> ideas) via *default* methods that report `leasingUnsupported`, preserving the **7 existing static
> providers** (Env, File, Composite, Caching, AWS-SM, GCP-SM, Azure-KV — verified count is 7, not the
> 8 the work order assumed).
>
> **Why.** Dynamic secrets with leases give automatic expiry and emergency prefix-revocation
> (blast-radius control) without breaking static providers that have no lease concept — they simply
> don't override the defaults. `revokePrefix` is the "compromise — kill everything under this path"
> emergency lever.
>
> **Rejected alternative.** *Make lease methods abstract* — breaks all 7 static impls. *A separate
> `DynamicSecretsProvider` interface* — fragments the SPI and the resolution chain.

### 6.4 Native broker + federation

> **Decision.** Ship a **minimal native secrets broker** (cluster-hosted, its store **encrypted via
> the keys plane**) plus **federation** to Vault (#119) / AWS-SM / GCP-SM / Azure-KV.
>
> **Why.** Decision-3: the native broker covers air-gapped/on-prem where no external secret store
> exists; federation covers deployments that already run Vault or a cloud SM. The broker's store is
> encrypted by `KeyProvider`, which creates the bootstrapping dependency made explicit in §6.7.
>
> **Rejected alternative.** *Federation-only* — fails air-gapped/on-prem (Decision-1/3).
> *Native-only* — ignores existing Vault/cloud-SM investments.

### 6.5 Secret-zero for the backend & slice fix (#269)

> **Decision (backend secret-zero).** A node authenticates to the secrets backend with **its
> identity** — cloud IAM auth where present (AWS/GCP/Azure), else a wrapped AppRole-style credential
> bootstrapped from the join (§3.4).
>
> **Why.** The node already has a verifiable identity from the identity plane; reusing it to
> authenticate to the secrets backend avoids a second standing credential. Vault AppRole +
> response-wrapping is the established pattern where no cloud IAM exists.
>
> **Rejected alternative.** *A static per-node backend password/token* — reintroduces secret-zero (a
> standing long-lived credential that must itself be distributed and protected), the exact anti-pattern
> the identity plane exists to remove.

> **Decision (slice fix #269).** Wrap the slice `IntrinsicConfigProvider` with
> `ConfigurationProvider.withSecretResolution(...)` — the exact machinery already used for
> `node.toml` — threading a `SecretsProvider` into the `SliceStore` record.
>
> **Why (verified).** `SliceStore.assembleSliceComposite` layers config with
> `LayeredConfigProvider.layered(...)` at `SliceStore.java:251` but **never** wraps it in
> `SecretResolvingConfigurationProvider`; intrinsic `resources.toml` loading (`getResourceAsStream`,
> `:298`) does no resolution; the whole file contains zero `withSecretResolution`/`secrets:` references.
> By contrast node.toml *is* resolved (`AetherNode.java:4284-4288` → `ConfigurationProvider.withSecretResolution`,
> defined at `ConfigurationProvider.java:87`), so the gap is **specifically the slice layer**. Reusing
> `withSecretResolution` (proven on the node path) is the minimal, consistent fix.
>
> **Rejected alternative.** *A slice-specific resolver* — duplicates working node-side machinery.

### 6.6 Redaction (R5) and #287 residual

> **Decision.** (a) Add a redacting `toString()` to every credential-bearing type and exclude
> credentials from any slice-reachable serialization/error payload. (b) Stop logging config values at
> INFO. (c) Close the #287 compose vector by resolving `cluster_secret` from a file/secret reference
> instead of an env var — **partially done**: #684 (closed 2026-09-04) removed the literal-secret
> variant (`DockerComposeGenerator.java` deleted; `DockerComposeTemplate.java:84` now emits a
> `${AETHER_CLUSTER_SECRET:?...}` reference), but delivery is still by env var, not a file/secret
> reference, so (c)'s full intent is not yet met.
>
> **Why (verified).** `SliceStore.java:265-269` logs *unresolved* `${secrets:...}` placeholders **and
> the override value** at INFO when a key is shadowed — leaking both the secret path and the actual
> value. `SecureFiles.java:24-54` already chmod-600s the on-disk secret (DONE); the compose
> literal-secret vector (`DockerComposeGenerator.java:80`) is closed by #684
> (`DockerComposeTemplate.java:84` now emits `${AETHER_CLUSTER_SECRET:?...}`, and
> `DockerComposeGenerator` is deleted) — but `cluster_secret` still rides
> `docker run -e AETHER_CLUSTER_SECRET="..."` and inline-JVM-env-on-the-SSH-command-line on live
> bootstrap/redeploy paths (`BootstrapPhaseDeploy.java:483,547,821`), visible via
> `docker inspect`/`/proc`/shell history — the residual #287 named in its own discussion and never
> fixed by its closure.
>
> **Rejected alternative.** *Rely on operators not to read logs / not to `docker inspect`* — not a
> control.

### 6.7 Bootstrapping order (chicken-and-egg)

```
operator-provided cluster_secret
  → derive CA seed (HKDF) ─────────────► cluster CA ready          (SelfSignedCertificateProvider)
  → derive KEK seed (HKDF) ────────────► KeyProvider (cluster_secret-KEK) ready
  → first node join (authenticated by cluster_secret bootstrap anchor)
  → NodeAttestor verifies → IdentityIssuer issues node SVID
  → native secrets broker starts (its store encrypted via the KeyProvider)   ← depends on keys plane
  → ${secrets:} resolution available (pull)
  → cloud-credential JIT (leader, via broker + node identity)                 (§7)
```

**Failure modes to handle explicitly:**
- *Broker before keys plane* — the native broker MUST NOT start before `KeyProvider` is ready, or its
  store is unencrypted. Order is enforced at node startup; broker init depends on `KeyProvider` init.
- *Node identity before CA* — a node cannot get an SVID before the CA exists; the first
  (leader/control-plane) node materializes the CA from `cluster_secret` before accepting joins.
- *Federation backends* — external KMS/Vault are only contacted *after* the node has identity (so it
  can authenticate). The native path must complete with zero external dependencies (air-gapped).

---

## 7. Plane 4 — Cloud Credential Handling (#206)

*This section is the inline #206 sub-spec. (#206 requests a `cloud-credential-handling-spec.md`; a
self-contained section is the accepted first-draft form per the work order. It may be extracted later.)*

### 7.1 The problem (verified)

`BootstrapOverlayGenerator.java:133` (`Map.of("api_token", token)`, in `cloudCredentialsSection` `:125-134`)
emits a literal `[cloud.credentials] api_token` into **every** node's composed config (any node may
become leader → call `provision()`). Consequences: any
single-node compromise leaks a **full-access** token; Hetzner tokens are **unscoped**; leader-gating
is merely **structural** (`ClusterTopologyManagerRecord.provisionReplacement:483-531` runs only when
the reconciler's `active.get()` guard is true — there is no explicit `isLeader` check at the call
site).

### 7.2 Option hierarchy (worst → best)

| Rank | Approach | Token exposure |
|---|---|---|
| ✗ worst | literal fan-out (today) | full-access token on every node |
| better | leader-only scoped token | token on leader only |
| good | short-lived STS / assumed-role | short-lived, auto-expiring |
| best | instance-profile / WIF (no static token at all) | none |

### 7.3 `CloudCredentialResolver` SPI + per-provider strategy

```java
record CloudCredential(String value, Option<Instant> expiry, Set<String> scopes) {}

interface CloudCredentialResolver {
    Promise<CloudCredential> resolveForProvision(CloudProviderName provider, Principal leader);
}
```

| Provider | Strategy | Static token? |
|---|---|---|
| AWS | instance profile / STS assume-role | none |
| GCP | workload identity / instance identity | none |
| Azure | managed identity | none |
| **Hetzner** | **leader-only, JIT-fetched scoped token from the broker, never fanned out** | leader only |

> **Decision.** Per-provider: AWS/GCP/Azure use **instance identity / workload identity (no static
> token)**; **Hetzner uses a leader-only, JIT-fetched scoped token** from the secrets broker, never
> fanned out. Recommend adding an **explicit `isLeader` check** at the `provision()` call site rather
> than relying on the structural `active.get()` guard.
>
> **Why (verified).** AWS/GCP/Azure all expose instance/workload identity, eliminating static tokens
> entirely (the "best" rank). Hetzner has **no instance roles** (verified: no signed IID), so the
> best available is leader-only JIT: the leader fetches at provision-time via its node identity, and
> non-leaders never hold the token. An explicit `isLeader` makes the security-critical gate legible
> instead of an emergent property of the reconciler.
>
> **Rejected alternative.** *Keep the literal fan-out* — the #206 vulnerability. *Leader-only static
> token without JIT* — still a standing token on the leader; JIT + scope shrinks the window.

### 7.4 Migration & DigitalOcean (#307)

- **Migration:** stop emitting the literal `[cloud.credentials] api_token`
  (`BootstrapOverlayGenerator.java:133`); emit a *reference* resolved JIT by the leader. Before/after
  config in §10.
- **#307 (verified):** `CloudCredentials.java:34` returns `operationNotSupported` for DigitalOcean
  (spec-only; implemented providers are AWS/Azure/GCP/Hetzner/Docker). GA target: implement the DO
  resolver or keep the clean error — all GA per Decision-2; recommend implementing the resolver.

**Worked example — leader JIT-fetches a Hetzner provision token (no fan-out).**

```
reconciler decides to scale → provisionReplacement() runs ONLY on the leader (active.get() + explicit isLeader)
  → CloudCredentialResolver.resolveForProvision(HETZNER, leaderPrincipal)
        → leader authenticates to the secrets broker with its node identity (§6.5)
        → broker returns a short-lived, provision-scoped token → CloudCredential{ value, expiry, scopes }
  → ComputeProvider.provision(...) uses it; the token is never written to any node's config
  → non-leaders hold NOTHING; on failover the new leader repeats the fetch
```

---

## 8. Runtime ↔ Slice Boundary & Credential-less Consumption

### 8.1 Two trust zones

The runtime zone holds all material; the slice zone holds capabilities + a `Principal`. The boundary
is in-process (Decision-5), enforced by a classloader topology + JPMS strong encapsulation.

### 8.2 Classloader topology

> **Decision.** Slice classloader is a **child** of the node classloader; the **security classloader
> is a sibling** of the slice CL, never on its parent chain; the node core holds an explicit reference
> to the security CL. (Decision-5: in-process, per-slice CL + sibling security CL — do not escalate to
> per-slice processes.)
>
> **Why.** A child CL sees its parent's classes by delegation. If security classes were loadable by
> the node CL (the slice's parent), the slice could load them too. Making the security CL a *sibling*
> means the slice cannot even *name* security classes by delegation. In-process isolation is
> *proportionate* to the threat model (§1.5 — the app trusts itself); per-slice processes add an
> IPC/latency tax for no benefit against the actual threat (buggy libraries, not malicious code).
>
> **Rejected alternative.** *Per-slice processes* — disproportionate for a single-security-domain app.
> *Security classes on the node CL* — reachable by the slice via delegation.

### 8.3 JPMS strong encapsulation is the real fence

> **Decision.** Put security code in a **named JPMS module that `opens` nothing** and `exports` only
> secret-free capability/Principal API packages. The classloader split is necessary but not
> sufficient; the module boundary is the load-bearing fence.
>
> **Why.** Classloaders stop *symbolic linking* (the slice can't `import`/`Class.forName` a security
> class) but **not reflection**: the moment any security-loaded object is *reachable* (passed in,
> returned, reachable via a field), the slice can `getClass().getDeclaredFields()` +
> `setAccessible(true)` and read it. `setAccessible` across modules throws
> `InaccessibleObjectException` only if the target module `opens` nothing (default since JDK 16, JEP
> 396). This matters *more* than usual because the **`SecurityManager` sandbox is gone** (JEP 411
> deprecated → **JEP 486 permanently disabled it in JDK 24**) — the module system is the only
> language-level boundary left. (Slices need not be modules; only the security code must be a named,
> non-`opens` module for encapsulation to bite.)
>
> **Rejected alternative.** *Rely on the classloader split alone* — defeated by reflection on any
> reachable object. *Rely on `SecurityManager`* — removed from the platform.

### 8.4 The invariant + opaque-id handle

> **The invariant:** *"No credential-bearing object is reachable from the slice, and the security
> module opens nothing to it."*

> **Decision.** The strongest form of a slice handle is a **stub holding an opaque id** (`long`); the
> real credential and the authenticated client live **behind** the boundary; the node resolves the id
> on its side.
>
> **Why.** If the handle holds nothing but an opaque id, there is **nothing to reflect** — the
> secret's safety no longer even depends on JPMS. This falls straight out of "runtime provisions
> everything" (§8.5). It also defeats the leak-by-`toString()`/serialization class (§6.6): an id
> serializes to a meaningless number. A capability interface MUST NOT expose secret accessors (a
> `DataSource.getPassword()` defeats everything — API hygiene is a precondition).
>
> **Rejected alternative.** *Handle holds a live credential-bearing client* — reflectable unless JPMS
> holds; brittle. *Handle holds an encrypted credential* — still reflectable material in the slice
> zone.

### 8.5 Capability injection

> **Decision.** The runtime provisions **all** resources; the slice loads nothing itself. Every
> authenticated downstream is a runtime-mediated capability handle. A generic "authenticated egress /
> sign-this-request" escape hatch covers un-adapted downstreams **without** handing over the secret.
>
> **Why.** "Slice never sees credentials" (Decision-4) is only true if the *first* un-adapted
> downstream can't force the slice to hold a credential. The completeness requirement: every
> authenticated downstream needs either a mediated resource or the generic egress hatch (runtime signs
> / attaches credentials on the slice's behalf).
>
> **Rejected alternative.** *Let slices open their own connections for "uncommon" downstreams* — the
> first such case leaks a credential into the slice zone.

### 8.6 `Principal` + `ScopedValue` propagation

**Reconciliation (verified).** A request-time model already exists:
`org.pragmatica.aether.http.handler.security.Principal` (a record wrapping a single prefixed `String
value`: `api-key:` / `user:` / `service:` / `anonymous`) and `SecurityContext` (a record bundling
`principal` + `Set<Role>` + claims + `AuthorizationRole{ADMIN,OPERATOR,VIEWER}`). The design's notion
of *"Principal = id + roles/claims + auth method + expiry"* maps onto the existing **`SecurityContext`**,
not the bare `Principal`. The spec builds on these types; it does not replace them.

> **Decision (a) — binding authority behind the boundary.** The `ScopedValue` *key* lives in the
> security module; only the runtime binds it. The slice reads the current identity via a secret-free
> accessor — a slice-facing context type (named to avoid colliding with the existing
> `http.handler.security.SecurityContext`, e.g. `SliceSecurityContext.currentPrincipal()`) returning
> an immutable value — **never** the key.
>
> **Why.** `ScopedValue.where(KEY, x).run(...)` is callable by anyone holding `KEY`. If the slice can
> see `KEY`, it rebinds to a forged admin `Principal` and elevates. So the key is one of the §8.3
> "security classes" — same boundary as §8.4. (This is the internal consistency check that §8.3 and
> §8.6 describe the *same* fence.)
>
> **Rejected alternative.** *Expose the `ScopedValue` key to slices for convenience* — direct
> privilege-escalation path.

> **Decision (b) — async + cross-node propagation is the runtime's job, by EXTENDING the existing
> `ContextPropagation` hook (not registering a parallel one).** The async snapshot/rebind hook already
> exists; the work is to *widen what it carries*, then sign + verify across nodes.
>
> **Why (verified — corrects an earlier "no core change required" overstatement).** A bare `ScopedValue`
> does **not** survive Promise thread hops: `Promise.AsyncExecutor` (`Promise.java:3150-3172`) submits
> independent actions to a `newVirtualThreadPerTaskExecutor()`, and a fresh virtual thread does not
> inherit ScopedValue bindings. The snapshot/rebind hook exists at `Promise.java:3155-3156`
> (`capture()` before `submit`, `runWith(snapshot, ...)` on the worker). **But two verified facts make
> "just provide an impl" wrong:** (1) `ContextPropagation` is selected by a **single-winner**
> `ServiceLoader.load(...).findFirst()` (`ContextPropagation.java:56-58`), and that single slot is
> **already owned** by `AetherContextPropagation` (`aether/aether-invoke`, registered in `aether/node`'s
> `META-INF/services`). A second registration would be **silently dropped**, not chained. (2) The existing
> impl captures only a **`String` principal** (`InvocationContext.java:171`, `ScopedValue<String>`),
> **not** the rich `SecurityContext` (`SecurityContextHolder.java:11` is a *separate* `ScopedValue` the
> hook does **not** propagate) — so today, across an async hop, roles/claims are **dropped** and only the
> principal string survives. The correct fix is therefore to **extend `AetherContextPropagation` /
> `InvocationContext.ContextSnapshot` to carry the full `SecurityContext`** (a change in `aether-invoke`,
> not a parallel registration). Note also that `Promise.map`/`flatMap` run **inline** on the completing
> thread (`Promise.java:121-123`, `:145-147`) with no explicit rebind — they inherit by thread-locality
> only, a correctness gap to confirm wherever a continuation may execute off the bound thread. Cross-node,
> the `Principal` travels as a **signed assertion** (short-lived, bound to the call, under mTLS),
> **verified against the identity plane** on the receiving node, and re-bound locally — never trusted raw
> off the wire (the network is an adversary boundary, §1.5).
>
> **Rejected alternative.** *`ThreadLocal`* — leaks/cleanup burden, not auto-inherited by
> `StructuredTaskScope`, costly on virtual threads. *Trust a cross-node Principal header raw* —
> forgeable over a compromised hop.

```java
// CORRECT shape: EXTEND the existing single-winner impl — do NOT register a parallel
// ContextPropagation (ServiceLoader.findFirst() would silently drop it). Widen the snapshot:
//
//   aether-invoke: AetherContextPropagation + InvocationContext.ContextSnapshot
//     - today  : ContextSnapshot carries `String principal`   (InvocationContext.java:171)
//     - change : carry the full SecurityContext (principal + roles + claims + AuthorizationRole),
//                capturing from SecurityContextHolder's ScopedValue in capture(),
//                rebinding it in runWith() — so roles/claims survive the async hop.
//   The ScopedValue key stays UNEXPORTED (SecurityContextHolder, security module). Slices read only:

public interface SliceSecurityContext { Principal currentPrincipal(); }  // exported, secret-free; never exposes the key
```

### 8.7 OBO / delegation — seam only

> **Decision.** Keep the delegation **seam** (every resource call already runs inside the Principal
> context) but do **not** implement on-behalf-of token exchange (RFC 8693) now.
>
> **Why.** There is no current use case; building it now is speculative. Because every resource call
> already carries the Principal context, adding "call downstream *as* this Principal" later is additive,
> not a redesign.
>
> **Rejected alternative.** *Build OBO now* — speculative; *foreclose it* — would force a later
> redesign.

### 8.8 Documented assumption (restate)

Against *actively malicious bytecode* this is **not** a hard sandbox — `Unsafe`, JNI, deserialization
gadgets, or any `opens` granted are escapes (§1.5, #313). The trust model (single security domain, app
trusts itself) puts that out of scope. This restatement is mandatory, not optional.

---

## 9. Policy & Audit (cross-cutting)

### 9.1 `PolicyEngine`

```java
record Action(String verb) {}                  // e.g. "secret.read", "key.unwrap", "cloud.provision"
record ResourceRef(String kind, String id) {}
sealed interface Decision permits Allow, Deny {}
record Allow() implements Decision {}
record Deny(Cause reason) implements Decision {}

interface PolicyEngine { Promise<Decision> evaluate(Principal p, Action a, ResourceRef r); }
```

> **Decision.** **Two RBAC domains, one engine.** Management-plane RBAC (operators) and application
> RBAC (end-users) share the `Principal` model + `PolicyEngine`, but use **distinct scopes/namespaces**.
>
> **Why.** They have different principals and lifecycles (an operator API key vs an end-user identity);
> conflating their scopes bleeds privilege (an app-user role accidentally granting a management
> action). Sharing the engine avoids two policy implementations. This builds on the **existing**
> `AuthorizationRole{ADMIN,OPERATOR,VIEWER}` and `Role`/`RoutePermission`
> (`aether/http-handler-api/.../security/`); note the verified **default role is VIEWER**
> (`AetherValue.DEFAULT_ROLE`, `KvStoreApiKeyValidator:145`, `ApiKeySecurityValidator:99` — #290/#209).
>
> **Rejected alternative.** *One flat role set for both domains* — privilege bleed. *Two separate
> engines* — duplicated policy logic and audit.

### 9.2 Relationship to the existing RBAC spec

This plane *extends* `rbac-spec.md` (request-time API-key RBAC), it does not supersede it. The
existing `SecurityContext`/`Role`/`AuthorizationRole` are the management/application authorization
model; the `PolicyEngine` is the decision point that the new material-access call sites (secret read,
key unwrap, cert issuance, cloud provision) consult, using the same `Principal`.

### 9.3 `AuditSink` (revives #23)

```java
record AuditEvent(Instant at, Principal actor, Action action, ResourceRef resource,
                  Decision outcome, Option<String> prevHash) {}
interface AuditSink { Promise<Unit> record(AuditEvent e); }     // append-only
```

> **Decision.** Record **every** secret read, key wrap/unwrap, cert issuance, cloud `provision()`
> call, and management action; append-only, with an optional **hash-chain** for tamper-evidence.
>
> **Why.** Audit is the "did anyone touch this material" backstop the threat model needs; a hash-chain
> (each event carries the prior event's hash) makes silent deletion/modification detectable. Revives
> #23.
>
> **Rejected alternative.** *Log lines only* — mutable, not tamper-evident, easily lost.

**Worked example — Policy + Audit together (a gated, audited material access).**

```
operator → POST /api/keys/{id}/rotate              (management plane)
  → runtime authenticates the API key → Principal{ api-key:ops-7 }, SecurityContext{ role=OPERATOR }
  → PolicyEngine.evaluate(principal, Action("key.rotate"), ResourceRef("kek", id))
        → OPERATOR in the *management* scope permits key.rotate → Allow()
  → KeyProvider rotates the KEK
  → AuditSink.record(AuditEvent(now, ops-7, key.rotate, kek/id, Allow, prevHash))  → 200
```

The identical engine *denies* the same action to an application-plane caller: an end-user `Principal`'s
roles live in the *application* scope, which grants no `key.*` action, so `evaluate(...) → Deny(...)`
with **no scope bleed** (§9.1) — and the denial is audited too (`AuditSink.record(... Deny(PolicyDenied))`).

### 9.4 Management surfaces & SECURITY.md (#319)

All new management surfaces (key rotation, lease revoke, attestor config, audit query) MUST follow the
**REST→CLI→Docs triad** (CLAUDE.md invariant #1): a REST endpoint, a CLI command, and operator docs,
delivered together. **#319 (SECURITY.md) is a Phase-5 deliverable** (§12): a public-facing security
policy doc (skeleton + pointer) surfacing this spec's threat model (§1.5) and the single-trust-domain
assumption (#313). It is scoped here, not yet drafted.

---

## 10. Configuration Model

### 10.1 TOML additions

```toml
[security]
trust_domain   = "example.aether"          # SPIFFE-style trust domain
attestor       = "join-token"              # join-token | aws-iid | gcp-iid | azure-imds | tpm

[identity]
cert_ttl       = "8h"                       # short-lived SVID
renew_at       = 0.4                         # fraction of validity remaining (CertificateRenewalScheduler)

[keys]
provider       = "cluster-secret"          # cluster-secret | vault-transit | aws-kms | gcp-kms | azure-kvkms
aead           = "aes-gcm-siv"             # aes-gcm-siv | xchacha20-poly1305
kek_rotation   = "P90D"

[secrets]
providers      = ["env", "file", "broker"] # resolution chain; federation appended when configured
```

### 10.2 `${secrets:}` reference syntax (unified)

`${secrets:<path>}` is resolved identically at CLI bootstrap, `node.toml`, and slice `resources.toml`
(§6.1). Unresolved references fail closed (an error), never pass through as a literal.

### 10.3 Cloud credentials: before → after (#206)

```toml
# BEFORE (fanned out to EVERY node — BootstrapOverlayGenerator.java:133)
[cloud.credentials]
api_token = "hcloud_AbCdEf...full-access-literal..."

# AFTER (a reference; resolved JIT by the leader via CloudCredentialResolver — §7)
[cloud.credentials]
api_token = "${secrets:cloud/hetzner/provision-token}"   # never materialized on non-leaders
```

---

## 11. Error Model

JBCT `Cause` taxonomy — one sealed hierarchy per SPI; parse-don't-validate at every boundary (raw
input → typed value object once). Existing precedent: `CertificateProviderError` (sealed `Cause` with
`CaGenerationFailed` / `CertificateIssueFailed` / `KeyDerivationFailed`) and `Principal.PrincipalError`.

| SPI | Representative `Cause` variants |
|---|---|
| `NodeAttestor` | `AttestationFailed`, `EvidenceExpired`, `UnsupportedEvidence`, `UntrustedIssuer` |
| `IdentityIssuer` / `CertificateProvider` | (existing) `CaGenerationFailed`, `CertificateIssueFailed`, `KeyDerivationFailed`, + `IdentityExpired` |
| `KeyProvider` | `UnwrapFailed`, `WrapFailed`, `UnknownKekId`, `KekVersionRetired` |
| `SecretsProvider` | `SecretNotFound`, `LeaseExpired`, `LeasingUnsupported`, `RevocationFailed` |
| `CloudCredentialResolver` | `ProviderUnsupported`, `NotLeader`, `ScopeDenied`, `CredentialExpired` |
| `PolicyEngine` | `PolicyDenied(reason)`, `UnknownAction`, `UnknownResource` |
| `AuditSink` | `AuditWriteFailed`, `ChainBroken` |

All material-access methods are `Promise<T>` (async, resolve to `Result<T>`) except the existing
synchronous `CertificateProvider` (kept as-is, §4.1) and the slice-facing `currentPrincipal()` (a
pure value read).

---

## 12. Implementation Plan

Risk-first ordering; **all phases land in GA** (Decision-2 — order, not scope).

| Phase | Scope | Key tickets |
|---|---|---|
| 0 — quick wins | slice `${secrets:}` unify; R5 redaction + stop INFO-logging values; #287 compose-env; #307 clean error/resolver | #269, #287, #307 |
| 1 — this spec | the normative artifact (this document) | #313 #319 #23 (docs) |
| 2 — identity plane | per-node SVID from CA + mTLS everywhere + `NodeAttestor` (join-token baseline + cloud-IID); replace #88 CA-echo with real external cert issuance | #209 (CA→`IdentityIssuer`), #88 |
| 3 — keys plane | `KeyProvider` + envelope + wire `BlockEncryptor` + AEAD-SIV | #253 |
| 4 — secrets plane | unified pull + lease/revoke + native broker + Vault/cloud federation + #206 redesign | #119, #206 |
| 5 — hardening | `PolicyEngine`, `AuditSink` (hash-chain), **SECURITY.md skeleton (#319)**, trust-domain doc (#313), **#209 residual hardening** (default-role/VIEWER review #290, `http://`→`https://` healthcheck, `curl -k` audit) | #319, #313, #23, #209 |

**Envelope-version note.** `ManifestGenerator.ENVELOPE_FORMAT_VERSION` is currently `1000`
(`ManifestGenerator.java:34`). Bump it (CLAUDE.md invariant #3) **only** if slice-processor codegen
changes — e.g. if injecting the slice `SecretsProvider`/capability handles alters the generated slice
manifest/envelope. Pure runtime wiring that doesn't touch codegen does not require a bump.

---

## 13. Reconciliation to Existing Code

Verified against source on `design/security-subsystem` (re-verified). Tags: DONE / PARTIAL /
STUB / MISSING. **Trust the code, not the ticket** — and the code was re-checked: the transcript's
caution on #88 ("unverified") and on Principal-carry ("hand-wavy") proved **correct** — both are
STUB/PARTIAL, not the DONE an earlier draft claimed.

| Capability | Current state | Real anchor (verified) | Target | Tag |
|---|---|---|---|---|
| `SecretsProvider` SPI | resolve + metadata + batch + watchRotation; **7** impls (Env, File, Composite, Caching, AWS-SM, GCP-SM, Azure-KV) | `aether/environment-integration/.../SecretsProvider.java:17-43` | extend w/ lease/revoke; add broker + Vault | DONE (extend) |
| Node `${secrets:}` | eager, SPI-backed, wired | `AetherNode.java:4283-4288`; `integrations/config/.../SecretResolvingConfigurationProvider.java:21` | keep | DONE |
| CLI `${secrets:}` | env-var-only in prod (1-arg overload → `Option.none()`) | `cli/.../ConfigReferenceResolver.java:27`; `ClusterBootstrapCommand.java:120` | thread SPI provider | PARTIAL |
| Slice `${secrets:}` (#269) | not resolved; no secrets field; log-leak of unresolved value | `SliceStore.java:251` (layering), `:298` (intrinsic load), `:265-269` (log-leak) | `withSecretResolution` wrap | STUB |
| Cloud token fan-out (#206) | literal `api_token` on every node; structural leader-gate | `BootstrapOverlayGenerator.java:133`; `ClusterTopologyManagerRecord.provisionReplacement:483-531`; `HetznerEnvironmentIntegrationFactory.java:46,63` | JIT resolver; explicit `isLeader` | STUB/risky |
| Storage at-rest (#253) | `aesGcm` exists; prod defaults to empty `Option` (clear); plain GCM/random IV; no key source | `BlockEncryptor.java:48,51`; `AesGcmBlockEncryptor.java:19`; `SegmentReader.java:43` (`none()` default); `AetherNode.java:2537` (call site) | wire `KeyProvider`; AEAD-SIV | STUB |
| `cluster_secret` at rest (#287) | chmod-600 DONE; compose literal-secret vector closed 2026-09-04 by #684; env-var delivery (compose) and `docker run -e`/SSH-inline-JVM argv on live bootstrap/redeploy remain | `SecureFiles.java:24-54`; `DockerComposeTemplate.java:84` (#684); `BootstrapPhaseDeploy.java:483,547,821` | resolve via file/secret reference, not env var/argv, on every path | PARTIAL |
| CA from `cluster_secret` (#209) | CA derivation DONE (salt `aether-ca-seed`, daily rotation); residual: `http://` healthcheck + default role VIEWER (#290) unaddressed | `ClusterTrust:23-70`; `SelfSignedCertificateProvider.java:64,148-157`; `KvStoreApiKeyValidator:145`; `ApiKeySecurityValidator:99` | promote to `IdentityIssuer`; close residual (Phase 5) | PARTIAL |
| **Cloud cert adapters (#88)** | adapter classes + renewal scheduler exist, but are **CA-echo** — `issueCertificate` returns the pre-seeded CA cert verbatim, ignores `nodeId`/`hostname`; **no** cloud cert API (no ACM) | `CloudCertificateProvider.java:70-75`; `AzureCertificateProvider.java:73-78`; `AwsClient.java:43,78`; (genuine issuer: `SelfSignedCertificateProvider.java:106-187`) | build real external cert issuance | **STUB** |
| DigitalOcean (#307) | spec-only; `operationNotSupported` | `CloudCredentials.java:34`; `CloudProviderName.java` | implement resolver | MISSING |
| Gossip encryption | AES-GCM, multi-key rotation; keys from cert provider (#256 overlap) | `swim/.../AesGcmGossipEncryptor.java:25`; `SwimGossipEncryptors.java:39` | keep | DONE |
| Request-time RBAC | `Principal`/`SecurityContext`/`Role`/`AuthorizationRole`; ANONYMOUS→VIEWER | `aether/http-handler-api/.../security/{Principal,SecurityContext,AuthorizationRole,Role}.java` | extend via `PolicyEngine` | DONE (extend) |
| **Principal async carry** | snapshot/rebind hook exists, but the single-winner ServiceLoader slot is already owned by `AetherContextPropagation` carrying only a **`String`** principal (not `SecurityContext`); `map`/`flatMap` run inline w/o rebind | `Promise.java:3155-3156,121-123,145-147`; `ContextPropagation.java:56-58`; `InvocationContext.java:171`; `SecurityContextHolder.java:11` | **extend** the existing snapshot to carry `SecurityContext` | **PARTIAL** |
| Envelope version | `ENVELOPE_FORMAT_VERSION = 1000` | `ManifestGenerator.java:34` | bump iff codegen changes | n/a |

---

## 14. Open Questions

1. **Intermediate CA — single root vs root+intermediate?**
   *Options:* (a) single derived root signs node certs directly; (b) derived root signs an
   intermediate, intermediate signs node certs.
   **Recommendation:** (b) intermediate — lets the root stay offline/derived and be rotated
   independently of the issuing intermediate, limiting blast radius if the issuer is compromised.
   Justify or refute during phase 2.

2. **Native secrets-broker storage backing.**
   *Options:* reuse the cluster KV store (encrypted via `KeyProvider`); a dedicated encrypted file.
   **Recommendation:** cluster KV encrypted via `KeyProvider`, honoring the §6.7 ordering (broker
   starts only after the keys plane). Confirm the KV store's at-rest path during phase 4.

3. **Policy model/language — roles vs claims/capabilities; authoring + distribution.**
   *Options:* extend the existing `AuthorizationRole` role model; or a claims/capability model.
   **Recommendation:** start from the existing role model (already shipped) + scoped namespaces
   (§9.1); revisit a capability grammar only if role explosion appears.

4. **Principal async/cross-node mechanics.**
   **PARTIAL (verified).** The async snapshot/rebind hook exists (`Promise.java:3155-3156`), but the
   single-winner `ContextPropagation` slot is already owned by `AetherContextPropagation`, which carries
   only a **`String` principal** — so the fix is to **extend** that snapshot to the full `SecurityContext`
   (not register a parallel impl, which `findFirst()` would silently drop), and to handle the inline
   `map`/`flatMap` path (§8.6). Plus: finalize the **signed cross-node assertion format** (recommend a
   short-lived JWT/CWT bound to the call, verified against the identity plane). → resolve in phase 2.

5. **Revocation — short-TTL-only vs CRL/OCSP for node identities.**
   **Recommendation (§3.6):** short TTL + control-plane deny-list signal as primary; keep CRL/OCSP as
   an open option for long-TTL *external* certs only.

6. **OBO/delegation — seam-only acceptable for GA?**
   **Recommendation (§8.7):** yes — no current use case; the seam keeps it additive. Confirm with
   stakeholders.

---

## 15. References

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
  JEP 486 (SecurityManager permanently disabled, JDK 24) · `ScopedValue` (JEP 506) · `StructuredTaskScope`
- **Delegation (future):** OAuth 2.0 Token Exchange RFC 8693 — https://datatracker.ietf.org/doc/html/rfc8693
- **Attestation (future):** RATS RFC 9334 — https://datatracker.ietf.org/doc/html/rfc9334
