<!-- SPDX-License-Identifier: BUSL-1.1 -->
<!-- Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko -->
<!-- Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0. -->

# RFC-0017 — Cluster-Owned Provisioning

| Field | Value |
|---|---|
| Status | Draft |
| Author | Sergiy Yevtushenko |
| Date | 2026-08-07 |
| Supersedes | — |
| Amends | RFC-0016 §2 (producer sites), REQ-5.1.8.3 (clarification only) |
| Related | RFC-0012 (Resource Provisioning), RFC-0013 (Deployment Provider), RFC-0015 (Cluster-Label Scoping); issues #570, #574, #578 |

## Summary

Bootstrap stops being a provisioner of the whole cluster and becomes a **seeder of
the core quorum**. It creates core nodes, hands them the topology spec, and gets
out of the way. Cores self-assemble via provider-native discovery instead of
having a peer list pushed to them over SSH, then provision workers and spot nodes
themselves from the same spec.

Three properties follow, and each is the reason for one part of the design:

1. **No inbound SSH is required on cloud nodes.** The SSH channel exists today
   solely to push a finalized `PEERS` list that is unknowable at create time.
   Discovery removes the need for the push, and with it the trampoline.
2. **Infrastructure credentials live only where infrastructure is mutated.**
   Cores hold cloud tokens — which they already require for auto-heal — and
   workers hold none. Workers are where user slice code runs, so this puts the
   credential on the *less* exposed tier.
3. **One source of truth for topology.** The desired shape of the cluster lives in
   cluster state, not in two producers that must agree.

## Motivation

### The trampoline

`BootstrapPhaseDeploy` SSHes into every cloud node to re-launch it with a
finalized `PEERS` value, because peers are not known until all nodes exist. That
single push is the only reason bootstrap needs inbound SSH on port 22, and it is
why the deploy path is already core-shaped in practice (`aether-role=core` at
`:372`; `NodeRole.CORE` hardcoded at `:396`, `:463`, `:610`).

Cloud-native alternatives to SSH exist — AWS SSM Run Command, GCP IAP/OS Login,
Azure Run Command — and all are better than inbound SSH: agent-initiated,
outbound-only, identity-authenticated, audited. They are rejected here (see
Alternatives) because **Hetzner has none of them**, and Hetzner is the only
provider Aether actually provisions on today. Discovery is provider-agnostic,
already implemented for all four providers, and works on Hetzner precisely
because it uses the HTTP API rather than an agent.

### The gap that makes it inert

The original draft of this table overstated readiness; corrected 2026-08-09 during stage-4
implementation, from the code rather than from memory:

| Part | Location | State (corrected) |
|---|---|---|
| `DiscoveryProvider` SPI | `environment-integration` | live |
| Hetzner/AWS/GCP/Azure implementations | `environment/*` | live |
| `HetznerDiscoveryProvider.discoverPeers()` | label lookup `aether-cluster=<name>` | live, **zero production consumers** until stage 4 |
| Node-side `registerSelf`/`deregisterSelf` | `AetherNode` | wired but **inert in production** — nothing populates `selfServerId`, so registration always fails `operationNotSupported`. NOT load-bearing for stage 4 |
| Node-side post-formation self-tag (`aether-node-id`) | `AetherNode.applyNodeIdTag`, IP-match, **no selfServerId needed** | live — merges via provider-side read-modify-write. Stage 4's C4 signal (`aether-formed=true`) rides THIS mechanism, not `registerSelf` |
| `[cloud.discovery] cluster_name` in composed node config | `BootstrapOverlayGenerator.cloudDiscoverySection` | **already emitted** for every cloud node |
| `[cluster] nodes` (expected core count) in composed node config | — | was missing — without it a cloud node fell back to `Environment.defaultNodes()` (5, DOCKER), the wrong expectation for any 3-core cluster |
| **Consume side: discovery → topology → formation** | — | **missing entirely** — the actual gap. No code ever called `discoverPeers()` |

So the real gap was twofold: the consume side did not exist, and the expected-count signal was
absent from the node config. The push was mandatory because of those two, not because of any
missing provider capability.

### Why bootstrap should not provision workers

Bootstrap provisions all three roles today
(`BootstrapPhaseProvision:254` — `roleOrder = List.of(CORE, WORKER, SPOT)`). Moving
worker and spot provisioning into the cluster gives:

- **No stale peer list.** Workers are created by a live core holding current
  membership, so a seeded-at-birth peer list can never go stale. This *removes*
  the failure mode rather than mitigating it.
- **One provisioning mechanism.** RFC-0016 §2 already notes three producer sites
  funnelling through `ProvisionRequest.resolve`. This reduces them.
- **Parallel provisioning for free.** Bulk creation already has to exist in the
  cluster for scale-out; bootstrap would otherwise need a second copy of it, or
  large topologies deploy slowly.
- **A smaller bootstrap.** One node type, one runtime, one deploy path.

## Design

### Boundaries

- **CLI / bootstrap** — resolves credentials, creates the **core** nodes, publishes
  the topology spec into cluster state, observes formation, exits. Never
  provisions workers or spot. Never requires inbound SSH for cloud sources.
- **Core nodes** — self-assemble via `DiscoveryProvider`; hold cloud credentials
  for every source they provision into; reconcile actual topology toward the
  published spec, creating and destroying worker/spot nodes.
- **Worker / spot nodes** — run workloads. No cloud credentials, no discovery, no
  inbound SSH. Peer set arrives at birth and is maintained by ordinary membership
  gossip thereafter.

### Contracts

**C1 — Typed topology in cluster state.** `ClusterTopologyManager.setDesiredSize(int)`
is a scalar and cannot express "3 cores in `hetzner-eu` + 5 `cpx32` workers in
`aws-us`". It is promoted to the per-source, per-role spec that
`ClusterBootstrapConfig` already models. Bootstrap publishes this at formation;
the cluster reconciles toward it. This is the prerequisite for everything else.

**C2 — Cluster label is a hard precondition.** Provisioning MUST fail when no
cluster name resolves, instead of stamping `aether-cluster=unknown`
(`HetznerComputeProvider.clusterNameOrDefault:386`). Under label-sweep teardown an
unattributable VM is a permanent paid orphan; a loud failure at create is the only
moment it is cheap to fix. The `AETHER_CLUSTER_NAME` fallback is retained for the
genuine pre-bootstrap window; only the terminal `"unknown"` fallback is removed.

**C3 — Teardown by scoped label sweep.** Cluster-provisioned nodes are not in
`bootstrap-state.json`, so destroy cannot delete purely by recorded id. Destroy
therefore: asks the cluster to scale to zero, then sweeps
`aether-cluster=<name>` — **never a bare selector** — reusing the
`PROTECTED_CLUSTERS` guard added in `14d1da8e3`, unioning `aether-node-id` orphans
*scoped to the cluster*, and printing a dry-run inventory before deleting. C2 is
what makes this sound: the sweep is only as reliable as label discipline at create.

**C4 — Readiness without inbound ports.** Bootstrap observes cluster formation via
provider-API self-tagging (nodes already self-tag at join) rather than polling each
node's management port. This removes the requirement that made a firewalled
management port fail bootstrap on healthy nodes (see REQ-5.1.8.3 note below).

**C5 — Credentials.** Cores hold credentials for every source they provision into.
There is no alternative that survives multi-source clusters: whoever provisions
needs the credentials. Mitigations, in order of cost:
  - **One cloud project per cluster** (Hetzner projects isolate API tokens) — free,
    no code, bounds a compromised core to its own cluster. Recommended default.
  - **Vault-backed short-lived credentials** via the existing `SecretsProvider`
    seam (`Aws`/`Gcp`/`Azure`/`File`/`Env`/`Composite`/`Caching` exist; Vault does
    not yet). Changes the secret from *static at rest* to *short-lived, revocable,
    audited*. Note the limit: authenticating **to** the vault needs instance
    identity, which AWS/GCP/Azure have and **Hetzner does not** — so on Hetzner a
    static vault token remains, and the win is scoping and revocability, not
    secret elimination.

### REQ-5.1.8.3 — clarification, not amendment

Aether never opens the cluster or management ports on its own initiative. An
explicit `allow_ingress` rule naming one of them is an operator decision, applied
like any other rule. Under C4 the bootstrap readiness gate no longer requires
either port to be externally reachable, so the requirement stands as written.

### Examples

```toml
# Operator writes the whole topology once. Bootstrap seeds cores from it and
# publishes it; the cluster provisions the rest.
[source.hetzner-eu.core]
count         = 3
instance_type = "cpx32"

[source.hetzner-eu.worker]      # created by the cores, not by bootstrap
count         = 10
instance_type = "cpx22"
```

## Alternatives Considered

- **Per-provider agent exec (SSM / IAP / Run Command).** Genuinely better than
  inbound SSH, and the industry direction. Rejected for v1: Hetzner — the only
  provider actually provisioned on today — offers no equivalent, so it would add
  three provider-specific transports that do not help the one provider that
  matters, while leaving the trampoline in place there.
- **Keep pushing peers, but over a better channel.** Treats the transport as the
  problem. The problem is the push; discovery removes it entirely.
- **Workers seeded at birth by bootstrap** (the intermediate design). Works, but
  leaves a stale-seed failure mode: a worker down long enough for auto-heal to
  replace every seeded core cannot rejoin. Cluster-owned provisioning eliminates
  it structurally.
- **Cluster-state-owned resource tracking for teardown** instead of a label sweep.
  Stronger in principle, but the case that matters most — a cluster too broken to
  answer — is exactly when cluster-owned state is unavailable. Deferred; the
  simple approach is taken first per owner ruling 2026-08-06.

## Migration

Staged, each stage independently landable and verifiable:

1. **Guardrails** (no architectural dependency): C2 label precondition; firewall
   preset fixes; pre-flight error for management port open to `0.0.0.0/0` with
   `security_mode = "NONE"`.
2. **C1 topology model** promotion + publication at formation.
3. **#570** — `setDesiredSize` unguarded read-modify-write, which this design leans
   on much harder.
4. **Discovery-based core assembly**: enable discovery in the generated node
   config; drop the SSH re-launch for cloud sources. SSH remains the mechanism for
   `type = "ssh"` sources, where it is the only option.
   **Implemented 2026-08-09.** Notes from implementation:
   - The peers-resolution chain in `Main` gained a discovery arm AFTER the explicit arms
     (`--peers=`, `CLUSTER_PEERS` — so operator lists and CTM replacements are byte-identical)
     and BEFORE config-generated synthesis. Poll until `cluster().nodes()` cores visible; at the
     deadline a majority proceeds with a warning, below majority fails loudly.
   - Discovered peers use the LOCAL cluster port, never the `aether-port` label — that label is
     applied only after join and cannot exist pre-formation; cores share one port by composition.
   - Workers/spot get core seeds **baked at create**: cores are provisioned first, so their
     addresses are known when worker user-data renders. Core-only seeds ("peer set arrives at
     birth"); the removed re-launch used to push the full node list.
   - C4 readiness: bootstrap polls the provider API for `aether-formed=true` — the label the node
     merges onto itself after formation via the IP-match self-tag (NOT `registerSelf`, which is
     inert; see the corrected gap table).
   - **Gate:** engages only when EXACTLY ONE source carries cores and it is CLOUD. Cores spread
     across providers cannot find each other by label — a structural limit of provider-native
     discovery. Multi-core-source clusters keep the legacy SSH push.
5. **Cores provision workers/spot** from the published spec.
   **Implemented 2026-08-09.** Notes from implementation:
   - A separate, deliberately simpler reconcile pass on the CTM (`reconcileWorkerTopology`), NOT
     woven into the hardened core `LeaderReconciler`: a worker deficit is never quorum-ambiguous
     and carries none of the cold-start/debounce machinery cores need. Leader-gated by the same
     `active` guard as the membership actuator path; serialized against overlapping passes.
   - ACTUAL is the provider's label inventory, not SWIM membership — for create/destroy decisions
     "the VM exists" is the honest ground truth; a created-but-not-yet-joined worker must not be
     double-provisioned.
   - Triggers: every committed `ClusterConfigKey` change (ONE trigger source for scale/apply/
     restore, same fan-out as the core reconciler) + leader activation (a scale committed under a
     dead leader converges on handoff). Deferred provisions (open circuit, no visible peers)
     surface on the next commit — matching `provisionReplacement`'s deferral semantics.
   - Found and fixed while wiring: `provisionReplacement`'s spec hardcoded role `"core"` and
     instance type `"default"` — survivable only while nothing but core replacements used it. Now
     role-aware (role's own instance type from the role sub-table, fallback `"default"`).
   - Surplus terminates NEWEST FIRST: reconciler-minted `-r<clock36>` ids sort after bootstrap
     `-<index>` ids, so cluster-provisioned workers are reaped before bootstrap-provisioned ones.
   - Scale-to-zero accepted end-to-end for worker/spot (CLI + REST) — drain-all is real, and C3's
     teardown scales to zero before sweeping.
6. **C3 teardown** rework.
7. **Delete worker/spot provisioning from bootstrap** — the payoff, last.

## Open Questions

- Does `--wait` mean "core quorum formed" or "converged to full desired topology"?
  The latter is more useful and more expensive; quota exhaustion for workers now
  surfaces after bootstrap returns either way, so a *desired vs actual, and why
  not* surface is required regardless.
- Should worker provisioning have per-source affinity (cores in source X provision
  workers in source X)? It would narrow credential spread, but conflicts with
  cores being quorum-shaped rather than per-source.
