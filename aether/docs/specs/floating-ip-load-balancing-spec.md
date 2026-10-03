# DNS load balancing over a floating-IP pool

Scope: normative target for #1867 (`load_balancer = "floating_ip"`). Present-tense requirements
below do not assert that `release-1.0.0-rc4` implements them. Observations of the current code are
labelled **Baseline** and cite `file:line` at the baseline SHA.

Status: **Proposed; design only; not implemented. Target 1.0.0-rc5 (#1867).**
Baseline: `release-1.0.0-rc4`, `a918adce2` (2026-10-03).
Operator procedures: [runbook](../operators/runbooks/floating-ip-load-balancing.md).

Evidence tags follow the project's claim discipline: `[verified: …]` (exercised end-to-end on the
live path), `[mechanism: …]` (follows from a named design property), `[design intent — unverified]`
(the default for failure-mode claims), and `[unverified: …]` / `[limit: …]` for bounds a reader must
not miss. Nothing in this document is `[verified:]` yet; the acceptance runs in §14 are what would
earn those tags.

## 1. Purpose and owner decisions

Expose a cluster's application port to the internet through a pool of provider-owned public IPs.
The operator's DNS lists every pool IP as A/AAAA records. DNS does not change during operation;
when a node that holds a pool IP fails, drains or becomes unready, the cluster leader moves the IP
to another eligible node through the provider API. Failover therefore happens below DNS, and its
window does not include DNS propagation or client resolver caching.

Owner decisions (2026-10-03, recorded on #1867):

| # | Decision |
|---|---|
| D1 | Target release rc5. |
| D2 | Ingress is a ROLE held by regular cluster nodes, not a node type or a separate deployable. |
| D3 | DNS stays static; IP ownership moves. |
| D4 | The new mode REPLACES `elected`. Pre-GA: no compatibility path, no alias. |
| D5 | #560 stays closed: the retired passive-LB deployable is not revived. |
| D6 | Desired assignment is committed cluster state; only the current consensus leader applies it, in the reconcile/fencing shape of the #1842 node-source epic. |

Name: the mode is `floating_ip`. The codebase already uses "floating IP" for the SPI
(`FloatingIpProvider`), the Hetzner API model and the bootstrap resource record, so the name matches
the existing vocabulary on every layer.

## 2. Model

```
                  DNS (static)  app.example.com  A 203.0.113.10, A 203.0.113.11, AAAA 2001:db8:1::10
                                     │                     │                      │
                     provider routes each pool IP to exactly one instance (the holder)
                                     ▼                     ▼                      ▼
   ┌──────────── eligible nodes of source "eu-workers" (role: worker) ─────────────────────┐
   │  w-1  holds .10      w-2  holds .11      w-3 holds 2001:db8:1::10     w-4  holds none │
   │  app_http :8070 TLS  app_http :8070 TLS  app_http :8070 TLS           (standby)       │
   └─────────┬────────────────────────────────────────────────────────────────────────────┘
             │ local-first dispatch, otherwise forward over the cluster transport
             ▼
   slice instances on any node (core or worker)

   leader (a core): owns the committed assignment table, runs the reconcile loop,
                    is the only node that calls the provider's assign API
```

- **Pool** — a list of provider IPs declared on one source. An IP belongs to at most one pool.
- **Eligible node** — a node provisioned from that source, with a role in the source's
  `eligible_roles`, that passes the eligibility predicate (§7.2).
- **Assignment** — the committed map `pool IP → holder node`, with an epoch (§6).
- **Ingress role** — "holds at least one pool IP". It confers no special code path: every node
  already terminates app HTTP and routes to slices (§3, B13–B14). A node that holds no pool IP is
  still a valid ingress for its own primary address.

## 3. Baseline (rc4) — what exists and what it actually does

Each row is a reading of the code at `a918adce2` unless marked otherwise. Several rows contradict
assumptions in #1867; §16 collects those.

| # | Fact | Where |
|---|---|---|
| B1 | `FloatingIpProvider` has three operations: `attach(ip, targetNodeId)`, `verify(ip) → IpOwnership(ownedByAccount, currentAttachment)`, `compatibleZones(ip)`. There is no detach/unassign and no pool listing. | `aether/environment-integration/.../FloatingIpProvider.java:13-17`, `IpOwnership.java` |
| B2 | Hetzner `attach` parses `targetNodeId` with `Number.parseLong` and passes it as the Hetzner **server id**. | `HetznerFloatingIpProvider.java:71-75` |
| B3 | Bootstrap attach passes the **Aether node id** (`<source>-core-<n>`, the pattern at `BootstrapPhaseProvision.java:356`) of the first core of an `elected` source. That string is not a number, so on Hetzner `parseLong` fails and attach logs `Warning: floating IP attach failed` for every IP. By reading, `elected` attach has not succeeded on Hetzner at this SHA; no test exercises it (no `HetznerFloatingIpProvider` test, no attach test in `BootstrapPhasePost*Test`). | `BootstrapPhasePost.java:37-81` |
| B4 | Teardown "detach" prints a line and returns success; it calls no provider. The attach path never records a `CreatedResource.FloatingIpAssignment`; the only constructor call is in JSON deserialization. | `BootstrapCleanup.java:1200-1204`, `BootstrapStateJson.java:206` |
| B5 | Hetzner `verify` reports `ownedByAccount=true` for any IP present in the account and the server id (or `""`) as `currentAttachment`. | `HetznerFloatingIpProvider.java:81-100` |
| B6 | `HetznerClient.listFloatingIps` reads one page of `/floating_ips` (no `page`/`per_page` handling), has no unassign call, no 429/backoff handling, and `assignFloatingIp` returns when the request is accepted, not when Hetzner's action completes. [unverified: Hetzner's default page size, 25 per its API docs] | `integrations/cloud/hetzner/.../HetznerClient.java:289-301` |
| B7 | IP matching is exact string equality against the API's `ip` field. [unverified: Hetzner reports an IPv6 floating IP as its /64 network; if so an address-form pool entry never matches] | `HetznerFloatingIpProvider.java:57-63` |
| B8 | AWS, GCP and Azure integrations return `Option.empty()` from `floatingIp()` (no provider at all). Docker returns the `NoopFloatingIpProvider`. | `AwsEnvironmentIntegration.java:108`, `GcpEnvironmentIntegration.java:107`, `AzureEnvironmentIntegration.java:116`, `DockerEnvironmentIntegration.java:58-59` |
| B9 | `LoadBalancerMode` = `none` / `external` / `elected`. An unrecognised string falls back silently to the source-type default, which is `elected` for `forge` sources and `none` otherwise. | `LoadBalancerMode.java:14-18`, `ClusterBootstrapConfigParser.java:399-409` |
| B10 | `elected` validator rules are **PF-17** (rejected on SSH sources) and **PF-14** (needs a non-spot sub-table). **PF-18 is firewall-rule validation** (port, protocol, CIDR), unrelated to `elected`. REQ-5.1.3.2 ("`elected` requires non-empty `load_balancer_ips`") has no validator rule. | `ClusterBootstrapConfigValidator.java:297-298, 392-407, 499-512` |
| B11 | Other `elected`-gated code: firewall auto-open of `app_http` TCP+UDP (REQ-5.1.8.2) and its warnings, and preflight "PF-12" which only resolves the provider (it never calls `verify` or `compatibleZones`; no production caller of either exists). | `BootstrapPhaseFirewall.java:95, 250, 267`, `PreflightChecker.java:120-133` |
| B12 | **The node runtime never reads `LoadBalancerMode`.** The only consumers are the config module and the CLI. The cluster-bootstrap spec's REQ-6.3.1 (CDM LB task group with hard anti-affinity) and REQ-6.3.4 (3–5 s failover) describe behaviour with no implementation. `LoadBalancerManager` (the `external`-mode target sync) is wired whenever `[cloud.load_balancer]` is configured, regardless of mode, and is activated by the local `LeaderChange` notification with no epoch fence on its provider calls. | `AetherNode.java:2846-2851, 7385-7391` |
| B13 | Every node's app HTTP server binds the wildcard address for its port, so it accepts traffic on any address configured on the host, including an alias. | `integrations/net/http-server/.../NettyHttpServer.java:179` |
| B14 | App HTTP dispatch is local-first and forwards to a hosting node otherwise; security overrides are enforced "at every ingress" (#1659). Any node can therefore serve as ingress for any route. | `AppHttpServer.java:872-876, 910-914` |
| B15 | `/health/ready` (management port, `LOCAL`) is `UP` iff the node's `NodeLifecycle` state is `ACTIVE`. Cluster-wide, the leader holds an authoritative readiness view (`NodeReportedState` SYNCING / READY / DRAINING, carried on the cluster-sync pong). A new leader's view starts non-authoritative. | `StatusRoutes.java:516-532`, `NodeReportedState.java:23`, `NodeLifecycleRoutes.java:140-155` |
| B16 | Drain is a **leader-local command**, not a KV record: every operator/controller drain goes through one sink (`requestDrainThroughFsm`) that enqueues it in `DrainCommandRegistry` and notifies `MembershipFsm`; the target receives `DRAIN` on the cluster-sync ping and, on entering DRAINING, **stops accepting new app-layer work** at once. | `AetherNode.java:1052-1065`, `DrainProcedure.java:28-31`, `NodeLifecycleRoutes.java:219-268` |
| B17 | Committed-state writes can be leader-fenced: a `KVCommand.LeaderTransaction` carries the committing leader's `LeaderValue`, read witnesses and per-key expected values; the applier accepts it iff the carried leader equals the committed `LeaderKey` value and every expectation matches, deterministically on every replica. `LeaderAuthorized` values refuse plain Put/Remove. `HierarchyStateWriter` wraps this for leader-authored state. | `KVStore.java:192-209`, `LeaderAuthorized.java`, `HierarchyStateWriter.java:23-79` |
| B18 | Epoch-bearing values (`EpochBearing`, `Epoch(incarnation, rabiaTerm, localCounter)`) are refused when older than the committed value; a `mintsEpoch` write must be strictly newer. `LeaderTerm` mints the term component from the committed election's `viewSequence`. | `EpochBearing.java:35-49`, `Epoch.java:22`, `LeaderTerm.java` |
| B19 | NodeId → provider instance id is recorded as `NodePlacementValue(sourceName, observedZone, providerInstanceId)` under `NodePlacementKey(nodeId)` for worker placements. [unverified: whether core nodes get a placement record] | `AetherValue.java:2461`, `ClusterTopologyManagerRecord.java:1211-1224, 1286-1300` |
| B20 | Bootstrap and leader-provisioned replacements render cloud-init through one shared `NodeUserDataRenderer`, fed from the committed cluster TOML. Containers run with `--network host`; JVM mode runs under systemd as a non-root user. The user-data script itself runs as root at first boot. | `NodeUserDataRenderer.java:272-275`, `ClusterTopologyManagerRecord.java:1453-1500` |
| B21 | SWIM defaults: period 1 s, probe timeout 500 ms, suspect timeout 10 s. | `TimeoutsConfig.java:192-195` |
| B22 | `aether cluster apply` in rc4 actuates scale changes only; any change to a source field is rejected. There is no runtime path to change `load_balancer_ips`. | `aether/docs/reference/cli.md` (`aether cluster apply`) |
| B23 | `[app-http.tls] cert_path` / `key_path` configure an operator certificate for the app listener, server-auth only. | `ConfigLoader.java:394-402`, `AppHttpServer.java:562-566` |

## 4. Invariants

FIP-01. **One committed holder per pool IP.** The assignment table holds at most one holder per IP.
[mechanism: one KV key per IP; every write is a `LeaderTransaction` whose expected prior value must
match (B17), so two leaders that planned from the same prior value cannot both commit]

FIP-02. **A deposed leader cannot commit an assignment.** [mechanism: the transaction's
`LeaderValue` witness must equal the committed `LeaderKey` at the transaction's log position (B17);
a leader deposed before its transaction applies is refused on every replica]

FIP-03. **Assignment epochs only advance.** Each move mints a new epoch strictly above the
committed one. [mechanism: `FloatingIpAssignmentValue` is `EpochBearing` with `mintsEpoch()=true` for
a holder change (B18)]

FIP-04. **Provider calls follow commits, never precede them.** The leader calls `assign(ip, X)` only
after a transaction naming `X` at epoch `E` was accepted, and only while (a) its local committed
`LeaderKey` still names itself, (b) the committed value for that IP is still `(X, E)`, and (c) it
has not observed quorum loss. The check runs immediately before each call. [mechanism: a local
read of committed state before each provider request]
[limit: in-flight-request] A request already sent by a leader that is deposed while it is in
flight cannot be recalled; it can land after the new leader's request and move the IP back to a
stale holder. FIP-05 bounds that state; nothing prevents it.

FIP-05. **The physical holder converges to the committed holder.** Every reconcile pass reads the
provider's actual holder for each IP and re-issues `assign` where it differs from the committed
holder. A divergence from any cause (a stale leader's late request, a manual console move, a lost
API response) is corrected by the next pass of the current leader. Bound: provider request timeout
+ `verify_interval` (default 30 s) + one provider assign latency. [design intent — unverified]

FIP-06. **A leader change alone moves no IP.** A new leader adopts the committed table, verifies it
against the provider, and corrects divergence only (FIP-05). Death/readiness triggers act only on
facts observed after activation, and only once its readiness view is authoritative (B15).
[design intent — unverified]

FIP-07. **Only eligible nodes are assigned.** A move target satisfies the full eligibility
predicate (§7.2) at plan time. [mechanism: the predicate is evaluated by the planner; the
transaction carries the target's activation/placement record as a read witness, so a target whose
record changed before apply refuses the move]

FIP-08. **Make-before-break on planned removal.** For an operator or controller drain/shutdown of a
node that holds pool IPs, the leader moves those IPs to other eligible nodes, and sees the provider
report the new holders, before the `DRAIN` command is released to the target. Bounded by
`make_before_break_timeout`; on expiry the drain proceeds and the cost is reported (§9.3).
[mechanism: the gate sits at the one drain sink, B16] [unverified: that every leader-initiated drain
path reaches that sink; the implementation PR must enumerate them]

FIP-09. **No IP is unassigned by the reconcile loop.** With no eligible node, an IP stays with its
last holder and the table reports it `UNSERVED`. Unassigning would turn "maybe served" into
"certainly not served". Teardown is the only unassign path.

FIP-10. **Guest readiness gates assignment where the provider needs guest setup.** On providers
whose capability says the guest must carry the address (Hetzner), an IP is assigned only to a node
that reports that address configured on its interfaces (§7.2). [mechanism: the node's own report
of its interface addresses, not the configuration it was supposed to boot with]

FIP-11. **Configuration errors are refused, never defaulted.** An unknown `load_balancer` value, a
`floating_ip` source on a provider with no `FloatingIpProvider`, an IP in two pools, or an empty
eligible-role set is a validation error at parse/preflight. [mechanism: PF-28..PF-32, §7.3] (B9 shows
the current parser silently defaulting.)

FIP-12. **Rate of provider calls is bounded.** Moves are event-driven; verification reads are one
pool listing per `verify_interval`, not one per IP; rebalance moves are paced (§8.4). 429 responses
back off per #1839. [design intent — unverified: budget fit against provider limits, §11]

## 5. Facts and owners

| Fact | Owner | Readers and enforcement |
|---|---|---|
| Pool membership (which IPs, which source, which roles are eligible) | Committed cluster configuration (`ClusterConfigKey` TOML) | Leader planner; node user-data renderer |
| Desired assignment `ip → (holder, epoch, reason, since)` | Leader, via `LeaderTransaction` (FIP-01..03) | Reconciler, Management API, dashboard |
| Actual provider holder | The cloud provider | Reconciler verify pass (FIP-05); reported, never committed |
| Node liveness / departure | Membership (committed departure decision) | Death trigger |
| Node readiness | Node-reported state on the leader's authoritative view (B15) | Readiness trigger |
| Pending drain | Leader-local `DrainCommandRegistry` (B16) | Make-before-break gate (FIP-08) |
| Guest alias set | The node itself, from its interface list | Eligibility (FIP-10) |
| NodeId → provider instance id, observed zone | Placement record (B19) | Provider `assign` target; zone eligibility |
| IP → compatible zones | Provider (`compatibleZones`) | Eligibility; preflight |

Each row has a producer and a consumer on the live path; a field with neither does not ship.

## 6. Committed state

New records (each needs a `SystemTags` pin, or every node fails to boot):

```java
record FloatingIpAssignmentKey(SourceName source, String ip) implements RuntimeKey   // "floating-ip/<source>/<ip>"

record FloatingIpAssignmentValue(NodeId holder,
                                 Epoch epoch,
                                 MoveReason reason,     // INITIAL, DEATH, UNREADY, DRAIN, REBALANCE, ZONE, OPERATOR
                                 long sinceEpochMillis) // wall time of the commit, display only
        implements AetherValue, LeaderAuthorized, EpochBearing<Epoch>
```

- One key per IP, so a move touches one key; a rebalance pass that moves several IPs puts every
  mutation in one `LeaderTransaction` (all-or-nothing at apply, B17).
- `epoch` is minted from `LeaderTerm` (B18): `(incarnation, committedTerm, counter)`.
- Removing an IP from the pool deletes its key through an authorized transaction (the only legal
  de-authorization of a `LeaderAuthorized` key).
- Not committed: the observed provider holder, last provider error, per-IP status. These are
  leader-local and exposed through the Management API (§12); a new leader rebuilds them with one
  verify pass.

## 7. Configuration and eligibility

### 7.1 Surface

```toml
[source.eu-workers]
type = "cloud"
provider = "hetzner"
load_balancer = "floating_ip"

[source.eu-workers.floating_ip]
pool = ["203.0.113.10", "203.0.113.11", "2001:db8:1::10"]
eligible_roles = ["worker"]           # default ["worker"]; "core" allowed; "spot" refused
rebalance = "on_imbalance"            # "on_imbalance" (default) | "manual"
make_before_break_timeout = "60s"     # FIP-08
verify_interval = "30s"               # FIP-05
readiness_grace = "5s"                # §8.2
```

- `pool` entries are single addresses. IPv4 and IPv6 may be mixed. For a Hetzner IPv6 floating IP
  (a /64), the entry is the one address inside the /64 that DNS publishes; the provider matches it
  by containment (fixes B7).
- `load_balancer_ips` is removed. (Owner question Q2: keep the old key name instead.)
- Spot nodes are not eligible (PF-31 refuses `spot` in `eligible_roles`): a spot reclaim is a death
  with no make-before-break.

### 7.2 Eligibility predicate

A node `n` is eligible for IP `ip` of source `s` iff all hold:

1. `n` was provisioned from `s` and its committed role is in `s.eligible_roles`;
2. `n` is a live member (no committed departure);
3. the leader's readiness view is authoritative and reports `n` READY;
4. `n` is not a pending drain target (B16);
5. `n`'s observed zone is in `compatibleZones(ip)` (zone binding);
6. if the provider requires guest setup, `n` reports `ip` configured on an interface (FIP-10);
7. `n` holds fewer than the provider's `maxIpsPerNode` (§10).

Input 6 is new: each node includes the set of pool addresses present on its interfaces in its
cluster-sync pong, next to `NodeReportedState` (enumerated with `java.net.NetworkInterface`, no
privilege needed).

### 7.3 Validation (new PF rules; numbers continue after PF-27)

| Rule | Refuses |
|---|---|
| PF-28 | `load_balancer` value not in `none` / `external` / `floating_ip` (replaces the silent default, B9) |
| PF-29 | `floating_ip` on a source whose provider has no `FloatingIpProvider`, or on SSH; Forge/Docker use the no-op provider |
| PF-30 | empty `pool`, a malformed address, a duplicate address, or one address in two pools |
| PF-31 | `eligible_roles` empty, containing `spot`, or naming a role with no sub-table on the source |
| PF-32 | preflight: an IP not owned by the account, or whose compatible zones exclude every zone of the source (the checks PF-12/PF-13 promised and never ran, B11) |
| PF-33 | `pool` size above `eligible node count × maxIpsPerNode` (provider capability) — warning, not error, because the eligible count changes at runtime |

## 8. Reconcile loop

### 8.1 Shape

A leader-only `FloatingIpReconciler`, activated and deactivated on `LeaderChange` exactly like
`LeaderReconciler`, with the same CAS-debounced single entry point
`triggerReconcile(trigger)` (one in-flight pass plus at most one follow-up). It follows the #1842
shape: `reconcile(desired, live, observed)` run every verify tick, not only at activation.

One pass:

1. **Read** the pool from committed config, the committed assignment table, membership, the
   authoritative readiness view, pending drains, placement records and guest-alias reports.
2. **Observe**: one provider listing of the pool (`holders()`, §10) unless the last one is fresher
   than `verify_interval / 2`.
3. **Plan** the target table with the rule in §8.4. The plan contains only changes.
4. **Commit** all changes in one `LeaderTransaction` (FIP-01..03, FIP-07 witnesses). Refused →
   stop the pass and re-trigger (state changed or leadership lost).
5. **Apply**: for each IP whose observed holder differs from the committed holder, run the FIP-04
   check, then `assign`. Calls are paced (§11).
6. **Confirm**: poll `holders()` until each assigned IP reports the committed holder or the
   provider's action fails; record status and emit events (§12).

### 8.2 Triggers

| Trigger | Source | Action |
|---|---|---|
| Activation | `LeaderChange` gained | verify-only pass (FIP-06); triggers armed after `nttDepartureTimeout × 1.5`, as `LeaderReconciler` does |
| Death | committed departure of a holder (`NodeRemoved`, `NodeDecommissioned`, worker leave decision) | move its IPs |
| Unready | holder reported non-READY on an authoritative view for ≥ `readiness_grace` | move its IPs; a node that returns to READY becomes eligible again but does not get its IPs back (stickiness) |
| Drain / shutdown | drain sink (B16), before release to the target | make-before-break (§8.3) |
| Join / ready | an eligible node becomes eligible | rebalance check (§8.4) |
| Config | `ClusterConfigKey` change touching a pool | assign new IPs, delete removed keys |
| Verify tick | every `verify_interval` | FIP-05 correction; also catches an unready transition missed by an event |

Death detection uses committed departures, not raw SWIM. In the hierarchical runtime a core does
not keep SWIM state for every worker (hierarchical-cluster-contract-spec H12); worker departures
reach the leader as committed decisions. [unverified: worker departure latency at the leader under
the hierarchy; measure in the cloud run]

### 8.3 Make-before-break

At `requestDrainThroughFsm(target)` on the leader:

1. If `target` holds no pool IP, release the drain immediately (today's behaviour).
2. Otherwise mark `target` pending-drain (predicate 4 now excludes it), trigger a pass, and hold the
   `DRAIN` command. The pass moves every IP of `target` (reason `DRAIN`).
3. Release the drain when the provider reports a new holder for each of those IPs, or when
   `make_before_break_timeout` expires. Expiry releases anyway, logs at WARN, emits
   `FloatingIpMakeBeforeBreakExpired`, and the remaining IPs move by the death trigger later.
   (Owner question Q10: refuse the drain instead.)
4. The rolling upgrade path drains through the same sink, so it inherits this without its own code.

[limit: leader-local-gate] The pending drain lives in leader memory (B16). If the leader changes
while a drain is held, the new leader has no record of it; the operator's drain call must be
repeated. The Management API response says so. (Making drains committed is outside this spec.)

Make-before-break bounds, but does not remove, connection loss: established TCP connections to a
moved IP reset (§11).

### 8.4 Balancing rule

Let `E` be eligible nodes for a source and `P` its pool. Target holding per node is
`⌊P/E⌋` or `⌈P/E⌉`.

1. **Unserved first**: an IP whose holder is ineligible moves to the eligible node with the fewest
   IPs; ties go to the lowest `NodeId` (deterministic, so tests can name the expected holder).
2. **Stickiness**: an IP whose holder is eligible does not move, except under rule 3.
3. **Rebalance** (`rebalance = "on_imbalance"`): if `max(count) − min(count) ≥ 2`, move ONE IP from a
   most-loaded node to a least-loaded node, then wait at least `rebalance_pacing` (default 5 min)
   before the next rebalance move. A difference of 1 is unavoidable and never triggers a move.
   Each rebalance move resets that IP's connections, so it is paced and can be switched off
   (`manual`).
4. **Zone**: rules 1–3 consider only nodes whose zone is compatible with the IP.
5. **Families**: IPv4 and IPv6 are balanced as separate pools, so one node does not end up holding
   both families while another holds neither.

## 9. Fencing in detail

### 9.1 What the KV fence gives

Two leaders cannot both commit a holder for the same IP from the same prior value: the second
transaction's expected value no longer matches, and a deposed leader's witness no longer equals the
committed `LeaderKey` (B17). Every replica decides identically from committed state.

### 9.2 What it does not give, and what closes the gap

The provider call is an external side effect; KV fencing cannot cover it. Three layers:

1. **Pre-call check** (FIP-04): leadership, committed `(holder, epoch)` and quorum are re-read
   immediately before each call. A leader that has applied its own deposition stops.
2. **Self-stop on quorum loss**: a leader that observes quorum loss issues no provider calls; a
   minority node self-drains after `split_timeout` anyway.
3. **Convergence** (FIP-05): the remaining case, a request sent before deposition that lands after
   the new leader's request, leaves the IP on the stale holder until the new leader's next verify
   pass. The stale holder was the committed holder of an earlier epoch, so it is often still
   serving. When it is dead, that IP is unserved for up to `verify_interval` + one assign.

What this does not cover: a provider that accepts two concurrent assigns and ends in an order
different from both requests' completion. The provider guarantees one holder per IP at any
instant (Hetzner: one server per floating IP; AWS: one association per EIP with
`AllowReassociation`) [unverified: provider docs, not exercised here]; this spec depends on that
and on nothing stronger.

### 9.3 Stated costs of the fence

- A held drain can wait up to `make_before_break_timeout`.
- A stale late request can misroute one IP for one verify interval (rare; needs a leader change
  during an in-flight move).

## 10. Provider SPI and per-provider behaviour

### 10.1 SPI (replaces B1)

```java
public interface FloatingIpProvider {
    Promise<Unit> assign(String ip, ProviderInstanceId target);   // one request; idempotent when already held
    Promise<Map<String, Option<ProviderInstanceId>>> holders(Set<String> pool); // one paged listing
    Promise<Set<String>> compatibleZones(String ip);
    Promise<Unit> unassign(String ip);                           // teardown only (FIP-09)
    FloatingIpCapabilities capabilities();
}

record FloatingIpCapabilities(GuestSetup guestSetup,     // ALIAS_ON_ALL_ELIGIBLE | NONE
                              int maxIpsPerNode,
                              boolean singleRequestMove)  // false: a move is unassign + assign
```

The target is the provider instance id from the placement record (B19), never the Aether node id
(fixes B2/B3). `holders` reads every page (fixes B6).

### 10.2 Behaviour by provider

| Provider | Move | Guest setup | IPs per node | rc5 status |
|---|---|---|---|---|
| Hetzner | `POST /floating_ips/{id}/actions/assign` (one request; reassigns from the current server) | Every eligible node configures **every** pool address at boot via cloud-init (IPv4 /32; IPv6 the chosen address of the /64). Traffic reaches only the holder, so a move needs no guest action. | no fixed limit [unverified] | implement (Hetzner-first) |
| AWS | `AssociateAddress` with `AllowReassociation=true` (one request) | None for one EIP on the primary private IP. More than one per node needs secondary private IPs, which some AMIs do not configure automatically. | 1 without guest setup | Q1 |
| GCP | Remove the instance's access config, add one with the static IP: **two requests, unserved between them** | None | 1 per NIC (one external IPv4 per access config); the node's ephemeral external IP is replaced | Q1 |
| Azure | Dissociate the public IP from one NIC ip-configuration, associate to another: two requests | None for the primary ip-configuration; secondary ones need guest setup | 1 without guest setup | Q1 |
| Docker / Forge | No-op provider: the table and triggers run, traffic is unaffected | — | unbounded | no-op (in-JVM tests) |
| SSH | Refused (PF-29); VRRP is out of scope | — | — | refused |

AWS/GCP/Azure facts in this table come from provider documentation, not from this codebase (no
client call for addresses exists in `integrations/cloud/{aws,gcp,azure}`) [unverified].

**Missing implementation rule:** a provider without a `FloatingIpProvider` is refused at
validation (PF-29). There is no silent fallback to `none` and no no-op provider for a real cloud.

### 10.3 Hetzner guest setup

`NodeUserDataRenderer` (B20) emits, for a node whose role is eligible on a `floating_ip` source, a
persistent alias for each pool address (a netplan drop-in or an `ip addr add … dev eth0` unit that
survives reboot). Because bootstrap and leader-provisioned replacements share the renderer, both get
it. A pool address added later is not on existing nodes; those nodes stay ineligible for it
(FIP-10) until replaced (runbook §6) or until an in-place alias path exists (Q3).

[unverified: hazard-h3-source-address] HTTP/3 runs over UDP on a wildcard-bound socket. On an alias
address the kernel can choose the primary address as the reply source unless the server sets the
source per datagram, and a client then sees replies from the wrong IP. TCP is not affected. The
Hetzner acceptance run must check HTTP/3 to a pool IP; if it fails, the runbook's guidance is to
stop advertising HTTP/3 on pool IPs until fixed.

## 11. Failure modes and costs

| Event | What clients see | Window / bound | Recovery |
|---|---|---|---|
| Holder JVM dies, VM up (Hetzner) | The alias is still on the guest; the kernel answers SYN with RST: fast connection failure | until reassignment: SWIM detection (suspect 10 s after the last failed probe, B21) + departure commit + one assign + provider action. Estimate 12–20 s [design intent — unverified; REQ-6.3.4's 3–5 s is withdrawn] | automatic (death trigger) |
| Holder VM lost | Packets to its IPs are dropped: clients wait for their connect timeout | same as above | automatic |
| Holder alive, not READY | Requests may fail or be slow | `readiness_grace` + one assign | automatic; the node gets no IPs back automatically (stickiness) |
| Planned drain / upgrade | Connections on moved IPs reset; new connections succeed | `make_before_break_timeout` at most | automatic; on expiry see §8.3 |
| Rebalance move | Connections on that IP reset | one IP per `rebalance_pacing` | set `rebalance = "manual"` to stop |
| Leader change | None by itself (FIP-06) | — | — |
| Stale leader's late request | One IP on a stale holder | ≤ `verify_interval` + one assign | automatic (FIP-05) |
| No eligible node | IP `UNSERVED` (FIP-09) | until a node becomes eligible | add capacity or fix readiness |
| Provider API down / 429 | Moves wait; IPs stay with current holders | until the API recovers | automatic with backoff; alert on `UNSERVED`/`DIVERGED` |
| Pool IP in a zone with no nodes | Never assignable | permanent | place eligible nodes in a compatible zone, or swap the IP |

Costs to state everywhere this feature is described:

- **Share of clients affected by one failure ≈ (IPs on the failed node) / P**, for the failover
  window, assuming even DNS spread, which DNS does not provide (next item).
- **DNS spread is uneven.** Resolvers and clients reorder, cache and prefer addresses (RFC 6724
  sorting, "first record" clients). Expect skew between pool IPs; capacity per node must absorb it.
- **TCP connections to a moved IP reset.** Long-lived connections (WebSocket, HTTP/2, gRPC
  streams) must reconnect.
- **Clients must retry** and should try more than one A/AAAA record. Retries of non-idempotent
  requests are the client's decision; the platform gives no exactly-once delivery across a failover.
- **TLS terminates on every eligible node**, so every eligible node carries the certificate.
- **The firewall must admit `app_http` on pool addresses** on every eligible node.
- **Floating IPs are zone-bound** (Hetzner: network zone/home location; GCP/AWS/Azure: region).
- **Provider API rate limits apply**: Hetzner documents a per-project hourly budget [unverified:
  value]. Steady state uses one listing per `verify_interval` per source (120/h at 30 s); moves are
  rare. Budget against other users of the same token (auto-heal, firewall, Hetzner LB sync).

## 12. Observability (REST → CLI → docs → dashboard)

| Layer | Surface |
|---|---|
| REST | `GET /api/v1/ingress/floating-ips` (leader-routed): per IP `source, family, committedHolder, epoch, reason, since, observedHolder, status, lastError`; status ∈ `CONVERGED` / `MOVING` / `DIVERGED` / `UNSERVED` / `UNVERIFIED`. `POST /api/v1/ingress/floating-ips/rebalance` runs one rebalance step now. (Operator move/pin: Q7.) |
| CLI | `aether ingress floating-ips` (table; `-o json`), `aether ingress rebalance` |
| Docs | `management-api.md`, `cli.md`, `bootstrap-config.md` (`[source.X.floating_ip]`), feature catalog, this spec, the runbook |
| Dashboard | An "Ingress" panel with the assignment table and per-IP status; on a cluster with no `floating_ip` source it shows the degenerate empty state, per the #494 ruling |
| Events | `FloatingIpMoved{ip, from, to, reason, epoch}`, `FloatingIpMoveFailed{ip, target, cause}`, `FloatingIpUnserved{ip}`, `FloatingIpMakeBeforeBreakExpired{node, ips}` on the cluster event stream |
| Metrics | `aether_floating_ip_moves_total{source,reason}`, `aether_floating_ip_unserved{source}`, `aether_floating_ip_diverged{source}`, `aether_floating_ip_provider_call_seconds{op,outcome}` |

## 13. Removing `elected`

Pre-GA; no compatibility path. The implementation PR removes, and the reviewer checks with a
repo-wide grep for `ELECTED`, `"elected"` and `elected LB` that nothing remains outside archives:

- `LoadBalancerMode.ELECTED`; the forge default (B9) becomes `none` (Q8);
- validator `validateElectedLbRestriction` (PF-17) and `validateElectedLbHasNonSpot` (PF-14), and
  their tests in `ClusterBootstrapConfigValidatorTest`; PF-18 stays (firewall rules, B10);
- `BootstrapPhasePost.activateElectedLoadBalancers` and helpers (bootstrap attach is replaced by
  the leader's initial assignment; the CLI no longer calls the provider);
- `BootstrapPhaseFirewall` `elected` gates and warnings (B11): the auto-open applies to
  `floating_ip` sources;
- `PreflightChecker.checkFloatingIpIfElected` → PF-32;
- `BootstrapCleanup.detachFloatingIp` becomes a real `unassign` driven by the configured pool
  (the leader, not the CLI, makes assignments now, so the CLI's `CreatedResource` ledger cannot
  list them); `aether cluster destroy` unassigns every pool IP before deleting servers (Hetzner may
  already unassign on server deletion [unverified]; the explicit call does not depend on it). Pool
  IPs themselves are operator-owned and never released by teardown;
- `parseLoadBalancerMode` silent default → PF-28;
- docs: cluster-bootstrap-spec REQ-5.1.3.2, REQ-5.1.4.2, REQ-5.1.7.3, REQ-5.1.8.2, §6.3 (REQ-6.3.1–6.3.5),
  §11.1a/§11.2 floating-IP rows; `bootstrap-config.md` `load_balancer` rows; feature catalog rows
  147/197/198; `cluster-generation-spec.md`, `cluster-init-wizard-spec.md`, `dashboard-ui-spec.md`,
  `docker-scaling-test-spec.md`, `hetzner-e2e-test-spec.md` mentions; `aether cluster init`/scaffold
  templates if they emit `elected`;
- tests that construct `LoadBalancerMode.ELECTED` (validator, parser, firewall tests).

## 14. Acceptance

Each unit/in-JVM test names the production line it pins and is shown red with that line reverted.

| ID | Test | Pins |
|---|---|---|
| T1 | death: holder departs → IP committed to the least-loaded eligible node, lowest id on tie | death trigger, §8.4 rule 1 |
| T2 | unready: holder non-READY past `readiness_grace` moves; a blip shorter than the grace does not | readiness trigger, grace |
| T3 | non-authoritative readiness view after leader change moves nothing | FIP-06 |
| T4 | drain: `DRAIN` is released only after the provider fake reports the new holder; reverting the gate releases it first | FIP-08 |
| T5 | drain timeout: provider fake never confirms → drain released at the timeout, event emitted | §8.3 step 3 |
| T6 | balance: 5 IPs, 2 nodes → 3/2; a third node joins → exactly one move, then none within pacing | §8.4 rule 3 |
| T7 | stickiness: a recovered node gets no IP back without imbalance ≥ 2 | §8.4 rule 2 |
| T8 | stale leader: a transaction carrying the previous `LeaderValue` is refused and its provider call is never issued | FIP-02, FIP-04 |
| T9 | competing planners from one prior value: exactly one commit accepted | FIP-01 |
| T10 | convergence: provider fake reports a holder ≠ committed → next pass re-assigns | FIP-05 |
| T11 | guest alias missing → node not eligible on Hetzner; eligible on a `NONE` provider | FIP-10 |
| T12 | zone: IP with compatible zones {A} never assigned to a zone-B node | predicate 5 |
| T13 | no eligible node → no unassign, status `UNSERVED` | FIP-09 |
| T14 | validation: PF-28..PF-31 each refuse their case; an unknown mode string is an error, not `none` | FIP-11 |
| T15 | Hetzner provider: paged listing, IPv6 containment match, server id from placement record | B2, B6, B7 fixes |
| T16 | codec: new key/value pinned (`SystemCodecPinningTest`) and round-trip | §6 |
| T17 | renderer: eligible-role cloud-init contains one alias per pool address; ineligible role contains none | §10.3 |

In-JVM (Ember, no-op provider): a 5-core + 3-worker cluster with a 3-IP pool; kill a holder; the
table converges with the dead node holding nothing; drain a holder; the moves precede DRAINING in
the event order.

**Hetzner cloud acceptance (`floating_ip` mode).** 5 cores + 3 workers, 3 IPv4 + 1 IPv6 pool IPs,
DNS not required (clients target IPs directly). A client loop per pool IP sends one request every
200 ms and records each failure's start and end. Steps, each with pass criteria written before the
run:

1. Bootstrap: every pool IP `CONVERGED` within 2 min of formation; each IP answers on `app_http`
   with TLS.
2. `kill -9` the JVM on one holder: its IPs answer again from another node; record the window per IP.
   Pass: ≤ the window the runbook documents (set from this measurement, with margin).
3. Power off one holder's VM (provider API): same, with client connect timeouts recorded.
4. Drain a holder: no request on its IPs fails other than connections reset by the move (count them).
5. Leader kill during a move (best effort): table converges within `verify_interval` + 1 assign.
6. HTTP/3 to a pool IP succeeds, or the hazard in §10.3 is confirmed and recorded.
7. Teardown leaves no IP attached to a deleted server and no orphaned assignment keys.

## 15. Delivery sequence

1. SPI v2 + Hetzner provider fixes (B2, B6, B7) + T15.
2. Config surface, PF-28..PF-33, `elected` removal (§13) + T14.
3. Committed records + reconciler + triggers + make-before-break + T1–T13, T16.
4. Guest alias rendering + pong alias report + T11, T17.
5. Observability quad (§12).
6. Runbook finalised with measured windows; Hetzner acceptance run.
7. AWS/GCP/Azure providers, per Q1.

## 16. Corrections to #1867's stated assumptions

| #1867 says | Baseline shows |
|---|---|
| "The CLI attaches floating IPs at bootstrap and detaches them on teardown." | Attach passes an Aether node id where Hetzner needs a server id and fails (B3); detach is a print (B4). Neither works today. |
| "AWS/GCP/Azure are no-ops." | They return no provider at all (B8); only Docker has the no-op. |
| "Validator rules PF-17 and PF-18." | `elected` rules are PF-17 and PF-14; PF-18 is firewall validation and stays (B10). |
| "AWS EIP, GCP static IP and Azure static public IP need no guest setup (1:1 NAT)." | True for one IP per node on the primary address. GCP allows one external IP per NIC and moves in two requests; Azure moves in two requests; more than one IP per node on AWS/Azure needs guest setup [unverified, §10.2]. |
| "Move trigger: node DEATH (SWIM)." | Under the hierarchy contract a core does not track every worker in SWIM (H12); the trigger is the committed departure, with worker latency unmeasured. |
| `elected` as an existing mode with behaviour | The runtime never reads the mode (B12); removing it removes config/CLI code only. |
| Pool management (add/remove IPs) | No runtime path exists: `cluster apply` rejects source-field changes (B22). |

## 17. Owner questions

| # | Question | Recommendation |
|---|---|---|
| Q1 | rc5 provider scope: Hetzner only (others refused by PF-29) or all four? | Hetzner-first; AWS next (single-request move); GCP/Azure after, with their one-IP-per-node and two-request limits documented |
| Q2 | Config shape: new `[source.X.floating_ip]` sub-table, or keep `load_balancer_ips`? | sub-table |
| Q3 | Changing the pool at runtime: extend `cluster apply` to accept pool changes as a leader-committed config write, or a dedicated endpoint? And new aliases on existing Hetzner nodes: rolling replacement, or a root-owned alias agent installed by cloud-init? | extend `apply`; rolling replacement for rc5 |
| Q4 | A source whose eligible roles have no nodes (e.g. core-only clusters): refuse, or fall back to cores? | refuse (PF-31); operators opt cores in explicitly |
| Q5 | Should readiness include "app HTTP is bound and answering", beyond lifecycle ACTIVE? | yes, as a pong field; out of rc5 if it grows |
| Q6 | Rebalance default: `on_imbalance` (paced) or `manual`? | `on_imbalance` with 5 min pacing |
| Q7 | Operator move/pin endpoint in rc5? | no; rebalance-now only |
| Q8 | Forge/Docker default after `elected` goes: `none`, or `floating_ip` with the no-op provider for dev parity? | `none` |
| Q9 | Act on SUSPECT (faster, more false moves) or only on committed death? | committed death |
| Q10 | Make-before-break timeout: release the drain (default here) or refuse it? | release, with the event |
