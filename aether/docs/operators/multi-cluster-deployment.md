<!-- SPDX-License-Identifier: BUSL-1.1 -->
<!-- Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko -->
<!-- Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0. -->

# Multi-Cluster Deployment

Running more than one Aether cluster on shared infrastructure (one Docker host, one HCloud project, one Kubernetes cluster) requires that operations tooling can distinguish the clusters at the deployment layer. Aether uses **two orthogonal labels** for this — `aether.cluster` and `aether.node-id`.

## The labels

| Label | Scope | Owner | Example |
|---|---|---|---|
| `aether.cluster` | deployment — names the cluster this container/instance belongs to | deployment infra (compose / k8s / `aether` CLI) | `us-prod`, `staging`, `a` |
| `aether.node-id` | within cluster — unique among that cluster's members | runtime / CTM / compose | `node-1`, `aether-core-node-3Do37u…` |

`aether.cluster` is the same value as the cluster's `name` in its bootstrap config TOML (validated against `^[a-z][a-z0-9-]{0,62}$` by `ClusterIdentity`). It propagates from there into:

- `ProvisionContext.clusterName()` when CTM provisions a replacement
- `DockerComputeProvider.buildRunCommand` — `--label aether.cluster=<name>`
- `HetznerComputeProvider.labelsFor` — `aether-cluster=<name>` (HCloud kebab-case convention)
- AWS / GCP / Azure compute providers — same `aether-cluster` tag

Cross-cluster operations key on `(aether.cluster, aether.node-id)`. Intra-cluster operations need only `aether.node-id`.

## Why NodeId is not cluster-scoped

Aether **does not** prefix `NodeId` with the cluster name. NodeId is the node's identity *within its cluster* — Rabia consensus messages, KV-Store keys, the DHT ring, MembershipFsm transitions all use it as opaque. Prefixing every NodeId with `<cluster>/` would:

- Bloat KV keys (CTM-provisioned NodeIds are already 27-char KSUIDs)
- Leak deployment topology into consensus payloads that have no business knowing it
- Conflate identity with membership context

Cross-cluster operations are a federation / observability concern handled at the deployment layer — separate from cluster-internal identity.

## Setting the labels

### Docker Compose

Add a `labels:` block to each node service:

```yaml
services:
  aether-us-prod-node-1:
    image: aether-node:latest
    labels:
      aether.cluster: "us-prod"
      aether.node-id: "node-1"
    # ... rest of service config
```

A correct-by-construction template is available via:

```sh
aether cluster scaffold --name us-prod --template docker-compose --nodes 5 > compose.yml
```

### Kubernetes

Set the labels in the Pod template metadata:

```yaml
spec:
  template:
    metadata:
      labels:
        aether.cluster: us-prod
        aether.node-id: node-1
```

### Hetzner / cloud providers

The `aether` CLI bootstrap path sets the label automatically — `aether cluster bootstrap cluster.toml --cluster us-prod` flows the name into `ProvisionContext` which the compute provider encodes as a server label (`aether-cluster=us-prod`, kebab-case per HCloud / AWS / GCP / Azure conventions).

CTM-provisioned replacements inherit the label automatically.

## Operations playbooks

### Enumerate all containers in a cluster

```sh
docker ps --filter label=aether.cluster=us-prod
hcloud server list -l aether-cluster=us-prod
```

### Find a specific node

```sh
docker ps --filter label=aether.cluster=us-prod --filter label=aether.node-id=node-3
```

### Cross-cluster reaping

`tools/cloud-reaper.sh` scopes by `aether-cluster` to avoid touching the wrong cluster's resources. Always pass `--cluster <name>`:

```sh
./tools/cloud-reaper.sh --cluster us-prod --provider hetzner
```

## See also

- `docs/specs/cluster-label-scoping-spec.md` — the design spec
- `aether/docs/operator/deployment-recovery.md` — why `restartPolicy: Always` competes with CTM auto-heal and must be disabled
- `aether/docs/reference/cli.md` — `aether cluster scaffold` reference
