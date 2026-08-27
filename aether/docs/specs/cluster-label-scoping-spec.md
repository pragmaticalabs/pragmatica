<!-- SPDX-License-Identifier: BUSL-1.1 -->
<!-- Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko -->
<!-- Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0. -->

---
title: Cluster-Label Scoping — Architectural Spec
status: proposed
target: RC1 (items 1-4) + RC2 (items 5-7)
related: docs/rfc/RFC-0003-http-layer.md, aether/docs/specs/membership-architecture-v2-spec.md
---

# Cluster-Label Scoping

## Problem

`aether.node-id` is the node's identity *within its cluster*. The value space is local: compose fixtures use `node-1`..`node-5`; CTM-provisioned replacements use KSUID strings. When multiple clusters share infrastructure (Docker host, HCloud project, K8s cluster), the per-cluster `node-id` namespaces collide, and infrastructure-layer tooling can't tell them apart.

Concrete failure mode observed in this session (`session-handover-2026-05-17.md`):
- Integration test runs cluster A + cluster B in parallel on TARGET_HOST.
- 15-delegation suite runs on cluster A, calls `kill_node "node-2"`.
- Test helper `_docker_container_by_node_id_label` runs `docker ps --filter 'label=aether.node-id=node-2'`.
- BOTH `aether-a-node-2` AND `aether-b-node-2` match. `head -1` picks whichever Docker enumerates first.
- 15-delegation accidentally kills cluster B's node-2; cluster B subsequent tests cascade.

## Non-goal

**Do NOT cluster-scope NodeId itself.** NodeId is the cluster-internal identifier — Rabia consensus, MembershipFsm, DHT ring, KV keys all use it as opaque. Prefixing every NodeId with `<cluster>/` bloats every consensus message and storage key, leaks deployment topology into runtime code, and conflates *identity* with *membership context*.

## Design

Add an **orthogonal `aether.cluster` label** alongside `aether.node-id`. Tools that span clusters key on `(cluster, node-id)`; tools that operate within one cluster keep using `node-id` alone.

| Label | Scope | Owned by |
|---|---|---|
| `aether.node-id` | within cluster — unique among that cluster's members | runtime / CTM / compose |
| `aether.cluster` | deployment — names the cluster this container/instance belongs to | deployment infra (compose / k8s / ComputeProvider / bootstrap CLI) |

Both labels propagate from the canonical source of cluster identity: `aether.toml`'s `cluster.name` (validated by `ClusterIdentity` per RC0, RFC-0001).

---

## RC1 items (small — ship in next session)

### 1. `DockerComputeProvider.buildRunCommand` adds `aether.cluster` label

File: `aether/environment/docker/src/main/java/org/pragmatica/aether/environment/docker/DockerComputeProvider.java`

Around the existing `--label aether.node-id=${nodeId}` line, add:
```java
"--label", "aether.cluster=" + spec.context().clusterName(),
"--label", "aether.node-id=" + nodeId,
```
The `ProvisionContext.clusterName()` is already plumbed (Wave 3a). New unit test in `DockerComputeProviderTest`: `provision_setsClusterLabel`.

### 2. `HetznerComputeProvider.labelsFor` adds `aether-cluster` label

File: `aether/environment/hetzner/src/main/java/org/pragmatica/aether/environment/hetzner/HetznerComputeProvider.java`

In the existing `labelsFor(ProvisionContext)` method, add:
```java
labels.put("aether-cluster", context.clusterName());
```
Update `translateKeys` to translate dotted `aether.cluster` (upper-layer canonical) ↔ hyphenated `aether-cluster` (HCloud native), same pattern as `aether.node-id` ↔ `aether-node-id` (Wave 4).

New unit test: `provision_setsClusterLabel`.

### 3. `docker-compose-{a,b}.yml` add cluster label to all 5 services per file

File: `aether/tests/integration/docker-compose-a.yml` (and `-b.yml`)

For each of the 5 compose-fixed node services, add to the existing `labels:` block:
```yaml
labels:
  aether.cluster: "a"     # or "b" in compose-b.yml
  aether.node-id: "node-1"
```

10 line additions total. CTM-provisioned containers in cluster A/B will use `aether.cluster=default` (from aether.toml on those nodes); this is a deliberate divergence from compose-fixture clusters (which use short `a`/`b` to match `CLUSTER_ID`). If we want them to align, we'd need to set `cluster.name=a` in the cluster A nodes' aether.toml — out of scope for the label-scoping work; track separately as cluster-naming-consistency improvement.

### 4. Test helper switches filter from `network=` to `aether.cluster=`

File: `aether/tests/integration/lib/cluster.sh`

Current (just shipped, network-scoped):
```bash
_docker_container_by_node_id_label() {
    local node_id="$1"
    local network_filter=""
    if [ -n "${CLUSTER_ID:-}" ]; then
        network_filter="--filter network=aether-${CLUSTER_ID}-network"
    fi
    remote_exec "docker ps --filter 'label=aether.node-id=${node_id}' ${network_filter} --format '{{.Names}}' | head -1"
}
```

Proposed (label-scoped, more portable):
```bash
_docker_container_by_node_id_label() {
    local node_id="$1"
    local cluster_filter=""
    if [ -n "${CLUSTER_ID:-}" ]; then
        cluster_filter="--filter label=aether.cluster=${CLUSTER_ID}"
    fi
    remote_exec "docker ps --filter 'label=aether.node-id=${node_id}' ${cluster_filter} --format '{{.Names}}' | head -1"
}
```

Why portable: works for k8s pods (label = annotation/label), bare-metal docker (label only), Docker Swarm. Doesn't depend on docker-network-name convention.

---

## RC2 items (operational polish)

### 5. First-boot cluster-label consistency check

File: `aether/node/src/main/java/org/pragmatica/aether/node/Main.java` (or `AetherNode.start()`)

On startup, if running under Docker (detect via `/.dockerenv` or `DOCKER_CONTAINER=true` env), read this container's own `aether.cluster` label via Docker API (`/var/run/docker.sock`) and compare against `aether.toml`'s `cluster.name`. On mismatch:
```
[FATAL] aether.toml says cluster.name='prod-us' but this container's
aether.cluster label is 'prod-eu'. Deployment infrastructure label is
inconsistent with cluster identity config. Aborting startup to prevent
the node from joining the wrong cluster.
```

Why it matters: today, an operator editing `aether.toml` in-place without updating the compose/k8s label causes the node to silently join the wrong cluster. With the check, the error is caught before the node sends its first SWIM ANNOUNCE.

Implementation note: if Docker socket isn't mounted (typical for production), skip the check with a debug log; don't fail-closed when we can't read labels (security risk to require socket mount).

### 6. Bootstrap CLI scaffolding subcommand

File: `aether/cli/src/main/java/org/pragmatica/aether/cli/cluster/ClusterScaffoldCommand.java`

New subcommand:
```
aether cluster scaffold --name us-prod --template docker-compose --nodes 5 > compose.yml
```

The template:
- Generates the right `aether.cluster`/`aether.node-id` labels per node.
- Sets `AETHER_CLUSTER_NAME=<name>` env so CTM-provisioned replacements label-match too.
- Includes the bootstrap-time peer list correctly.
- Is correct-by-construction — operators don't have to remember to set the label.

Implementation: `DockerComposeTemplate` (plain `StringBuilder`, no template engine — 6 unit tests cover label coverage, peer list, port bases, restart-no contract).

### 7. Documentation + RFC update

Files:
- `docs/rfc/RFC-NNNN-cluster-label-scoping.md` — new RFC explaining the orthogonal-label design + non-goal of cluster-scoping NodeId.
- `aether/docs/operators/multi-cluster-deployment.md` — operator guide for running multiple clusters on shared infrastructure.
- `aether/docs/reference/cli.md` — document the `cluster scaffold` subcommand.

---

## Affected files

### RC1 batch:
- `aether/environment/docker/src/main/java/org/pragmatica/aether/environment/docker/DockerComputeProvider.java` (+2 lines)
- `aether/environment/hetzner/src/main/java/org/pragmatica/aether/environment/hetzner/HetznerComputeProvider.java` (+5 lines incl. translateKeys)
- `aether/environment/docker/src/test/java/org/pragmatica/aether/environment/docker/DockerComputeProviderTest.java` (+1 test)
- `aether/environment/hetzner/src/test/java/org/pragmatica/aether/environment/hetzner/HetznerComputeProviderTest.java` (+1 test)
- `aether/tests/integration/docker-compose-a.yml` (+5 label lines)
- `aether/tests/integration/docker-compose-b.yml` (+5 label lines)
- `aether/tests/integration/lib/cluster.sh` (~3 line swap in `_docker_container_by_node_id_label`)

Net: ~25 lines, low risk, contained to deployment-infra layer + 2 unit tests.

### RC2 batch:
- `aether/node/src/main/java/org/pragmatica/aether/node/Main.java` or `AetherNode.java` (+30 lines for the check)
- `aether/cli/src/main/java/org/pragmatica/aether/cli/AetherCli.java` (+40 lines for subcommand)
- 3× template files in `aether/cli/src/main/resources/templates/` (~100 lines total)
- 3× docs (~150 lines total)

---

## Verification

### RC1:
1. `mvn -pl aether/environment/docker,aether/environment/hetzner test` — confirm new label-emission unit tests pass.
2. `mvn -pl aether/node package -am -DskipTests` — rebuild JAR.
3. Run integration suites: `./run-tests.sh --env remote --skip-build`.
4. Expected delta vs current-session run:
   - 15-delegation: stays at 2p/0f (network-filter and cluster-label-filter both work; the label-filter version is just more portable).
   - No regressions in other suites.
5. Manual smoke: `docker ps --filter label=aether.cluster=a --filter label=aether.node-id=node-2` returns exactly one container.

### RC2:
1. Unit test for the first-boot consistency check.
2. Manual: edit `aether.toml` to use a wrong cluster name; restart; observe fail-closed startup with clear error.
3. Manual: run `aether cluster scaffold --name testc --template docker-compose` and verify the generated compose file deploys a valid cluster.

---

## Risks

- **CTM-provisioned vs compose label divergence**: CTM containers use `aether.cluster=default` (from aether.toml `cluster.name`), compose fixtures use `aether.cluster=a/b` (test convention). The integration test infra works with either, but cluster identity should be CONSISTENT across compose and CTM-provisioned containers in the same cluster. Mitigation: set `cluster.name="a"` / `"b"` in cluster A / cluster B compose env so CTM and compose use the same value. Out of scope for THIS spec; track as `cluster-naming-consistency-improvement.md`.

- **Hetzner label kebab-case translation**: HCloud API requires lowercase, alphanumeric + hyphen. `cluster.name` is regex-validated by `ClusterIdentity.InvalidName` (set 2026-04-XX) to `^[a-z][a-z0-9-]{0,62}$`. So cluster.name is already valid Hetzner label format. Good — no extra validation needed.

- **Bootstrap CLI consistency check**: reading own docker labels requires `/var/run/docker.sock` mounted. Production deployments typically DON'T mount this for security. Mitigation: silently skip the check when socket isn't accessible. Future: support reading labels via Kubernetes downward API for K8s deployments.

---

## Out of scope

- Cluster-scoping NodeId itself (explicitly rejected — see Non-goal).
- Federation / multi-cluster control plane (orthogonal RC2+ work).
- Cross-cluster slice migration (separate spec).
- Service mesh integration (orthogonal).

---

## Open questions

1. **Should `aether.cluster` label match `cluster.name` in `aether.toml` exactly, or is a normalised form acceptable?** Recommend EXACT match (no normalisation) since `cluster.name` is already validated to a safe character set. Simplifies the consistency check.

2. **Should the label be optional or required?** For RC1: optional (backwards-compatible — if absent, falls back to current behavior). For RC2: enforced on Docker/Hetzner providers (CTM always sets it); legacy compose fixtures may need migration. Provide a deprecation period of one minor release.

3. **Should test helpers fail-closed if `CLUSTER_ID` is unset?** Current behavior: filter is omitted (matches across clusters). RC2 candidate: warn loudly and require explicit opt-in via env var to disable scoping.

---

**End of spec.** Items 1-4 are RC1 quick wins (~25 LOC, ~half a day). Items 5-7 are RC2 operational polish (~200 LOC, ~2 days). User has approved both batches for next-session execution.
