# Aether Operator-Surface Design-Completeness Assessment

**Date:** 2026-06-11
**Scope:** Dashboard · Cloud integration · Management API · CLI · Cluster bootstrapping
**Method:** Four parallel investigation agents over `feature/stream-namespaces-impl` HEAD, then three focused re-verification passes to pin every finding to a current `file:line` with a code excerpt and a CONFIRMED / PARTIAL / WITHDRAWN verdict (the first pass had stale line numbers after recent CLI/bootstrap refactors).
**Companion docs:** `design-completeness-assessment-2026-06-10.md` (whole-system), `cluster-topology-architecture-audit-2026-06-10.md` (topology — cross-referenced where bootstrap overlaps), `security-review-2026-04-09.md`.

> These are the operator-/control-plane-facing surfaces — the parts an SRE touches, not the data plane. The pattern that dominates here is the same "seam disease" seen elsewhere: capable backends whose last operator-facing wire was never pulled (deploy/rolling-update routes the UI can't drive), plus a cluster of transport-security shortcuts that are fine for a lab and dangerous in production. KV-in-memory, aspect-observability, and Rabia design choices are **not** treated as gaps (per established design principles).

---

## Executive summary

| Subsystem | Readiness | Headline risk |
|---|---|---|
| **Dashboard** | Partial — viewer drifted off the API contract | Multiple panels silently 404 (wrong path/verb); live alerts never render; secured mode sends no initial state; ops control read-only + rolling-update backend absent; fabricated percentiles; 0 JS tests |
| **Cloud integration** | Mixed — provisioning real, a few hardening niceties | Transport defaults (TrustAll/SSH-TOFU/secret-in-config) are **deliberate** for private-network elastic clusters (A.6); residual is LOW secret-at-rest hygiene (#287). Real gaps: hardcoded role label, incomplete orphan cleanup, no quota cap |
| **Management API** | Functional, insecure-by-default | `SecurityMode.NONE` default while docs claim "all endpoints require auth"; coarse prefix RBAC; no versioning |
| **CLI** | Broad coverage, sharp edges | Destructive commands (drain/scale/migrate/restore) have no confirmation; `events --follow` missing |
| **Bootstrap** | Works happy-path; one real safety bug | Config push has no version fence (`expectedVersion:0` skips CAS) → concurrent/repeat bootstrap blind-overwrites mutable config (#289); single-node quorum split-brain (#295). Transport trust model is deliberate (A.6) |

**Cross-cutting "security" theme — re-evaluated (see A.6).** The first pass flagged a family of "lab-grade transport" defaults (TrustAll TLS, SSH host-key-checking off, `cluster_secret` materialized in cloud-init/argv/config) as a compounding security cluster. **On review this is mostly the deliberate trust model for internal cluster transport** — a single trust domain on a *trusted private network*, with elastic membership where nodes cannot be pre-provisioned, and TLS present only because QUIC has no plaintext mode. SEC-1/SEC-2/SEC-4 were closed as by-design; what genuinely survives is (a) secret-at-rest hygiene (`chmod 600`, off-argv — LOW, #287), (b) an orthogonal config-overwrite concurrency bug (#289), and (c) the fact that the single-trust-domain assumption is **undocumented** and the defaults would mislead an operator who spanned an untrusted network (#313). This was authorized defensive review of the project's own runtime; the correction is recorded here in full for traceability.

---

## A. Cross-cutting security cluster (file first, fix once)

> **SUPERSEDED — read with A.6.** This section was written under a per-node-PKI yardstick that does not fit internal cluster transport on a trusted private network with elastic membership. After maintainer review, SEC-1/SEC-2/SEC-4 are **deliberate design** (tickets #285/#286/#288 closed), SEC-3 is downgraded to a LOW hygiene item (#287), SEC-5 stands (#289), and the real residual is a documentation/guardrail gap (#313). The SEC-1..SEC-5 detail below is retained as an accurate description of the *mechanism*; see **A.6** for the corrected interpretation and dispositions.

These span cloud + bootstrap; file as standalone security tickets and reference from both subsystem sections rather than duplicating.

### SEC-1 — `TrustAllManager` disables TLS cert verification on the real cluster path · **HIGH** · **#285**
- **Verdict:** CONFIRMED (production wiring, not test-only).
- `aether/cli/.../cluster/ClusterHttpClient.java:42-67` (`enableTlsSkipVerify()` swaps `HTTP_OPS_REF` to a `TrustAllManager` whose `checkServerTrusted`/`getAcceptedIssuers` are empty), triggered by `ClusterBootstrapOrchestrator.java:89` (`if (config.operations().tls().autoGenerate()) {ClusterHttpClient.enableTlsSkipVerify();}`). Duplicate trust-all path in `AetherCli.java:187-207,4023`.
- All formation POSTs (`BootstrapPhaseFormation.java:233 httpPost(url, body, apiKey)`) and health/quorum polling ride this client.
- **Impact:** when `tls.autoGenerate` is on, every management/formation call disables cert verification, exposing the operator's configured admin `X-API-Key` (sent at `ClusterHttpClient.java:168/182/196/242`) to MITM. (Subsumes cloud-C6 and bootstrap-T1 transport facet.)

### SEC-2 — SSH `StrictHostKeyChecking=no` + `UserKnownHostsFile=/dev/null` in prod provisioning · **HIGH** · **#286**
- **Verdict:** CONFIRMED (cli path used in production deploy/restart).
- `aether/cli/.../cluster/RemoteCommandRunner.java:27-32` (prod), also `aether/cloud-tests/.../RemoteCommandRunner.java:28-29` (test). Consumed by `BootstrapPhaseDeploy.java:551,580` and `WaveExecutor.java:336`.
- **Impact:** node deploy/restart accepts any host key, so an interceptor can impersonate the target node and receive the cluster secret pushed over that SSH session.

### SEC-3 — `cluster_secret` written cleartext into cloud-init user-data and onto `docker run` argv · **HIGH** · **#287**
- **Verdict:** CONFIRMED.
- `aether/cli/.../cluster/UserDataTemplate.java:183` (`sb.append("AETHER_CLUSTER_SECRET=\"").append(clusterSecret)...`; re-emitted at 222, 253); on the container command line at `BootstrapPhaseDeploy.java:331` (`-e AETHER_CLUSTER_SECRET="..."`, also :351,:575).
- **Impact:** the cluster join secret is readable via the cloud metadata endpoint (any SSRF or on-box process) for the instance lifetime, and via `ps`/`docker inspect`/shell history on the node. (Subsumes cloud-C7 + ARGV.)

### SEC-4 — Symmetric-secret trust with no certificate/identity pinning · **HIGH** · **#288**
- **Verdict:** CONFIRMED.
- Trust derives solely from the shared `cluster_secret` (`Main.java:189-194 resolveClusterSecret`); peer cert validation is the same empty `checkServerTrusted` no-op (`ClusterHttpClient.java:57-66`); the identical secret is injected to every node (`UserDataTemplate.java:183`).
- **Impact:** anyone holding `cluster_secret` is fully trusted and certs are never validated — no defense against a rogue/compromised node or interposed endpoint presenting the secret.

### SEC-5 — Re-bootstrap clobbers cluster identity (`expectedVersion:0`, no generation/term fence) · **HIGH** · **#289**
- **Verdict:** CONFIRMED. Cross-ref topology-audit **H2** (unfenced re-bootstrap) and **M1** (cluster-identity instability).
- `ClusterBootstrapOrchestrator.java:92-106 freshBootstrap` runs the full phase chain with zero remote generation query; the only guard is a LOCAL config-hash on `--resume` (`:126-135`), bypassed by fresh runs. `BootstrapPhaseFormation.java:274 buildConfigJson` always posts `"expectedVersion":0` (unconditional overwrite, never reads current). No `generation|term|epoch` reference exists in the formation path.
- **Impact:** re-running `aether cluster bootstrap` against a live cluster rewinds/clobbers cluster identity.

> **Note (bootstrap-T1, already partially fixed):** the *generated* formation key is now SHA-256 hashed before KV storage (`KvStoreApiKeyHasher.hashKey`, `BootstrapPhaseFormation.java:180`) and persisted locally with owner-only perms (`:79-84`). That sub-claim is **withdrawn**; the residual exposure is the *configured admin key* over the SEC-1 channel.

---

## A.6 — Deep dive + correction: the transport "shortcuts" are mostly the deliberate trust model (2026-06-11)

> **REVISED after maintainer review.** The threat-model writeup below was first framed as a five-link "kill chain." That framing applied the **wrong yardstick** — per-node verified PKI — to a subsystem whose actual design constraints are *internal cluster transport, on a trusted private network, with elastic membership and no node pre-provisioning*. Under the correct lens, **most of the cluster is deliberate and correct**, and the security tickets were dispositioned accordingly (see the end of this section). The mechanism facts (file:line) all still hold; what changed is their severity and interpretation. The technical detail is retained below because it is the accurate description of *how* the trust model works — read it as "the design," not "the vulnerability."

**The correct lens (why most of this is by design):**
- Cluster transport is a **single trust domain on a trusted private network**. QUIC has no plaintext mode, so TLS is a *transport requirement*, not an identity boundary.
- Nodes are **ephemeral** — they come and go with scale. You cannot pre-provision or allowlist certificates for nodes you don't know will exist. A shared secret that lets any authorized node derive the cluster CA and mint its own identity is the **correct** primitive for this (same class as Consul/Nomad gossip keys, K8s bootstrap tokens, etcd initial-cluster-token). "No nodeId allowlist" is the *definition* of elastic membership, not a missing control.

The original "unifying fact" — still accurate as **mechanism**, now read as **design**:

> **`cluster_secret` is not a join password — it is the cluster's Certificate Authority seed.** `SelfSignedCertificateProvider` runs HKDF over `cluster_secret` to derive a *deterministic CA keypair that is identical on every node* (`integrations/net/tcp/.../SelfSignedCertificateProvider.java:57-58,98-100,137-147`; fed at `aether/node/.../Main.java:119,189-194`). Node certs are random keypairs **signed by that CA, with `CN=<nodeId>` chosen by the presenter and never verified** (`SelfSignedCertificateProvider.java:152-174`). The cluster QUIC transport trusts **any cert that chains to the CA — no SAN/nodeId allowlist** (`QuicSslContextFactory.java:136-157,164-173`; `QuicTlsProvider.java:38-45`). So holding the secret = minting a valid identity for *any* node = full transport trust.

### Default vs opt-in — four of five are out-of-the-box

| Shortcut | Trigger | Verdict |
|---|---|---|
| SEC-1 TrustAll bootstrap TLS | `tls.autoGenerate` **defaults `true`** (`TlsDeploymentConfig.java:18`, `OperationsConfig.java:18-23`; wizard `ClusterInitCommand.java:287-291`); sole caller `ClusterBootstrapOrchestrator.java:89` | **DEFAULT** — `aether cluster bootstrap` with no flags installs `TrustAllManager`. (Day-2 CLI `AetherCli.java:172-184` *does* verify unless `-k` — the TrustAll default is specific to the bootstrap client.) |
| SEC-2 SSH host-key bypass | `SSH_FLAGS` hardcoded, appended in `buildSshCommand`/`buildScpCommand` (`RemoteCommandRunner.java:27-32,72,87`); no toggle anywhere | **ALWAYS** — no opt-out exists |
| SEC-3 secret cleartext at rest | every node, every path | **ALWAYS** — see expanded surface below |
| SEC-4 secret = sole trust primitive | data-plane trust derives only from the secret-seeded CA | **ALWAYS** |
| SEC-5 `expectedVersion:0` overwrite | `buildConfigJson` hardcodes it (`BootstrapPhaseFormation.java:272-274`) | **ALWAYS** (but narrower than first stated — see correction) |

### SEC-3 is broader than "user-data + argv" — add the world-readable config file

The cluster_secret rests in **three** unprotected places, not two:
1. cloud-init user-data (`UserDataTemplate.java:183`) — readable via the provider metadata endpoint for the instance lifetime;
2. `docker run -e AETHER_CLUSTER_SECRET=…` argv (`BootstrapPhaseDeploy.java:331`) + inline JVM env on the SSH command line (`:351`, `UserDataTemplate.java:253`) — readable via `docker inspect` / `ps` / shell history;
3. **NEW — the composed `/opt/aether/config/aether.toml` is written `chmod 644` (world-readable)** with the secret in its body (`UserDataTemplate.java:202`; secret injected at `BootstrapOverlayGenerator.java:138`). **Any unprivileged local process on any node can read the cluster CA seed.** This is the weakest-protection / highest-value combination in the system.

### Blast radius of a single leaked secret — total, and it bridges to the management plane

A secret-holder mints a node cert → joins SWIM → joins the Rabia/KV state machine. **KV has no per-key read authorization** (grep for KV-level ACL returned empty) — every member receives the entire replicated log: blueprints, cluster config, placement, **and the management API-key hashes** (`AetherValue.java:1312 keyHash`, `AetherKey.java:1158 ApiKeyKey`, validated at `KvStoreApiKeyValidator.java:85,98`). So the two trust primitives that *look* independent —

- **cluster secret** → data plane (SWIM, Rabia, KV read/write, slice exec, leadership; no second factor),
- **management API key** → management HTTP plane (`ManagementServer.java:1059-1077`, RBAC `RoutePermissionRegistry.java:14-71`),

— are in fact **chained, not separated**: `secret → KV → API-key hashes → management plane`. One leak collapses both.

### SEC-5 — correction and refinement

Server-side, `checkVersionAsync` **skips the CAS guard entirely when `expectedVersion==0`** (`ClusterConfigRoutes.java:332-337`: `if (expectedVersion != 0 && storedVersion != expectedVersion)`), so a re-bootstrap/concurrent caller blind-overwrites with no optimistic-concurrency fence. **Two correctness caveats on the original ticket:** (a) cluster *identity* is in fact protected — `cluster.name` and other immutable fields are rejected by `ClusterBootstrapConfigDiff.java:43` via `hasImmutableChanges()` (`ClusterConfigRoutes.java:310`); what's unfenced is **mutable** topology/scale/sources, not identity; (b) this endpoint is on the **management** plane and is OPERATOR-gated (`RoutePermissionRegistry.java:46`), so SEC-5 needs the API key — reachable through the secret→KV→hash chain above, but not the cluster secret directly. The #289 wording ("clobbers cluster identity") is therefore overstated; corrected in a ticket comment.

### Why it does NOT compound into a kill chain (the correction)

The original draft argued "four independent capture paths converge on one secret, with zero defense-in-depth layers." That over-pathologized a deliberate design. Re-evaluated honestly:

- **SEC-1 (TrustAll bootstrap TLS) — not a gap.** QUIC mandates encryption; the self-signed CA only satisfies that. Verifying a cert whose CA you're about to derive from the shared secret is near-circular. Crucially, `cluster_secret` travels **out-of-band** (cloud-init / SSH / config), *not* over this TLS channel — so TrustAll here does **not** leak the secret. The only credential on the channel is the admin `X-API-Key`; on the intended private network the residual MITM exposure is minor.
- **SEC-2 (SSH host-key off) — not a gap.** It provisions a VM that didn't exist seconds earlier; the host key cannot be known in advance. TOFU-on-first-provision is exactly what Ansible/Terraform `remote-exec` do. (Optional hardening: capture the host key from the cloud provider console/API — a nice-to-have, not a defect.)
- **SEC-4 (symmetric secret = CA seed) — not a gap.** This is the correct trust model for elastic membership (above). The cluster is one trust domain; all members are mutually trusted by design.
- **SEC-3 (secret at rest) — narrow hygiene residual only.** And the earlier "any slice can read it" claim was **wrong**: slices share the JVM (per-slice containment is a declared non-goal), so a co-located slice already reads the secret from memory/env regardless of file perms. The real residual is OS-level defense-in-depth — `chmod 600` not `644`, and keep the secret off `docker -e`/argv — protecting against a second OS user / sidecar / backup tooling / post-compromise forensics, **not** tenant escalation. Cheap and correct, but LOW.
- **SEC-5 (config version fence) — stands, but orthogonal.** Pure concurrency-safety; unrelated to the transport/network argument.

**The one finding that survives intact and is arguably more important than the original five:** the whole model rests on an assumption — *"cluster transport is a single trust domain on a trusted private network"* — that is **documented nowhere**, while the defaults (TrustAll on, secret materialized in a world-readable config) would be genuinely dangerous if an operator unknowingly spanned an untrusted link believing TLS gave them an identity boundary. That is a documentation + guardrail finding, now tracked as **#313**.

### Honest mitigations already present (the fix has scaffolding)

- The generated formation key **is** hashed before KV (`KvStoreApiKeyHasher.java:18-27`) and only stored plaintext locally at `chmod 600` — that sub-exposure is genuinely closed.
- Cloud-provider tokens (e.g. Hetzner) are **AES-GCM encrypted** in KV (`AetherValue.java:1430`) — though decryptable by any secret-holder.
- Day-2 CLI verifies certs unless `-k` is passed (`AetherCli.java:172-184`).
- `cluster.name` immutability gate (above) stops silent identity rewrite.
- **Full mTLS / PKI infrastructure already exists in-tree but is wired only to the app-HTTP layer, not the cluster trust path** (`integrations/net/tcp/.../TlsConfig.java:25-83` mutual-TLS; JWKS/JWT validators). The remediation is largely *engaging existing code on the formation path* (real CA with per-node identity, secret delivered out-of-band/encrypted, host-key pinning), not building PKI from scratch.

**Ticket dispositions from this dive (2026-06-11):**
- **#285** SEC-1 TrustAll — **CLOSED** (deliberate: QUIC encryption enabler, secret travels out-of-band).
- **#286** SEC-2 SSH host-key — **CLOSED** (deliberate: standard provisioning TOFU on fresh VMs).
- **#288** SEC-4 symmetric trust — **CLOSED** (deliberate: correct trust model for elastic membership).
- **#287** SEC-3 secret-at-rest — **OPEN, downgraded HIGH→LOW**, re-scoped to `chmod 600` + off-argv hygiene (slice-escalation claim retracted; `bug` label removed).
- **#289** SEC-5 version fence — **OPEN**, unchanged (orthogonal concurrency-safety bug; identity is protected, mutable config unfenced, management-gated).
- **#313** *(new)* — document the single-trust-domain assumption + guardrail when transport spans untrusted networks. **This supersedes the security framing of #285/#286/#288.**

---

> The dominant dashboard story is **a viewer wired to a stale/incorrect API contract with no test to catch the drift**: several panels call routes that were renamed or use the wrong HTTP verb and silently 404, live alerts never render because of a WebSocket envelope mismatch, secured clusters never receive initial state, and the percentile numbers shown to operators are fabricated. The ops-control gap the user already flagged is confirmed and deeper than expected — the rolling-update API does not exist server-side at all.

| ID | Finding | Verdict | Current ref | Sev |
|---|---|---|---|---|
| G5 | Live alerts never render via WS (envelope/type mismatch) + alerts absent from the secondary poll | CONFIRMED | `AlertManager.java:395-407` emits `{"type":"ALERT","data":{...}}`; client `app.js:127-128` does `updateFromWs(data.data\|\|data)` then `alerts.js:8-10` checks `data.type==='ALERT'` on a payload that no longer carries `type`; `app.js:151-161` poll omits alerts | **HIGH** |
| G6 | Ops control panel is read-only; rolling-update UI bound to a never-written store; `RollingUpdate` response records are orphans with no route and no construction site | CONFIRMED | `index.html:626-636` (panel), `deployments.js:5` (`rollingUpdates: []` init only), `ManagementApiResponses.java:359-380` (orphan records), `ManagementRoute.java:58-60` (promote/rollback/complete only — no list/create) | **HIGH** |
| G8 | `INITIAL_STATE` never delivered when security enabled — gated on the auth-*disabled* branch; the success path sends only `AUTH_SUCCESS` | CONFIRMED | `DashboardWebSocketHandler.java:43-48` (`if (authenticator.onOpen(session)) { session.send(buildInitialState()); }`), `WebSocketAuthenticator.java:152-157` (`acceptSession` sends only `AUTH_SUCCESS`) | **HIGH** |
| G9 | Polling not gated to degraded mode; in Forge (~18 missing endpoints, no proxy fallback) every poll raises an error toast → notification storm | CONFIRMED | `ForgeApiHandler.java:128` (`findRoute(...).onEmpty(sendNotFound)` no fallback), `rest-client.js:53` toasts on failure, `app.js:175` (`/api/slices`@2s), `app.js:67-73` (Requests@3s) | **HIGH** |
| G1 | Client polls `/api/topology` which does not exist (only `/api/slices/topology`) → 404 + toast every 10s, graph never populates | CONFIRMED | `topology.js:28` vs `ManagementRoute.java:76` | MED |
| G2 | Log-levels page calls renamed paths `/api/log-levels[...]` (now `/api/logging/levels`) → view + changes 404 | CONFIRMED | `cluster.js:138,143` vs `ManagementRoute.java:212-214` | MED |
| G3 | Controller config save uses wrong verb (client PUT, route POST) → edits never persist | CONFIRMED | `cluster.js:122` vs `ManagementRoute.java:208` | MED |
| G10 | Observability panel partial — depth-rule CRUD works, but trace drill-down/waterfall is unbuilt and node-mode live events collapse on an undefined `timestamp` key | PARTIAL | `ObservabilityRoutes.java:45-95` (CRUD ok), `index.html:512-524` (no click→waterfall), `events.js:11-13`+`index.html:273-275` (`:key=event.timestamp...` but `ClusterEventView` has no `timestamp`) | MED |
| G11 | Zero JS tests; no lint/bundle tooling — the entire JS↔API contract is unexercised, which is why every mismatch above ships | CONFIRMED | `dashboard/pom.xml` (deps only, no `src/test`) | MED |
| Gpct | Fabricated percentiles — p50/p95/p99 synthesized as `avg × 0.8/2.5/5`, presented as real | CONFIRMED | `metrics.js:92-94,213-215` | MED |
| G4 | Storage force-snapshot path-param order wrong (`/api/storage/{name}/snapshot` vs route `/api/storage/snapshot` + `name` param) → button 404s | CONFIRMED | `storage.js:27` vs `ManagementRoute.java:112` | LOW |
| G12 | Spec drift + dead weight — renderer is custom SVG swim-lane, not the spec's d3-force/canvas; no `topology.css`; several loaded components are dead (`trace-detail`/`node-detail`/`invocation-table`/`event-feed`/`rolling-update`); hardcoded `targetTracesPerSec:500`; no explicit a11y affordances | CONFIRMED | `topology-graph.js:1-3,468-519`; `observability.js:53`; `index.html:985-989` | LOW |

**Tickets:** G5 → **#292** · G6 (folds unsurfaced-routes + stuck-migration UX) → **#291** · G8 → **#293** · G9 → **#294** · G1+G2+G3+G4 (bundled) → **#302** · G10 → **#304** · G11 → **#305** · Gpct → **#303** · G12 → **#312** · G7 → folded into **#290**.

**G7 (auth posture) — folded into the management-plane default, not double-filed.** `WebSocketAuthenticator.java:53-57` bypasses WS auth when `!securityEnabled`; `StaticFileHandler.java:86-106` serves the shell with no auth; the API key is accepted via **URL parameter** (`index.html:36-37`) — keys in URLs leak into logs/history. These belong to the `SecurityMode.NONE` default finding (M-T1/M-T2); the URL-param key handling is noted there as an extra facet.

**Supporting (fold into G6, not separately filed):**
- **Unsurfaced backend routes** — the backend exposes a broad ops surface the UI never calls: blueprints (`MR:66-73`), A/B create/conclude (`64-65`), slice scale (`83`), cluster scale/upgrade/migrate (`48,54,87-88`), node drain/shutdown/promote/inflight (`98-102`), workers (`84-86`), backups (`198-200`), config overrides (`201-205`), circuit-breaker/auto-heal (`49-53`), API keys (`89-92`).
- **Stuck-migration UX** — backend has migrate/undo/baseline/retry/history (`MR:103-109`) but the UI shows only a Retry button, only on `FAILED` (`index.html:235-237`). A migration wedged in `MIGRATING` is invisible and unrecoverable from the dashboard.

---

## C. Cloud integration

| ID | Finding | Verdict | Current ref | Sev |
|---|---|---|---|---|
| C-ROLE | Container label hardcodes `aether-role=core` for all nodes | CONFIRMED | `UserDataTemplate.java:216`, `BootstrapPhaseDeploy.java:321` | MED |
| C-REAPER | Orphan-resource cleanup is Hetzner-only; firewall/floating-IP/remote-config destroy handlers are no-ops on every provider | CONFIRMED | `tools/cloud-reaper.sh:2,14`; `BootstrapCleanup.java:98,134,139,155` | MED |
| C-COST | No cost/quota cap before provisioning N nodes; `checkQuota` SPI exists but is unwired and stubbed to `unknown` | CONFIRMED | `CloudProvider.java:14`; `HetznerCloudProvider.java:26-28` | MED |
| C-SPOT | Spot/preemptible SPI is dead — every provider rejects `provisionSpot()` | CONFIRMED | `AwsCloudProvider.java:35` (`operationNotSupported`) | LOW |
| C-DO | DigitalOcean has a spec but no module/code; fails at credential resolution | CONFIRMED | `CloudCredentials.java:33` default→`operationNotSupported` | LOW |
| C-206 | Untyped `Map<String,String>` credentials (ticket #206 still open) | CONFIRMED | `CloudConfig.java:15`, `CloudCredentials.java:82-83` | LOW |

**Tickets:** C-ROLE → **#296** · C-REAPER → **#297** · C-COST → **#298** · C-SPOT → **#306** · C-DO → **#307** · C-206 → #206 (already open, not refiled). Cloud transport-security items re-dispositioned (A.6): SEC-1 → **#285 CLOSED** (deliberate), SEC-3 → **#287 OPEN/LOW** (secret-at-rest hygiene).

**Withdrawn after re-verification:**
- **INGRESS** — load-balancer providers are fully implemented for AWS/GCP/Azure/Hetzner with real client calls (`AwsLoadBalancerProvider.java:38-44`, etc.). No gap.
- **LEAK (VM rollback)** — `ClusterBootstrapOrchestrator.java:202-220 cleanupOnFailure` terminates created VMs on mid-bootstrap failure by default (unless `--keep-on-failure`). Residual leak is only the no-op resource types folded into **C-REAPER**.

Security items (SEC-1 TrustAll, SEC-3 secret-in-user-data/argv) are the cloud subsystem's HIGH findings — see section A.

---

## D. Management API

| ID | Finding | Verdict | Current ref | Sev |
|---|---|---|---|---|
| M-T1 | With `securityEnabled=false` the entire auth block is skipped — alert inject, threshold set/clear, and all three WS streams are open | CONFIRMED | `ManagementServer.java:669`; `WebSocketAuthenticator.java:54-57`; WS wired `:242` | HIGH |
| M-T2 | Spec claims "all endpoints require API key authentication" but code defaults `SecurityMode.NONE` | CONFIRMED | doc `architecture/12-management.md:92` vs `AppHttpConfig.java:48,58,68,94-95`, `AetherNode.java:1981-1986` | HIGH |
| M-T3 | Authorization is coarse path-prefix `startsWith` matching, not per-operation; all reads collapse to `ALL_AUTHENTICATED` | CONFIRMED | `RoutePermissionRegistry.java:89-90` (prefixes 27-49); `ManagementServer.java:1067` | MED |
| M-T4 | No `/v1/` version prefix on any route; dual overlapping surfaces (`backups`/`backup`, double route resolution) — consolidation debt #226/#198 | CONFIRMED | `ManagementRoute.java:34-58`; `ManagementRouter.java:65,84`; `AetherCli.java:3247,3310` | MED |
| M-T6 | RFC 9457 problem framework exists server-side but unmapped causes fall back to opaque 500; CLI renders all errors as plain text even with `--format json` | PARTIAL | `ProblemResponses.java:88-92`; `OutputFormatter.java:56,190,196` | LOW |

**Tickets:** M-T1+M-T2 (one root) → **#290** · M-T3 → **#299** · M-T4 → **#300** · M-T6 → **#308**.

M-T1 + M-T2 share one root (`SecurityMode.NONE` default) — file as a single HIGH ticket covering both the open-by-default behavior and the false doc claim. G3 (dashboard) is the same root; reference, don't refile.

---

## E. CLI

| ID | Finding | Verdict | Current ref | Sev |
|---|---|---|---|---|
| L-T5 | Destructive commands (`drain`/`scale`/`migrate`/restore) execute with no confirmation; `cluster destroy` already has `--yes`+name-confirm and is the model to follow | CONFIRMED | `ClusterDrainCommand.java:48-55`, `ClusterMigrateCommand.java:48-53`, `AetherCli.java:3293-3294`; cf. `ClusterDestroyCommand.java:44,105,166` | MED |
| L-T7 | `aether events` has only `--since`, no `--follow` live tail despite server `/ws/events` (ref #233) | PARTIAL | `AetherCli.java:3226-3245` | LOW |

**Tickets:** L-T5 → **#301** · L-T7 → **#309** (xref #233).

**Withdrawn after re-verification:** audit CLI exists (`ClusterAuditCommand`), cluster CLI exists (`ClusterCommand`, ~24 subcommands), storage CLI exists (`StorageCommand`). The "no CLI for X" sub-claims are stale.

---

## F. Bootstrap

| ID | Finding | Verdict | Current ref | Sev |
|---|---|---|---|---|
| B-T3 | `waitForQuorum` returns success on first 200 from one endpoint; `quorumOf(1)==1`, no minimum-quorum floor → two concurrent bootstraps can each own the cluster | CONFIRMED | `BootstrapPhaseFormation.java:134-162,152`; `StatusRoutes.java:236`; cross-ref topology **M5**, **M1** | MED |
| B-T5 | Authoritative declarative bootstrap/management spec vs stale imperative `architecture/12-management.md` (REPL, `/api/v1/...` that code never implements) | CONFIRMED | `cluster-bootstrap-spec.md:14-17`, `cluster-management-spec.md:30` vs `architecture/12-management.md:7,53-56,72-76` | LOW |
| B-T6 | `cluster init` config generator is non-idempotent (overwrite-or-abort, not merge); the "inconsistent node labels" sub-claim is withdrawn (no label model exists in the wizard) | PARTIAL | `init/ClusterConfigWizard.java:47-84,100-114`; `ClusterInitCommand.java:354` | LOW |

**Tickets:** B-T3 → **#295** (xref topology M5/M1) · B-T5 → **#310** · B-T6 → **#311**. Bootstrap transport-security items re-dispositioned (A.6): SEC-4 → **#288 CLOSED** (deliberate trust model), SEC-5 → **#289 OPEN** (config version fence); single-trust-domain doc/guardrail → **#313**.

SEC-4 (symmetric trust) and SEC-5 (config version fence) — see section A and the A.6 correction.

---

## Ticket plan (FILED — #285–312, labelled `rc1` / `rc1,bug`)

**HIGH (security, cross-cutting) — RE-DISPOSITIONED, see A.6:**
1. ~~SEC-1 TrustAll TLS~~ → **#285 CLOSED** (deliberate: QUIC encryption enabler, secret out-of-band)
2. ~~SEC-2 SSH host-key verification~~ → **#286 CLOSED** (deliberate: standard provisioning TOFU)
3. SEC-3 cluster_secret at rest → **#287 OPEN, downgraded to LOW** (`chmod 600` + off-argv hygiene)
4. ~~SEC-4 symmetric-secret trust~~ → **#288 CLOSED** (deliberate: correct elastic-membership trust model)
5. SEC-5 config push has no version fence → **#289 OPEN** (concurrency-safety; identity protected, mutable config unfenced)
5b. *(new)* single-trust-domain assumption undocumented + untrusted-network guardrail → **#313 OPEN** (supersedes the security framing of #285/#286/#288)
6. **#290** M-T1+M-T2 management plane open by default + false "auth required" doc claim (also covers dashboard G7)

**HIGH (dashboard):**
7. **#291** G6 ops control panel read-only + `RollingUpdate` backend missing (orphan records, no route); folds unsurfaced-routes + stuck-migration UX
8. **#292** G5 live alerts never render (WS envelope/type mismatch) + alerts not polled
9. **#293** G8 `INITIAL_STATE` never delivered in secured mode
10. **#294** G9 dashboard polling not gated to degraded mode + Forge notification storm (no proxy fallback)

**MEDIUM:**
11. **#295** B-T3 single-node quorum split-brain at formation (xref topology M5/M1)
12. **#296** C-ROLE hardcoded `aether-role=core` label
13. **#297** C-REAPER multi-provider orphan cleanup (reaper Hetzner-only + no-op destroy handlers)
14. **#298** C-COST no quota/cost cap; `checkQuota` unwired+stubbed
15. **#299** M-T3 coarse prefix RBAC
16. **#300** M-T4 no API versioning + dual surfaces (xref #226/#198)
17. **#301** L-T5 destructive CLI commands need confirmation
18. **#302** G1–G4 dashboard client calls wrong/renamed API paths+verbs (topology, log-levels, controller, storage-snapshot) — silent 404s
19. **#303** Gpct dashboard fabricates p50/p95/p99 as `avg×const`
20. **#304** G10 observability panel — trace waterfall unbuilt + node-mode events broken
21. **#305** G11 dashboard JS test/build tooling absent

**LOW:**
22. **#306** C-SPOT spot SPI dead (or document as v1 non-goal)
23. **#307** C-DO DigitalOcean spec-no-code
24. **#308** M-T6 structured-error coverage gaps (server 500 fallback + CLI plain-text)
25. **#309** L-T7 `events --follow` (xref #233)
26. **#310** B-T5 management architecture doc drift (folds into docs epic)
27. **#311** B-T6 `cluster init` non-idempotent
28. **#312** G12 dashboard spec drift / dead components / hardcoded / a11y

**Not refiled:** C-206 (#206 already open); G7 (folded into **#290**, noting URL-param key handling). KV persistence, aspect-observability, Rabia design — not gaps.
