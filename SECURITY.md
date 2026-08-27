# Security Policy

This repository contains two things with different security shapes: **Pragmatica Core** (an
in-process functional-programming library — `Result<T>`/`Option<T>`/`Promise<T>` — with no network
surface or trust boundary of its own) and **Aether** (a distributed application runtime with real
nodes, real network traffic, and a real trust model). Everything below the "Reporting" section is
about Aether; a memory-safety or logic bug in Core is reportable the same way, it just has no trust
model to describe.

## Reporting a Vulnerability

Please use [GitHub's private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing/privately-reporting-a-security-vulnerability)
for this repository (repository **Security** tab → **Report a vulnerability**) rather than a public
issue. This opens a private advisory visible only to maintainers until a fix is ready.

Do not open a public GitHub issue for a suspected vulnerability, and do not include exploit details
in a public PR.

## Supported Versions

Aether is pre-GA (`1.0.0-rc3` at this writing). There is currently one active release line — the
latest release candidate — and no formal backport/LTS policy yet. See the versioning and
compatibility document (once published) for the post-GA policy.

## Aether's Trust Model

### The single-trust-domain assumption

**Aether assumes every node in a cluster, and every slice deployed to it, belongs to one
application and is not mutually hostile.** [design intent — unverified] There is no per-tenant or
per-slice sandboxing against a *deliberately malicious* deployer. If you need to run mutually
untrusting workloads, run them in **separate clusters**, not as separate slices in one cluster.

Two consequences follow directly:

- **All nodes in a cluster trust each other completely.** Any node that can complete the join
  handshake is a full member — able to reach every slice, every KV key, and (if storage encryption
  is enabled) every data key in that cluster. There is currently no per-node revocation short of
  removing the node and rotating the shared secret.
- **The runtime/slice boundary is an accident boundary, not a security sandbox.** Each slice loads
  in its own `SliceClassLoader` [mechanism: `aether/slice/src/main/java/org/pragmatica/aether/slice/SliceClassLoader.java`],
  which isolates classpaths across slices/versions. This is **not** a hardened security boundary:
  the codebase does not currently use JPMS module encapsulation anywhere in the runtime (no
  `module-info.java` exists in `aether/`), so a slice that obtains a reference to a runtime object
  can use reflection against it like any other in-process Java code. Treat "a slice can't see the
  runtime's secrets" as a design goal under active work, not a guarantee you can currently rely on
  against a malicious slice author [design intent — unverified]. `Unsafe`, JNI, and deserialization
  gadgets are not defended against at all, by design — this is not a hard sandbox against
  intentionally malicious bytecode, on any Java runtime.

### What Aether actually defends against today

Given the above, the realistic threat this version defends against is **an untrusted network, not
an untrusted slice**: someone who can reach your cluster's ports but does not hold your
`cluster_secret` or a valid API key.

| Surface | Mechanism | Evidence |
|---|---|---|
| Node-to-node TCP (consensus, invocation, DHT) | mutual TLS, certs derived from a shared `cluster_secret` via HKDF | [mechanism: `aether/docs/operators/tls-certificates.md`; `SelfSignedCertificateProvider`] |
| SWIM gossip (UDP) | AES-256-GCM, daily key rotation with overlap | [mechanism: `AesGcmGossipEncryptor`, per `aether/docs/architecture/10-security.md`] |
| Management API | API-key authentication + role-based authorization (ADMIN / OPERATOR / VIEWER) | [mechanism: see "Default posture" below] |
| Cluster secret at rest (file) | `aether.toml` and the CLI's persisted `api-key` file are written `chmod 600` | [mechanism: `aether/aether-config/.../SecureFiles.java` — `writeSecure`/`restrictToOwner`, owner-read/write only, POSIX systems] |

All four of these authenticate **the transport or the caller**, not the slice code running behind
it. None of them isolates one slice's data from another slice in the same cluster (see above).

### Default security posture (management API)

As of a hardening fix tracked under **#290**, a node with no explicit `security_mode` configured
runs in **`API_KEY`** mode, not open access — the management plane and dashboard require a
credential by default [mechanism: `aether/aether-config/src/main/java/org/pragmatica/aether/config/ConfigLoader.java`,
`populateAppHttpConfig`: `explicitMode.or(SecurityMode.API_KEY)`]. If you provisioned no API key
yourself, the first elected leader generates one random `ADMIN` key on first startup and prints it
**once**, prominently, to its log and stdout [mechanism: `aether/node/src/main/java/org/pragmatica/aether/node/BootstrapAdminKeyRegistrar.java`].
Capture that key — it is not retrievable afterward except by rotating it via `/api/cluster/keys`.

An operator can still explicitly set `security_mode = "none"` in `aether.toml` to disable
authentication entirely (an explicit setting always wins over the default) — appropriate only for a
single-node local/dev instance, never for anything reachable over an untrusted network.

Note: this default applies to nodes started from `aether.toml` via the normal CLI/bootstrap path.
The bare in-process config builders used by test harnesses (`AppHttpConfig.appHttpConfig()` and
friends, used by Ember/Forge) still default to `NONE` — that is a test-harness convenience, not the
production default, and those harnesses are not meant to be exposed to a network.

**To configure roles**, give each API key an `authorization_role` (default `VIEWER` if omitted):

```toml
[app-http.api-keys."your-api-key-value"]
name              = "ci-deploy"
authorization_role = "OPERATOR"   # ADMIN | OPERATOR | VIEWER
```

`ADMIN` has full access including configuration changes; `OPERATOR` can deploy/scale/update but not
change configuration; `VIEWER` is read-only. Keys can also be supplied via the `AETHER_API_KEYS`
environment variable at startup.

### `cluster_secret` hygiene — a known gap

`cluster_secret` seeds the cluster's derived CA (mTLS) and, per the on-disk file, is chmod-600'd
when written by the CLI. **However**, the Docker Compose generator currently writes it as a plain
environment variable in the generated compose file
(`AETHER_CLUSTER_SECRET: "<value>"`) [mechanism: `DockerComposeGenerator.java` — the value is visible
via `docker inspect` on any host that can reach the Docker socket]. This is a known, tracked residual
(internally referenced as #287) — **not yet closed**. If you generate a Compose deployment, treat
the resulting `docker-compose.yml` and the Docker daemon's inspect API as secret-bearing, and control
access accordingly, exactly as you would a file containing the secret in the clear.

### Recognizing an untrusted-network deployment

Ask these questions before exposing a cluster beyond a private network:

1. **Is `security_mode` anything other than the default or `API_KEY`/`JWT`?** An explicit `"none"`
   means the management API has no authentication at all.
2. **Is `cluster_secret` reachable by anyone who shouldn't have full cluster membership?** Anyone
   who obtains it can mint a trusted node identity and join as a full peer — there is no
   per-node revocation short of rotating it. Check both the config file (should be `chmod 600`) and,
   if using Docker Compose, the compose file / `docker inspect` output (see above).
3. **Are the consensus/gossip/DHT ports (not just the management API port) reachable from an
   untrusted network?** mTLS protects node traffic in transit, but a node that completes the join
   handshake is fully trusted — network exposure of these ports is exposure of cluster membership,
   not just of an API.
4. **Are you running workloads from more than one trust owner in the same cluster?** Don't — see
   "single-trust-domain" above. Use separate clusters.

### Storage-at-rest encryption

Aether's storage layer has an AEAD block-encryption mechanism, but production defaults to writing
segments **unencrypted** — there is currently no wired key source for it in the default path
[design intent — unverified; tracked internally as #253]. Do not rely on at-rest encryption of
cluster storage in this release unless you have independently verified your deployment wires a key
provider.

## Reference material

- [`aether/docs/architecture/10-security.md`](aether/docs/architecture/10-security.md) — current
  mTLS/gossip/RBAC architecture with diagrams.
- [`aether/docs/operators/tls-certificates.md`](aether/docs/operators/tls-certificates.md) —
  operator guide to certificate configuration and troubleshooting.
- [`aether/docs/specs/security-subsystem-spec.md`](aether/docs/specs/security-subsystem-spec.md) —
  **Draft**, forward-looking design for identity-based (SPIFFE-style) node auth, envelope
  encryption, and unified secrets management. Describes target architecture, not current behavior;
  most of it is not yet built. Read this policy document, not that spec, for what is true today.
