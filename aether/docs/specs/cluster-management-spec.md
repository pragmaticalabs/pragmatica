# Declarative Cluster Management Specification

## Version: 0.1
## Status: Exploratory Draft
## Target Release: TBD
## Last Updated: 2026-03-25
## Ticket: #74

---

## 1. Overview

### 1.1 Motivation

Aether manages slice lifecycle declaratively (blueprint.toml → CDM converges). Cluster lifecycle (node provisioning, scaling, upgrades) is currently imperative — manual API calls, scattered config, no single source of truth. This spec introduces declarative cluster management: describe desired cluster state in `aether-cluster.toml`, let Aether converge.

### 1.2 Core Concept

Two-section config separating logical structure from physical deployment:

```
[deployment]  →  HOW to provision (cloud, instance types, zones, ports, TLS)
[cluster]     →  WHAT to provision (node count, roles, distribution, auto-heal)
```

Same logical cluster can deploy to any supported environment without changing the `[cluster]` section.

### 1.3 Scope

- **Phase 1:** Cloud deployment types (Hetzner first), bootstrap, scale, status, export
- **Phase 2:** Kubernetes deployment type, on-premises, config inheritance
- **Phase 3:** Forge/Ember as embedded deployment type (unifies forge.toml)

---

## 2. Configuration Format

### 2.1 Deployment Section

Describes the physical environment and how to map logical concepts to provider-specific resources.

```toml
[deployment]
type = "hetzner"                    # hetzner | aws | gcp | azure | kubernetes | on-premises | embedded

[deployment.instances]
core = "cx21"                       # Provider-specific instance type for core nodes
worker = "cx11"                     # Provider-specific instance type for workers

[deployment.runtime]
type = "container"                  # container | jvm
image = "ghcr.io/pragmaticalabs/aether-node:0.24.1"

[deployment.zones]
zone-a = "fsn1-dc14"               # Logical → physical zone mapping
zone-b = "fsn1-dc15"

[deployment.ports]
cluster = 6000                      # Logical → physical port mapping
management = 5150
app-http = 8070
swim = 6100

[deployment.tls]
auto_generate = true
cluster_secret = "${secrets:cluster-secret}"
```

### 2.2 Cluster Section

Describes the logical cluster structure, independent of deployment target.

```toml
[cluster]
name = "production"
version = "0.24.1"

[cluster.core]
count = 5
min = 3                            # Auto-scale range (future)
max = 9

[cluster.workers]
count = 0                          # Future: worker pool support

[cluster.distribution]
strategy = "balanced"              # balanced | manual
zones = ["zone-a", "zone-b"]

[cluster.auto_heal]
enabled = true
retry_interval = "60s"

[cluster.upgrade]
strategy = "rolling"               # rolling | blue-green
```

### 2.3 Provider-Specific Examples

TODO: Full examples for each deployment type (AWS, GCP, Azure, Kubernetes, on-premises, embedded).

---

## 3. CLI Commands

### 3.1 Bootstrap

```bash
aether cluster bootstrap aether-cluster.toml
```

Creates a cluster from scratch:
1. Read config, authenticate with cloud provider
2. Provision instances per `[deployment]`
3. Distribute across zones per `[cluster.distribution]`
4. Install/start Aether on each node
5. Wait for quorum
6. Store config in consensus KV-Store
7. Print cluster status + connection info

### 3.2 Ongoing Management

```bash
aether cluster apply aether-cluster.toml   # Diff and converge
aether cluster apply --dry-run              # Show planned changes
aether cluster status                       # Health, nodes, leader, slices
aether cluster scale --core 7               # Quick scale
aether cluster upgrade --version 0.25.0     # Rolling upgrade
aether cluster drain <node-id>              # Graceful drain
aether cluster export > current.toml        # Export running config
aether cluster destroy                      # Tear down
```

### 3.3 Multi-Cluster

```bash
aether cluster list                         # Show known clusters
aether cluster use <name>                   # Switch context
aether --endpoint=<url> cluster status      # Ad-hoc access
```

---

## 4. Cluster Registry

Local file `~/.aether/clusters.toml` for multi-cluster management:

```toml
[current]
context = "production"

[clusters.production]
endpoint = "https://aether.prod.example.com:5150"
api_key_env = "AETHER_PROD_API_KEY"

[clusters.staging]
endpoint = "https://aether.staging.example.com:5150"
api_key_env = "AETHER_STAGING_API_KEY"

[clusters.local]
endpoint = "http://localhost:5150"
```

Bootstrap auto-registers the new cluster. `--endpoint` flag overrides for ad-hoc access.

---

## 5. Config Lifecycle

### 5.1 Storage

After bootstrap, the canonical config lives in consensus KV-Store. All nodes share the same view of desired cluster state.

### 5.2 Apply (Diff + Converge)

`aether cluster apply` compares new TOML against stored config:
- Changed `core.count` → CTM scales up/down
- Changed `version` → triggers rolling upgrade
- Changed `deployment.zones` → future: rebalancing
- Unchanged sections → no action

### 5.3 Export

`aether cluster export` reconstructs TOML from KV-Store, enabling:
- Backup of cluster config
- Diff between desired and running state
- Migration between environments

### 5.4 Config Inheritance

Base config + environment overlays:

```bash
aether cluster apply --base cluster-base.toml --overlay cluster-prod.toml
```

Overlay merges on top of base. Enables shared defaults with per-environment overrides.

---

## 6. Deployment Type Adapters

### 6.1 Cloud (Hetzner, AWS, GCP, Azure)

Provisions VMs via ComputeProvider SPI. Uses existing cloud integration modules.

### 6.2 Kubernetes

Creates StatefulSets/Deployments via K8s API. `[deployment.instances]` becomes resource requests. `[deployment.zones]` becomes topology spread constraints.

### 6.3 On-Premises

Pre-provisioned nodes. Bootstrap discovers via static inventory or tags:

```toml
[deployment.nodes]
core = ["10.0.1.1", "10.0.1.2", "10.0.1.3"]
```

### 6.4 Embedded (Forge)

In-process nodes. Same config format, `type = "embedded"`. Unifies `forge.toml` with cluster config.

---

## 7. Authentication

Two auth contexts:
- **Cloud credentials** — for provisioning (env vars: `HETZNER_API_TOKEN`, `AWS_ACCESS_KEY_ID`, etc.)
- **Cluster API key** — for management (env var: `AETHER_API_KEY`)

Bootstrap creates the cluster API key and outputs it. Subsequent commands use the API key.

---

## 8. Monitoring Integration

TODO: Auto-setup of Prometheus scraping, Grafana dashboards, alerting rules from cluster config.

---

## 9. Open Questions

| # | Question | Notes |
|---|----------|-------|
| 1 | Should upgrade strategy be in config or per-invocation? | Config sets default, CLI flag overrides |
| 2 | How to handle config conflicts (two users apply simultaneously)? | Consensus handles serialization; last write wins with optimistic concurrency |
| 3 | Should Forge embed this as `type = "embedded"` or stay separate? | Unification is elegant but adds complexity to Phase 1 |
| 4 | Multi-cluster registry — local file vs shared service? | Local file for Phase 1; shared service (if needed) Phase 2+ |
