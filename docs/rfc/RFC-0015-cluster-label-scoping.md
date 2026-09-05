<!-- SPDX-License-Identifier: BUSL-1.1 -->
<!-- Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko -->
<!-- Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0. -->

# RFC-0015 — Cluster-Label Scoping

| Field | Value |
|---|---|
| Status | Accepted |
| Author | Sergiy Yevtushenko |
| Date | 2026-05-17 |
| Supersedes | — |
| Related | RFC-0012 (Resource Provisioning), RFC-0013 (Deployment Provider) |

## Summary

Deployment infrastructure (Docker, Kubernetes, cloud providers) sets two orthogonal labels on every Aether container/instance: `aether.cluster=<name>` (cluster membership) and `aether.node-id=<id>` (per-cluster node identity). The two together uniquely identify a node across the entire deployment fleet without leaking cluster identity into Aether's cluster-internal data structures.

## Motivation

When multiple Aether clusters run on shared infrastructure (one Docker host, one HCloud project, one K8s cluster), operations tooling needs to enumerate or target one cluster's containers/instances without affecting another's. The cluster name is the natural discriminator — it already exists as `ClusterIdentity.name` in the bootstrap config and is validated against `^[a-z][a-z0-9-]{0,62}$` for safety as a label across all systems.

Before this RFC, two failure modes were observed:

- **Compose label collisions.** Both `aether-a-node-2` and `aether-b-node-2` carried `aether.node-id=node-2`. A bare `docker ps --filter label=aether.node-id=node-2` returned whichever Docker enumerated first, causing integration tests on cluster A to accidentally kill cluster B containers.

- **CTM reaper miss.** Without an `aether-cluster` Hetzner label, `tools/cloud-reaper.sh --cluster X` would either reap across clusters (if it relied on naming convention only) or miss CTM-provisioned VMs entirely.

## Non-goal

**NodeId is not cluster-scoped.** A NodeId names a node *within its cluster*. Adding a cluster prefix would:

1. Bloat KV-Store keys, consensus payloads, log lines — CTM-provisioned NodeIds are already 27-char KSUIDs (e.g., `aether-core-node-3Do37u9J6rVlLLQ9kQSIs1yciLa`); prefixing those compounds the bloat.
2. Leak deployment topology into Rabia consensus, MembershipFsm, DHT ring, KV keys — components that have no business knowing what cluster they're in. A cluster is, by definition, a closed membership group; cross-cluster operations are federation/observability concerns handled at a different layer.
3. Conflate identity with membership context, making refactor of cluster naming structurally harder.

## Design

Two orthogonal labels, each owned by a different layer:

| Label | Scope | Owned by | Provider-native convention |
|---|---|---|---|
| `aether.cluster` | deployment — names the cluster this container/instance belongs to | deployment infra (compose / k8s / `aether` CLI / ComputeProviders) | `aether.cluster` (Docker dotted), `aether-cluster` (HCloud/AWS/GCP/Azure kebab) |
| `aether.node-id` | within cluster — unique among that cluster's members | runtime / CTM / compose | `aether.node-id` (Docker dotted), `aether-node-id` (HCloud kebab) |

Both labels propagate from the same canonical source — `ClusterIdentity.name`, validated at bootstrap time, persisted in KV-Store as `ClusterConfigValue.clusterName`, and flowed into `ProvisionContext.clusterName()` for CTM-provisioned containers.

### Provider encoding

Each `ComputeProvider` natively encodes both labels in its own convention:

- `DockerComputeProvider.buildRunCommand`: `--label aether.cluster=<name>` and `--label aether.node-id=<id>`
- `HetznerComputeProvider.labelsFor`: `aether-cluster=<name>` and `aether-node-id=<id>` (HCloud API requires kebab-case keys)
- AWS / GCP / Azure: same `aether-cluster` and `aether-node-id` tags

The dotted↔hyphenated translation is encoded inside each provider's `listInstances` translation layer (e.g., `HetznerComputeProvider.translateKeys`) so upper layers (`NodeLifecycleManager.NODE_ID_TAG = "aether.node-id"`) remain provider-agnostic.

### Compose fixtures

Integration test compose YAMLs (`docker-compose-a.yml`, `docker-compose-b.yml`) set both labels on every node service:

```yaml
labels:
  aether.cluster: "a"
  aether.node-id: "node-1"
```

Test helpers filter on the cluster label with the docker network filter as defence-in-depth:

```bash
docker ps --filter label=aether.cluster=${CLUSTER_ID} --filter network=aether-${CLUSTER_ID}-network
```

### Scaffolding

`aether cluster scaffold --name <name> --template docker-compose --nodes N` generates a ready-to-use compose file with all labels correctly set. The label structure is correct-by-construction; operators can't forget to set it.

## Alternatives considered

### A1. Cluster-prefixed NodeIds (`<cluster>/<nodeId>`)

Rejected per Non-goal. The bloat, leakage, and architectural conflation costs are not paid back by any operational benefit — `(cluster, node-id)` two-dimensional keys give exactly the same federation/observability discriminator without polluting internal data structures.

### A2. Docker-network-name as cluster discriminator

Implemented as a stop-gap before this RFC. Works on Docker (`--filter network=aether-<id>-network`) but doesn't generalise — Kubernetes uses namespaces, Hetzner has no docker-network concept, bare-metal Docker can share host networks. The label-based approach is portable; the network filter remains as defence-in-depth in the test helper.

### A3. Hostname-derived cluster identity

Container hostnames like `aether-a-node-1` carry the cluster name structurally. Parsing it works but is fragile — the convention isn't enforced anywhere, and CTM-provisioned KSUID-suffixed containers like `aether-core-node-3Do37u…` don't follow the same shape. Explicit labels are robust against naming convention drift.

## Implementation status

- `DockerComputeProvider` / `HetznerComputeProvider` / `AwsComputeProvider` / `GcpComputeProvider` / `AzureComputeProvider`: emit `aether-cluster` label from `ProvisionContext.clusterName()` with `AETHER_CLUSTER_NAME` env fallback when context-side is empty — landed with this RFC
- `docker-compose-{a,b}.yml`: `aether.cluster: a/b` labels on all 10 node services + `AETHER_CLUSTER_NAME: "a"/"b"` in the shared env so CTM replacements carry matching labels — landed with this RFC
- `aether/tests/integration/lib/cluster.sh`: `_docker_container_by_node_id_label` filters on both label and network — landed with this RFC
- `aether cluster scaffold --template docker-compose`: generates a correct-by-construction compose template — landed with this RFC
- `ContainerLabelInspector` + `Main.verifyClusterLabelConsistency`: first-boot consistency check via `/var/run/docker.sock` Unix-socket HTTP (JDK-native `UnixDomainSocketAddress`); fail-closed on `aether.cluster` label vs `AETHER_CLUSTER_NAME` env mismatch — landed with this RFC
- `aether/docs/operators/multi-cluster-deployment.md`: operator playbook — landed with this RFC

### Future work (tracked separately)

- **Runtime `[cluster] name` in TOML** — once a `name` field is added to runtime `ClusterConfig` (currently bootstrap-only), the consistency check can read it from local TOML as a second source alongside `AETHER_CLUSTER_NAME` env. Requires touching 39 existing `ClusterConfig` call sites; deferred to a separate refactor.

## See also

- `aether/docs/specs/cluster-label-scoping-spec.md` — full design spec with line-level affected files
- `aether/docs/operators/multi-cluster-deployment.md` — operator playbook
- `aether/aether-config/src/main/java/org/pragmatica/aether/config/cluster/ClusterIdentity.java` — name validation
