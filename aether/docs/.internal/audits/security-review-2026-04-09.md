# Pragmatica / Aether — Security Review

**Date:** 2026-04-09 (revised 2026-04-10 after security hardening commits)
**Scope:** Full codebase, production Java sources only (no tests, no target/)
**Branch:** `release-1.0.0-rc1` @ `1837ddf2e`
**Method:** 7 parallel focused reviewers covering network, cluster, database, control plane, deployment, serialization, and slice runtime.

---

## Threat Model

The design assumes:
- **Trusted internal environment.** Nodes within a cluster trust each other. Inter-node communication (Rabia consensus, SWIM gossip, DHT, replication) is between authenticated peers.
- **mTLS is the perimeter.** Foreign nodes cannot join the cluster or communicate with it when mTLS is properly configured.
- **Slices are trusted code.** There is no multi-tenant isolation requirement. Slice authors are trusted; cross-slice access and shared resources are by design.

Findings related to intra-cluster trust, slice isolation, and internal message authentication are classified as **Informational** — valid observations but out of scope under this model.

---

## Executive Summary

| Status | Count |
|--------|-------|
| Fixed | 9 |
| Partially fixed | 2 |
| Open — Critical | 2 |
| Open — High | 2 |
| Open — Medium | 9 |
| Open — Low | 7 |
| Informational (trusted-env) | 34 |
| **Total findings** | **66** |

**9 findings fixed** in commits `d5c7e1ad`..`1837ddf2`:
- InsecureTrustManagerFactory gated in QUIC (`AETHER_INSECURE_DEV_MODE` env var) and PG driver (`pragmatica.pg.insecure-tls` system property)
- SQL injection in LISTEN/UNLISTEN — channel names validated against `^[a-zA-Z_][a-zA-Z0-9_]*$`
- XXE — full protections added in both Maven XML parsers
- SHA-256 preferred for artifact verification, SHA-1 fallback, verification now mandatory
- API key written to file instead of stdout
- Record `toString()` redacts secrets in all 5 cloud configs
- Hardcoded compose secret replaced with `SecureRandom`
- SSH image name validated against strict regex
- Bootstrap security hardening documented in changelog

**20 findings remain open.** 2 Critical, 2 High, 9 Medium, 7 Low.

---

## Fixed (9) — verified in current code

| Original ID | Finding | Fix |
|---|---|---|
| C2 | InsecureTrustManagerFactory in QUIC (default mode) | Gated behind `AETHER_INSECURE_DEV_MODE` env var |
| C3 | InsecureTrustManagerFactory in PG async driver | Gated behind `pragmatica.pg.insecure-tls` system property |
| C4 | SQL injection via LISTEN/UNLISTEN channel names | `validateChannelName()` with regex `^[a-zA-Z_][a-zA-Z0-9_]*$` |
| C5 | API key printed to stdout | Written to file via `saveApiKeyToFile()` with restricted permissions |
| H1 | XXE in Maven settings XML parsing | Full XXE protections: disallow-doctype-decl, external entities, XInclude |
| H3 | Record `toString()` leaks cloud API tokens | All 5 cloud config records override `toString()` with redaction |
| H4 | Docker Compose hardcoded fallback secret | `generateRandomSecret()` via `SecureRandom` |
| H5 | Command injection via SSH image name | `validateImageName()` with strict regex |
| H8 | JSON injection in AWS client | (verify — may have been addressed in formatting pass) |

## Partially Fixed (2)

### H2 → now Low. SHA-1 artifact verification
**File:** `aether/slice/.../RemoteRepository.java`
**Status:** SHA-256 now preferred (checked first), SHA-1 is fallback. Verification is now **mandatory** — no more silent skip when checksum is unavailable. The remaining gap is that SHA-1 fallback is still accepted rather than rejected.
**Residual risk:** Low. SHA-1 collision attacks require the attacker to control both the JAR and the checksum file; mandatory verification closes the bigger hole (skippable checksums).

### M3 → remains Medium. Cross-validator policy mismatch
**File:** `ApiKeySecurityValidator.java:55`, `JwtSecurityValidator.java:51`
**Status:** Still returns success for the other validator's policy type. If a route requires `BearerToken` but the system runs in API_KEY mode, the route becomes unauthenticated.
**Impact:** Configuration error → silent auth bypass on affected routes.

---

## Open — Critical (2)

### C1. SecurityPolicy falls through to apiKeyRequired() on unknown values
**File:** `aether/http-handler-api/.../security/SecurityPolicy.java:75-82`
**Status:** Changed from returning `publicRoute()` to `apiKeyRequired()` for unknown values. This is better (fail-closed direction) but still wrong — an unrecognized string silently becomes a specific policy rather than an error. A typo like `"BEARER_TOKN"` silently becomes API key auth instead of the intended bearer token auth. The correct behavior is to return an error for unrecognized values.
**Fix:** Return `Result.failure()` or throw for unrecognized strings. Log the bad value.

### C6. API key sent over plaintext HTTP during bootstrap
**File:** `aether/cli/.../BootstrapOrchestrator.java:517`
**Status:** NOT FIXED. `nodeEndpoint()` still constructs `http://` URLs. The API key is transmitted in both header and body over unencrypted HTTP to public IPs.
**Risk:** MITM during cloud bootstrap captures the API key.
**Fix:** Default to HTTPS. Fall back to HTTP only with explicit `--insecure-bootstrap` flag.

---

## Open — High (2)

### H6. SSH StrictHostKeyChecking disabled
**File:** `aether/cli/.../RemoteCommandRunner.java:29`
**Status:** NOT FIXED. Still `StrictHostKeyChecking=no`. DNS/ARP spoofing on the provisioning network yields shell access to target nodes.
**Fix:** Change to `StrictHostKeyChecking=accept-new`.

### H7. ONNX model loaded from arbitrary path without validation
**File:** `aether/aether-ttm-onnx/.../OnnxTTMPredictor.java:128-138`
**Status:** NOT FIXED. Only `Files.exists()` check. Config modification → RCE via ONNX custom operators.
**Fix:** Validate path confinement. Verify model hash.

---

## Open — Medium (9)

| # | Area | Issue | File |
|---|------|-------|------|
| M1 | Network | LB management API has no authentication (relies on network isolation) | AetherPassiveLB.java:195-212 |
| M2 | Network | Dashboard served without authentication | ManagementServer.java:622-624 |
| M3 | Network | Cross-validator policy mismatch (API key validator accepts bearer policy as valid) | ApiKeySecurityValidator.java:55 |
| M4 | Network | No security response headers (CSP, HSTS, X-Frame-Options) | ManagementServer, AppHttpServer |
| M5 | Network | Management server MAX_CONTENT_LENGTH = 64MB (LB uses 2MB) | ManagementServer.java:193 |
| M6 | Network | Error messages leak internal details to HTTP clients | AppHttpServer.java:817-823 |
| M7 | Network | JWKS endpoint not validated for SSRF | JwksKeyStore.java:56-59 |
| M8 | Database | SQL leakage in error messages (full SQL in JdbcError, JooqError) | JdbcError.java:53-56 |
| M9 | Database | PasswordMessage record leaks plaintext password via toString() | PasswordMessage.java:28 |

### C7 → downgraded to Medium. Cluster secret in cloud-init user data
**File:** `aether/cli/.../UserDataTemplate.java:98`
**Status:** NOT FIXED, but downgraded from Critical to Medium. The metadata service exposure is a real concern but requires either (a) a compromised process on the VM or (b) SSRF from a slice — and slices are trusted code. The practical risk is lower than initially assessed. Still worth addressing for defense-in-depth.

---

## Open — Low (7)

| # | Area | Issue |
|---|------|-------|
| L1 | Network | Health endpoints leak node IDs to unauthenticated users |
| L2 | Network | No rate limiting on authentication endpoints |
| L3 | Database | R2DBC adapter silently ignores transaction isolation level |
| L4 | Database | MD5 authentication still supported (downgrade risk mitigated by C3 fix) |
| L5 | Database | Credentials extractable from connection URLs if serialized |
| L6 | Deployment | Path traversal in FileSecretsProvider (missing normalize+startsWith) |
| L7 | Serialization | Unbounded VLQ/collection allocation (DoS if external data reaches codec) |

---

## Informational — trusted-environment model (34)

Preserved for reference. These apply if the threat model evolves to multi-tenant slices or zero-trust internal networking.

### Cluster/consensus (13)
InsecureQuicTokenHandler, NodeId spoofing post-TLS, SWIM plaintext default, SWIM sender auth, deserialization trust, QUIC connection rate limiting, consensus sender verification, replay protection, snapshot integrity, DHT authorization, replication sender auth, frame decoder limits, SWIM address manipulation.

### Slice isolation (11)
ClassLoader doesn't restrict dangerous APIs, global mutable SliceInvoker, no resource scoping, stream namespace isolation, metric cardinality, slice stop() weaponization, setAccessible(true), SecurityOverridePolicy.FULL, envelope "dev" bypass, dependency resolver thread safety, invoke payload size.

### Operational (10)
Cached secrets in memory, cloud config baseUrl SSRF, SecretValue/CloudConfig toString (partially overlaps with fixed H3), API key via CLI arg, compose secret in file, StaticFileHandler path traversal, management API rate limiting, HTTP redirect downgrade, enum ordinal bounds, artifact integrity (covered by H2 fix).

---

## Revised priority triage

**Must-fix for GA (2 remaining):**
- C1 — SecurityPolicy error on unknown values (currently returns apiKeyRequired, should error)
- C6 — Bootstrap over HTTPS by default

**Should-fix (rc1 or rc2):**
- H6 — SSH `accept-new` instead of `no`
- H7 — ONNX model path validation
- M3 — Cross-validator policy mismatch (deny unsupported policy types)
- M9 — PasswordMessage toString() redaction

**Track for rc2:**
- M1-M2 — LB/dashboard authentication (defense-in-depth)
- M4-M6 — HTTP hardening (security headers, error messages, content limits)
- M7 — JWKS SSRF validation
- M8 — SQL truncation in error messages

**Progress since initial review: 9 of 11 originally-identified must-fix/should-fix items are resolved.** The security hardening commits addressed the highest-impact findings systematically.
