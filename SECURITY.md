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

Aether is pre-GA (`1.0.0-rc4` at this writing). There is currently one active release line — the
latest release candidate — and no formal backport/LTS policy yet. See
[`aether/docs/reference/versioning-and-compatibility.md`](aether/docs/reference/versioning-and-compatibility.md)
for what is and isn't decided yet on versioning, including the open node-version-skew gap.

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
  removing the node and rotating the shared secret. For the gossip layer specifically there is one
  in-place mitigation, `aether cluster rotate-gossip-key` (`POST /api/v1/cluster/gossip-key/rotate`,
  ADMIN), which pushes a fresh, non-derived gossip key through consensus without a restart (#683).
  Three facts about it, accepted and stated:
  - **The key material lives in the consensus log and its snapshots, readable by any KV reader** —
    accepted by the §5.8 delivery design because a per-node out-of-band channel would need a second
    trust root, and every KV reader is already a full member.
  - **After the first rotation the `cluster_secret`-derived daily key is permanently superseded on
    that cluster.** The delivered key is what every node encrypts under from then on. The first
    rotation carries NO decrypt overlap (there is no prior record to carry), so boot-key ciphertext
    is rejected from the moment it lands; subsequent rotations carry the previous key.
  - **A ROTATED CLUSTER CANNOT GROW UNTIL AN OPERATOR ACTS — INCLUDING AUTO-HEAL.**

    **If a node will not join after a rotation, CHECK THE SEED NODES' LOGS, NOT THE NEW NODE'S.**
    Look for `Failed to decrypt gossip from <id>` on the seeds. This is the first thing to know
    because it inverts where you will instinctively look: a node the cluster has never heard of
    logs **nothing** about the cause — worse, it logs `Aether node <id> started, cluster forming...`
    and then stays quiet, because an unreachable quorum is retried and never exits. The only
    evidence is on the healthy machines you have no reason to suspect.

    The mechanism: a node booting after a rotation derives its gossip key from `cluster_secret`,
    which the rotated cluster no longer accepts; its SWIM datagrams are dropped and it cannot decrypt
    the cluster's either. SWIM therefore discovers no peers, the QUIC dial set stays self-only, no
    quorum forms, and the consensus replay that carries the rotation record — the only way to obtain
    the cluster key — never runs. The node cannot join, and the cycle cannot resolve itself.
    **So an emergency rotation, performed precisely because something was compromised, leaves the
    cluster unable to self-heal: auto-heal replacements, scale-up and re-provisioned nodes all fail
    to join until they are given the rotated key material out of band.** Existing running nodes are
    unaffected.

    Which nodes say so for themselves:
    - A **restarted existing member** refuses to boot with a `FATAL` line naming gossip-key
      divergence (#683) — its peers still hold it in their configured seed set, so they probe it and
      it can see gossip arriving under a key id it does not hold.
    - A **node the cluster has never heard of** — an auto-heal replacement or scale-up node — cannot
      detect it at all. Nobody probes an address they do not know, so it receives nothing, and its
      silence is indistinguishable from a partition, down seeds, or a wrong advertise address. Hence
      the instruction above.

    A general key-delivery path for joining nodes is not in rc4; it is an architecture change (it
    needs either a second trust root or a deliberately weakened revocation) and is tracked separately.
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
authentication entirely (an explicit setting wins over the default, with the `enabled = false` exception below) — appropriate only for a
single-node local/dev instance, never for anything reachable over an untrusted network.

The same default holds for the in-process config builders (`AppHttpConfig.appHttpConfig()` and
friends) since #665: with no `SecurityMode` named they yield `API_KEY`, and with no key configured
that is fail-closed — the cluster bootstrap admin key is the only credential accepted. `Main`'s
fallback when `[app-http]` is absent or disabled uses the same builder, so a node with no app-HTTP
section now has the same fail-closed posture for its Management API as an `aether.toml` with no
`security_mode`. **This is a behaviour change for such nodes, not a wording change:** before #665
that fallback yielded `NONE`, and under `NONE` the management server dispatches every request
without consulting any validator [mechanism: `ManagementServerImpl.handleRequest` gates on
`securityEnabled`; the #573 `denyUnlessPublicValidator` installed for that arm was never reached], so the
Management API, forwarded requests, websockets and unauthenticated artifact `PUT`
(`MavenProtocolRoutes.admitPush` → `SECURITY_DISABLED`) were all open. A node upgraded across #665
with no `[app-http]` section (or `enabled = false`) refuses those requests until the cluster
bootstrap admin key is presented; present that key, or declare `[app-http]` with the mode you
mean. Note the one case where an explicit setting does NOT win: `[app-http] enabled = false` with an
explicit `security_mode = "none"` is discarded whole by `Main`'s fallback (one flag governs two
planes — #573) and the fail-closed default applies. The only way
to `NONE` without naming the mode is
`AppHttpConfig.insecureAppHttpConfig(port)`, and its name is the opt-in: Ember passes `NONE`
explicitly (`EmberCluster.withAppHttpSecurity` overrides it, which is how Forge authenticates), and
unit tests that need an open listener call the insecure builder by name. Those harnesses are not
meant to be exposed to a network.

**To configure roles**, give each API key an `authorization_role` (default `VIEWER` if omitted):

```toml
[app-http.api-keys."your-api-key-value"]
name              = "ci-deploy"
authorization_role = "OPERATOR"   # ADMIN | OPERATOR | VIEWER
```

`ADMIN` has full access including configuration changes; `OPERATOR` can deploy/scale/update but not
change configuration; `VIEWER` is read-only. Keys can also be supplied via the `AETHER_API_KEYS`
environment variable at startup.

### `cluster_secret` hygiene

`cluster_secret` seeds the cluster's derived CA (mTLS) and, per the on-disk file, is chmod-600'd
when written by the CLI. The `aether cluster scaffold` compose template
(`DockerComposeTemplate.java`) and the shipped reference file (`aether/docker/docker-compose.yml`)
both emit `AETHER_CLUSTER_SECRET` as a `${AETHER_CLUSTER_SECRET:?...}` shell-substitution
reference, never a literal [mechanism: `DockerComposeTemplate.appendCommon`] — the generated
compose file itself carries no secret value, so a copy of it in git, in a backup, or on disk is not
secret-bearing on its own (#684, distinct from #287, which closed only the on-disk `aether.toml`
permission hygiene — chmod 600, via `SecureFiles.writeSecure`/`restrictToOwner`. A related exposure
of the same secret named in #287's own discussion — `docker run -e AETHER_CLUSTER_SECRET=...` and
inline-JVM-env-on-the-SSH-command-line, on live bootstrap/redeploy paths in
`BootstrapPhaseDeploy.java` — was not addressed by that closure and remains open; it is a distinct
code path from the generated-file vector #684 closes). The `DockerComposeGenerator`
class, which used to bake a literal `AETHER_CLUSTER_SECRET: "<value>"` into a separate,
never-wired code path, has been deleted rather than fixed — it had zero production callers, so
patching it would not have closed anything a real deployment used.

**What this does not close.** Once `docker compose up` resolves the reference, the running
container's environment carries the actual secret value like any env-var-delivered secret, and
remains visible via `docker inspect` (or `/proc`) on any host that can reach the Docker socket.
This is unchanged by #684 and is inherent to env-var secret delivery generally, not specific to
Aether's compose path — treat any host that can run `docker inspect` against a cluster container as
able to read `cluster_secret`, and scope Docker socket access accordingly.

**Migrating a compose file generated before this fix.** A `docker-compose.yml` produced by
`aether cluster scaffold` before #684 has the literal value baked in under
`AETHER_CLUSTER_SECRET: "change-me-cluster-secret"` (or whatever you edited it to). To migrate:
note the actual secret value you are running with, `export AETHER_CLUSTER_SECRET=<that value>` in
the shell that runs `docker compose up` (or put it in a git-ignored `.env` file next to the compose
file), then either regenerate the file with `aether cluster scaffold` and reapply your local edits,
or hand-edit the `AETHER_CLUSTER_SECRET:` line to
`"${AETHER_CLUSTER_SECRET:?export AETHER_CLUSTER_SECRET before docker-compose up}"`. The
regenerated or hand-edited file carries no secret value of its own, but that does **not** make it
safe to commit outright — confirm every literal was replaced with the `${...}` reference form
first, and never commit the exported value itself. If the old file with the literal value was ever
committed or otherwise left where it could have been read, rotating `cluster_secret` is the only
way to invalidate that exposure; deleting the file does not undo a leak into git or shell history.

Separately: the daily-rotated gossip key described above is itself HKDF-derived from
`cluster_secret`, so rotating `cluster_secret` does not immediately revoke gossip decryption for
an attacker who captured it beforehand; the KV-delivered path built for exactly this
case (`GossipKeyRotationKey`, pushing independent key material without a full secret
rotation/restart) has no production writer and no CLI/admin trigger, so it cannot currently be
invoked — tracked as #683.

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
