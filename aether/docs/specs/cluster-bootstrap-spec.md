# Aether Cluster Bootstrapping and Configuration Specification

| Field       | Value                                    |
|-------------|------------------------------------------|
| Status      | Draft -- ready for implementation        |
| Date        | 2026-04-11                               |
| Module      | `aether/cli`, `aether/aether-config`     |
| Related     | `aether/cli/src/.../cluster/`            |

---

## Table of Contents

1. [Overview](#1-overview)
2. [Design Model](#2-design-model)
3. [Configuration Schema](#3-configuration-schema)
4. [Config Composability](#4-config-composability)
5. [Named Profiles](#5-named-profiles)
6. [Node Groups](#6-node-groups)
7. [Infrastructure](#7-infrastructure)
8. [Operations](#8-operations)
9. [Bootstrap Flow](#9-bootstrap-flow)
10. [Apply Flow (Desired-State Diffing)](#10-apply-flow-desired-state-diffing)
11. [CLI Commands](#11-cli-commands)
12. [Provider SPI](#12-provider-spi)
13. [Pre-flight Validation](#13-pre-flight-validation)
14. [Idempotent Bootstrap](#14-idempotent-bootstrap)
15. [Example Configurations](#15-example-configurations)
16. [Migration from Current Model](#16-migration-from-current-model)
17. [Non-Goals](#17-non-goals)
18. [Known Limitations](#18-known-limitations)
19. [References](#19-references)

---

## 1. Overview

### 1.1 Purpose

This specification defines the cluster bootstrapping and configuration system for Aether, a Unified Application Runtime. The goal is a one-liner bootstrap experience:

```
aether cluster bootstrap --config cluster.toml
```

All information required to provision, configure, and form a cluster lives in a single TOML configuration file. The config file acts as an **orchestrator** that combines desired cluster topology with environment-specific details.

### 1.2 Core Principle

The configuration is structured around a **3D matrix**:

| Dimension            | Description                                      | Config Expression         |
|----------------------|--------------------------------------------------|---------------------------|
| Unit of Deployment   | What runs on a node (container, JVM, ember)      | `[runtime.X]` profiles    |
| Environment          | Where nodes come from (cloud, SSH, local)        | `[source.X]` profiles     |
| Topology             | How nodes are organized (cores, workers, zones)  | `[[groups]]` definitions  |

These three dimensions are expressed through a **node-group-centric** configuration model with **named source and runtime profiles**.

### 1.3 Relationship to Existing Code

The current codebase uses a flat `ClusterManagementConfig` composed of `DeploymentSpec` (single deployment type, single runtime, single set of zones) and `ClusterSpec` (cluster-level counts and policies). Key existing types:

- `ClusterManagementConfig` = `DeploymentSpec` + `ClusterSpec`
- `DeploymentSpec` contains `DeploymentType` (HETZNER, AWS, ON_PREMISES, EMBEDDED), `RuntimeConfig`, `PortMapping`, `TlsDeploymentConfig`, `SshConfig`
- `ClusterSpec` contains `CoreSpec`, `WorkerSpec`, `DistributionConfig`, `AutoHealSpec`, `UpgradeSpec`
- `ClusterConfigParser` parses TOML into `ClusterManagementConfig`
- `ClusterConfigValidator` validates the parsed config
- `ClusterConfigDiff` computes differences between stored and desired configs
- `ConfigReferenceResolver` resolves `${env:...}` and `${secrets:...}` placeholders

This specification replaces the flat model with a **multi-group, multi-profile** model. The parser, validator, and diff logic must all be rewritten. See [Section 16](#16-migration-from-current-model) for migration details.

---

## 2. Design Model

### 2.1 Conceptual Architecture

```
cluster.toml
  |
  +-- [cluster]                    # Cluster identity and topology constraints
  |
  +-- [source.X] ...               # Named source profiles (where nodes come from)
  |
  +-- [runtime.X] ...              # Named runtime profiles (how nodes are packaged)
  |
  +-- [infrastructure.networking]  # Cross-site networking config
  |
  +-- [load_balancers.X] ...       # Named load balancer definitions
  |
  +-- [operations]                 # Operational policies
  |
  +-- [[groups]]                   # Node group definitions (references profiles by name)
```

### 2.2 Profile Resolution

Each node group references a source profile and a runtime profile **by name**. If omitted, `"default"` is implied. Resolution rules:

| Group Config                     | Resolved Source     | Resolved Runtime    |
|----------------------------------|---------------------|---------------------|
| `source = "hetzner-eu"`         | `[source.hetzner-eu]` | `[runtime.default]` |
| `runtime = "large"`             | `[source.default]`    | `[runtime.large]`   |
| Both omitted                    | `[source.default]`    | `[runtime.default]` |
| `source = "aws"`, `runtime = "large"` | `[source.aws]` | `[runtime.large]`   |

**REQ-2.2.1**: If a group references profile `"default"` (explicitly or implicitly) and no `[source.default]` or `[runtime.default]` section exists, pre-flight validation MUST fail with a clear error message identifying the group and the missing profile.

**REQ-2.2.2**: If a group references a named profile that does not exist, pre-flight validation MUST fail.

---

## 3. Configuration Schema

### 3.1 Top-Level Structure

```toml
# File includes (optional, must be first)
include = ["path/to/fragment.toml", ...]

[cluster]                          # REQUIRED
[cluster.core]                     # REQUIRED
[cluster.workers]                  # Optional

[source.<name>]                    # At least one required
[runtime.<name>]                   # At least one required

[infrastructure.networking]        # REQUIRED

[load_balancers.<name>]            # Optional

[operations]                       # Optional (defaults applied)
[operations.timeouts]              # Optional
[operations.ports]                 # Optional

[[groups]]                         # At least one required
```

### 3.2 `[cluster]` -- Cluster Identity

| Field     | Type   | Required | Constraints                                   | Default |
|-----------|--------|----------|-----------------------------------------------|---------|
| `name`    | string | Yes      | Lowercase, hyphens only, max 63 chars, must start with letter, must match `^[a-z][a-z0-9-]{0,62}$` | -- |
| `version` | string | Yes      | Valid semver (X.Y.Z)                          | --      |

### 3.3 `[cluster.core]` -- Core Node Topology

| Field          | Type | Required | Constraints                         | Default         |
|----------------|------|----------|-------------------------------------|-----------------|
| `count`        | int  | Yes      | Odd, >= 3                           | --              |
| `min`          | int  | No       | Odd, >= 3, <= `count`               | Same as `count` |
| `max`          | int  | No       | Odd, >= `count`                     | Same as `count` |
| `max_per_zone` | int  | No       | >= 1                                | No limit        |

**REQ-3.3.1**: `count` MUST be odd and >= 3. This is required for quorum-based consensus.

**REQ-3.3.2**: `min` MUST be odd, >= 3, and <= `count`.

**REQ-3.3.3**: `max` MUST be odd and >= `count`.

**REQ-3.3.4**: `count` MUST equal the sum of `count` (or `hosts` length) across all groups with `role = "core"`.

**REQ-3.3.5**: `max_per_zone` is advisory only. Pre-flight validation WARNS (does not fail) if any single zone holds a majority of core nodes. A zone holds a majority when `nodes_in_zone > total_cores / 2`.

### 3.4 `[cluster.workers]` -- Worker Topology

| Field   | Type | Required | Constraints | Default |
|---------|------|----------|-------------|---------|
| `count` | int  | No       | >= 0        | 0       |

**REQ-3.4.1**: `count` represents the total desired workers across all worker groups. It MUST equal the sum of `count` (or `hosts` length) across all groups with `role = "worker"`.

---

## 4. Config Composability

### 4.1 File Includes

```toml
include = ["profiles/sources.toml", "profiles/runtimes.toml"]
```

**REQ-4.1.1**: The `include` directive MUST appear at the top of the config file, before any TOML sections.

**REQ-4.1.2**: Includes are resolved by `ConfigReferenceResolver` **before** TOML parsing.

**REQ-4.1.3**: Each fragment file MUST be valid TOML independently.

**REQ-4.1.4**: Merge order: later includes override earlier includes. The main file overrides all includes.

**REQ-4.1.5**: Include paths are relative to the directory containing the main config file.

**REQ-4.1.6**: Circular includes MUST be detected and rejected.

### 4.2 Variable Substitution

Two substitution patterns are supported, resolved by `ConfigReferenceResolver`:

| Pattern               | Resolution                                          | Example                     |
|-----------------------|-----------------------------------------------------|-----------------------------|
| `${env:VAR_NAME}`     | Read from environment variable `VAR_NAME`           | `${env:HCLOUD_TOKEN}`       |
| `${secrets:key-name}` | Read from env var `AETHER_<KEY_NAME>` (uppercased, hyphens to underscores) | `${secrets:cluster-secret}` -> `AETHER_CLUSTER_SECRET` |

**REQ-4.2.1**: If any referenced environment variable is missing, `ConfigReferenceResolver.resolveAll()` MUST return a failure listing **all** missing variables, not just the first.

**REQ-4.2.2**: Unknown patterns (e.g., `${unknown:something}`) MUST be left as-is without error.

### 4.3 Resolution Pipeline

```
1. Read main file as raw text
2. Resolve include directives (read and merge fragment files)
3. Resolve ${env:...} and ${secrets:...} placeholders
4. Parse merged text as TOML
5. Validate schema
```

---

## 5. Named Profiles

### 5.1 Source Profiles `[source.<name>]`

A source profile defines **where nodes come from** and what infrastructure-level configuration they carry.

#### 5.1.1 Common Fields

| Field   | Type   | Required | Values                               |
|---------|--------|----------|--------------------------------------|
| `type`  | string | Yes      | `"cloud"`, `"ssh"`, `"forge"`        |

#### 5.1.2 Database URL Passthrough

Source profiles carry database connection URLs via dot notation. These are NOT provisioned by Aether; they are passed through to nodes at runtime.

```toml
[source.hetzner-eu]
type = "cloud"
# ...
databases.default = "postgresql://user@rds.example.com:5432/app"
databases.analytics = "postgresql://user@analytics.example.com:5432/analytics"
```

**REQ-5.1.2.1**: Nodes receive the database URLs from their group's source profile. The key names (e.g., `default`, `analytics`) correspond to named database connections within the Aether runtime.

**REQ-5.1.2.2**: No validation is performed on database URL values at bootstrap time beyond basic URL format. Runtime failures for unreachable databases are a runtime concern.

#### 5.1.3 Cloud Source (`type = "cloud"`)

| Field         | Type   | Required | Description                    |
|---------------|--------|----------|--------------------------------|
| `provider`    | string | Yes      | `"hetzner"`, `"aws"`, `"gcp"`, `"azure"` |
| `credentials` | string | Yes      | Provider API token/key (typically `${env:...}`) |
| `region`      | string | Yes      | Provider-specific region identifier |

```toml
[source.hetzner-eu]
type = "cloud"
provider = "hetzner"
credentials = "${env:HCLOUD_TOKEN}"
region = "fsn1"
databases.default = "postgresql://user@rds.example.com:5432/app"
```

#### 5.1.4 SSH Source (`type = "ssh"`)

| Field  | Type   | Required | Default              | Description            |
|--------|--------|----------|----------------------|------------------------|
| `user` | string | No       | `"root"`             | SSH user               |
| `key`  | string | No       | `"~/.ssh/id_ed25519"` | Path to SSH private key |
| `port` | int    | No       | `22`                 | SSH port               |

```toml
[source.office]
type = "ssh"
user = "aether"
key = "~/.ssh/id_ed25519"
databases.default = "postgresql://user@office-pg:5432/app"
```

**REQ-5.1.4.1**: Groups using an SSH source MUST specify `hosts` (list of IP addresses or hostnames) in the group definition.

#### 5.1.5 Forge Source (`type = "forge"`)

No additional fields beyond `type` and optional `databases.*`.

```toml
[source.default]
type = "forge"
databases.default = "postgresql://forge:forge@localhost:5432/forge"
```

Forge sources are for local development. Provisioning uses Docker Compose locally.

### 5.2 Runtime Profiles `[runtime.<name>]`

A runtime profile defines **how a node is packaged and executed**.

#### 5.2.1 Common Fields

| Field  | Type   | Required | Values                                                |
|--------|--------|----------|-------------------------------------------------------|
| `type` | string | Yes      | `"container"`, `"compose"`, `"ember"`, `"jvm"`, `"managed-container"` (future) |

#### 5.2.2 Container Runtime (`type = "container"`)

| Field      | Type   | Required | Description                          |
|------------|--------|----------|--------------------------------------|
| `image`    | string | Yes      | Container image reference            |
| `jvm_args` | string | No       | JVM arguments passed to the node     |

```toml
[runtime.default]
type = "container"
image = "ghcr.io/pragmaticalabs/aether-node:1.0.0"
jvm_args = "-Xmx2g -XX:+UseZGC"
```

#### 5.2.3 Compose Runtime (`type = "compose"`)

| Field   | Type   | Required | Description                    |
|---------|--------|----------|--------------------------------|
| `image` | string | Yes      | Container image for compose    |

```toml
[runtime.compose]
type = "compose"
image = "aether-node:local"
```

#### 5.2.4 Ember Runtime (`type = "ember"`)

No additional fields. Ember is a lightweight worker process.

```toml
[runtime.ember]
type = "ember"
```

#### 5.2.5 JVM Runtime (`type = "jvm"`)

| Field      | Type   | Required | Description                    |
|------------|--------|----------|--------------------------------|
| `jvm_args` | string | No       | JVM arguments                  |

Bare JVM process deployed via SSH. The Aether JAR is transferred to the node and started directly.

#### 5.2.6 Managed Container Runtime (`type = "managed-container"`) -- FUTURE

For Kubernetes-managed deployments (EKS, GKE, AKS). Not implemented in v1.

---

## 6. Node Groups

### 6.1 Group Definition

Each group is an entry in the `[[groups]]` TOML array. A group represents a set of nodes that share the same source, runtime, role, and zone.

| Field           | Type     | Required | Default       | Description                         |
|-----------------|----------|----------|---------------|-------------------------------------|
| `name`          | string   | Yes      | --            | Unique identifier for the group     |
| `role`          | string   | Yes      | --            | `"core"` or `"worker"`             |
| `count`         | int      | Cond.    | --            | Number of nodes (cloud/forge)       |
| `source`        | string   | No       | `"default"`   | Named source profile reference      |
| `runtime`       | string   | No       | `"default"`   | Named runtime profile reference     |
| `zone`          | string   | No       | --            | Availability zone (cloud sources)   |
| `instance_type` | string   | No       | --            | Cloud instance type (cloud sources) |
| `hosts`         | string[] | Cond.    | --            | IP addresses/hostnames (SSH sources)|

**REQ-6.1.1**: `name` MUST be unique across all groups.

**REQ-6.1.2**: Either `count` or `hosts` MUST be specified, not both. `count` is used for cloud and forge sources. `hosts` is used for SSH sources. When `hosts` is provided, the effective count is `len(hosts)`.

**REQ-6.1.3**: At least one group with `role = "core"` MUST exist.

**REQ-6.1.4**: Cloud groups SHOULD specify `instance_type`. If omitted, the provider's default is used.

**REQ-6.1.5**: Cloud groups MAY specify `zone`. If omitted, the provider selects based on region.

### 6.2 Group Examples

```toml
# Cloud group with explicit profile references
[[groups]]
name = "hetzner-eu-cores"
role = "core"
count = 3
source = "hetzner-eu"
runtime = "default"
zone = "fsn1-dc14"
instance_type = "cx22"

# SSH group with host list
[[groups]]
name = "office-workers"
role = "worker"
source = "office"
runtime = "ember"
hosts = ["10.0.1.10", "10.0.1.11", "10.0.1.12"]

# Forge group using defaults
[[groups]]
name = "dev"
role = "core"
count = 3
# source = "default" (implicit)
# runtime = "default" (implicit)
```

---

## 7. Infrastructure

### 7.1 Networking

```toml
[infrastructure.networking]
type = "manual"
```

| Field  | Type   | Required | Values                                        |
|--------|--------|----------|-----------------------------------------------|
| `type` | string | Yes      | `"manual"` (v1). Future: `"wireguard"`, `"tailscale"`, `"cloud-peering"` |

**REQ-7.1.1**: In `type = "manual"` mode, Aether does NOT provision any network infrastructure. The operator is responsible for ensuring all nodes can reach each other on the cluster port.

**REQ-7.1.2**: Pre-flight validation MUST perform pairwise reachability checks (TCP probe on the cluster port) between nodes in different groups.

### 7.2 Load Balancers

Named collection of load balancer definitions.

```toml
[load_balancers.<name>]
```

| Field      | Type     | Required | Description                                    |
|------------|----------|----------|------------------------------------------------|
| `type`     | string   | Yes      | `"embedded"`, `"external"`, `"none"`          |
| `runtime`  | string   | Cond.    | Runtime profile for embedded LB (required when `type = "embedded"`) |
| `endpoint` | string   | Cond.    | External LB URL (required when `type = "external"`) |
| `groups`   | string[] | Yes      | List of group names this LB fronts             |

**REQ-7.2.1**: `type = "embedded"` deploys an Aether passive load balancer as a node using the specified runtime profile. Deployed during Phase 6 (post-bootstrap).

**REQ-7.2.2**: `type = "external"` means the operator has provisioned their own LB. Aether records the endpoint for connection info output.

**REQ-7.2.3**: `type = "none"` means direct node access. No LB deployed or referenced.

**REQ-7.2.4**: All group names in `groups` MUST reference existing `[[groups]]` entries. Pre-flight validation MUST fail if a referenced group does not exist.

**REQ-7.2.5**: Embedded LBs MUST reference an existing runtime profile. Pre-flight validation MUST fail if the referenced runtime profile does not exist.

### 7.3 Database Configuration

**REQ-7.3.1**: There is NO top-level `[databases]` section. Database URLs are specified per source profile via dot notation (`databases.<name> = "..."` within `[source.<name>]`).

**REQ-7.3.2**: Aether NEVER provisions databases. All database URLs point to operator-managed instances.

**REQ-7.3.3**: Nodes inherit their database URLs from their group's source profile. At runtime, each node is configured with all `databases.*` entries from the source profile of its group.

---

## 8. Operations

### 8.1 Top-Level Operations

```toml
[operations]
auto_heal = true
tls_auto_generate = true
cert_ttl = "720h"
```

| Field               | Type   | Required | Default  | Description                        |
|---------------------|--------|----------|----------|------------------------------------|
| `auto_heal`         | bool   | No       | `true`   | Automatically restart failed nodes |
| `tls_auto_generate` | bool   | No       | `true`   | Generate TLS certificates at bootstrap |
| `cert_ttl`          | string | No       | `"720h"` | Certificate time-to-live           |

### 8.2 Timeouts

```toml
[operations.timeouts]
health_check = "300s"
quorum_formation = "600s"
drain = "120s"
```

| Field              | Type   | Required | Default  | Description                              |
|--------------------|--------|----------|----------|------------------------------------------|
| `health_check`     | string | No       | `"300s"` | Max wait for a node to become healthy     |
| `quorum_formation` | string | No       | `"600s"` | Max wait for quorum to form              |
| `drain`            | string | No       | `"120s"` | Max wait for a node to drain gracefully  |

### 8.3 Ports

```toml
[operations.ports]
cluster = 8090
management = 8080
app_http = 8070
```

| Field        | Type | Required | Default | Description                     |
|--------------|------|----------|---------|---------------------------------|
| `cluster`    | int  | No       | `8090`  | Inter-node cluster communication |
| `management` | int  | No       | `8080`  | Management API port             |
| `app_http`   | int  | No       | `8070`  | Application HTTP traffic        |

**REQ-8.3.1**: All ports MUST be in range 1-65535.

**REQ-8.3.2**: All three ports MUST be distinct.

---

## 9. Bootstrap Flow

The bootstrap command `aether cluster bootstrap --config <file>` executes six sequential phases. Phases with per-group work execute groups in parallel where noted.

### Phase 1: Validate

**Trigger**: Always runs first.

**Steps** (sequential within the phase):

1. **Resolve includes**: Read the main config file, detect `include = [...]`, read fragment files, merge (later overrides earlier, main overrides all).
2. **Resolve variables**: Replace `${env:...}` and `${secrets:...}` with values. Fail if any required variable is missing, reporting ALL missing variables.
3. **Parse TOML**: Parse the merged, resolved text as TOML into the config model.
4. **Validate schema**:
   - `cluster.name`: matches `^[a-z][a-z0-9-]{0,62}$`
   - `cluster.version`: valid semver
   - `cluster.core.count`: odd, >= 3, equals sum of core group counts
   - All ports valid (1-65535) and distinct
   - At least one group with `role = "core"`
   - All group names unique
5. **Validate profile references**: Every group's `source` and `runtime` reference must resolve to an existing profile. If a group omits the reference and no `[source.default]` / `[runtime.default]` exists, fail.
6. **Validate load balancer references**: Every LB's `groups` must reference existing groups. Embedded LBs must reference existing runtime profiles.
7. **Check max_per_zone**: WARN (do not fail) if any zone holds a majority of core nodes.
8. **Per-group validation** (parallel):
   - **Cloud groups**: Verify credentials with provider API. Check quota sufficient for `count` nodes of `instance_type`.
   - **SSH groups**: Verify SSH connectivity (attempt handshake to each host). Verify Docker/ember installed as appropriate for the runtime.
   - **Forge groups**: Verify Docker daemon running. Verify Docker Compose available.
9. **Cross-group networking**: TCP probe on cluster port between all groups (pairwise reachability). This is skipped for Forge groups (local networking).

**Output**: Validated config model or list of validation errors.

### Phase 2: Provision Infrastructure

**Execution**: Parallel per group.

| Source Type | Action                                           |
|-------------|--------------------------------------------------|
| Cloud       | Create VMs via provider API. Wait for `running` status. |
| SSH         | No-op. Hosts already exist.                      |
| Forge       | No-op. Local Docker environment.                 |

**REQ-9.2.1**: Cloud provisioning MUST tag/label all created VMs with: `aether-cluster = <cluster-name>`, `aether-group = <group-name>`, `aether-role = <role>`.

**REQ-9.2.2**: If any VM fails to reach `running` status within `operations.timeouts.health_check`, the phase fails.

### Phase 3: Collect Addresses

**Execution**: Sequential (fast, no external I/O for SSH/Forge).

| Source Type | Address Collection                               |
|-------------|--------------------------------------------------|
| Cloud       | Read public/private IPs from provider API        |
| SSH         | IPs from `hosts` field in group config           |
| Forge       | Docker DNS names (container names)               |

**Output**: Complete peer list mapping `(group, index)` to `(addresses[])` for every node across all groups.

### Phase 4: Deploy Runtime

**Execution**: Parallel per group.

| Runtime Type       | Deployment Steps                                              |
|--------------------|---------------------------------------------------------------|
| `container`        | Pull image on target host. Start container with peer list, database URLs from source, port bindings, JVM args. |
| `compose`          | Generate `docker-compose.yml` from template. Deploy via SSH or locally. Start with `docker compose up -d`. |
| `ember`            | Transfer ember binary/config to target. Start ember process.  |
| `jvm`              | Transfer Aether JAR to target via SSH. Start JVM process with args and peer list. |
| `managed-container`| Apply Kubernetes manifests. (FUTURE)                          |

**REQ-9.4.1**: Every deployed node receives:
- The complete peer list (all nodes across all groups)
- Database URLs from its group's source profile
- Port configuration from `[operations.ports]`
- TLS configuration from `[operations]`
- Cluster name and version

### Phase 5: Cluster Formation

**Execution**: Sequential. Waits for the cluster to self-organize.

1. Wait for quorum: at least `cluster.core.min` core nodes connected and participating in consensus.
2. Verify leader election: a single leader is elected among core nodes.
3. Generate API key: random 256-bit key, saved to `~/.aether/clusters/<name>/api-key` with file permissions `0600`.
4. Store cluster config: serialize the full config into the consensus KV-Store under a well-known key.

**REQ-9.5.1**: If quorum is not achieved within `operations.timeouts.quorum_formation`, the phase fails.

**REQ-9.5.2**: The API key MUST be generated by the CLI, not by any node. The CLI pushes it to the cluster via the management API once the leader is available.

### Phase 6: Post-Bootstrap

**Execution**: Sequential.

1. Deploy embedded load balancers (if any `type = "embedded"` LBs are configured). Each embedded LB is a lightweight Aether process started using the specified runtime profile.
2. Register cluster in local CLI registry (`~/.aether/clusters.toml`). Entry includes: name, management endpoint, API key environment variable name.
3. Print connection information to stdout:
   - Cluster name
   - Management endpoint (host:port)
   - Load balancer endpoints (if configured)
   - Node count by role
   - API key file location

---

## 10. Apply Flow (Desired-State Diffing)

The command `aether cluster apply --config <file>` performs desired-state reconciliation.

### 10.1 Prerequisite

The config file is the **complete desired state**. There is no partial config. Every apply operation requires the full file.

### 10.2 Flow

```
1. Parse and validate desired config (same as bootstrap Phase 1)
2. Fetch current state from running cluster: GET /api/cluster/config
3. Compute diff: compare current groups vs. desired groups
4. Present plan to operator (terraform-style output)
5. On confirmation (or --yes flag), execute delta
6. Update stored cluster config in consensus KV-Store
```

### 10.3 Diff Categories

| Change Type        | Symbol | Action                                                     |
|--------------------|--------|------------------------------------------------------------|
| New group          | `+`    | Execute bootstrap Phases 2-4 for the new group. Nodes join existing cluster. |
| Removed group      | `-`    | Drain all nodes in group. Destroy VMs / stop containers.   |
| Count increased    | `~`    | Provision additional nodes in existing group. Join cluster. |
| Count decreased    | `~`    | Drain excess nodes (LIFO order). Destroy.                  |
| Runtime changed    | `~`    | Rolling restart: for each node, drain -> deploy new runtime -> rejoin. |
| Source changed     | `~`    | Provision new nodes in new source. Drain old nodes. Destroy old. |
| Cluster-level only | `~`    | Update stored config. May trigger rolling restart for version changes. |

**REQ-10.3.1**: The plan MUST be presented to the operator before execution. The `--dry-run` flag shows the plan and exits without executing.

**REQ-10.3.2**: Immutable fields (cluster name) MUST be rejected. The diff MUST report these as errors, not actionable changes.

**REQ-10.3.3**: Source migration (changing a group's source profile) is complex. The initial implementation MAY require destroy + recreate rather than live migration. This MUST be clearly communicated in the plan output.

### 10.4 Plan Output Format

```
Cluster: production
Current: 5 cores, 3 workers (3 groups)
Desired: 7 cores, 5 workers (4 groups)

  + aws-us-workers        worker  count=5  source=aws-us  runtime=default
  ~ hetzner-eu-cores      core    count: 3 -> 5
  ~ office-workers        worker  runtime: ember -> container
  - legacy-workers        worker  (3 nodes will be drained and destroyed)

Apply these changes? [y/N]
```

---

## 11. CLI Commands

### 11.1 Cluster Lifecycle

| Command | Description |
|---------|-------------|
| `aether cluster bootstrap --config <file>` | Full bootstrap from config file |
| `aether cluster bootstrap --config <file> --dry-run` | Validate and show plan without provisioning |
| `aether cluster bootstrap --config <file> --resume` | Resume failed bootstrap from last successful phase |
| `aether cluster apply --config <file>` | Desired-state diff and apply |
| `aether cluster apply --config <file> --dry-run` | Show diff plan only |
| `aether cluster apply --config <file> --yes` | Apply without confirmation prompt |
| `aether cluster destroy [--yes]` | Full cluster teardown |

### 11.2 Operational Commands

| Command | Description |
|---------|-------------|
| `aether cluster status` | Cluster health summary and node list |
| `aether cluster topology` | Node group topology view |
| `aether cluster drain <node>` | Drain a specific node (remove from routing, wait for in-flight) |
| `aether cluster scale <group> --count <N>` | Quick resize a group without editing config |
| `aether cluster export [--file <output.toml>]` | Export current cluster state as TOML config |

**REQ-11.2.1**: `aether cluster scale` is a convenience shortcut. It modifies the stored config and triggers the same logic as `apply`. The operator SHOULD update their config file to match afterwards.

**REQ-11.2.2**: `aether cluster export` generates a valid config file that, if used as input to `aether cluster apply`, would produce no changes (idempotent round-trip).

---

## 12. Provider SPI

### 12.1 CloudProvider Interface

Each cloud provider implements:

```java
public interface CloudProvider {
    Promise<List<ProvisionedNode>> provision(NodeGroupConfig group);
    Promise<Unit> destroy(List<NodeId> nodes);
    Promise<List<NodeStatus>> status(List<NodeId> nodes);
    Promise<List<NodeAddress>> addresses(List<NodeId> nodes);
}
```

### 12.2 Implementation Status

| Provider  | Module                         | Status           |
|-----------|--------------------------------|------------------|
| Hetzner   | `aether/environment/hetzner`   | Fully implemented. Uses `HetznerComputeProvider` backed by `HetznerClient`. |
| AWS       | `aether/environment/aws`       | Stub. `AwsComputeProvider` exists with test structure. |
| GCP       | `aether/environment/gcp`       | Stub. `GcpComputeProvider` exists with test structure. |
| Azure     | `aether/environment/azure`     | Stub. `AzureComputeProvider` exists with test structure. |

### 12.3 Non-Cloud Provisioning

SSH and Forge sources do NOT use the `CloudProvider` SPI:

- **SSH**: Uses `RemoteCommandRunner` (existing in `aether/cloud-tests`) to execute commands on target hosts.
- **Forge**: Generates Docker Compose files and runs `docker compose` locally.

### 12.4 Existing Environment Integration

Each provider implements the `EnvironmentIntegration` interface (existing pattern from Hetzner):

```java
public interface EnvironmentIntegration {
    Option<ComputeProvider> compute();
    Option<SecretsProvider> secrets();
    Option<DiscoveryProvider> discovery();
}
```

The `CloudProvider` SPI in Section 12.1 is a **new, higher-level** interface specifically for the bootstrap/apply flow, wrapping the existing `ComputeProvider` with group-aware batch operations.

---

## 13. Pre-flight Validation

### 13.1 Per-Group Checks

| ID     | Check                                       | Source Type | Severity |
|--------|---------------------------------------------|-------------|----------|
| PF-01  | Source profile exists and type is valid      | All         | Error    |
| PF-02  | Runtime profile exists and type is valid     | All         | Error    |
| PF-03  | Cloud credentials valid (API call)           | Cloud       | Error    |
| PF-04  | Cloud quota sufficient for count + type      | Cloud       | Error    |
| PF-05  | SSH connectivity (handshake to each host)    | SSH         | Error    |
| PF-06  | Docker/ember installed on SSH hosts          | SSH         | Error    |
| PF-07  | Docker daemon running                        | Forge       | Error    |
| PF-08  | Docker Compose available                     | Forge       | Error    |
| PF-09  | Port conflicts on same-host groups           | All         | Error    |
| PF-10  | `hosts` field present for SSH source groups  | SSH         | Error    |
| PF-11  | `count` field present for cloud/forge groups | Cloud/Forge | Error    |

### 13.2 Cluster-Level Checks

| ID     | Check                                       | Severity |
|--------|---------------------------------------------|----------|
| CL-01  | Cluster name matches `^[a-z][a-z0-9-]{0,62}$` | Error |
| CL-02  | Version is valid semver                      | Error    |
| CL-03  | Core count sum equals `cluster.core.count`   | Error    |
| CL-04  | Core count odd and >= 3                      | Error    |
| CL-05  | `max_per_zone` not violated                  | Warning  |
| CL-06  | All referenced profiles exist                | Error    |
| CL-07  | At least one group with `role = "core"`      | Error    |
| CL-08  | All group names unique                       | Error    |
| CL-09  | Embedded LBs reference valid runtime profiles | Error   |
| CL-10  | LB `groups` reference existing groups        | Error    |
| CL-11  | All ports distinct and in range 1-65535      | Error    |
| CL-12  | Worker count sum equals `cluster.workers.count` (if specified) | Error |

### 13.3 Cross-Group Checks

| ID     | Check                                       | Severity |
|--------|---------------------------------------------|----------|
| XG-01  | Pairwise reachability (TCP probe on cluster port) | Error |

**REQ-13.3.1**: XG-01 is skipped for Forge groups (local networking is assumed reachable).

**REQ-13.3.2**: XG-01 is only performed between groups that have addressable nodes at validation time (SSH groups with `hosts`). Cloud groups' addresses are not known until Phase 2.

---

## 14. Idempotent Bootstrap

### 14.1 State Tracking

Each bootstrap phase records completion state to enable resume on failure.

**State file**: `~/.aether/clusters/<name>/bootstrap-state.json`

```json
{
  "cluster_name": "production",
  "config_hash": "sha256:abc123...",
  "started_at": "2026-04-11T10:00:00Z",
  "phases": {
    "validate": { "status": "completed", "completed_at": "..." },
    "provision": {
      "status": "completed",
      "completed_at": "...",
      "provisioned_nodes": {
        "hetzner-eu-cores": ["node-id-1", "node-id-2", "node-id-3"]
      }
    },
    "collect_addresses": {
      "status": "completed",
      "addresses": { "hetzner-eu-cores": ["1.2.3.4", "5.6.7.8", "9.10.11.12"] }
    },
    "deploy_runtime": { "status": "failed", "error": "..." },
    "cluster_formation": { "status": "pending" },
    "post_bootstrap": { "status": "pending" }
  }
}
```

### 14.2 Resume Behavior

`aether cluster bootstrap --config <file> --resume`

**REQ-14.2.1**: Resume reads the state file and skips completed phases.

**REQ-14.2.2**: Resume re-validates the config (Phase 1 always runs). If the config has changed (different `config_hash`), resume is rejected with an error instructing the operator to either use the original config or run `aether cluster destroy` and re-bootstrap.

**REQ-14.2.3**: If no state file exists, `--resume` behaves identically to a fresh bootstrap.

### 14.3 Cleanup

`aether cluster destroy` cleans up:
- Provisioned VMs (cloud) / stopped containers (forge)
- Bootstrap state file
- Cluster registry entry
- Local API key file

---

## 15. Example Configurations

### 15.1 Minimal Forge (Local Development)

```toml
[cluster]
name = "local-dev"
version = "1.0.0"

[cluster.core]
count = 3

[source.default]
type = "forge"
databases.default = "postgresql://forge:forge@forge-postgres:5432/forge"

[runtime.default]
type = "compose"
image = "aether-node:local"

[load_balancers.local]
type = "embedded"
runtime = "default"
groups = ["dev"]

[infrastructure.networking]
type = "manual"

[[groups]]
name = "dev"
role = "core"
count = 3
```

### 15.2 Single Cloud (Hetzner Production)

```toml
[cluster]
name = "production"
version = "1.0.0"

[cluster.core]
count = 5
min = 3
max = 15

[source.default]
type = "cloud"
provider = "hetzner"
credentials = "${env:HCLOUD_TOKEN}"
region = "fsn1"
databases.default = "${env:DATABASE_URL}"

[runtime.default]
type = "container"
image = "ghcr.io/pragmaticalabs/aether-node:1.0.0"
jvm_args = "-Xmx4g -XX:+UseZGC"

[load_balancers.primary]
type = "embedded"
runtime = "default"
groups = ["eu-cores"]

[infrastructure.networking]
type = "manual"

[operations]
auto_heal = true
tls_auto_generate = true

[[groups]]
name = "eu-cores"
role = "core"
count = 5
zone = "fsn1-dc14"
instance_type = "cx22"
```

### 15.3 Multi-Cloud Hybrid

```toml
include = ["profiles/sources.toml", "profiles/runtimes.toml"]

[cluster]
name = "global-prod"
version = "1.0.0"

[cluster.core]
count = 5
min = 3
max_per_zone = 2

[load_balancers.eu]
type = "embedded"
runtime = "default"
groups = ["hetzner-eu"]

[load_balancers.us]
type = "external"
endpoint = "https://us-alb.example.com"
groups = ["aws-us"]

[infrastructure.networking]
type = "manual"

[operations]
auto_heal = true
tls_auto_generate = true
cert_ttl = "720h"

[[groups]]
name = "hetzner-eu"
role = "core"
count = 3
source = "hetzner-eu"
zone = "fsn1-dc14"
instance_type = "cx22"

[[groups]]
name = "aws-us"
role = "core"
count = 2
source = "aws-us"
zone = "us-east-1a"
instance_type = "t3.medium"

[[groups]]
name = "office-workers"
role = "worker"
source = "office"
runtime = "ember"
hosts = ["10.0.1.10", "10.0.1.11", "10.0.1.12"]
```

### 15.4 On-Prem SSH

```toml
[cluster]
name = "datacenter"
version = "1.0.0"

[cluster.core]
count = 5

[source.default]
type = "ssh"
user = "aether"
key = "~/.ssh/id_ed25519"
databases.default = "postgresql://aether@db.internal:5432/aether"

[runtime.default]
type = "container"
image = "ghcr.io/pragmaticalabs/aether-node:1.0.0"
jvm_args = "-Xmx4g -XX:+UseZGC"

[load_balancers.primary]
type = "external"
endpoint = "https://lb.internal"
groups = ["dc-cores"]

[infrastructure.networking]
type = "manual"

[[groups]]
name = "dc-cores"
role = "core"
hosts = ["10.0.1.1", "10.0.1.2", "10.0.1.3", "10.0.1.4", "10.0.1.5"]
```

### 15.5 Full Reference (All Sections)

```toml
include = ["profiles/sources.toml", "profiles/runtimes.toml"]

[cluster]
name = "production"
version = "1.0.0"

[cluster.core]
count = 5
min = 3
max = 15
max_per_zone = 2

[cluster.workers]
count = 3

[source.hetzner-eu]
type = "cloud"
provider = "hetzner"
credentials = "${env:HCLOUD_TOKEN}"
region = "fsn1"
databases.default = "postgresql://user@rds.example.com:5432/app"
databases.analytics = "postgresql://user@analytics.example.com:5432/analytics"

[source.aws-us]
type = "cloud"
provider = "aws"
credentials = "${env:AWS_ACCESS_KEY}"
region = "us-east-1"
databases.default = "postgresql://user@us-rds.example.com:5432/app"

[source.office]
type = "ssh"
user = "aether"
key = "~/.ssh/id_ed25519"
databases.default = "postgresql://user@office-pg:5432/app"

[source.default]
type = "forge"
databases.default = "postgresql://forge:forge@localhost:5432/forge"

[runtime.default]
type = "container"
image = "ghcr.io/pragmaticalabs/aether-node:1.0.0"
jvm_args = "-Xmx2g -XX:+UseZGC"

[runtime.large]
type = "container"
image = "ghcr.io/pragmaticalabs/aether-node:1.0.0"
jvm_args = "-Xmx8g -XX:+UseZGC"

[runtime.ember]
type = "ember"

[runtime.compose]
type = "compose"
image = "aether-node:local"

[infrastructure.networking]
type = "manual"

[load_balancers.eu]
type = "embedded"
runtime = "default"
groups = ["hetzner-eu-cores"]

[load_balancers.us]
type = "external"
endpoint = "https://us-alb.example.com"
groups = ["aws-us-cores"]

[load_balancers.local]
type = "embedded"
runtime = "compose"
groups = ["dev"]

[operations]
auto_heal = true
tls_auto_generate = true
cert_ttl = "720h"

[operations.timeouts]
health_check = "300s"
quorum_formation = "600s"
drain = "120s"

[operations.ports]
cluster = 8090
management = 8080
app_http = 8070

[[groups]]
name = "hetzner-eu-cores"
role = "core"
count = 3
source = "hetzner-eu"
runtime = "default"
zone = "fsn1-dc14"
instance_type = "cx22"

[[groups]]
name = "aws-us-cores"
role = "core"
count = 2
source = "aws-us"
runtime = "default"
zone = "us-east-1a"
instance_type = "t3.medium"

[[groups]]
name = "office-workers"
role = "worker"
source = "office"
runtime = "ember"
hosts = ["10.0.1.10", "10.0.1.11", "10.0.1.12"]
```

---

## 16. Migration from Current Model

### 16.1 Current Model (to be replaced)

The existing `ClusterManagementConfig` is a flat structure:

```
ClusterManagementConfig
  +-- DeploymentSpec
  |     +-- type: DeploymentType (HETZNER | AWS | ON_PREMISES | EMBEDDED)
  |     +-- instances: Map<String, String>
  |     +-- runtime: RuntimeConfig (type, image, jvmArgs)
  |     +-- zones: Map<String, String>
  |     +-- ports: PortMapping
  |     +-- tls: Option<TlsDeploymentConfig>
  |     +-- ssh: Option<SshConfig>
  |     +-- nodes: Option<Map<String, String>>
  +-- ClusterSpec
        +-- name, version
        +-- core: CoreSpec (count, min, max)
        +-- workers: WorkerSpec
        +-- distribution: DistributionConfig
        +-- autoHeal: AutoHealSpec
        +-- upgrade: UpgradeSpec
```

This model supports only a **single deployment type** and a **single runtime** per cluster. The new model supports multiple source/runtime profiles and multiple node groups.

### 16.2 Type Mapping

| Old Type                  | New Equivalent                          |
|---------------------------|----------------------------------------|
| `DeploymentType.HETZNER`  | `[source.X] type = "cloud", provider = "hetzner"` |
| `DeploymentType.AWS`      | `[source.X] type = "cloud", provider = "aws"` |
| `DeploymentType.ON_PREMISES` | `[source.X] type = "ssh"`           |
| `DeploymentType.EMBEDDED` | `[source.X] type = "forge"`            |
| `RuntimeType.CONTAINER`   | `[runtime.X] type = "container"`       |
| `RuntimeType.JVM`         | `[runtime.X] type = "jvm"`             |

### 16.3 Classes to Rewrite

| Existing Class              | Action                                    |
|-----------------------------|-------------------------------------------|
| `ClusterManagementConfig`   | Replace with new multi-group config model |
| `DeploymentSpec`            | Split into `SourceProfile` + `RuntimeProfile` |
| `ClusterConfigParser`       | Rewrite for new TOML schema              |
| `ClusterConfigValidator`    | Rewrite with per-group + cross-group validation |
| `ClusterConfigDiff`         | Rewrite for group-level diffing          |
| `ConfigReferenceResolver`   | Extend with `include` file support       |

### 16.4 Classes to Keep/Extend

| Existing Class                   | Action                              |
|----------------------------------|-------------------------------------|
| `ConfigReferenceResolver`        | Extend (add include resolution)     |
| `ClusterRegistry`                | Keep as-is                          |
| `HetznerComputeProvider`         | Wrap with `CloudProvider` SPI adapter |
| `HetznerEnvironmentIntegration`  | Keep, used by provider adapter      |
| `RemoteCommandRunner`            | Keep, used by SSH provisioning      |

---

## 17. Non-Goals

| Item                              | Rationale                                    |
|-----------------------------------|----------------------------------------------|
| Database provisioning (RDS, Cloud SQL) | Always operator-provided. Aether passes URLs through. |
| VPN/mesh networking provisioning  | User-managed for v1. `type = "manual"` placeholder. |
| Kubernetes operator               | `managed-container` is a future runtime type. |
| Multi-cluster federation           | Separate concern, separate spec.            |
| Certificate authority management   | TLS auto-generation is a runtime concern; Aether generates self-signed certs. |
| GUI/web-based cluster management   | CLI-only for v1.                            |

---

## 18. Known Limitations

| ID   | Limitation                                      | Impact                                    |
|------|-------------------------------------------------|-------------------------------------------|
| KL-1 | Only Hetzner cloud provider fully implemented   | AWS/GCP/Azure bootstrap will fail at Phase 2 |
| KL-2 | `managed-container` runtime not implemented     | Kubernetes deployments not supported       |
| KL-3 | JVM runtime partially implemented               | SSH-based JVM deployment path untested     |
| KL-4 | No database URL validation at bootstrap         | Runtime failure if database unreachable    |
| KL-5 | Source migration in `apply` is destroy+recreate | Downtime during group source changes       |
| KL-6 | No SWIM port in new schema                      | Old config had explicit SWIM port; new model uses cluster port for all inter-node traffic |

---

## 19. References

### Internal References

| Path | Description |
|------|-------------|
| `aether/aether-config/src/test/java/org/pragmatica/aether/config/cluster/ClusterConfigParserTest.java` | Current config parser tests (old TOML schema) |
| `aether/aether-config/src/test/java/org/pragmatica/aether/config/cluster/ClusterConfigDiffTest.java` | Current config diff tests (flat model) |
| `aether/aether-config/src/test/java/org/pragmatica/aether/config/cluster/ClusterConfigValidatorTest.java` | Current validation rules |
| `aether/cli/src/test/java/org/pragmatica/aether/cli/cluster/ConfigReferenceResolverTest.java` | Variable substitution (${env:...}, ${secrets:...}) |
| `aether/cli/src/test/java/org/pragmatica/aether/cli/cluster/ClusterRegistryTest.java` | CLI cluster registry (clusters.toml) |
| `aether/environment/hetzner/src/test/java/org/pragmatica/aether/environment/hetzner/HetznerComputeProviderTest.java` | Hetzner cloud provider implementation |
| `aether/cloud-tests/src/test/java/org/pragmatica/aether/cloud/RemoteCommandRunner.java` | SSH command execution for on-prem |
| `aether/cloud-tests/src/test/java/org/pragmatica/aether/cloud/ClusterFormationCloudIT.java` | Cloud cluster formation integration test |

### Technical Documentation

- [TOML Specification v1.0.0](https://toml.io/en/v1.0.0) -- Configuration file format
- [Semantic Versioning 2.0.0](https://semver.org/) -- Version field validation
- [Hetzner Cloud API](https://docs.hetzner.cloud/) -- Server provisioning API (primary cloud provider)
