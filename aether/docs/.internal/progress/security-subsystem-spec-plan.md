# Security Subsystem Spec — Authoring Plan (agent work order)

| Field | Value |
|-------|-------|
| Purpose | A self-contained brief for an agent to **author** `aether/docs/specs/security-subsystem-spec.md` |
| Produced by | design-stream, 2026-06-23 |
| Companion | `aether/docs/internal/progress/session-transcript-2026-06-23-security-subsystem-design.md` (the "why we got here") |
| Status of design | **Converged.** Locked decisions are not to be re-litigated; code claims are to be re-verified. |
| Target spec path | `aether/docs/specs/security-subsystem-spec.md` |
| Related issues | #139, #119, #269, #206, #253, #287, #209, #88, #307, #313, #319, #23 |

> **How to use this plan.** Read §1–§2 fully before writing a line. The architecture is already
> decided (transcript §7); your job is to turn it into a complete, normative, internally-consistent
> spec with **detailed rationale for every decision**, reconciled against the actual code. §3 is the
> mandatory outline. §4 gives SPI sketches to formalize. §5 lists code you MUST verify as you write
> (do not trust ticket line numbers — several were already stale). §9 is a prompt you can be spawned
> with. **Do not** invent new architecture or overturn a locked decision; if you find a locked
> decision is technically impossible, stop and flag it rather than silently substituting your own.

---

## 1. Context you must absorb first

**Read these before writing** (in order):
1. The transcript (companion file above) — §7 is the converged design, §9 the code anchor map, §5
   the locked decisions, §6 the two hardest mechanisms.
2. `CLAUDE.md` (repo root) — project invariants, esp. the REST→CLI→Docs triad (#1), envelope-version
   bump (#3), BSL headers (#4), and the "spec is done only when reconciled" rule.
3. The code anchors in §5 below — open each, confirm it still says what the transcript claims.

**The unifying thesis (state this up front in the spec).** Keys/certs and secrets are **one**
subsystem: protecting confidential material across its lifecycle (issue → distribute → use → rotate
→ revoke → audit), gated by **identity**, under one **root of trust**. The core architectural move is
**push → pull**: stop pushing material onto nodes; give each node an identity and let it pull what
it's entitled to, scoped and short-lived.

**Threat model & trust assumptions (state these explicitly; the whole design rests on them).**
- Slices belong to a **single application / single security domain**; they are **not mutually
  hostile** — the application trusts itself. (Ties #313.)
- The realistic in-domain threat is **buggy or compromised third-party libraries** (largely
  eliminated in this stack, but not zero).
- The runtime/slice boundary is therefore an **accident- and blast-radius boundary hardened against
  reflection/casual access**, **NOT a hard sandbox against actively malicious bytecode**. `Unsafe`,
  JNI, deserialization gadgets, and any `opens` granted are acknowledged escapes and are
  **out of scope** — say so in writing.
- Cross-**node** trust is a real adversary boundary (network): material crossing nodes must be
  signed/verified, never trusted raw off the wire.

**Locked decisions (user-mandated — do NOT change):**
1. **All clouds first-class + on-prem.** AWS/GCP/Azure/Hetzner + bare-metal. Identity baseline must
   work with **zero** platform attestation; cloud-IID attestation is an *upgrade*.
2. **Complete, end-to-end GA — no deferral/staging.** The spec describes the *full* target; every
   plane ships in GA, reconciled (`MISSING=STUB=SHORTCUT=OMISSION=SIMPLIFICATION=0`).
3. **Minimal native + federation** on every plane (only combo that covers on-prem/air-gapped).
4. **Credential-less slices.** Slice gets capabilities (connected handles) + a `Principal`, **never**
   keys/secrets/certs. Covers cluster-owned secrets, user-provided secrets (e.g. DB creds), and
   user-managed secrets (mgmt-API RBAC, application RBAC).
5. **Runtime/slice isolation = in-process**, per-slice classloader + sibling security classloader
   (do not escalate to per-slice processes).
6. **Principal propagated via `ScopedValue`** (like request-ID).

**Recommendations (design-stream's; you MAY refine with stated justification):** JPMS named-module
hardening + stub/opaque-handle pattern; AES-GCM-SIV/XChaCha20 over plain AES-GCM; envelope KEK/DEK
split; demote `cluster_secret` to bootstrap anchor; per-node SVID-style identity; two-RBAC-domains-
one-engine; per-provider cloud-credential strategy; signed cross-node Principal assertions.

---

## 2. Deliverable shape & conventions

- **Path:** `aether/docs/specs/security-subsystem-spec.md`. (Optionally also produce
  `cloud-credential-handling-spec.md` as a #206 sub-spec — see §3 section 7; a self-contained section
  inside the main spec is acceptable for the first draft.)
- **Format:** house style — `# Title`, a metadata block (bold key/value lines *or* a table; match
  `cloud-integration-spi-spec.md`), `---`, a numbered **Table of Contents**, then numbered sections.
  **No BSL/SPDX header** on the markdown (the sibling specs don't carry one; it's a source-file
  invariant). Status: `Draft`. Date: `2026-06-23`.
- **Code style: JBCT throughout.** `Promise<T>` for async (resolves to `Result<T>`), `Result<T>` for
  sync fallible, `Option<T>` for optional, `record`s for value objects, `sealed interface` for sum
  types, `Cause` for errors, parse-don't-validate at every boundary. Match the existing
  `SecretsProvider` signature style (`Promise<String> resolveSecret(...)`).
- **Mandatory rationale pattern.** Every design decision and every important implementation detail
  uses:
  > **Decision.** …
  > **Why.** … (the reasoning — cite the research/standard/threat it addresses)
  > **Rejected alternative.** … (what else was considered and why not)

  This is the explicit user requirement: the implementing agent must inherit the full picture, not
  just the conclusion. A decision without a Why is incomplete.
- **Reconcile to code.** Reference real `file:line` (verified — §5). Every ticket in the header table
  must be explicitly addressed (mapping table, §3 section 13).
- **Cross-reference tickets inline** where a section resolves one (e.g. "(resolves #269)").

---

## 3. Required outline (write every section; per-section guidance below)

### 1. Overview & Goals
Purpose; goals/non-goals; the unifying thesis (§1); the **threat model & trust assumptions** (§1 —
this is load-bearing, give it its own subsection); design principles (identity-first, least
privilege, native+federate, capability injection, pull-not-push, observability/audit, JBCT
parse-don't-validate); a table mapping each related ticket → which section addresses it.

### 2. Architecture Overview
The plane stack diagram (transcript §7.1); the **two trust zones** (runtime holds all material; slice
gets capabilities + Principal); the **SPI inventory** table (§4); an **end-to-end request flow**
narrative (inbound request → authenticate → bind Principal → slice runs → slice uses an injected
resource handle → runtime performs the authenticated downstream call → audit); and the
**bootstrapping order** (§6 — critical, has a chicken-and-egg).

### 3. Plane 0 — Root of Trust & Identity
- **`cluster_secret` demotion.** *Decision:* it becomes a **bootstrap anchor only** — seeds the CA and
  authenticates the *first* join handshake; it is NOT a runtime authorizer. *Why:* a symmetric shared
  secret grants full membership to anyone who holds it (#288/#313), can't revoke one node, and is the
  blast-radius root; demoting it removes the standing liability while keeping the one thing it's good
  for (a bottom-turtle on infra with no platform identity — Hetzner). *Rejected:* keep it as runtime
  trust (status quo — the #288 problem); replace it entirely (impossible on Hetzner — no signed IID).
- **Three-tier identity model:** (0) bootstrap anchor; (1) **per-node identity** = own keypair +
  short-lived X.509 SVID-equivalent issued by the cluster CA, carrying node-id + role + trust-domain
  (SPIFFE-style URI SAN); all node↔node mTLS uses these; (2) **pluggable attestation** strengthens the
  join. Give the *Why* for each tier (revocation + least-privilege need per-node identity; attestation
  removes TOFU risk where the platform signs identity).
- **`NodeAttestor` SPI** (§4): join-token (TOFU) default + cloud-IID + TPM. Per-provider table
  (AWS instance identity/IMDSv2, GCP instance identity token, Azure attested metadata, **Hetzner =
  join-token only — no signed IID**, TPM where present). *Why* the baseline is join-token: Decision-1
  + the verified Hetzner gap.
- **Bootstrap / secret-zero flow:** one-time join token (short TTL, single-use, TOFU), delivered
  **response-wrapped** (tamper-evident); the node attests, generates a keypair locally (private key
  never leaves), submits a CSR, the CA (control-plane/leader) issues the SVID. Cite SPIRE join_token,
  Vault response-wrapping.
- **HKDF usage & limits:** keep HKDF for deriving operational keys from `cluster_secret` **with
  distinct `info` labels** (sound per NIST SP 800-108 key-separation). *Why/limit:* no per-key
  revocation; rotating the root rotates everything ⇒ derive only the **KEK seed** and the CA seed this
  way, never data DEKs.
- **Revocation model:** revoke a node = revoke its cert (short TTL bounds exposure; need a revocation
  signal / very-short TTL + re-issue). Discuss CRL-vs-short-TTL tradeoff (prefer short TTL + frequent
  re-issue over CRL distribution).

### 4. Plane 1 — Certificates / PKI
CA hierarchy (root from `cluster_secret`; decide & justify whether an intermediate CA is needed —
*open question, §8*); **internal identity certs (cluster CA) vs external-facing certs (#88 adapters
— ACM/GCP CM/Azure KV)** — make the split explicit (*Why:* internal mTLS trust must chain to the
cluster's own root, not a public CA; external ingress/mgmt certs may want public/managed CAs).
Short TTL + auto-rotation via `CertificateRenewalScheduler` (cite NIST SP 800-57 cadence). PQC
posture: AES-256 at-rest is quantum-safe; only the asymmetric CA/handshake would need ML-KEM/ML-DSA
(FIPS 203/204) and only for >10yr secrets ⇒ **make the CA algorithm swappable; do not implement PQC
now** (*Decision + Why + Rejected: implement-now = premature*).

### 5. Plane 2 — Keys / Encryption
- **Envelope hierarchy:** root → KEK → per-tier/stream DEK. *Why:* never bulk-encrypt with the root;
  KEK rotation re-wraps the small DEK (cheap, no data re-encryption); keep old KEK versions for
  read-back; crypto-shredding = destroy the key. Cite AWS/GCP KMS, NIST SP 800-57.
- **`KeyProvider` SPI** (§4) modeled on **K8s KMS v2** / AWS Encryption SDK keyring (local-file *or*
  cloud KMS behind one contract — satisfies native+federate). *Why* this model: proven, abstracts
  cluster-local and external KMS identically.
- **`BlockEncryptor` stays the DEK/AEAD layer; wire it to `KeyProvider`** (resolves #253's
  `none()`-in-prod). Storage sink: `generateDataKey` at segment/tier creation, store the `WrappedKey`
  in the segment header, encrypt with the plaintext DEK; on read, `unwrap` via the header. `keyId`
  carries `(logical, version)`.
- **AEAD choice:** *Decision:* use a nonce-misuse-resistant AEAD (**AES-GCM-SIV / RFC 8452** or
  **XChaCha20-Poly1305**) for tier DEKs. *Why:* random 96-bit AES-GCM nonces cap near **2³²
  messages/key** before forgery risk (NIST SP 800-38D), catastrophic on a single reuse, and a
  fan-out cluster reaches that scale. *Rejected:* plain AES-GCM with random nonces (unsafe at scale);
  strict per-DEK counters (workable but fragile across restarts/replicas).
- **Rotation & crypto-shredding** (DEK/KEK/root cadences, NIST SP 800-57); **KEK-not-co-located-with-
  ciphertext** rule (*Why:* otherwise at-rest encryption is theater). Default KeyProvider =
  `cluster_secret`-derived KEK; federation = Vault transit / AWS-GCP-Azure KMS.

### 6. Plane 3 — Secrets Management
- **Unified resolution pipeline:** one `${secrets:}` engine used at **CLI bootstrap, node.toml, and
  slice `resources.toml`** (resolves #269 + the CLI env-only gap). *Why:* today there are two divergent
  resolvers and a third trivial one, the CLI path is env-only in practice, and the slice layer isn't
  resolved at all — one pipeline removes the inconsistency and the silent-literal footgun.
- **Pull not push:** slices/nodes resolve at use-time via the provider chain authenticated by node
  identity. *Why:* OWASP anti-patterns (secrets in env/argv/world-readable files); blast radius.
- **`SecretsProvider` SPI extended** (§4) with leases/TTL + revoke + prefix-revoke (Vault dynamic-
  secret ideas) while preserving the existing 8 static providers. **Native minimal broker**
  (cluster-hosted, **encrypted via the keys plane** — note the bootstrapping order, §6) + **federation**
  to Vault (#119)/AWS-SM/GCP-SM/Azure-KV.
- **Secret-zero for the backend:** the node authenticates to the secrets backend with its identity
  (cloud IAM auth where present; else wrapped AppRole-style). 
- **Slice resolution fix (#269):** wrap the slice `IntrinsicConfigProvider` with
  `withSecretResolution(...)` (the machinery already exists for node.toml), threading a
  `SecretsProvider` into the `SliceStore` record. **Redaction (R5):** redacting `toString()` on
  credential types + stop logging config values at INFO.

### 7. Plane 4 — Cloud Credential Handling (#206) *(this is the #206 sub-spec)*
The problem (literal token fanned out to every node; any-node-compromise → full-access token; Hetzner
tokens unscoped). The **option hierarchy worst→best** (literal fan-out ✗ → leader-only scoped →
short-lived STS/assumed-role → instance-profile/WIF with no static token). **Per-provider strategy:**
AWS/GCP/Azure → instance identity / workload-identity (no static token); **Hetzner → leader-only,
JIT-fetched scoped token from the broker, never fanned out** (*Why:* Hetzner has no instance roles).
The **leader-only JIT model**: leader fetches at provision time via its node identity; non-leaders
never hold the token; note that leader-gating is currently structural (`active.get()` reconciler
guard) not an explicit `isLeader` at the call site — recommend an explicit check. **Migration:** stop
emitting the literal `[cloud.credentials] api_token` into every node's config; emit a reference
resolved JIT by the leader. Include a per-provider recommendation table + GA-vs-later split (all GA per
Decision-2).

### 8. Runtime ↔ Slice Boundary & Credential-less Consumption
- **Two trust zones**; the **classloader topology** (node CL parent → slice CL child; **security CL is
  a sibling of the slice CL**, never on its parent chain; node core holds an explicit reference to the
  security CL). *Why* sibling: a child sees its parent's classes by delegation, so security must not be
  an ancestor of the slice.
- **JPMS strong encapsulation is the real fence**, not the classloader. Security code in a **named
  module that `opens` nothing** and `exports` only secret-free capability/Principal API packages.
  *Why:* classloaders stop symbolic linking but not reflection on a reachable object; `setAccessible`
  is blocked only by module encapsulation (JEP 396 default); the `SecurityManager` sandbox is gone
  (JEP 411 → JEP 486, JDK 24). *(Slices need not be modules; only the security code must be.)*
- **The invariant:** *"no credential-bearing object is reachable from the slice, and the security
  module opens nothing to it."* Strongest form: **slice handle = stub holding an opaque id** (`long`),
  real credential + authenticated client live behind the boundary, node resolves the id on its side ⇒
  nothing to reflect. Plus **redacting `toString()`/serialization** (the §6 R5 leak class).
- **Capability injection:** runtime provisions **all** resources (Decision-4 follow-up: slice loads
  nothing itself). Note the completeness requirement (every authenticated downstream needs a mediated
  resource) + a generic "authenticated egress / sign-this-request" escape hatch that still never hands
  over the secret.
- **`Principal` + `ScopedValue` propagation** (transcript §6.2): (a) **binding authority behind the
  boundary** — the `ScopedValue` key lives in the security module; only the runtime binds it; slice
  reads via `SecurityContext.currentPrincipal()` (immutable value), never the key (*Why:* otherwise a
  slice rebinds to a forged admin Principal and elevates); (b) **propagation across async + nodes is
  the runtime's job** — snapshot/rebind across Promise hops (verify this survives the Promise runtime —
  §5); cross-node = **signed assertion verified against the identity plane**, then re-bound locally.
  `Principal` = immutable value object (id, roles/claims, auth method, expiry).
- **OBO/delegation:** keep the seam (every resource call already carries the Principal context), do
  **not** implement now (RFC 8693 later). *Why:* no current case; nothing speculative.
- **Documented assumption:** not a hard sandbox vs malicious bytecode (restate from threat model).

### 9. Policy & Audit (cross-cutting)
**`PolicyEngine`** (§4): `Principal × Action × Resource → Decision`. **Two RBAC domains, one engine:**
management-plane RBAC (operators) vs application RBAC (end-users) — distinct scopes/namespaces, shared
Principal model + engine (*Why:* different principals/lifecycles; conflating them bleeds privilege).
**`AuditSink`:** record every secret read, key wrap/unwrap, cert issuance, cloud `provision()` call,
and mgmt action; append-only, consider a hash-chain for tamper-evidence (revives #23). All new
management surfaces must follow the **REST→CLI→Docs triad** (invariant #1).

### 10. Configuration Model
TOML schema additions: `${secrets:}` reference syntax (unified), `[security]` / `[identity]` /
`[keys]` / `[cloud.credentials]` (now a reference, not a literal) sections, attestor selection,
provider/federation selection. Show before/after for the #206 cloud-credentials change.

### 11. Error Model
JBCT `Cause` taxonomy for each SPI (attestation failure, unwrap failure, secret-not-found,
lease-expired, policy-denied, identity-expired). Parse-don't-validate at boundaries.

### 12. Implementation Plan (risk-first, all in GA)
The phased sequence (transcript §8): (0) quick wins (#269 + R5 + #287 compose-env + #307); (1) this
spec; (2) identity plane; (3) keys plane; (4) secrets plane + #206; (5) hardening (policy/audit/#319/
#313). Note the envelope-version bump (#3) if slice-processor codegen changes.

### 13. Reconciliation to Existing Code
The capability table (transcript §7.3 + §9 anchors): each capability → current state (`file:line`) →
target → gap, tagged DONE/PARTIAL/STUB/MISSING. This is the implementer's map. **Include the
verification results from §5.**

### 14. Open Questions
List the genuinely-unresolved items (§8 of this plan) with options + a recommendation each.

### 15. References
Carry the verified citation list from transcript §10.

---

## 4. SPI contracts to formalize (sketches — finalize types & `Cause`s in the spec)

```java
// Plane 0 — attestation & identity
sealed interface AttestationEvidence permits JoinTokenEvidence, CloudIidEvidence, TpmQuoteEvidence {}
interface NodeAttestor {                       // issuer side (control plane)
    Promise<AttestedClaims> attest(AttestationEvidence evidence);   // node-id, role, trust-domain
}
interface IdentityIssuer {                     // the cluster CA
    Promise<NodeIdentity> issue(Csr csr, AttestedClaims claims);    // short-lived SVID-equiv cert
    Promise<NodeIdentity> renew(NodeIdentity current);
}
record NodeIdentity(X509Cert cert, SpiffeId id, Instant notAfter) {}

// Plane 1 — external certs (#88)
interface CertificateProvider {                // self-signed | ACM | GCP CM | Azure KV
    Promise<Certificate> obtain(CertRequest request);
    Promise<Certificate> renew(Certificate current);
}

// Plane 2 — keys / envelope
interface KeyProvider {                        // cluster_secret-KEK | Vault transit | cloud KMS
    Promise<DataKey>    generateDataKey(KeyId kekId);   // plaintext DEK + wrapped DEK
    Promise<byte[]>     unwrap(WrappedKey wrapped);
    Promise<WrappedKey> wrap(byte[] dek, KeyId kekId);
    KeyId               currentKekId();
}
record DataKey(byte[] plaintext, WrappedKey wrapped) {}
record WrappedKey(KeyId kekId, byte[] ciphertext) {}
record KeyId(String logical, int version) {}

// Plane 3 — secrets (EXTEND the existing SecretsProvider; keep static resolve back-compat)
interface SecretsProvider {
    Promise<String> resolveSecret(String path);                 // existing
    default Promise<Lease> lease(String path) { /* optional */ } // dynamic secret
    default Promise<Unit>  renew(LeaseId id) { ... }
    default Promise<Unit>  revoke(LeaseId id) { ... }
    default Promise<Unit>  revokePrefix(String prefix) { ... }   // emergency
}
record Lease(LeaseId id, String value, Duration ttl, Instant expiry) {}

// Plane 4 — cloud creds (#206)
interface CloudCredentialResolver {            // instance-identity | STS | leader-JIT broker
    Promise<CloudCredential> resolveForProvision(CloudProviderName provider, Principal leader);
}

// Cross-cutting
interface PolicyEngine { Promise<Decision> evaluate(Principal p, Action a, ResourceRef r); }
interface AuditSink    { Promise<Unit> record(AuditEvent e); }   // append-only

// Slice-facing (secret-free; lives in the EXPORTED security API package)
interface SecurityContext { Principal currentPrincipal(); }      // reads the ScopedValue; never exposes the key
```

The executing agent must: finalize every type, define the `Cause` set per call, mark which methods
are native-default vs federation-only, and show one worked example per plane (e.g. a storage segment
encrypt/decrypt round-trip through `KeyProvider` + `BlockEncryptor`).

---

## 5. Code you MUST verify while writing (tickets are hypotheses)

Open each, confirm, and record DONE/PARTIAL/STUB/MISSING with the **real** `file:line` in §13. Several
ticket line numbers were already stale this session — trust the code, not the ticket.

- [ ] `SecretsProvider.java:17` (+ the 7 sibling providers) — confirm the SPI shape before extending it.
- [ ] Node resolution wired at `AetherNode.java:4283-4288` via `withSecretResolution`; CLI env-only at
      `ClusterBootstrapCommand.java:120`.
- [ ] **#269 slice gap** — `SliceStore.java:271-337` (raw compose), `:236-256`, record `:160-167`
      (no secrets field), log-leak `:255-259`. Confirm the fix site.
- [ ] **#206 fan-out** — `BootstrapOverlayGenerator.java:125-134` emits literal `api_token`;
      `ClusterTopologyManagerRecord.provisionReplacement:482-531` (`active.get()` gating);
      `HetznerEnvironmentIntegrationFactory.java:46,63`.
- [ ] **#253** — `BlockEncryptor.java:51`; `AetherNode.java:2537` uses `none()` (prod unencrypted).
- [ ] gossip key separate/HKDF — `SelfSignedCertificateProvider.java:258-267` (salt `aether-ca-seed`),
      rotation `:152-157`.
- [ ] **#287 residual** — `SecureFiles.java:24-54`; `DockerComposeGenerator.java:80` (env injection).
- [ ] **#209** — `ClusterTrust` (commit `6293b206c`): confirm item 1; check items 2–5 (curl -k,
      http:// hardcodes, VIEWER default-role) — likely still open.
- [ ] **#88 (highest uncertainty)** — find whether ACM / GCP CM / Azure KV cert adapters actually
      exist + scheduler integration. Closure comment was unsubstantiated.
- [ ] **#307** — `CloudCredentials.java:34` (`default -> operationNotSupported`); `CloudProviderName.java`;
      `aether/docs/specs/cloud-provider-digitalocean.md` (Planned).
- [ ] **Principal async carry (the hand-wavy bit)** — find how request-ID propagates today and confirm
      whether a `ScopedValue` survives the Promise runtime's thread hops. If it does NOT auto-propagate,
      specify the snapshot/rebind hook. This is the one mechanism flagged as not yet concrete.

---

## 6. Bootstrapping order (specify this explicitly — it has a chicken-and-egg)

```
operator-provided cluster_secret
   → derive CA seed (HKDF) ─────────────► cluster CA ready
   → derive KEK seed (HKDF) ────────────► KeyProvider (cluster_secret-KEK) ready
   → first node join (authenticated by cluster_secret bootstrap anchor)
   → NodeAttestor verifies → CA issues node SVID
   → native secrets broker starts (its store encrypted via the KeyProvider)  ← depends on keys plane
   → ${secrets:} resolution available (pull)
   → cloud-credential JIT (leader, via broker + node identity)
```
The agent must state the ordering, the failure modes (e.g. broker can't start before the keys plane;
node can't get identity before the CA), and how federation backends (external KMS/Vault) slot in
without breaking the air-gapped native path.

---

## 7. Acceptance / done criteria for the spec

- [ ] Every related ticket (#139/#119/#269/#206/#253/#287/#209/#88/#307/#313/#319/#23) explicitly
      addressed in the §13 mapping table.
- [ ] Every design decision carries **Decision → Why → Rejected alternative**.
- [ ] All §4 SPIs fully typed with `Cause` sets and native-vs-federation labels; one worked example
      per plane.
- [ ] All §5 code claims verified and reconciled with real `file:line` (tagged DONE/PARTIAL/STUB/
      MISSING).
- [ ] Bootstrapping order (§6) specified with failure modes.
- [ ] Threat model + the "not a hard sandbox" assumption stated in writing.
- [ ] Locked decisions honored; any conflict surfaced rather than silently resolved.
- [ ] Open questions (§8) listed with options + recommendation each.
- [ ] House format + JBCT idioms + REST→CLI→Docs triad for new surfaces.

## 8. Open questions to resolve or explicitly defer (carry into the spec's §14)

1. **Intermediate CA** — single root vs root+intermediate for the cluster CA? (Recommend: intermediate,
   so the root can stay offline/derived and be rotated independently — justify or refute.)
2. **Native secrets-broker storage backing** — where it persists, and the bootstrap order vs the keys
   plane (§6).
3. **Policy model/language** — roles vs claims/capabilities; authoring + distribution.
4. **Principal async/cross-node mechanics** — the concrete snapshot/rebind hook + signed-assertion
   format (§5 last item).
5. **Revocation** — short-TTL-only vs CRL/OCSP-style for node identities.
6. **OBO/delegation** — confirm "seam only, not built" is acceptable for GA.

---

## 9. Ready-to-use agent prompt

> CHALLENGE MODE: $500 on the line. Produce a complete, correct, internally-consistent spec; verify
> every code claim; give a Why + Rejected-alternative for every decision.
>
> Author `aether/docs/specs/security-subsystem-spec.md` for the Aether unified security subsystem.
> **First read**, in order: `aether/docs/internal/progress/security-subsystem-spec-plan.md` (your work
> order — follow it exactly), its companion transcript
> `aether/docs/internal/progress/session-transcript-2026-06-23-security-subsystem-design.md` (the
> converged design + rationale), and repo `CLAUDE.md` (invariants). The architecture is **converged** —
> do not re-litigate the locked decisions in the plan's §1; if one is technically impossible, STOP and
> report instead of substituting your own.
>
> Write all five planes + the runtime/slice boundary + credential-less consumption, following the
> plan's §3 outline, formalizing the §4 SPIs, verifying the §5 code anchors against the actual source
> (tickets are hypotheses — trust the code; record real file:line), specifying the §6 bootstrapping
> order, and meeting the §7 acceptance criteria. JBCT idioms throughout (`Promise`/`Result`/`Option`/
> records/sealed). Every decision: **Decision → Why → Rejected alternative**. House spec format; no
> BSL header on the markdown. When done, report the reconciliation table and any locked-decision
> conflicts you hit.

---

*This plan is the authoritative work order; the transcript is the rationale; the spec is the
normative output. Keep all three consistent — if the spec diverges from a locked decision, the
divergence must be surfaced, not silently absorbed.*
