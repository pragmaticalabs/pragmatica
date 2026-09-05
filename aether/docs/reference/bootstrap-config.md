<!-- SPDX-License-Identifier: BUSL-1.1 -->
<!-- Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko -->
<!-- Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0. -->

# Bootstrap Config Reference

The bootstrap-config TOML is the input file for `aether cluster bootstrap` and `aether cluster apply`
(see [CLI Reference](cli.md#aether-cluster-bootstrap)). It describes a *cluster you don't have yet*:
where to provision nodes, how many, and what runtime to install. This is a **different schema** from a
running node's own `aether.toml` (documented in [Configuration Reference](configuration.md)) — the CLI
reads the bootstrap-config once per phase and, among other things, generates the `[cloud]` /
`[cloud.credentials]` / `[cloud.discovery]` sections of each provisioned node's `aether.toml` from it.
You never hand-write `[cloud.*]` when using `aether cluster bootstrap`; see
["Bootstrap-config vs. composed node config"](#bootstrap-config-vs-composed-node-config) below.

Every field below is verified against the parser
(`aether-config/src/main/java/org/pragmatica/aether/config/cluster/ClusterBootstrapConfigParser.java`);
see the accompanying report for file:line citations. TOML only — this project does not use YAML.

## Minimal working example (Hetzner, JVM runtime)

Derived from the validated integration fixture
`aether/tests/integration/env/cloud-hetzner-jvm.toml` by dropping the database blocks. 5 nodes is the
project's supported minimum for a CORE quorum — don't shrink this further.

```toml
config_version = "1.0.0"

[cluster]
name    = "my-cluster"
version = "1.0.0"

[cluster.core]
min            = 3
max            = 9
max_unavailable = 1

[source.hetzner-eu]
type        = "cloud"
provider    = "hetzner"
zones       = ["fsn1", "nbg1", "hel1"]
region      = "fsn1"
credentials = "${env:HCLOUD_TOKEN}"
load_balancer = "none"

[source.hetzner-eu.core]
count         = 5
instance_type = "cpx32"
image         = "ubuntu-22.04"

[infrastructure.networking]
type = "manual"

[operations.tls]
auto_generate = false

[operations.ports]
cluster    = 6000
management = 8080
app_http   = 8070
swim       = 6100

[operations.auto_heal]
enabled          = true
# retry_interval / startup_cooldown shown for illustration only — parsed but currently
# discarded at runtime. See "[operations.auto_heal] is bootstrap-only" trap below (#675).
retry_interval   = "30s"
startup_cooldown = "15s"

# --- Cost guardrail (#298): refuse provisioning past 12 nodes for this cluster.
# Opt-in — omit it and provisioning stays unbounded, as it always has. See "Fleet cap" below.
[source.hetzner-eu.node_config.cluster]
max_nodes = 12

# --- Trap (a): without this, JVM nodes default to API_KEY security mode and the
# bootstrap's own cluster-config write is rejected with 401 Unauthorized. See "Traps" below.
[source.hetzner-eu.node_config.app-http]
enabled       = true
security_mode = "NONE"

# --- Trap (b): pin jar_url explicitly whenever cluster.version isn't itself the exact
# published release tag. See "Traps" below.
[runtime.default]
type    = "jvm"
jar_url = "https://github.com/pragmaticalabs/pragmatica/releases/download/v1.0.0-rc3-candidate/aether-node.jar"
```

This is a **dev/eval** config (`security_mode = "NONE"`) — see Trap (a) for what changes in production.

## Schema reference

### `[cluster]` — required

| Field | Type | Default | Required | Notes |
|---|---|---|---|---|
| `name` | string | — | **yes** | Cluster identity. Overridable by `aether cluster bootstrap --cluster <name>` (CLI > TOML). |
| `version` | string | — | **yes** | Target Aether version for provisioned nodes. Drives the auto-derived `jar_url` / image tag — see Trap (b). |

Top-level `config_version` (outside any table) is also required and must equal exactly `"1.0.0"` for
this build; absent, older, or newer values are rejected before any other field is parsed.

### `[cluster.core]` — optional

| Field | Type | Default | Required | Notes |
|---|---|---|---|---|
| `min` | int | none | no | Lower bound checked against the *derived* CORE count (sum of every `[source.<name>.core]` `count`/`hosts` across all sources). No bound is enforced if omitted. |
| `max` | int | none | no | Upper bound, same derivation. |
| `max_unavailable` | int | `1` | no | Rolling-restart budget for `aether cluster apply`. |

If `[cluster.core]` is absent entirely, `min`/`max` are unset (no bound) and `max_unavailable` is `1`.

### `[source.<name>]` — one or more required

`<name>` is an arbitrary label (e.g. `hetzner-eu`); a cluster can mix multiple sources.

| Field | Type | Default | Required | Notes |
|---|---|---|---|---|
| `type` | string | — | **yes** | `cloud` \| `ssh` \| `forge` \| `docker`. |
| `provider` | string | — | no | `hetzner` \| `aws` \| `gcp` \| `azure`. **Silently discarded if unrecognized** — see Gaps/silent-wrong-state below. |
| `credentials` | string | — | no (required by cloud providers at deploy time) | Supports `${env:VAR}` interpolation. |
| `region` | string | — | no | Provider-specific. |
| `zone` | string | — | no | Single zone; mutually informative with `zones`. |
| `zones` | string list | `[]` | no | Multi-zone spread, e.g. `["fsn1","nbg1","hel1"]`. |
| `user` | string | — | no | SSH source: remote user. |
| `key` | string | — | no | SSH source: private key path. |
| `ssh_port` | int | — | no | SSH source: port override. |
| `load_balancer` | string | type-dependent | no | `none` \| `external` \| `elected`. |
| `load_balancer_ips` | string list | `[]` | no | Used with `external` mode. |
| `load_balancer_endpoint` | string | — | no | Used with `external` mode. |
| `databases.<name> = "url"` (inline) or `[source.<name>.databases]` (subtable) | string map | `{}` | no | Maps to composed **`[database.<name>]`** (nested), never flat `[database]` — see Trap (c). |
| `[source.<name>.node_config.<section>]` | raw TOML overlay | — | no | Merged verbatim as `[<section>]` into the composed per-node `aether.toml`, prefix-stripped. Escape hatch for any node-level setting not otherwise modeled (used above for `[app-http]`). |
| `[source.<name>.firewall] allow_ingress` | table array | `[]` | no | Each entry: `port` (int, required), `protocol` (default `"tcp"`, may be `"tcp+udp"`), `source_cidr` (default `"0.0.0.0/0"`), `description` (optional). **Hetzner only** — see below. |

#### Ingress firewall (`[source.<name>.firewall]`)

Applied at bootstrap as a standalone cloud firewall associated with the source's servers, created
**before** the servers themselves so no node is ever briefly reachable without its rules.

- **Hetzner only.** Declaring `allow_ingress` on AWS/GCP/Azure is rejected at pre-flight (PF-23) —
  their ingress arms are not implemented. Manage ingress with your own security groups there; their
  defaults deny inbound, so nothing is silently exposed.
- `"tcp+udp"` expands to two provider rules on the same firewall.
- Rules you do not list are never touched — a firewall is patched by union, never replaced.
- The **cluster (8090) and management (8080) ports are yours to manage** and are never opened by
  Aether, consistent with `[infrastructure.networking] type = "manual"`.
- With `load_balancer = "elected"` and **no** `[source.<name>.firewall]` block, Aether auto-opens
  `app_http` (default 8070) on TCP **and** UDP to `0.0.0.0/0` so HTTP/3 works out of the box, and
  warns. Declare the block to scope it.
- `aether cluster destroy` deletes the firewalls it created, by recorded id. Server deletion is
  asynchronous, so the delete retries while Hetzner finishes detaching; a firewall that is truly
  stuck still fails loudly, and `tools/cloud-reaper.sh --cluster <name> --destroy` is the fallback.

> **Open ports 22 AND the management port, or bootstrap cannot reach its own nodes.** `allow_ingress` is deny-by-default for
> everything it does not list, and the `DEPLOY_RUNTIME` phase installs the runtime over SSH. A config
> that omits port 22 provisions its VMs correctly and then fails with
> `SSH preflight failed: N host(s) unreachable after 300s` — the firewall doing exactly its job.
> Pre-flight now warns about this. Scope it to your operator network rather than `0.0.0.0/0`:
>
> ```toml
> [[source.hetzner-eu.firewall.allow_ingress]]
> port        = 22
> protocol    = "tcp"
> source_cidr = "203.0.113.0/24"   # your operator network
> description = "bootstrap SSH"
>
> [[source.hetzner-eu.firewall.allow_ingress]]
> port        = 8080                # operations.ports.management
> protocol    = "tcp"
> source_cidr = "203.0.113.0/24"
> description = "bootstrap readiness gate + operator management API"
> ```
>
> The readiness gate polls `http://<public-ip>:<management>/health/live` on every node, and
> REQ-5.1.8.3 deliberately keeps the management port operator-managed — Aether never opens it for
> you. Omitting it fails bootstrap with `N node(s) never answered the management API on port 8080`
> on nodes that are perfectly healthy. Verified live on 2026-08-05: from inside the host that exact
> URL returned **HTTP 200** with the runtime running, while from outside it never connected.

> **The cluster transport is QUIC — open the cluster port as `udp`, and SWIM as `udp`.** A `tcp`
> rule on the cluster port reads plausibly and passes every pre-flight, but inbound QUIC is
> dropped by the deny-by-default firewall and the cores can never dial each other: discovery
> resolves all peers, SWIM gossip (if its UDP rule is present) partially connects, and the
> formation gate still times out at `0 of N cores reported formation`. Live-proven 2026-08-09:
> two full bootstraps failed exactly this way behind `in tcp 6000` before the rule was corrected.
> The `standard`/`restrictive` presets emit `udp` for both since that date.
>
> ```toml
> [[source.hetzner-eu.firewall.allow_ingress]]
> port        = 6000                # operations.ports.cluster — QUIC
> protocol    = "udp"
> source_cidr = "0.0.0.0/0"         # nodes address each other by PUBLIC IP under manual networking
> description = "Cluster (Rabia consensus over QUIC)"
>
> [[source.hetzner-eu.firewall.allow_ingress]]
> port        = 6100                # operations.ports.swim
> protocol    = "udp"
> source_cidr = "0.0.0.0/0"
> description = "SWIM gossip"
> ```

> **Bootstrap-only.** Editing `allow_ingress` on an existing cluster does not currently re-apply it
> — `ClusterConfigApplier` discards the diffed action (#578). Re-bootstrap to change ingress rules.


### `[source.<name>.<role>]` — role sub-tables (`core` \| `worker` \| `spot`)

At least one role sub-table per source is expected in practice (`core` in the example above).

| Field | Type | Default | Required | Notes |
|---|---|---|---|---|
| `count` | int | — | **count XOR hosts** | Number of nodes to provision (cloud/forge/docker sources). |
| `hosts` | string list | — | **count XOR hosts** | Explicit host list (ssh sources). |
| `instance_type` | string | — | no (cloud sources need it) | e.g. `cpx32`. |
| `image` | string | — | no | VM image, e.g. `ubuntu-22.04`. |
| `runtime` | string | source-type default | no | References a `[runtime.<name>]` profile. Must satisfy the source/runtime compatibility matrix below. |

**Source/runtime compatibility** (validator codes `PF-19`..`PF-22`): `forge` sources require `EMBER`
runtime; `docker` sources require `DOCKER`; `cloud` sources require `CONTAINER` or `JVM`; `ssh` sources
allow `CONTAINER`, `JVM`, or `EMBER`. A mismatch fails validation before provisioning starts.

### `[infrastructure.networking]` / `[infrastructure.ssh]`

| Field | Type | Default | Required | Notes |
|---|---|---|---|---|
| `[infrastructure.networking] type` | string | — | no | Only `"manual"` is currently a valid value. |
| `[infrastructure.ssh] authorized_keys` | string list | `[]` | no | Keys injected into every provisioned VM. |
| `[infrastructure.ssh] public_key_file` | string | — | no | Single operator key path. |
| `[infrastructure.ssh] public_key_files` | string list | `[]` | no | Multiple operator key paths. Resolution priority at bootstrap time: CLI `--ssh-public-key` > this TOML field > `${AETHER_SSH_KEY}.pub` sibling. |

### `[operations.*]`

| Field | Type | Default | Notes |
|---|---|---|---|
| `[operations] auto_heal` | bool | `true` | Cluster-wide auto-heal master switch. |
| `[operations.auto_heal] enabled` | bool | `true` | `false` is **rejected at bootstrap** (error PF-25) — it has no runtime effect, so the parser refuses to accept a value that would silently lie. Use `aether cluster topology auto-heal disable` on a running cluster instead. See warning below. |
| `[operations.auto_heal] retry_interval` | duration string | `"60s"` | **Parsed, discarded** — see warning below (#675). |
| `[operations.auto_heal] startup_cooldown` | duration string | `"15s"` | **Parsed, discarded** — see warning below (#675). |
| `[operations.auto_heal] stale_observation_ttl` | duration string | parser default | **Parsed, discarded** — see warning below (#675). |
| `[operations.auto_heal] quic_miss_promotion_threshold` | int | parser default | **Parsed, discarded** — see warning below (#675). |
| `[operations.auto_heal] provisioning_timeout` | duration string | parser default | **Parsed, discarded** — see warning below (#675). |
| `[operations.auto_heal] provision_stability_window` | duration string | parser default | **Parsed, discarded** — see warning below (#675). |
| `[operations.auto_heal] decommissioned_retention` | duration string | parser default | **Parsed, discarded** — see warning below (#675). |
| `[operations.auto_heal] swim_hints_ttl` | duration string | parser default | **Parsed, discarded** — see warning below (#675). |
| `[operations.tls] auto_generate` | bool | `true` | When `false`, the management API listener uses plain HTTP instead of an auto-generated self-signed cert. |
| `[operations.tls] cert_ttl` | duration string | `"720h"` | |
| `[operations.timeouts] health_check` | duration string | `"300s"` | |
| `[operations.timeouts] quorum_formation` | duration string | `"600s"` | |
| `[operations.timeouts] drain` | duration string | `"120s"` | |
| `[operations.ports] cluster` | int | `8090` | Consensus/gossip port. |
| `[operations.ports] management` | int | `8080` | Management API port. |
| `[operations.ports] app_http` | int | `8070` | Slice app-HTTP port. |
| `[operations.ports] swim` | int | `8190` | SWIM membership port. |

### `[runtime.<name>]`

| Field | Type | Default | Required | Notes |
|---|---|---|---|---|
| `type` | string | — | **yes** | `container` \| `jvm` \| `docker` \| `ember` \| `managed-container`. |
| `image` | string | — | no | Container runtimes: image reference. |
| `jvm_args` | string | — | no | JVM runtime: extra `java` flags. |
| `jar_url` | string | auto-derived from `cluster.version` if unset | no | JVM runtime: download URL for `aether-node.jar`. See Trap (b). |

A role sub-table references a runtime profile by name via `runtime = "<name>"`; if omitted, a
source-type-appropriate default profile name is assumed.

### Bootstrap-config vs. composed node config

`[source.<name>]` fields are the **input** the CLI reads to provision infrastructure. The CLI then
*generates* each node's `[cloud]`, `[cloud.credentials]`, `[cloud.discovery]`, and provider-specific
`[cloud.compute]` sections — the schema documented under
[Cloud Configuration](configuration.md#cloud-configuration) — and writes that composed `aether.toml`
to the VM. You do not write `[cloud.*]` by hand in a bootstrap-config file; if you find yourself doing
so, you're editing the wrong schema.

## Traps

### Fleet cap — bounding what a cluster may provision (#298)

`[cluster] max_nodes` is a ceiling on how many nodes a cluster may have provisioned. It is enforced at
`NodeLifecycleManager.provisionNode`, the single chokepoint **every** provisioning path funnels through
— the auto-heal reconciler, bootstrap, and `aether cluster` wave reprovision alike — by counting the
cluster's live instances (scoped by the `aether-cluster` label) before each provision and refusing with
`EnvironmentError.NodeCapExceeded` once the count reaches the cap.

It is a **node-level** setting, so for a cloud source it is supplied through the `node_config` overlay:

```toml
[source.<name>.node_config.cluster]
max_nodes = 12
```

For a hand-managed node it goes directly in that node's `aether.toml` under `[cluster]`.

**Opt-in, and unbounded by default.** Omitting `max_nodes` (or setting `0`) preserves today's behaviour
exactly. There is deliberately no numeric default: any default we picked would silently refuse
provisioning on an existing cluster already larger than it — an outage on upgrade, not a guardrail.

**What the cap does and does not promise.** The check reads the live count and then provisions, so the
guarantee is *"bounded by `max_nodes` plus whatever was concurrently in flight"* — not *"never exceeds
`max_nodes`"*. It bounds the runaway case, which is sequential reconciler passes; it is not a barrier
against a deliberate parallel burst. A cap read that **fails** refuses the provision rather than allowing
it, so an unreachable provider API cannot silently disable the guard.

**Operator recovery when it fires:** raise `max_nodes`, or terminate instances until the cluster is under
the cap. Provisioning resumes on the next reconcile pass with no further action. The refusal is logged at
WARN naming the cluster, the cap, and the observed count.

> **Of the nine `[operations.auto_heal]` fields, only `enabled` and the fleet cap above are live.**
> `retry_interval`, `startup_cooldown`, `stale_observation_ttl`, `quic_miss_promotion_threshold`,
> `provisioning_timeout`, `provision_stability_window`, `decommissioned_retention`, and `swim_hints_ttl`
> all parse and validate into `AutoHealSpec`, but `Main.resolveAutoHeal` builds every running node's
> `AutoHealConfig` from `AutoHealConfig.DEFAULT`, overriding only `maxNodes` — sourced from `[cluster]
> max_nodes` above, a different key entirely, not from this section. Nothing renders the other eight
> fields into the composed per-node `aether.toml`; they are validated and then discarded. Do not use
> this section to try to set the fleet cap — use `node_config.cluster` as above. (Recorded 2026-08-12
> while wiring #298.)
>
> `enabled = false` is **rejected at bootstrap** (error PF-25) rather than silently accepted and ignored
> — the parsed value is never read by the provisioning path, so a `false` here would falsely promise
> suppression it can't deliver. Set it to `true` (its only honest value) or omit the key, and use the
> live operator toggle instead: `aether cluster topology auto-heal disable`, which actually suppresses
> replacement provisioning for the current leader term.
>
> **Tracked as #675**: one open decision — wire every field or reject it PF-25-style, per surface — not
> yet made. This warning describes current behavior, not a promise about future wiring.

### (a) `security_mode = "NONE"` — why dev/eval bootstrap needs it

Node `[app-http]` security defaults to `API_KEY` mode when `security_mode` is not set (issue #290,
"secure by default" — `ConfigLoader.populateAppHttpConfig`). On first leadership a fresh cluster does
auto-generate one random ADMIN API key (`BootstrapAdminKeyRegistrar`) and prints it once to that node's
own log — but the bootstrap CLI has no channel to read that printed value back, and the bootstrap flow
needs to POST the cluster config (`storeClusterConfig`) as an unattended step. Under the default
`API_KEY` mode that POST has no credential to present and is rejected as unauthorized (401; `SecurityError`
maps unauthenticated failures to `HttpStatus.UNAUTHORIZED`, authorization failures to `FORBIDDEN`).
Setting `security_mode = "NONE"` in `[source.<name>.node_config.app-http]` is what the reference example
above does, and it is explicitly a **dev/eval** posture — it disables app-HTTP auth entirely, matching
the project's `AETHER_INSECURE_DEV_MODE` C2 gate used elsewhere for dev/test.

**What the code supports for production instead:** pre-provision a real credential so `API_KEY` mode
has something to authenticate with from the first boot, rather than disabling security. Two verified
mechanisms: an `AETHER_API_KEYS` environment variable baked into the node's cloud-init/user-data
(format `key:name:roles:authRole;key2:...`), or a `[app-http.api-keys.<key>] name=... roles=[...]
authorization_role="ADMIN"` table under `node_config.app-http` in the bootstrap-config itself. Either
lets the bootstrap POST authenticate immediately, with `security_mode` left at its secure `API_KEY`
default.

> **Artifact publication under `NONE` (resolved in #520, live-verified 2026-07-24):** a
> `NONE`-mode node ignores API keys entirely — every caller is `anonymous`/`VIEWER`, and
> `aether whoami` reports `authenticated: false` even for the bootstrap-minted admin key.
> Because publication would otherwise require an `OPERATOR`/`ADMIN` role that nobody can
> hold in that mode, the publication gate now treats `security_mode = "NONE"` as the
> dev-mode posture and accepts the push, logging a WARN that names the artifact and the
> reason. So `aether artifacts push` works against a NONE cluster — and everything it
> accepts is unauthenticated by construction, which is precisely why `NONE` is dev/eval
> only. Under `API_KEY`/`JWT` the gate is unchanged and still rejects anonymous callers.

### (b) `jar_url` pinning

For the `jvm` runtime, an unset `jar_url` is auto-derived from `cluster.version` as
`https://github.com/pragmaticalabs/pragmatica/releases/download/v<version>{-candidate?}/aether-node.jar`
(`NodeUserDataRenderer.deriveJarTag`/`resolveJarUrl`). The derivation only appends `-candidate` under
specific version-string conditions; whenever `cluster.version` is a plain release-looking string (e.g.
`"1.0.0-rc3"`) but the *only* jar actually published under that version lives at a `-candidate`-suffixed
tag (the common case pre-GA — release candidates are tagged `vX.Y.Z-rcN-candidate`, not `vX.Y.Z-rcN`),
the derived URL 404s. Pin `jar_url` explicitly in `[runtime.default]` whenever your `cluster.version`
doesn't exactly match a published release tag, as the example above does.

### (c) `databases.X` vs. flat `[database]`

`[source.<name>] databases.forge = "${env:PG_URL}"` (or the `[source.<name>.databases]` subtable form)
composes into the provisioned node's **`[database.forge]`** section — a *named* datasource
(`BootstrapOverlayGenerator.databaseSections`). It never produces a flat `[database]` section. This
matters because of the multi-datasource convention (see
[Database Configuration & Schema Migration](configuration.md#multi-datasource-convention)): `[database]`
is the *default* datasource resolved by `@Sql` and by migration scripts under `schema/` root;
`[database.<name>]` is resolved only by `@ResourceQualifier(config="database.<name>")` and migrations
under `schema/<name>/`. Resolution is strict — no fallback between the two — so a slice written against
`@Sql`/`schema/` root will fail to find its datasource if the bootstrap config only ever populates a
named `databases.X` entry and nothing maps to the default. If a slice needs the default datasource,
route it through a source's flat `node_config.database` overlay instead of (or in addition to) `databases.X`.
