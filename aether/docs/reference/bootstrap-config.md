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
# EXAMPLE region — the region decides where your data resides; choose it deliberately.
zones       = ["fsn1", "nbg1", "hel1"]
region      = "fsn1"
credentials = "${env:HCLOUD_TOKEN}"
load_balancer = "none"

[source.hetzner-eu.core]
count         = 5
# EXAMPLE instance type — providers retire types; check your provider's current catalogue.
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
# No other key is accepted here (#675): auto-heal settings live in the NODE config, under
# node_config — [timeouts.scaling] auto_heal_startup_cooldown / auto_heal_provisioning_timeout /
# auto_heal_swim_hints_ttl, and [cluster] max_nodes.

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
| `provider` | string | — | no | `hetzner` \| `aws` \| `gcp` \| `azure`. **Rejected loudly if unrecognized** — `ClusterBootstrapConfigParser.parseProvider` (`ClusterBootstrapConfigParser.java:242-250`) returns a `ParseFailed` naming the bad value and the valid provider names; parsing aborts rather than silently dropping the field. |
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
| `replacement_ceiling` | duration string | `"10m"` | no | Cloud sources only (rejected on any other type — PF-26). The longest an auto-heal replacement from this source may stay in flight while the provider still reports it provisioning or running, reports a status it cannot state, or cannot report at all; past it, the leader re-dispatches. A replacement is re-dispatched sooner, after the deficit debounce, when the provider reports it stopped, terminated or failed; when an instance the provider has listed is no longer listed; when an instance the provider has never listed is omitted by twelve consecutive successful listings spanning at least three minutes since its create call resolved; or when the provider's readiness check fails the provision (on a cloud, after 5 minutes still provisioning). A replacement the leader gives up on is not terminated: find it by the `auto-heal PROVISIONED a billable instance` WARN line's `instanceId`, or through the `aether-cluster` label sweep at teardown. Must exceed `5m` (the three-minute first-listing floor plus a two-minute join allowance); a shorter value is refused at load. Read at runtime by the leader from the persisted cluster config (#1049). |
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
| `instance_type` | string | — | no (cloud sources need it) | An EXAMPLE, e.g. `cpx32`. Providers retire instance types — take the value from your provider's current catalogue. |
| `image` | string | — | no | VM image, e.g. `ubuntu-22.04`. |
| `runtime` | string | source-type default | no | References a `[runtime.<name>]` profile. Must satisfy the source/runtime compatibility matrix below. |

**Source/runtime compatibility** (validator codes `PF-19`..`PF-22`): `forge` sources require `EMBER`
runtime; `docker` sources require `DOCKER`; `cloud` sources require `CONTAINER` or `JVM`; `ssh` sources
require `CONTAINER` — the deploy phase can launch only a container over SSH in this release, so a
`JVM` or `EMBER` profile on an `ssh` source is refused at config load (`PF-22`), before any other
source provisions (#1090); the deploy phase refuses it by name again as a backstop. A mismatch fails
validation before provisioning starts. A host may be declared by at most one `ssh` source (`PF-27`);
`PF-09` covers the same host twice within one source.

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
| `[operations.auto_heal] retry_interval`, `startup_cooldown`, `stale_observation_ttl`, `quic_miss_promotion_threshold`, `provisioning_timeout`, `provision_stability_window`, `decommissioned_retention`, `swim_hints_ttl` | — | — | **Rejected at bootstrap** (error PF-26, #675). They parsed into `AutoHealSpec` and reached no node; see warning below. |
| `[operations.tls] auto_generate` | bool | `true` | When `false`, the management API listener uses plain HTTP instead of an auto-generated self-signed cert. |
| `[operations.tls] cert_ttl` | duration string | `"720h"` | |
| `[operations.timeouts] health_check` | duration string | `"300s"` | |
| `[operations.timeouts] quorum_formation` | duration string | `"600s"` | |
| `[operations.timeouts] drain` | duration string | `"120s"` | |
| `[operations.ports] cluster` | int | `8090` | Consensus/gossip port. |
| `[operations.ports] management` | int | `8080` | Management API port. |
| `[operations.ports] app_http` | int | `8070` | Slice app-HTTP port. |
| `[operations.ports] swim` | int | `8190` | SWIM membership port. |

### `[rollback]` — automatic rollback policy (cluster-wide)

Automatic rollback is **ON by default**. It is cluster-wide policy, read by the leader from the committed
cluster TOML at every decision, so a change applied to a running cluster takes effect without a restart.
A blank or seed cluster TOML, or a document without this section, gets the defaults below.

| Key | Type | Default | Meaning |
|---|---|---|---|
| `enabled` | bool | `true` | `false` turns automatic rollback off. The SliceFailure event and alert still fire. |
| `trigger_on_all_instances_failed` | bool | `true` | Roll back when every live instance of a version is broken (the trigger below). |
| `cooldown` | duration | `"5m"` | Minimum time between two automatic rollbacks of one slice. |
| `max_rollbacks` | non-negative int | `2` | Automatic rollbacks per slice before a human must act. |
| `bake_window` | positive duration | `"15m"` | Only a version that became the target less than this long ago is rolled back; an older one failing is an incident and only alerts. |

**The trigger.** Every hosting node of one version reports at least 3 bridge-level defects (the method
threw, a request or response codec failed, or the method is missing) and no success within 30 s. A
failure the slice returns itself, and a timeout, never count. See the deploy guide for the full rule and
its limits.

**To turn it off:**

```toml
[rollback]
enabled = false
```

Every key is typed and validated when the TOML is applied; a mistyped value, a negative count, a
non-positive bake window or an unknown key refuses the apply.

### `[replication]` — default replication factors (cluster-wide, #1564)

The defaults a stream, durable topic or durable entity takes for a replication factor it does not declare
itself (`guarantees.md` §4a). Cluster-wide, read from the committed cluster TOML, so every node resolves
an undeclared value to the same default; it is not a per-node `aether.toml` key. A blank or seed cluster
TOML, or a document without this section, gets the built-in defaults below.

| Key | Type | Default | Meaning |
|---|---|---|---|
| `replication_factor` | int | `3` | RF: copies of each partition, the owner included. At least 3 — a lower RF is declared on the resource itself (with a LOUD warning), never taken from a default. |
| `confirmation_factor` | int | `2` | CF: copies, the owner included, that hold a write before it is acknowledged. `1 <= confirmation_factor <= replication_factor`. |
| `[replication.cluster_events] confirmation_factor` | int | `1` | CF of the `system:cluster-events` stream (its RF is the desired core count, so the CF may not exceed it). At CF 1 an event is acknowledged on the owner's append, so events acknowledged after the last peer-acked offset are lost when the owner dies. |

```toml
[replication]
replication_factor = 3
confirmation_factor = 2

[replication.cluster_events]
confirmation_factor = 1
```

**How a resource resolves:** an undeclared RF takes `replication_factor`; an undeclared CF takes
`min(confirmation_factor, RF)`. Resolution happens once, when the resource's config is committed, so a
change applied here affects only resources declared afterwards; a live resource keeps the factors it
resolved (redeclaring it with different factors is refused at slice activation, `ChangedOnLiveResource`; the
blueprint itself still publishes). Whether a factor fits
is checked per resource at deploy and activation: an RF above the cluster's desired core count is refused,
so a cluster with fewer than three desired cores cannot use the built-in default and its resources declare
their own factors.

Every key is typed and validated when the TOML is applied; a mistyped value, an RF below 3, a CF outside
`1..RF`, a `cluster_events` CF below 1 or above the desired core count, or an unknown key refuses the apply,
naming the key. A core scale that would drop the desired core count below the `cluster_events` CF is refused
the same way (`ClusterEventsFactorsRefused`).

**The DHT (#1777 track 1).** The DHT takes its factors from this same section: a write is acknowledged by
`confirmation_factor` replicas and a read asks `replication_factor - confirmation_factor + 1`, so every read meets
at least one replica that acknowledged the last write when the replica set has not changed
[mechanism: R + W = RF + 1 > RF]. A ring smaller than `replication_factor` holds every key on every node, with the
quorums capped at the ring size. Unlike a stream, the DHT applies a changed value **live**:
- **Gate:** ANY change of `replication_factor` or `confirmation_factor` re-opens the catch-up gate on every partition a
  node replicates.
- **Transitional quorums:** every node, workers included, writes to max(old, new) and reads from max(old, new)
  replicas, capped at the new factor, until the cluster COMMITS that the change has settled. No node switches on its
  own.
- **When it settles:** the leader commits three stages in one consensus record. First every member reports that it
  applied the change. The members are the cores, the workers the leader's membership view counts, and anyone who
  reported; the leader's view drops a member it holds `Dead`. Then every core runs a fresh catch-up pass, which copies
  any write a slower node made at the old quorum before it applied the change. When every core reports that pass
  complete, the change is settled and the new quorums apply.
- **Write fence:** every put carries the replication change its writer had applied when it started. A replica that has
  applied a newer change refuses the put with the retryable `ReplicationChangeStale`, and the writer retries once it
  has applied the change too. A put that was in flight across the whole change, or a writer the roster missed, can
  therefore never land an old-quorum write after the settle
  [verified: DHTReplicationChangeTest `straddlingPut_…`, `writerExcludedFromTheSettle_…`, `writerTaughtTheOldChange_…`, in-JVM].
  The fence is keyed on the change a replica has APPLIED, not on the writers-switched stage: every old-quorum write a
  replica accepts then predates its report, so every core's writers-switched pass pulls it
  [verified: `wOldWriteLandingBetweenAReplicasApplyAndItsWritersSwitched_isRefused`].
  A writer that is itself a replica cannot satisfy an old quorum with its own copy: the local slot is not fenced, so a
  put is acknowledged only on remote EVIDENCE: a success, or a refusal as stale (which fails it). Any other reply — fence
  unknown, an owner-epoch fence, a dispatch failure — is not evidence and the put keeps waiting; a `ReplicationChangeStale`
  refusal seen before the acknowledgement fails the put and rolls the local copy back
  [verified: DHTReplicationChangeTest `v1882r6_excludedWriterThatIsAReplica_localSlotAcceptsAtWold`, `v1882r7_orderA_…`,
  `v1882r7_orderB_…`, in-JVM]. A refusal that arrives AFTER the acknowledgement cannot revoke it; it starts the stale-writer
  clock, and the copies other replicas accepted are pulled by the writers-switched pass
  [verified: `v1882r7_orderC_ackThenLateStale_ackStands_andTheRecordIsKept`].
  The wait for that evidence is bounded at one tenth of the operation timeout (3 s by default): one intra-cluster round
  trip plus a GC pause, an order of magnitude below the caller-visible timeout, so a partitioned or down replica set does
  not stall every W=1 write for the whole timeout [verified: `v1882r9b_allRemotesSilent_acksWithinTheEvidenceWait_notTheOperationTimeout`].
  [limit: with NO evidence — every remote silent, down, fence-unknown or owner-epoch-fenced until every slot has replied or
  the wait runs out — the put is acknowledged on the writer's own slot and sets no stale record; a replica on the newer
  change that does not answer within the wait (slow, GC-paused, partitioned) cannot refute it
  (`v1882r7_orderD_totalSilence_acksPerTheLimit_andSetsNoStaleRecord`,
  `v1882r9_allRemoteRepliesNonStale_acksPerTheLimit_andSetsNoStaleRecord`); #1683-class]
  A put refused this way restores the writer's own slot to what it held before the write (the displaced entry is read in
  the same step as the write), it does not delete it [verified: `v1882r9b_stalePutRollback_restoresTheOverwrittenLocalCopy_exactly`,
  `v1882r9b_aWriteLandingBeforeOurWrite_isRestoredByTheRollback_notLost`].
- **Restarted replicas:** a replica refuses writes (retryable, `ReplicationFenceUnknown`) until its state is restored AND
  consensus reports no catch-up pending (`isPendingCatchUp` false). Before that, an unknown fence never accepts
  [verified: DHTReplicationChangeTest `restartedReplica_refusesWrites_untilItHasAdoptedTheCommittedChange`,
  DhtReplicationFenceRestoreTest]. Such a refusal says nothing about the writer and never counts toward `DHT_WRITER_STALE`.
  The bound is what `isPendingCatchUp` can see: it compares against log positions the node has been TOLD about, so a
  committed change in a log tail the node has not yet received is invisible to it, and the fence can still be too old
  once it is confirmed (`confirmFence` runs synchronously when the state is restored).
  [unverified: mutation M7 — the consensus-caught-up wiring in `AetherNode` replaced by a constant `true` — stays green over
  all 2325 `aether/node` tests, and no boot route reaches "restored AND consensus pending"; the wiring is not pinned. The
  unsafe direction is a replica that is "never pending" and so confirms its fence early, which is the restore-prefix
  residual below [limit: #1683].]
  [unverified: no run shows a restarted replica confirming a fence older than the committed change; the window is the one
  recorded for #1683 (the signal can report "caught up" for up to one consensus sync-retry interval on a replica that
  missed all traffic, `RabiaEngine.probeQuietSlot`).] [limit: #1683]
- **Stale writer event:** a node whose writes stay refused this way for over 5 minutes without adopting the change emits
  `DHT_WRITER_STALE`, and `DHT_WRITER_STALE_RESOLVED` once it adopts it (at most once each).
- **The roster is the leader's membership view, not a committed fact.** A wrong roster only delays the settle (a member
  that is gone but still counted) or hastens it (a live writer held `Dead`); the write fence keeps both safe.
- **What a read returns while unsettled:** a value acknowledged under the old factors reads as the value or a
  retryable `NotCaughtUp`, never "absent". This includes a value written at the old quorum by a node that had not
  applied the change yet
  [verified: integrations/dht/src/test/java/org/pragmatica/dht/DHTReplicationChangeTest.java, in-JVM].
  A partition that is catching up refuses reads until it has caught up, and the catch-up runs twice per change.
- **Liveness cost:** a member that never reports keeps the change unsettled. Reads then stay at the stricter
  transitional quorum: they fail sooner when replicas are down, but they never return a false "absent". A member stops
  being waited for once the leader's membership view holds it `Dead`. During the change, a put from a writer that has
  not applied it yet is refused (retryable) by replicas that have.
- **Operator event:** a change still unsettled after 5 minutes emits `DHT_REPLICATION_UNSETTLED` (WARNING). When it
  settles, or a newer change replaces it, `DHT_REPLICATION_SETTLED` (INFO) follows. Each is published at most once: it
  is missed if the cluster-events owner cannot publish at that moment. See the management API event list.

Idempotency's dedup records live in this replicated DHT; only the cache uses `[cache]`. A node refuses DHT operations
(retryable `ReplicationUnresolved`) until it
has read the committed value after its consensus state is restored. The node-local `[dht.replication] target_rf`
is removed; a node config that still sets it is refused.

### `[cache]` — DHT cache replication (cluster-wide, #1777)

The DHT cache namespace declares its own, lower factors; it holds recomputable data.

| Key | Type | Default | Meaning |
|---|---|---|---|
| `replication_factor` | int | `1` | Copies of each cache entry. At least 1. |
| `confirmation_factor` | int | `1` | Copies that hold a cache write before it is acknowledged. `1 <= confirmation_factor <= replication_factor`; a read asks `replication_factor - confirmation_factor + 1`. |

```toml
[cache]
replication_factor = 1
confirmation_factor = 1
```

Validated on apply like `[replication]`: a mistyped value, a factor out of range or an unknown key refuses the
apply, naming the key. A change takes effect on every node without a restart; cache entries placed under the old
factors may then miss and be recomputed.

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

> **`[operations.auto_heal]` carries `enabled` and nothing else (#675).** The eight tunables that used
> to be accepted here parsed into `AutoHealSpec` and reached no node: every running node builds its
> `AutoHealConfig` from its OWN config — `[cluster] max_nodes` (the fleet cap, via `node_config.cluster`)
> and, under `node_config.timeouts.scaling`, `auto_heal_startup_cooldown` (the formation-check delay, 15s),
> `auto_heal_provisioning_timeout` (the replacement boot window, circuit backoff and drain grace, 60s) and
> `auto_heal_swim_hints_ttl` (the SUSPECTED-hint decay, 15s). The parser now refuses any of the eight with
> PF-26 — naming every stale key in the file and, for the three that named a live timing, the node key
> that sets it — rather than parse and discard; the runtime record itself dropped the five fields nothing
> read (retry interval, stale-observation TTL, QUIC miss threshold, provision stability window,
> decommissioned retention).
>
> `enabled = false` is **rejected at bootstrap** (error PF-25) rather than silently accepted and ignored
> — the parsed value is never read by the provisioning path, so a `false` here would falsely promise
> suppression it can't deliver. Set it to `true` (its only honest value) or omit the key, and use the
> live operator toggle instead: `aether cluster topology auto-heal disable`, which actually suppresses
> replacement provisioning for the current leader term.
>
> #675 made that decision: the node config is the one live surface; every key here that could not reach
> a node is refused (PF-26), and `[timeouts.scaling] auto_heal_retry` was removed from the node config
> for the same reason.

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
