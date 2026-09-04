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
6. [Infrastructure](#6-infrastructure)
7. [Operations](#7-operations)
8. [Bootstrap Flow](#8-bootstrap-flow)
9. [Apply Flow (Desired-State Diffing)](#9-apply-flow-desired-state-diffing)
10. [CLI Commands](#10-cli-commands)
11. [Provider SPI](#11-provider-spi)
12. [Pre-flight Validation](#12-pre-flight-validation)
13. [Idempotent Bootstrap](#13-idempotent-bootstrap)
14. [Example Configurations](#14-example-configurations)
15. [Migration from Current Model](#15-migration-from-current-model)
16. [Non-Goals](#16-non-goals)
17. [Known Limitations](#17-known-limitations)
18. [References](#18-references)

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

| Dimension            | Description                                      | Config Expression                   |
|----------------------|--------------------------------------------------|-------------------------------------|
| Unit of Deployment   | What runs on a node (container, JVM, ember, docker) | `[runtime.X]` profiles              |
| Environment          | Where nodes come from (cloud, SSH, forge, docker) | `[source.X]` profiles               |
| Topology             | How nodes are organized (cores, workers, spot)   | `[source.X.<role>]` sub-tables      |

These three dimensions are expressed through a **source-centric** configuration model: a source defines a failure domain (provider + region + zone + credentials + databases + load balancer + firewall), and role sub-tables attached to the source carry per-role sizing and runtime selection.

### 1.3 Relationship to Existing Code

The current codebase uses a flat `ClusterManagementConfig` composed of `DeploymentSpec` (single deployment type, single runtime, single set of zones) and `ClusterSpec` (cluster-level counts and policies). Key existing types:

- `ClusterManagementConfig` = `DeploymentSpec` + `ClusterSpec`
- `DeploymentSpec` contains `DeploymentType` (HETZNER, AWS, ON_PREMISES, EMBEDDED), `RuntimeConfig`, `PortMapping`, `TlsDeploymentConfig`, `SshConfig`
- `ClusterSpec` contains `CoreSpec`, `WorkerSpec`, `DistributionConfig`, `AutoHealSpec`, `UpgradeSpec`
- `ClusterConfigParser` parses TOML into `ClusterManagementConfig`
- `ClusterConfigValidator` validates the parsed config
- `ClusterConfigDiff` computes differences between stored and desired configs
- `ConfigReferenceResolver` resolves `${env:...}` and `${secrets:...}` placeholders

This specification replaces the flat model with a **multi-source, multi-profile, role-subtable** model. The parser, validator, and diff logic must all be rewritten. See [Section 15](#15-migration-from-current-model) for migration details.

---

## 2. Design Model

### 2.1 Conceptual Architecture

```
cluster.toml
  |
  +-- [cluster]                    # Cluster identity and topology constraints
  |
  +-- [template.X] ...             # Optional reusable fragments referenced via `inherit`
  |
  +-- [source.X] ...               # Named source profiles (failure domain = provider + zone)
  |     +-- [source.X.core]        # Core role sub-table (count / hosts / instance_type / runtime)
  |     +-- [source.X.worker]      # Worker role sub-table
  |     +-- [source.X.spot]        # Spot role sub-table (cloud only)
  |     +-- [source.X.firewall]    # Optional ingress rules for app_http
  |
  +-- [runtime.X] ...              # Named runtime profiles (how nodes are packaged)
  |
  +-- [infrastructure.networking]  # Cross-site networking config
  |
  +-- [operations]                 # Operational policies
```

### 2.2 Source Resolution

Sources are the unit of deployment addressing. Every role sub-table belongs to exactly one parent source, which determines its provider, region, zone, credentials, databases, load balancer, and firewall.

A role sub-table can override its `runtime` profile by name; if omitted, `"default"` is implied. Runtime resolution rules:

| Sub-table field                     | Resolved Runtime    |
|-------------------------------------|---------------------|
| `runtime = "large"`                 | `[runtime.large]`   |
| `runtime` omitted                   | `[runtime.default]` |

Template inheritance (see §5.3) allows both `[source.X]` tables and `[runtime.X]` tables to `inherit = "<template-name>"` a reusable fragment declared in `[template.<template-name>]`.

**REQ-2.2.1**: If a role sub-table resolves (explicitly or implicitly) to `runtime = "default"` and no `[runtime.default]` section exists, pre-flight validation MUST fail with a clear error identifying the source, role, and missing profile.

**REQ-2.2.2**: If a role sub-table references a named runtime profile that does not exist, pre-flight validation MUST fail.

**REQ-2.2.3**: If a source or runtime references `inherit = "X"` and no `[template.X]` exists, pre-flight validation MUST fail.

---

## 3. Configuration Schema

### 3.1 Top-Level Structure

```toml
# Config format version (required, must be first non-include line)
config_version = "1.0.0"

# File includes (optional, must appear before any sections)
include = ["path/to/fragment.toml", ...]

[cluster]                          # REQUIRED
[cluster.core]                     # REQUIRED (policy fields only; counts derived)
[cluster.workers]                  # Optional (policy fields only; counts derived)

[template.<name>]                  # Optional reusable template(s)

[source.<name>]                    # At least one required
[source.<name>.core]               # At least one `core` sub-table across all sources required
[source.<name>.worker]             # Optional
[source.<name>.spot]               # Optional (cloud only)
[source.<name>.firewall]           # Optional

[runtime.<name>]                   # At least one required

[infrastructure.networking]        # REQUIRED

[operations]                       # Optional (defaults applied)
[operations.auto_heal]             # Optional
[operations.timeouts]              # Optional
[operations.ports]                 # Optional
```

### 3.2 Config Format Version

| Field            | Type   | Required | Constraints        | Default |
|------------------|--------|----------|--------------------|---------|
| `config_version` | string | Yes      | Must be `"1.0.0"`  | --      |

`config_version` is a **top-level scalar** (not inside any section). It identifies which version of this specification the config file was written against. The CLI validates it before any other processing.

**REQ-3.2.1**: `config_version` MUST be present. It is the persisted-document format version, gated by an **exact match** with the build's required version (W6, RFC-0016 §3.5). Absent → validation error: "Persisted config has no config_version (document format version); this build requires `1.0.0` — re-bootstrap the cluster."

**REQ-3.2.2**: The CLI MUST reject config files with a non-current `config_version` (exact-match gate). The error names the file's version and the required version, and distinguishes direction: an **older/unsupported** version → "…is not supported by this build (requires `1.0.0`) — re-bootstrap the cluster"; a **newer** version → "…is NEWER than this build supports…; restore the pre-upgrade persisted state…" (the binary-rollback-after-migration failure mode). There is no `absent = legacy-baseline` case.

**REQ-3.2.3**: `config_version` is checked FIRST — before template inheritance and variable substitution — at the single `parse` boundary both readers share (the CLI bootstrap and the leader/CTM KV re-parse). An old- or newer-format config is therefore rejected with the clean version message rather than a downstream template-resolution error, and before any external I/O (env-var lookups, cloud API calls). (#480: the live path was reordered so the gate precedes `TemplateInheritanceResolver`.)

### 3.3 `[cluster]` -- Cluster Identity

| Field     | Type   | Required | Constraints                                   | Default |
|-----------|--------|----------|-----------------------------------------------|---------|
| `name`    | string | Yes      | Lowercase, hyphens only, max 63 chars, must start with letter, must match `^[a-z][a-z0-9-]{0,62}$` | -- |
| `version` | string | Yes      | Valid semver 2.0.0 (`MAJOR.MINOR.PATCH`, optional `-pre-release`/`+build`) | -- |

### 3.3 `[cluster.core]` -- Core Node Topology

This section carries **policy fields only**. The effective core count is derived at parse time by summing `count` (or `hosts.length` for SSH) across every `[source.X.core]` sub-table.

| Field             | Type | Required | Constraints                                        | Default         |
|-------------------|------|----------|----------------------------------------------------|-----------------|
| `min`             | int  | No       | Odd, >= 3, <= derived count                        | Derived count   |
| `max`             | int  | No       | Odd, >= derived count                              | Derived count   |
| `max_unavailable` | int  | No       | >= 1, upper-bounded by `(derived_count - 1) / 2`  | `1`             |

**REQ-3.3.1**: The derived core count MUST be odd and >= 3. This is required for quorum-based consensus.

**REQ-3.3.2**: `min` MUST be odd, >= 3, and <= the derived core count.

**REQ-3.3.3**: `max` MUST be odd and >= the derived core count.

**REQ-3.3.5**: **CL-13** — Pre-flight validation WARNS (does not fail) if any single source holds a majority of core nodes. A source holds a majority when `cores_in_source > derived_core_count / 2`. Rationale: a user who splits 3 cores as 2 Hetzner + 1 AWS has no quorum safety — a Hetzner outage takes the cluster down. The warning does not fail validation because a single-source cluster is sometimes intentional (cost, latency, operational simplicity); users who split across sources for HA should see the warning when they mis-split.

**REQ-3.3.6**: **CL-14** — Pre-flight validation WARNS (does not fail) if any single source holds more than half of total capacity (cores + workers, spot excluded). This complements CL-13 for non-quorum capacity planning.

**REQ-3.3.7**: `max_unavailable` bounds how many cores may be drained simultaneously during a runtime-change rolling restart (see §9.3). The default `1` preserves quorum for any healthy cluster. The hard ceiling `(count - 1) / 2` is enforced at parse time — operators cannot request a value that would break quorum.

### 3.4 `[cluster.workers]` -- Worker Topology

This section is optional and carries no sizing fields in v1. The effective worker count is derived from the sum of `[source.X.worker]` sub-table sizes. Spot counts are tracked separately and are not included in the worker total.

---

## 4. Config Composability

### 4.1 File Includes

```toml
include = ["profiles/sources.toml", "profiles/runtimes.toml"]
```

**REQ-4.1.1**: The `include` directive MUST appear at the top of the config file, before any TOML sections.

**REQ-4.1.2**: Each include is parsed as a complete, independent TOML document and merged into the main config at the AST level. Raw text concatenation is **not** used — that would produce duplicate-table errors for shared sections like `[cluster]`.

**REQ-4.1.3**: Each fragment file MUST be valid TOML independently.

**REQ-4.1.4**: Merge order: later includes override earlier includes; the main file overrides all includes. Merge strategy is **deep merge per key**. At every table path, later values replace earlier values for the same key, but keys present only in the earlier source are preserved. Arrays of tables and scalar arrays are replaced wholesale, not concatenated. Example: if `a.toml` has `[source.default] type = "cloud"` and `b.toml` has `[source.default] region = "us"`, the merged `[source.default]` has both `type` and `region`; if both files set `type`, `b.toml` wins.

**REQ-4.1.5**: Include paths are relative to the directory containing the main config file.

**REQ-4.1.6**: Circular includes MUST be detected and rejected with a clear error identifying the cycle.

**REQ-4.1.7**: Nested includes are supported: an included file may itself declare `include = [...]`. Nested includes are resolved recursively, depth-first, before being merged into the parent. Depth limit is 16 levels; exceeding it is a validation error (catches runaway recursion).

### 4.2 Variable Substitution

Two substitution patterns are supported, resolved by `ConfigReferenceResolver`:

| Pattern               | Resolution                                          | Example                     |
|-----------------------|-----------------------------------------------------|-----------------------------|
| `${env:VAR_NAME}`     | Read from environment variable `VAR_NAME` verbatim  | `${env:HCLOUD_TOKEN}`       |
| `${secrets:key-name}` | Read from env var `AETHER_<KEY_NAME>` (uppercased, hyphens to underscores) | `${secrets:cluster-secret}` -> `AETHER_CLUSTER_SECRET` |

**Rationale — two prefixes for the same backing store (env vars):**

`${env:X}` is a raw passthrough: whatever name the operator's environment uses, Aether reads it unchanged. This is how third-party provider credentials get in — e.g. `${env:HCLOUD_TOKEN}`, `${env:AWS_SECRET_ACCESS_KEY}`.

`${secrets:X}` is a **namespacing convention** for Aether-owned secrets. Aether prefixes `AETHER_` so there is no collision with third-party env vars, and uppercases the key so the TOML surface can stay kebab-case. This makes configuration self-documenting: `${secrets:cluster-secret}` tells the reader "this is an Aether-defined secret" without revealing the underlying env var name.

**Evolution path:** the `secrets:` prefix is the extension point for external secret providers. v1 resolves from environment variables only; future versions may resolve from HashiCorp Vault, AWS Secrets Manager, Kubernetes Secrets, or other secure storage backends via a pluggable `SecretsProvider` SPI. The prefix ensures operators' configs don't change when the backing store evolves — only the CLI's resolution pipeline does. See issue #139.

Neither prefix is more secure than the other at v1 runtime; both resolve at config-load time from environment variables visible to the CLI process. The distinction is ergonomic/documentary in v1 and becomes a real security boundary once an external secrets provider is wired.

**REQ-4.2.1**: If any referenced environment variable is missing in a CLI-resolved field, `ConfigReferenceResolver.resolveAll()` MUST return a failure listing **all** missing variables, not just the first. Missing variables in deferred fields (§4.2.1) are not reported by the CLI.

**REQ-4.2.2**: Unknown patterns (e.g., `${unknown:something}`) MUST be left as-is without error.

#### 4.2.1 Resolution Timing

Variable resolution is **two-phase**. The CLI produces two artifacts stored in the cluster KV-Store:

| Artifact | KV-Store Key | Content | Consumer |
|----------|--------------|---------|----------|
| Config template | `ClusterConfigKey.TEMPLATE` | All `${...}` placeholders intact | `aether cluster export`, `config_hash` |
| Resolved config | `ClusterConfigKey.CURRENT` | CLI-time fields resolved; `databases.*` placeholders preserved | Node runtime via `ConfigFacade` |

**CLI-resolved fields** — the CLI resolves `${env:...}` / `${secrets:...}` from the operator's environment for all fields EXCEPT `databases.*`. These resolved values are needed to call cloud APIs, SSH into hosts, verify floating IPs, pull images, etc.

**Deferred fields** — `databases.*` values on any source are stored with `${...}` placeholders intact in both the resolved config and the template. Nodes resolve these from their own process environment at access time (when creating a database connection). The CLI machine never needs database credentials.

**REQ-4.2.3**: The CLI MUST NOT resolve `${...}` placeholders inside `databases.*` values. These values are stored verbatim in the resolved config. Nodes resolve them at access time from their own environment via `ConfigFacade`.

**REQ-4.2.4**: `aether cluster export` reads the config template (`ClusterConfigKey.TEMPLATE`), not the resolved config. The exported file preserves ALL original `${...}` references — including CLI-resolved fields like `credentials`. The export is a faithful reproduction of the operator's intent, not of any particular resolved state.

**REQ-4.2.5**: The set of deferred-resolution field paths is: `databases.*` on any source. This set may be extended in future spec revisions.

**REQ-4.2.6**: `config_hash` (§13 resume state) is computed from the config template, ensuring that environment changes (different API token value) do not invalidate resume, while structural changes (different field or placeholder reference) do.

**REQ-4.2.7** (cloud credential propagation): For sources of `type = "cloud"`, the CLI-resolved value of `credentials` MUST be written into each node's per-node TOML overlay under `[cloud.credentials].api_token` so the consensus leader can authenticate with the cloud provider when auto-provisioning nodes during runtime `/api/cluster/scale` operations. The literal token (not the `${env:...}` placeholder) is distributed to every cluster node, because any core node may become the consensus leader after failover and the leader is the sole caller of `ComputeProvider.provision()`. Operators MUST treat the cluster's per-node TOML files as containing the cloud project's full-access credential and protect them accordingly (see [`reference/cloud-integration.md` § Credential Propagation to Nodes](../reference/cloud-integration.md#credential-propagation-to-nodes)). For deployments that cannot accept this propagation model, defer cloud auto-scaling and use external orchestration to call `/api/cluster/scale` only after manually provisioning nodes — or wait for the secrets-provider runtime resolution path (RC2; see issue tracking the investigation).

### 4.3 Resolution Pipeline

```
1. Parse main file as TOML (initial AST).
2. Read `include = [...]` from the AST; for each include, recursively
   repeat steps 1-2 on the included file, then deep-merge its AST into
   the main AST under REQ-4.1.4 semantics.
3. Resolve template inheritance per-section: for each `[source.X]` and
   `[runtime.X]` that declares `inherit = "Y"`, deep-merge `[template.Y]`
   under the declaring section (declaring keys win). Repeat until no
   `inherit` fields remain. Cycles rejected (REQ-5.3.3). Depth limit 16.
4. Save the merged AST as the CONFIG TEMPLATE (all ${...} placeholders
   intact). This is stored in KV-Store as ClusterConfigKey.TEMPLATE
   during bootstrap Phase 5.
5. Walk the merged AST and resolve ${env:...} and ${secrets:...}:
   a. For `databases.*` fields: leave ${...} placeholders intact (REQ-4.2.3).
   b. For all other fields: resolve from CLI environment. Collect ALL
      missing variables before failing (REQ-4.2.1).
6. Validate schema against the selectively-resolved AST.
7. The selectively-resolved AST is stored in KV-Store as
   ClusterConfigKey.CURRENT during bootstrap Phase 5.
```

Note: variable substitution happens **after** include merging and template inheritance, not before. A placeholder defined in a fragment file or a template can reference an environment variable that is only set when the main file is loaded. Substitution cannot be used to dynamically pick an include path or template name — `include` and `inherit` values are literal.

**REQ-4.3.1**: `aether cluster export` reads `ClusterConfigKey.TEMPLATE` and emits the config with all `${...}` placeholders preserved, no includes, no `inherit` directives. Re-applying the exported file to `aether cluster apply` produces no diff (idempotent roundtrip).

---

## 5. Named Profiles

### 5.1 Source Profiles `[source.<name>]`

A source profile defines **a failure domain**: where nodes come from, in which physical/cloud zone, using which credentials and databases, and how the load balancer and firewall for that zone are configured. Role sub-tables attached to a source carry per-role sizing and runtime selection.

**Source is the unit of failure-domain binding.** A multi-zone deployment uses multiple sources (one per zone), typically sharing a `[template.X]` for common fields.

#### 5.1.1 Common Fields

| Field     | Type   | Required | Values                               |
|-----------|--------|----------|--------------------------------------|
| `type`    | string | Yes      | `"cloud"`, `"ssh"`, `"forge"`, `"docker"` |
| `inherit` | string | No       | Name of a `[template.X]` to merge (see §5.3) |

#### 5.1.2 Database URL Passthrough

Source profiles carry database connection URLs via dot notation. Databases are a **source-level (failure-domain-level)** concern: role sub-tables do not override them. Operators wanting different databases for different nodes in the same zone declare multiple sources sharing the same zone via a template.

Database URL values support `${env:...}` and `${secrets:...}` substitution (§4.2), but resolution is **deferred to the node** — not performed by the CLI (REQ-4.2.3). The CLI stores the verbatim placeholders in the cluster KV-Store during bootstrap Phase 5. Each node resolves the variables from its own environment when creating a database connection. This means the CLI machine never needs database credentials — only the nodes do.

```toml
[source.hetzner-eu]
type = "cloud"
# ...
databases.default = "postgresql://${secrets:db-user}:${secrets:db-password}@${env:DB_HOST}:5432/app"
databases.analytics = "${env:ANALYTICS_DATABASE_URL}"
```

In the examples above:
- `${secrets:db-user}` is resolved **by the node** to env var `AETHER_DB_USER` (§4.2 secrets convention)
- `${secrets:db-password}` is resolved **by the node** to env var `AETHER_DB_PASSWORD`
- `${env:DB_HOST}` is resolved **by the node** to env var `DB_HOST`
- `${env:ANALYTICS_DATABASE_URL}` is resolved **by the node** to the full URL from a single env var
- The CLI sees these placeholders but does NOT attempt to expand them

**REQ-5.1.2.1**: Database URLs are stored in the cluster KV-Store under `ConfigKey("cluster.databases.<name>", none())` with `${...}` placeholders intact. Nodes resolve placeholders from their own process environment at access time via `ConfigFacade`. The key names (e.g., `default`, `analytics`) correspond to named database connections within the Aether runtime.

**REQ-5.1.2.2**: No validation is performed on database URL values at bootstrap time beyond basic URL format check on the unexpanded string. Missing environment variables on a node produce a runtime error at connection-creation time, not a bootstrap error.

#### 5.1.3 Cloud Source (`type = "cloud"`)

| Field                     | Type     | Required | Description                                                         |
|---------------------------|----------|----------|---------------------------------------------------------------------|
| `provider`                | string   | Yes      | `"hetzner"`, `"aws"`, `"gcp"`, `"azure"`                            |
| `credentials`             | string   | Yes      | Provider API token/key (typically `${env:...}`)                     |
| `region`                  | string   | Yes      | Provider-specific region identifier                                 |
| `zone`                    | string   | Yes      | Provider-specific availability zone. Each source is a single zone. |
| `load_balancer`           | string   | No       | `"none"` (default) / `"external"` / `"elected"`                     |
| `load_balancer_ips`       | string[] | Cond.    | Required when `load_balancer = "elected"` (pre-allocated floating IPs) |
| `load_balancer_endpoint`  | string   | Cond.    | Required when `load_balancer = "external"`                          |

```toml
[source.hetzner-eu-fsn1-dc14]
type = "cloud"
provider = "hetzner"
credentials = "${env:HCLOUD_TOKEN}"
region = "fsn1"
zone = "fsn1-dc14"
load_balancer = "elected"
load_balancer_ips = ["138.201.1.1", "138.201.1.2"]
databases.default = "postgresql://user@rds.example.com:5432/app"

[source.hetzner-eu-fsn1-dc14.core]
count = 3
instance_type = "cx23"
runtime = "default"
```

**REQ-5.1.3.1**: Each cloud source pins exactly one zone. Multi-zone deployments are expressed as multiple sources, typically sharing a template for common fields.

**REQ-5.1.3.2**: `load_balancer = "elected"` requires a non-empty `load_balancer_ips` list; the number of LB task-group holders is `load_balancer_ips.length`.

**REQ-5.1.3.3**: `load_balancer = "external"` requires `load_balancer_endpoint`; Aether records the endpoint for connection info output but does not provision anything.

#### 5.1.4 SSH Source (`type = "ssh"`)

SSH sources have no `zone` field — physical hardware zones are implicit. The load balancer mode is restricted.

| Field                     | Type   | Required | Default               | Description                        |
|---------------------------|--------|----------|-----------------------|------------------------------------|
| `user`                    | string | No       | `"root"`              | SSH user                           |
| `key`                     | string | No       | `"~/.ssh/id_ed25519"` | Path to SSH private key            |
| `port`                    | int    | No       | `22`                  | SSH port                           |
| `load_balancer`           | string | No       | `"none"`              | `"none"` / `"external"` only       |
| `load_balancer_endpoint`  | string | Cond.    | --                    | Required when `load_balancer = "external"` |

```toml
[source.office]
type = "ssh"
user = "aether"
key = "~/.ssh/id_ed25519"
databases.default = "postgresql://user@office-pg:5432/app"

[source.office.worker]
hosts = ["10.0.1.10", "10.0.1.11", "10.0.1.12"]
runtime = "ember"
```

**REQ-5.1.4.1**: SSH role sub-tables MUST specify `hosts` (list of IP addresses or hostnames). The effective count is `hosts.length`.

**REQ-5.1.4.2**: SSH sources MUST NOT declare `load_balancer = "elected"`. Pre-flight rejects this combination (see PF-17 and KL-13). Operators on bare metal use `"external"` (e.g. hardware LB, BGP, keepalived managed outside Aether) or `"none"`.

#### 5.1.5 Forge Source (`type = "forge"`)

Forge runs all nodes in a single JVM via Ember — a lightweight in-process cluster simulator with built-in dashboard, chaos testing, and load generation. No container provisioning occurs. No `zone` field (local machine). The default load balancer mode is `"elected"` backed by a NOOP floating-IP provider (localhost is always attached).

| Field            | Type | Required | Default      | Description                    |
|------------------|------|----------|--------------|--------------------------------|
| `load_balancer`  | string | No     | `"elected"`  | `"none"` / `"elected"`         |

```toml
[source.default]
type = "forge"
databases.default = "postgresql://forge:forge@localhost:5432/forge"

[source.default.core]
count = 3
```

**REQ-5.1.5.1**: The `runtime` field on forge role sub-tables is implicit (`ember`). If specified, only `"ember"` is accepted; any other value is a pre-flight error. No `[runtime.X]` profile is needed for forge sources — the runtime is built into the Forge binary.

**REQ-5.1.5.2**: Forge sources do not support the `spot` role (PF-15).

Elected LB election is still performed so dev-time behavior matches production.

#### 5.1.5a Docker Source (`type = "docker"`)

Docker sources provision individual containers on a local or remote Docker daemon via `DockerComputeProvider`. Each node runs as a separate container with its own port mappings. No `zone` field. Useful for integration testing and local multi-container clusters where real container isolation is needed (unlike Forge, which runs everything in-process).

| Field            | Type | Required | Default      | Description                    |
|------------------|------|----------|--------------|--------------------------------|
| `load_balancer`  | string | No     | `"none"`     | `"none"` / `"elected"`         |

```toml
[source.local]
type = "docker"
databases.default = "postgresql://aether:aether@host.docker.internal:5432/aether"

[source.local.core]
count = 3

[source.local.worker]
count = 2
```

**REQ-5.1.5a.1**: The `runtime` field on docker role sub-tables is implicit (`docker`). If specified, only `"docker"` is accepted; any other value is a pre-flight error. No `[runtime.X]` profile is needed for docker sources.

**REQ-5.1.5a.2**: Docker sources do not support the `spot` role (PF-15).

**REQ-5.1.5a.3**: Docker source provisioning requires: Docker daemon running, container image pullable. Pre-flight checks PF-07 (Docker daemon) and PF-06 (image availability) apply.

#### 5.1.6 Role Sub-Tables `[source.X.<role>]`

Every source declares one or more **role sub-tables**. The allowed role names are a closed set: **`core`, `worker`, `spot`**. Adding a new role name is a spec-version bump (§16).

| Sub-table field    | Type     | Required | Applies to    | Description                                          |
|--------------------|----------|----------|---------------|------------------------------------------------------|
| `count`            | int      | Cond.    | cloud, forge  | Number of nodes to provision                         |
| `hosts`            | string[] | Cond.    | ssh           | Physical host list; effective count is `hosts.length` |
| `instance_type`    | string   | No       | cloud         | Provider-specific instance type                      |
| `runtime`          | string   | No       | all           | Name of a `[runtime.X]` profile; default `"default"` |

```toml
[source.hetzner-eu-fsn1-dc14.core]
count = 3
instance_type = "cx23"
runtime = "default"

[source.hetzner-eu-fsn1-dc14.worker]
count = 5
instance_type = "cx33"
runtime = "default"

[source.hetzner-eu-fsn1-dc14.spot]
count = 10
instance_type = "cx33"
runtime = "default"
```

**REQ-5.1.6.1**: Each role sub-table MUST specify either `count` (cloud/forge) or `hosts` (ssh), but not both.

**REQ-5.1.6.2**: At least one `[source.X.core]` sub-table MUST exist across all sources in the config (CL-07).

**REQ-5.1.6.3**: Role sub-tables inherit provider, region, zone, credentials, databases, load balancer, and firewall from their parent source. They cannot override these fields.

#### 5.1.7 Role Constraints

The `spot` role carries specific restrictions because spot/preemptible instances are unstable by design.

| Constraint                           | core | worker | spot |
|--------------------------------------|------|--------|------|
| Valid on `cloud` sources             | Yes  | Yes    | Yes  |
| Valid on `ssh` sources               | Yes  | Yes    | **No** |
| Valid on `forge` sources             | Yes  | Yes    | **No** |
| Valid on `docker` sources            | Yes  | Yes    | **No** |
| Participates in quorum               | Yes  | No     | No   |
| Eligible as elected LB candidate     | Yes  | Yes    | **No** |
| Eligible as floating-IP holder       | Yes  | Yes    | **No** |
| Hosts stateful slices                | Yes  | Yes    | **No** (stateless only) |
| Auto-heal policy on failure          | Replace | Replace | Silent preemption replacement |

**REQ-5.1.7.1**: Spot sub-tables on `ssh`, `forge`, or `docker` sources are a pre-flight error (PF-15).

**REQ-5.1.7.2**: Cloud providers that do not support preemptible instances reject spot sub-tables at pre-flight via `ClusterBootstrapConfigValidator.SPOT_UNSUPPORTED_REASONS` (PF-16) — a static map keyed on `CloudProviderName`, which is the single place to extend when a provider's spot arm lands. Hetzner is in that map and therefore rejects spot at pre-flight. AWS has a real spot arm; GCP / Azure are schema-recognized but remain listed as unsupported until their client surfaces support it (see KL-10). (Corrected 2026-08-12: this requirement previously cited `CloudProvider.supportsPreemptible()`. That method was implemented by all five providers and called by nothing — the gate has always been the validator map — and the `CloudProvider` SPI has since been deleted as dead surface.)

**REQ-5.1.7.3**: Elected LB sources MUST contain at least one `core` or `worker` sub-table (PF-14). A source with only a `spot` sub-table cannot hold a floating IP.

#### 5.1.8 Firewall Rules `[source.X.firewall]`

An optional block on any source declaring the ingress rules that Aether will create for that failure domain.

```toml
[source.hetzner-eu-fsn1-dc14.firewall]
allow_ingress = [
    { port = 8070, protocol = "tcp",     source_cidr = "0.0.0.0/0",  description = "app HTTP/1.1 + HTTP/2" },
    { port = 8070, protocol = "udp",     source_cidr = "0.0.0.0/0",  description = "app HTTP/3 (QUIC)" },
    { port = 8443, protocol = "tcp",     source_cidr = "10.0.0.0/8", description = "internal HTTPS" },
]
```

| Field            | Type    | Required | Description                                              |
|------------------|---------|----------|----------------------------------------------------------|
| `allow_ingress`  | table[] | Yes      | List of `{ port, protocol, source_cidr, description }` entries |

Each entry has:

| Field          | Type   | Required | Default  | Description                        |
|----------------|--------|----------|----------|------------------------------------|
| `port`         | int    | Yes      | --       | Destination port (1..65535)        |
| `protocol`     | string | No       | `"tcp"`  | `"tcp"`, `"udp"`, or `"tcp+udp"`  |
| `source_cidr`  | string | Yes      | --       | Source CIDR in IPv4 or IPv6 form   |
| `description`  | string | No       | --       | Free-form audit text               |

The `protocol` field matters for HTTP/3 support: HTTP/3 runs over QUIC (UDP), while HTTP/1.1 and HTTP/2 use TCP. An application-ingress port that serves all three HTTP versions needs both protocols opened. The shorthand `"tcp+udp"` creates both rules in a single entry.

**REQ-5.1.8.1**: When `[source.X.firewall]` is declared, Aether creates exactly the listed rules during Phase 2 and destroys them during `aether cluster destroy`. A `"tcp+udp"` entry expands to two provider-level rules (one per protocol). Rules not listed are not touched.

**REQ-5.1.8.2**: When `load_balancer = "elected"` and no `[source.X.firewall]` block is declared, Aether **auto-creates** two rules — `{ port = app_http, protocol = "tcp", source_cidr = "0.0.0.0/0" }` and `{ port = app_http, protocol = "udp", source_cidr = "0.0.0.0/0" }` — and emits a warning: *"Created permissive firewall rules (TCP+UDP) for elected LB on `<source>`; declare `[source.X.firewall]` to scope."* Both protocols are opened by default so HTTP/3 works out of the box.

**REQ-5.1.8.3**: The cluster port (default 8090) and management port (default 8080) are **operator-managed**, not touched by Aether — consistent with `[infrastructure.networking] type = "manual"`. Operators are responsible for inter-node cluster reachability and management-API ingress. Default security-group behavior per cloud provider is documented in §6.2.

**REQ-5.1.8.4**: Firewall rules are created via `ComputeProvider.openIngress(sourceId, port, protocol, cidr, description)` and destroyed via `ComputeProvider.closeIngress(sourceId, port, protocol, cidr)`. Pre-flight validation (PF-18) rejects invalid ports, unknown protocols, or malformed CIDRs before any call is issued; PF-23 rejects `allow_ingress` on providers with no implemented ingress arm.

`openIngress` is **create-or-patch** and returns an `IngressHandle` carrying the provider resource id. All of a source's rules land on ONE provider resource (a `"tcp+udp"` entry is two rules on one firewall), so repeated calls for the same source return the same handle. The handle is threaded into instance-create (`firewall_ids`) so rules are in force **before** the instance exists — applying them post-create would leave a window in which the node is up and, per §6.2, fully open on Hetzner. Rules the caller did not name are never touched: a patch sends the union of current and new rules, never a replacement set.

### 5.2 Runtime Profiles `[runtime.<name>]`

A runtime profile defines **how a node is packaged and executed**. Runtime profiles support `inherit` (§5.3) in addition to the fields below.

#### 5.2.1 Common Fields

| Field     | Type   | Required | Values                                                |
|-----------|--------|----------|-------------------------------------------------------|
| `type`    | string | Yes      | `"container"`, `"docker"`, `"ember"`, `"jvm"`, `"managed-container"` (future) |
| `inherit` | string | No       | Name of a `[template.X]` to merge                     |

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

#### 5.2.3 Docker Runtime (`type = "docker"`)

Multiple containers managed on one host. Used by `docker` source types for local multi-container clusters and integration testing. Each node runs as an individual Docker container provisioned via `DockerComputeProvider`.

| Field   | Type   | Required | Description                    |
|---------|--------|----------|--------------------------------|
| `image` | string | Yes      | Container image reference      |

```toml
[runtime.docker]
type = "docker"
image = "aether-node:local"
```

Note: Docker sources use this runtime implicitly — operators do not need to declare a `[runtime.X]` profile for docker sources unless they want to override the image.

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

### 5.3 Template Inheritance

Templates let operators factor shared infrastructure fields — credentials, region, image, jvm flags — out of concrete sources and runtimes. A template is declared under the reserved top-level prefix `[template.X]`; any `[source.Y]` or `[runtime.Y]` may adopt it via `inherit = "X"`.

```toml
[template.hetzner-base]
type = "cloud"
provider = "hetzner"
credentials = "${env:HCLOUD_TOKEN}"
databases.default = "${env:DATABASE_URL}"

[source.hetzner-eu-fsn1-dc14]
inherit = "hetzner-base"
region = "fsn1"
zone = "fsn1-dc14"
load_balancer = "elected"
load_balancer_ips = ["138.201.1.1"]

[source.hetzner-eu-fsn1-dc15]
inherit = "hetzner-base"
region = "fsn1"
zone = "fsn1-dc15"
load_balancer = "elected"
load_balancer_ips = ["138.201.2.1"]
```

Merge rules:

- **Deep merge per key.** Scalars and table entries present in the inheriting section override template values. Keys present only in the template are preserved.
- **Arrays replace wholesale** — a source's `load_balancer_ips` fully replaces a template's, not concatenated.
- **Single inheritance only.** Chains are allowed (`[source.A] inherit = "mid"; [template.mid] inherit = "base"`), but a single section cannot declare two parents.
- **Templates need not be complete.** They may omit required fields; only the final merged section is validated.
- **Env var substitution** (`${env:}`, `${secrets:}`) runs after template resolution, so template-borrowed placeholders resolve against the caller's environment.

**REQ-5.3.1**: Template cycles are a validation error (CL-09). The detection reuses the include-cycle check from REQ-4.1.6.

**REQ-5.3.2**: Maximum inheritance chain depth is **16 levels**. Exceeding it is a validation error.

**REQ-5.3.3**: `aether cluster export` (REQ-10.2.2) emits fully flattened sources and runtimes — no `[template.X]` blocks, no `inherit` fields — so exports round-trip idempotently.

**REQ-5.3.4**: Template fragments themselves are never executed. They are purely merge sources; only the final merged section is schema-validated.

---

## 6. Infrastructure

### 6.1 Networking

```toml
[infrastructure.networking]
type = "manual"
```

| Field  | Type   | Required | Values                                        |
|--------|--------|----------|-----------------------------------------------|
| `type` | string | Yes      | `"manual"` (v1). Future: `"wireguard"`, `"tailscale"`, `"cloud-peering"` |

**REQ-6.1.1**: In `type = "manual"` mode, Aether does NOT provision any network infrastructure. The operator is responsible for ensuring all nodes can reach each other on the cluster port.

**REQ-6.1.2**: Pre-flight validation MUST perform pairwise reachability checks (TCP probe on the cluster port) between sources that already have addressable nodes (SSH sources with `hosts`). Cloud sources' addresses are only known after Phase 2.

### 6.2 Port Architecture

Aether distinguishes **Aether-managed ingress** from **operator-managed ingress** to make the security surface explicit.

| Port              | Default | Managed By | Notes                                                           |
|-------------------|---------|------------|-----------------------------------------------------------------|
| `app_http`        | 8070    | Aether     | Opened automatically when `load_balancer = "elected"` with no explicit `[source.X.firewall]` block (REQ-5.1.8.2), or via explicit rules in `[source.X.firewall] allow_ingress`. |
| `cluster`         | 8090    | Operator   | Inter-node QUIC / SWIM multiplex. Must be reachable between all source zones. Aether does not touch cloud security groups on this port (consistent with `type = "manual"` networking). |
| `management`      | 8080    | Operator   | Management API. Reached directly on each node's primary address; the elected LB floating IP does **not** forward management traffic. Operators restrict ingress via their own rules. |

Default cloud provider security-group behavior:

- **Hetzner Cloud**: servers created without an explicit firewall association accept all inbound traffic. Aether creates its firewall rules as a standalone firewall associated with the source's servers.
- **AWS / GCP / Azure** (v1 stubs): default security groups typically deny inbound traffic. Operators must either declare `[source.X.firewall]` or attach their own security group to the provider's VPC before bootstrap.

### 6.3 Load Balancers

Load balancers are **not** a standalone top-level section. They are source-level fields (see §5.1.3 / §5.1.4 / §5.1.5) so that each failure domain owns its own LB configuration.

Three modes are supported:

| Mode        | Meaning                                                                                      |
|-------------|----------------------------------------------------------------------------------------------|
| `"none"`    | Direct node access. No LB deployed or referenced.                                            |
| `"external"` | Operator has provisioned their own LB. Aether records `load_balancer_endpoint` for connection info. |
| `"elected"` | Aether forms an LB task group inside the cluster; one node per `load_balancer_ips` entry holds a floating IP via `FloatingIpProvider`. |

Defaults by source type:

| Source type | Default `load_balancer` |
|-------------|-------------------------|
| `cloud`     | `"none"`                |
| `ssh`       | `"none"`                |
| `forge`     | `"elected"` (NOOP floating IP) |
| `docker`    | `"none"`                |

**REQ-6.3.1**: Elected LB binds to a CDM-managed task group. The CDM applies **hard anti-affinity**: nodes currently holding an LB task are excluded from ALL slice assignment — both stateful and stateless. The LB node is a dedicated ingress node with no application workload, minimizing attack surface and reserving its full resource budget for traffic forwarding.

**REQ-6.3.2**: LB candidate nodes MUST have role `core` or `worker`. Spot nodes are never eligible (REQ-5.1.7.1).

**REQ-6.3.3**: Elected LB floating IPs route the application port (`app_http`) only. The management port (`management`) is reached via each node's primary address, not the floating IP. Cluster port is never exposed through the LB.

**REQ-6.3.4**: Failover flow: SWIM detects the LB holder's departure (~1–3 s) → consensus re-elects a new holder (~500 ms) → `FloatingIpProvider.attach()` moves the IP (~200–500 ms) → clients reconnect. Total failover window ~3–5 s. Long-lived connections break during failover; this is documented as an SLO (KL-11).

**REQ-6.3.5**: SSH sources cannot declare `load_balancer = "elected"` (REQ-5.1.4.2, PF-17, KL-13). V1 does not ship VRRP/keepalived integration.

### 6.4 Management API Forwarding

Every non-elected-LB node exposes a `ManagementServer` configured with `HttpForwarder.forwardManagement()`. When an incoming management request targets a route owned by a task group running on a different node, the server transparently forwards the request to the current task-group owner, obtained from the CDM task-group registry.

This makes management forwarding **universal**: an operator may hit any node's management port and reach any cluster capability, without running a separate aether-lb process. The `aether/lb` module is deleted entirely — all forwarding logic lives in `HttpForwarder` inside `aether/aether-invoke`, which `aether/node` already depends on.

**REQ-6.4.1**: Every node's `ManagementServer` MUST construct its router with `HttpForwarder.forwardManagement()` wired in. The forwarder consults the task-group registry before dispatching a request locally; if the local node is not the owner, the request is forwarded over the existing inter-node QUIC connection.

**REQ-6.4.2**: Forwarding is transparent to CLI clients: an `aether cluster ...` command may target any reachable node's management port and receive the same response as if it had hit the task-group owner directly.

### 6.5 Database Configuration

**REQ-6.5.1**: There is NO top-level `[databases]` section. Database URLs are declared per source profile via dot notation (`databases.<name> = "..."` within `[source.<name>]`).

**REQ-6.5.2**: Aether NEVER provisions databases. All database URLs point to operator-managed instances.

**REQ-6.5.3**: Nodes inherit their database URLs from their parent source profile. At runtime, each node is configured with all `databases.*` entries from its source. Role sub-tables cannot override database URLs — databases are a failure-domain concern.

---

## 7. Operations

### 7.1 Top-Level Operations

```toml
[operations]
auto_heal = true
tls_auto_generate = true
cert_ttl = "720h"
```

| Field               | Type   | Required | Default  | Description                        |
|---------------------|--------|----------|----------|------------------------------------|
| `auto_heal`         | bool   | No       | `true`   | Shortcut alias for `[operations.auto_heal] enabled = <value>` with defaults for the other fields. |
| `tls_auto_generate` | bool   | No       | `true`   | Generate TLS certificates at bootstrap |
| `cert_ttl`          | string | No       | `"720h"` | Certificate time-to-live           |

### 7.2 Auto-Heal

```toml
[operations.auto_heal]
enabled = true
retry_interval = "10s"
startup_cooldown = "15s"
```

| Field              | Type   | Required | Default  | Description                                          |
|--------------------|--------|----------|----------|------------------------------------------------------|
| `enabled`          | bool   | No       | `true`   | Master toggle for the CTM auto-heal reconcile loop.  |
| `retry_interval`   | string | No       | `"10s"`  | Period of the reconcile loop between heal attempts.  |
| `startup_cooldown` | string | No       | `"15s"`  | Delay between cluster formation and the first heal check, giving nodes time to complete boot. |

**REQ-7.2.1**: The shortcut `auto_heal = true` under `[operations]` is equivalent to `[operations.auto_heal] enabled = true` with default-valued `retry_interval` and `startup_cooldown`. Declaring both forms for the same field is a validation error.

**REQ-7.2.2**: Retry count, exponential backoff schedule, and the max-concurrent-replacements cap are not operator-tunable in v1 (KL-7).

### 7.3 Timeouts

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

### 7.4 Ports

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

**REQ-7.4.1**: All ports MUST be in range 1-65535.

**REQ-7.4.2**: All three ports MUST be distinct.

---

## 8. Bootstrap Flow

The bootstrap command `aether cluster bootstrap --config <file>` executes six sequential phases. Phases with per-source work execute sources in parallel where noted; within a source, role sub-tables execute in order `core` → `worker` → `spot`.

### Phase 1: Validate

**Trigger**: Always runs first.

**Steps** (sequential within the phase):

1. **Resolve includes**: Read the main config file, detect `include = [...]`, read fragment files, merge (later overrides earlier, main overrides all).
2. **Resolve templates**: For every `[source.X]` / `[runtime.X]` that declares `inherit`, deep-merge the referenced `[template.Y]`. Reject cycles and chains deeper than 16.
3. **Resolve variables**: Replace `${env:...}` and `${secrets:...}` with values. Fail if any required variable is missing, reporting ALL missing variables.
4. **Parse TOML**: Parse the fully merged text as TOML into the config model.
5. **Validate schema**:
   - `cluster.name`: matches `^[a-z][a-z0-9-]{0,62}$`
   - `cluster.version`: valid semver
   - Derived core count: odd and >= 3 (from sum of `[source.X.core]` sizes)
   - All ports valid (1-65535) and distinct
   - At least one `[source.X.core]` sub-table across all sources
   - All source names unique; all role names within the closed set `{core, worker, spot}`
6. **Validate profile references**: Every sub-table's `runtime` reference must resolve; if a sub-table omits `runtime` and no `[runtime.default]` exists, fail. Every `inherit` reference must resolve.
7. **Check source quorum concentration (CL-13)**: WARN if a single source holds a majority of cores.
8. **Per-source validation** (parallel):
   - **Cloud sources**: Verify credentials with provider API. Check quota sufficient for the sum of `count × instance_type` across role sub-tables. Validate floating IPs (ownership, compatible zones) when `load_balancer = "elected"`.
   - **SSH sources**: Verify SSH connectivity (handshake to each host). Verify Docker/ember installed as appropriate for the runtime.
   - **Forge sources**: Verify Docker daemon running. Verify Docker Compose available.
9. **Cross-source networking**: TCP probe on cluster port between sources with addressable nodes (pairwise reachability). Skipped for Forge sources.

**Output**: Validated config model or list of validation errors.

### Phase 2: Provision Infrastructure

**Execution**: Parallel per source. Within a source, role sub-tables execute in order `core` → `worker` → `spot`.

| Source Type | Action                                                                                  |
|-------------|-----------------------------------------------------------------------------------------|
| Cloud       | For each role sub-table, call `CloudProviderSupport.provisionVia(ComputeProvider, NodeGroupConfig)` (spot is expressed as `InstanceType.SPOT` on the resolved `ProvisionRequest`, not a separate method). Wait for `running` status. Then create firewall rules (see below). |
| SSH         | No-op for node provisioning. Hosts already exist.                                        |
| Forge       | No-op for node provisioning. Ember nodes are created in-process during Phase 4.          |
| Docker      | For each role sub-table, create containers via `DockerComputeProvider`. Wait for `running` status. |

**Firewall step (per source, after node provisioning)**:

- If `[source.X.firewall]` is declared, call `ComputeProvider.openIngress(sourceId, port, protocol, sourceCidr, description)` for each entry. (Ingress moved from the deleted `CloudProvider` SPI to `ComputeProvider` — see the CHANGELOG 'Dead `CloudProvider` ingress SPI' entry.)
- Else if `load_balancer = "elected"` and no `[source.X.firewall]` block exists, auto-create `{ port = app_http, source_cidr = "0.0.0.0/0" }` and log a warning (REQ-5.1.8.2).
- Cluster and management ports are never touched — see REQ-5.1.8.3.

**REQ-8.2.1**: Cloud provisioning MUST tag/label all created VMs with: `aether-cluster = <cluster-name>`, `aether-source = <source-name>`, `aether-role = <role>`.

**REQ-8.2.2**: If any VM fails to reach `running` status within `operations.timeouts.health_check`, the phase fails.

**REQ-8.2.3**: A spot sub-table on a provider listed in `ClusterBootstrapConfigValidator.SPOT_UNSUPPORTED_REASONS` MUST have been rejected at pre-flight (PF-16). A spot sub-table on a provider whose `provisionSpot` is a v1 stub (AWS/GCP/Azure) fails the phase with a clear "spot provisioning not implemented in v1 for provider X" error. Tracked as KL-10.

### Phase 3: Collect Addresses

**Execution**: Sequential (fast, no external I/O for SSH/Forge).

| Source Type | Address Collection                                    |
|-------------|-------------------------------------------------------|
| Cloud       | Read public/private IPs from provider API             |
| SSH         | IPs from `hosts` field in each role sub-table         |
| Forge       | In-process addresses (localhost with port offsets)     |
| Docker      | Container IPs from Docker daemon                      |

**Output**: Complete peer list mapping `(source, role, index)` to `(addresses[])` for every node.

### Phase 4: Deploy Runtime

**Execution**: Parallel per source, sequential across roles within a source.

| Runtime Type       | Deployment Steps                                              |
|--------------------|---------------------------------------------------------------|
| `container`        | Pull image on target host. Start container with peer list, database URLs from source, port bindings, JVM args. |
| `docker`           | Start containers via `DockerComputeProvider` with peer list, database URLs from source, port bindings, environment variables. Each node is a separate container on the same Docker network. |
| `ember`            | Start Ember nodes in-process (forge source). Configure peer list, database URLs, ports within the single JVM. |
| `jvm`              | Transfer Aether JAR to target via SSH. Start JVM process with args and peer list. |
| `managed-container`| Apply Kubernetes manifests. (FUTURE)                          |

**REQ-8.4.1**: Every deployed node receives:
- The complete peer list (all nodes across all sources)
- Database URLs from its parent source profile
- Port configuration from `[operations.ports]`
- TLS configuration from `[operations]`
- Cluster name and version
- Its source name and role label (for CDM task-group scheduling and firewall/LB identity)

### Phase 5: Cluster Formation

**Execution**: Sequential. Waits for the cluster to self-organize.

1. Wait for quorum: at least `cluster.core.min` core nodes connected and participating in consensus (derived from sum of core sub-tables).
2. Verify leader election: a single leader is elected among core nodes.
3. Generate API key: random 256-bit key, saved to `~/.aether/clusters/<name>/api-key` with file permissions `0600`.
4. Store cluster config in the consensus KV-Store as two entries (§4.2.1):
   - `ClusterConfigKey.TEMPLATE` — the config template with ALL `${...}` placeholders intact (used by `aether cluster export` and `config_hash`).
   - `ClusterConfigKey.CURRENT` — the selectively-resolved config: CLI-time fields resolved, `databases.*` placeholders preserved (used by nodes via `ConfigFacade`).

**REQ-8.5.1**: If quorum is not achieved within `operations.timeouts.quorum_formation`, the phase fails.

**REQ-8.5.2**: The API key MUST be generated by the CLI, not by any node. The CLI pushes it to the cluster via the management API once the leader is available.

**REQ-8.5.3**: Between Phase 4 (runtime started) and the API key push in Phase 5, nodes authenticate to each other via mTLS using the cluster TLS certificates (generated when `tls_auto_generate = true`, or supplied by the operator otherwise). The management API is NOT served to external clients until the API key has been pushed — the CLI is the only client with bootstrap-time access, and it authenticates using the same cluster TLS trust chain.

### Phase 6: Post-Bootstrap

**Execution**: Sequential.

1. **Activate elected load balancers**: For each source with `load_balancer = "elected"`, the CDM activates the LB task group, elects one holder per IP from the source's core/worker nodes (spot excluded), and calls `FloatingIpProvider.attach(ip, self)`. Connection info is printed for each floating IP.
2. **Record external load balancers**: For each source with `load_balancer = "external"`, print `load_balancer_endpoint` as connection info.
3. Register cluster in local CLI registry (`~/.aether/clusters.toml`). Entry includes: name, management endpoint, API key environment variable name.
4. Print connection information to stdout:
   - Cluster name
   - Management endpoint (host:port)
   - Load balancer endpoints / floating IPs (per source)
   - Node count by source and role
   - API key file location

---

## 9. Apply Flow (Desired-State Diffing)

The command `aether cluster apply --config <file>` performs desired-state reconciliation.

### 9.1 Prerequisite

The config file is the **complete desired state**. There is no partial config. Every apply operation requires the full file.

### 9.2 Flow

```
1. Parse and validate desired config (same as bootstrap Phase 1)
2. Fetch current state from running cluster: GET /api/cluster/config
3. Pre-flight cluster-health check: verify every configured core node
   is currently responding and consensus reports a healthy leader.
   Refuse to proceed if the cluster is degraded.
4. Compute diff at (source, role) granularity
5. Present plan to operator (terraform-style output)
6. On confirmation (or --yes flag), execute delta wave by wave
7. Update stored cluster config in consensus KV-Store
```

**REQ-9.2.1**: If the management API endpoint is unreachable (network failure, leader unavailable, cluster destroyed), `apply` MUST fail immediately after step 2 with a clear error message and a non-zero exit code. No changes to stored state or provisioned infrastructure may happen before step 2 returns a current-state snapshot. The error message SHOULD suggest `aether cluster status` for diagnosis and `aether cluster destroy` + `aether cluster bootstrap` if the cluster is unrecoverable.

**REQ-9.2.2**: `apply` MUST NOT fall back to local stored state or cached config if the cluster is unreachable — that would risk acting on a stale plan. The "I'm offline, reconcile from cache" use case is deferred to a future `aether cluster bootstrap --resume` extension.

**REQ-9.2.3**: The pre-flight cluster-health check (step 3) refuses to proceed if any configured core is down or consensus reports no stable leader. Operators recover via `aether cluster status` to identify the degraded node before re-running `apply`.

### 9.3 Diff Categories

The diff operates at `(source, role)` granularity. Changes are classified, ordered into waves, and executed with the safety policy documented below.

| Change Type                          | Symbol | Action                                                                                |
|--------------------------------------|--------|---------------------------------------------------------------------------------------|
| Add source                           | `+`    | Provision new source's sub-tables in parallel (core → worker → spot). Join cluster.   |
| Add role sub-table to source         | `+`    | Provision new sub-table nodes. Join cluster.                                           |
| Remove source                        | `-`    | Drain all nodes in source. Destroy VMs / stop containers. Delete firewall rules.     |
| Remove role sub-table                | `-`    | Drain nodes in that sub-table (LIFO). Destroy.                                         |
| Count increase in sub-table          | `~`    | Provision additional nodes. Join cluster.                                              |
| Count decrease in sub-table          | `~`    | Drain excess (LIFO order). Destroy.                                                    |
| Runtime change in sub-table          | `~`    | Rolling restart with `[cluster.core] max_unavailable` budget for cores; parallel for workers. |
| Source-level field change (DB URL, credentials) | `~` | Roll the whole source's nodes using the same rolling policy as runtime change.       |
| Cluster-level only                   | `~`    | Update stored config. May trigger rolling restart for version changes.                 |

**Source changes** (anything that changes the parent `[source.X]` table such as credentials, databases, region/zone, firewall rules) and **role-level count increases** are safe operations that use **replace-before-retire**: Aether provisions new nodes first, waits for them to catch up, drains old nodes, then destroys old. The cluster never drops below its original core count during a source change.

**Runtime changes** (rolling the image or jvm args for an existing sub-table) use a rolling restart with `[cluster.core] max_unavailable` (default `1`) limiting concurrent core drains. Worker sub-tables parallelize. The roll reuses the existing `checkDisruptionBudget()` path so auto-heal and budget gates apply.

**REQ-9.3.1**: The plan MUST be presented to the operator before execution. The `--dry-run` flag shows the plan and exits without executing.

**REQ-9.3.2**: Immutable fields (cluster name) MUST be rejected. The diff MUST report these as errors, not actionable changes.

**REQ-9.3.3**: A wave failure (any node failing to come up or drain within its timeout) **halts in place**. `apply` does not auto-rollback and does not auto-continue. The operator must explicitly re-invoke `apply --resume` to retry the remaining waves, or `apply --rollback` to unwind the changes already applied and return the cluster to its pre-apply state.

**REQ-9.3.4**: Runtime changes that alter the wire protocol between Aether versions cannot use rolling restart — they require a full-cluster replacement via source-level change. The diff engine flags cross-major-version runtime upgrades and forces the replace-before-retire path.

**REQ-9.3.5**: `apply --resume` reads the last wave state and continues from the first unfinished wave, re-running pre-flight before each resumption. `apply --rollback` reverses completed waves in LIFO order: re-provision drained nodes, re-join cluster, destroy nodes that were added, restore prior firewall rules.

### 9.4 Plan Output Format

```
Cluster: production
Current: 5 cores, 3 workers (2 sources)
Desired: 7 cores, 5 workers, 4 spot (3 sources)

  + hetzner-eu-fsn1-dc15                source (zone=fsn1-dc15)
      + core     count=2   instance=cx22   runtime=default
      + worker   count=2   instance=cx32   runtime=default
  ~ hetzner-eu-fsn1-dc14.core            count: 3 -> 5
  ~ aws-us-east-1a.worker                runtime: ember -> container   (rolling, max_unavailable=1)
  + aws-us-east-1a.spot                  count=4   instance=t3.medium
  - legacy-office.worker                 (3 nodes will be drained and destroyed)

Apply these changes? [y/N]
```

---

## 10. CLI Commands

### 10.1 Cluster Lifecycle

| Command | Description |
|---------|-------------|
| `aether cluster bootstrap --config <file>` | Full bootstrap from config file |
| `aether cluster bootstrap --config <file> --dry-run` | Validate and show plan without provisioning |
| `aether cluster bootstrap --config <file> --resume` | Resume failed bootstrap from last successful phase |
| `aether cluster apply --config <file>` | Desired-state diff and apply |
| `aether cluster apply --config <file> --dry-run` | Show diff plan only |
| `aether cluster apply --config <file> --yes` | Apply without confirmation prompt |
| `aether cluster apply --resume` | Resume a halted-in-place apply from the first unfinished wave |
| `aether cluster apply --rollback` | Unwind completed waves and return to pre-apply state |
| `aether cluster destroy [--yes]` | Full cluster teardown |

### 10.2 Operational Commands

| Command | Description |
|---------|-------------|
| `aether cluster status` | Cluster health summary and node list |
| `aether cluster topology` | Source + role sub-table topology view |
| `aether cluster drain <node>` | Drain a specific node (remove from routing, wait for in-flight) |
| `aether cluster scale [--source <name>] --role <role> --count <N>` | Quick resize of a `(source, role)` sub-table without editing config |
| `aether cluster export [--file <output.toml>]` | Export current cluster state as TOML config (flattened) |

**REQ-10.2.1**: `aether cluster scale [--source <name>] --role <role> --count <N>` is a convenience shortcut. It modifies the stored config for the specified sub-table and triggers the same logic as `apply`. The operator SHOULD update their config file to match afterwards. If the local file is left stale, a subsequent `aether cluster apply --dry-run` will show the drift. The recovery path is `aether cluster export --file cluster.toml` (REQ-10.2.2) to regenerate a drift-free local file from the stored state.

**REQ-10.2.1a**: `--source` MAY be omitted. The server resolves it against the stored topology and MUST refuse rather than guess: exactly one source declaring `role` resolves to that source; several MUST be refused with the candidate source names in the message. A `(source, role)` pair the stored topology does not declare MUST be refused rather than created — creating it would make a mistyped source name a provisioning target. `--role` defaults to `core`.

**REQ-10.2.1b**: Quorum validation (odd, `>= 3`, within `core.min`/`core.max`) applies to the `core` role only, and MUST be evaluated against the resulting **cluster-wide** core total rather than the per-source count. A per-source count is not a cluster total: scaling one core source to 1 is valid when another source carries 2. Validation is server-side; the CLI does not hold the whole topology and therefore cannot perform this arithmetic.

**REQ-10.2.2**: `aether cluster export` generates a valid config file that, if used as input to `aether cluster apply`, would produce no changes (idempotent round-trip). Exports **flatten** includes, template inheritance, and variable substitution: the output contains no `include = [...]`, no `[template.X]` blocks, no `inherit` fields, and all `${env:...}` / `${secrets:...}` placeholders are resolved to their literal values.

**REQ-10.2.3**: `aether cluster topology` prints each source as a parent row followed by its role sub-tables, indented one level, with derived counts.

---

## 11. Provider SPI

### 11.1 CloudProvider Interface

Each cloud provider implements:

```java
public interface CloudProvider {
    Promise<QuotaStatus> checkQuota(NodeGroupConfig group);
    Promise<List<ProvisionedNode>> provision(NodeGroupConfig group);
    Promise<List<ProvisionedNode>> provisionSpot(NodeGroupConfig group);
    Promise<Unit> destroy(List<NodeId> nodes);
    Promise<List<NodeStatus>> status(List<NodeId> nodes);
    Promise<List<NodeAddress>> addresses(List<NodeId> nodes);

    boolean supportsPreemptible();

    Promise<Unit> openIngress(int port, String sourceCidr, String description, SourceId source);
    Promise<Unit> closeIngress(int port, String sourceCidr, SourceId source);
}

public record QuotaStatus(boolean sufficient,
                          int requested,
                          int availableInRegion,
                          String limitingResource) {}
```

**REQ-11.1.1**: `checkQuota` is called by pre-flight validation PF-04. It MUST not mutate cloud state — it is a read-only call to the provider API that reports whether the requested `count × instance_type` (summed across role sub-tables of the source) can be provisioned in the source's `region`/`zone`.

**REQ-11.1.2**: If the provider API does not expose quota data (e.g., some Hetzner endpoints), `checkQuota` MAY return `sufficient = true` with `limitingResource = "unknown"` and a warning logged. PF-04 MUST NOT hard-fail on a best-effort provider (KL-12).

**REQ-11.1.3**: `supportsPreemptible()` is consulted at pre-flight (PF-16) to reject spot sub-tables on providers that do not support them (Hetzner).

**REQ-11.1.4**: `provisionSpot` is a separate entry point from `provision` so that v1 stubs can error early without touching the stable provisioning path. For Hetzner, it is never called (rejected at pre-flight). For AWS / GCP / Azure, it currently errors with *"spot provisioning not implemented in v1 for provider <name>"* (KL-10).

**REQ-11.1.5**: `openIngress` / `closeIngress` create and destroy firewall rules for a source. They are called in Phase 2 after node provisioning (for `openIngress`) and during `aether cluster destroy` (for `closeIngress`). Rules are scoped to the source's infrastructure so that tearing down one source does not affect another.

### 11.1a FloatingIpProvider Interface

A new SPI under `EnvironmentIntegration` manages the floating IPs used by elected load balancers.

```java
public interface FloatingIpProvider {
    Promise<Unit> attach(String floatingIp, NodeId target);
    Promise<IpOwnership> verify(String floatingIp);
    Promise<Set<String>> compatibleZones(String floatingIp);
}

public record IpOwnership(boolean ownedByAccount, String currentAttachment) {}
```

- **Hetzner**: implemented via `HetznerClient`.
- **AWS / GCP / Azure**: v1 stubs.
- **SSH**: no implementation. Pre-flight rejects `load_balancer = "elected"` on SSH (PF-17).
- **Forge**: NOOP provider — localhost is always considered attached.

`verify` is called during pre-flight (PF-12) to confirm the IP is owned by the account whose credentials are configured. `compatibleZones` is called during pre-flight (PF-13) to confirm the IP can attach to servers in the source's zone.

### 11.2 Implementation Status

| Provider  | Module                         | Compute          | FloatingIp | Spot              |
|-----------|--------------------------------|------------------|------------|-------------------|
| Hetzner   | `aether/environment/hetzner`   | Full             | Full       | Rejected (pre-flight, unsupported) |
| AWS       | `aether/environment/aws`       | Full             | Stub       | Stub (KL-10)      |
| GCP       | `aether/environment/gcp`       | Full             | Stub       | Stub (KL-10)      |
| Azure     | `aether/environment/azure`     | Full             | Stub       | Stub (KL-10)      |
| Docker    | `aether/environment/docker`    | Full             | NOOP       | N/A               |

### 11.3 Non-Cloud Provisioning

SSH, Forge, and Docker sources do NOT use the `CloudProvider` SPI:

- **SSH**: Uses `RemoteCommandRunner` (existing in `aether/cloud-tests`) to execute commands on target hosts. No `FloatingIpProvider` is wired — elected LB is rejected at pre-flight.
- **Forge**: Runs all nodes in-process via Ember (single JVM). A NOOP `FloatingIpProvider` is registered so that elected LB election logic runs unchanged in dev mode.
- **Docker**: Uses `DockerComputeProvider` (existing in `aether/environment/docker`) to manage individual containers on a Docker daemon. A NOOP `FloatingIpProvider` is registered for elected LB support.

### 11.4 Existing Environment Integration

Each provider implements the `EnvironmentIntegration` interface:

```java
public interface EnvironmentIntegration {
    Option<ComputeProvider> compute();
    Option<SecretsProvider> secrets();
    Option<DiscoveryProvider> discovery();
    Option<FloatingIpProvider> floatingIp();
}
```

The `CloudProvider` SPI in §11.1 is a **new, higher-level** interface specifically for the bootstrap/apply flow, wrapping the existing `ComputeProvider` with source/role-aware batch operations.

---

## 12. Pre-flight Validation

### 12.1 Per-Source Checks

| ID     | Check                                                                 | Source Type | Severity |
|--------|-----------------------------------------------------------------------|-------------|----------|
| PF-01  | Source type valid; `inherit` resolves                                 | All         | Error    |
| PF-02  | Runtime profile for each sub-table exists                             | All         | Error    |
| PF-03  | Cloud credentials valid (API call)                                    | Cloud       | Error    |
| PF-04  | Cloud quota sufficient for all sub-tables on the source (best-effort) | Cloud       | Error    |
| PF-05  | SSH connectivity (handshake to each host)                             | SSH         | Error    |
| PF-06  | Docker/ember installed on SSH hosts                                   | SSH         | Error    |
| PF-07  | Docker daemon running                                                 | Forge       | Error    |
| PF-08  | Docker Compose available                                              | Forge       | Error    |
| PF-09  | Port conflicts between sub-tables on same SSH host                    | SSH         | Error    |
| PF-10  | `hosts` field present for SSH sub-tables                              | SSH         | Error    |
| PF-11  | `count` field present for cloud/forge sub-tables                      | Cloud/Forge | Error    |
| PF-12  | Floating IPs owned by account (elected LB sources)                    | Cloud       | Error    |
| PF-13  | Floating IPs compatible with the source's zone                        | Cloud       | Error    |
| PF-14  | Elected LB source has at least one non-spot sub-table                 | All         | Error    |
| PF-15  | `spot` sub-table only allowed on cloud sources                        | SSH/Forge/Docker | Error |
| PF-16  | Cloud provider supports preemptible instances when spot is declared   | Cloud       | Error    |
| PF-17  | Elected LB not declared on SSH source                                 | SSH         | Error    |
| PF-18  | Firewall rules have valid port range and CIDR                         | All         | Error    |
| PF-19  | Forge sub-tables: `runtime` must be `"ember"` or omitted             | Forge       | Error    |
| PF-20  | Docker sub-tables: `runtime` must be `"docker"` or omitted           | Docker      | Error    |
| PF-21  | Cloud sub-tables: `runtime` must be `"container"` or `"jvm"`         | Cloud       | Error    |
| PF-22  | SSH sub-tables: `runtime` must be `"container"`, `"jvm"`, or `"ember"` | SSH       | Error    |
| PF-23  | Provider implements ingress management when `allow_ingress` declared  | Cloud       | Error    |
| PF-24  | Management port not open to `0.0.0.0/0` while `security_mode = "none"` | Cloud       | Error    |

### 12.2 Cluster-Level Checks

| ID     | Check                                                                 | Severity |
|--------|-----------------------------------------------------------------------|----------|
| CL-01  | Cluster name matches `^[a-z][a-z0-9-]{0,62}$`                         | Error    |
| CL-02  | Version is valid semver 2.0.0 (`MAJOR.MINOR.PATCH`, optional `-pre-release` and `+build` metadata) | Error |
| CL-04  | Derived core count odd and >= 3                                       | Error    |
| CL-06  | All referenced profiles exist                                         | Error    |
| CL-07  | At least one `[source.X.core]` sub-table across all sources           | Error    |
| CL-08  | All source names unique                                               | Error    |
| CL-09  | Template inheritance has no cycles; depth <= 16                       | Error    |
| CL-11  | All ports distinct and in range 1-65535                               | Error    |
| CL-13  | WARN if a single source holds a majority of cores                     | Warning  |
| CL-14  | WARN if a single source holds more than half of total capacity (cores + workers, spot excluded) | Warning |

CL-03, CL-05, CL-10, and CL-12 are removed: counts are derived (CL-03, CL-12), `max_per_zone` is deleted (CL-05), and `[load_balancers]` no longer exists (CL-10).

### 12.3 Cross-Source Checks

| ID     | Check                                                      | Severity |
|--------|------------------------------------------------------------|----------|
| XG-01  | Pairwise reachability (TCP probe on cluster port)          | Error    |

**REQ-12.3.1**: XG-01 is skipped for Forge and Docker sources (local networking is assumed reachable).

**REQ-12.3.2**: XG-01 is only performed between sources that already have addressable nodes at validation time (SSH sources with `hosts`). Cloud sources' addresses are not known until Phase 2.

---

## 13. Idempotent Bootstrap

### 13.1 State Tracking

Each bootstrap phase records completion state to enable resume on failure.

**State file**: `~/.aether/clusters/<name>/bootstrap-state.json`

**`config_hash` scope**: The state file records a SHA-256 of the **fully resolved, normalized TOML text** — that is, the result after include merging (REQ-4.1.2), template inheritance (§5.3), and `${env:}`/`${secrets:}` substitution (REQ-4.2.1), serialized back to a canonical TOML representation with sorted keys. Hashing the raw main file is insufficient because edits to an included fragment or a template would not change the main file's hash. Normalizing removes whitespace and comment noise that would otherwise invalidate resume on cosmetic edits.

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
        "hetzner-eu-fsn1-dc14": {
          "core":   ["node-id-1", "node-id-2", "node-id-3"],
          "worker": ["node-id-4", "node-id-5"]
        }
      }
    },
    "collect_addresses": {
      "status": "completed",
      "addresses": {
        "hetzner-eu-fsn1-dc14": {
          "core":   ["1.2.3.4", "5.6.7.8", "9.10.11.12"],
          "worker": ["13.14.15.16", "17.18.19.20"]
        }
      }
    },
    "deploy_runtime": { "status": "failed", "error": "..." },
    "cluster_formation": { "status": "pending" },
    "post_bootstrap": { "status": "pending" }
  }
}
```

### 13.2 Resume Behavior

`aether cluster bootstrap --config <file> --resume`

**REQ-13.2.1**: Resume reads the state file and skips completed phases.

**REQ-13.2.2**: Resume re-validates the config (Phase 1 always runs). If the config has changed (different `config_hash`), resume is rejected with an error instructing the operator to either use the original config or run `aether cluster destroy` and re-bootstrap.

**REQ-13.2.3**: If no state file exists, `--resume` behaves identically to a fresh bootstrap.

### 13.3 Cleanup

`aether cluster destroy` cleans up:
- Provisioned VMs (cloud) / stopped containers (forge)
- Firewall rules created via `ComputeProvider.closeIngress`
- Bootstrap state file
- Cluster registry entry
- Local API key file

---

## 14. Example Configurations

### 14.1 Minimal Forge (Local Development)

```toml
config_version = "1.0.0"

[cluster]
name = "local-dev"
version = "1.0.0"

[cluster.core]
min = 3

[source.default]
type = "forge"
databases.default = "postgresql://forge:forge@localhost:5432/forge"

[source.default.core]
count = 3

[infrastructure.networking]
type = "manual"
```

Forge sources use Ember implicitly — no `[runtime.X]` section is needed. Forge defaults to `load_balancer = "elected"` with a NOOP floating-IP provider, so no explicit LB configuration is required for local dev.

### 14.1a Minimal Docker (Local Multi-Container)

```toml
config_version = "1.0.0"

[cluster]
name = "local-docker"
version = "1.0.0"

[cluster.core]
min = 3

[source.local]
type = "docker"
databases.default = "postgresql://aether:aether@host.docker.internal:5432/aether"

[source.local.core]
count = 3

[infrastructure.networking]
type = "manual"
```

Docker sources use the `docker` runtime implicitly. Each node runs as a separate container managed by `DockerComputeProvider`. Useful for integration testing where real container isolation is needed.

### 14.2 Single Cloud (Hetzner Production, Single Zone)

```toml
config_version = "1.0.0"

[cluster]
name = "production"
version = "1.0.0"

[cluster.core]
min = 3
max = 15

[source.hetzner-eu-fsn1-dc14]
type = "cloud"
provider = "hetzner"
credentials = "${env:HCLOUD_TOKEN}"
region = "fsn1"
zone = "fsn1-dc14"
load_balancer = "elected"
load_balancer_ips = ["138.201.1.10"]
databases.default = "${env:DATABASE_URL}"

[source.hetzner-eu-fsn1-dc14.core]
count = 5
instance_type = "cx23"
runtime = "default"

[source.hetzner-eu-fsn1-dc14.firewall]
allow_ingress = [
    { port = 8070, source_cidr = "0.0.0.0/0", description = "public app traffic" },
]

[runtime.default]
type = "container"
image = "ghcr.io/pragmaticalabs/aether-node:1.0.0"
jvm_args = "-Xmx4g -XX:+UseZGC"

[infrastructure.networking]
type = "manual"

[operations]
auto_heal = true
tls_auto_generate = true
```

### 14.3 Multi-Cloud Hybrid With Templates

Shared infrastructure fields factored into `[template.X]` blocks; two Hetzner zones share the same base, AWS has its own template, and on-prem office workers use SSH.

```toml
config_version = "1.0.0"

[cluster]
name = "global-prod"
version = "1.0.0"

[cluster.core]
min = 3

[template.hetzner-base]
type = "cloud"
provider = "hetzner"
credentials = "${env:HCLOUD_TOKEN}"
region = "fsn1"
databases.default = "${env:HETZNER_DB_URL}"

[template.aws-base]
type = "cloud"
provider = "aws"
credentials = "${env:AWS_ACCESS_KEY}"
region = "us-east-1"
databases.default = "${env:AWS_DB_URL}"

[source.hetzner-eu-fsn1-dc14]
inherit = "hetzner-base"
zone = "fsn1-dc14"
load_balancer = "elected"
load_balancer_ips = ["138.201.1.1"]

[source.hetzner-eu-fsn1-dc14.core]
count = 1
instance_type = "cx23"
runtime = "default"

[source.hetzner-eu-fsn1-dc15]
inherit = "hetzner-base"
zone = "fsn1-dc15"
load_balancer = "elected"
load_balancer_ips = ["138.201.2.1"]

[source.hetzner-eu-fsn1-dc15.core]
count = 1
instance_type = "cx23"
runtime = "default"

[source.aws-us-east-1a]
inherit = "aws-base"
zone = "us-east-1a"
load_balancer = "external"
load_balancer_endpoint = "https://us-alb.example.com"

[source.aws-us-east-1a.core]
count = 1
instance_type = "t3.medium"
runtime = "default"

[source.aws-us-east-1a.worker]
count = 3
instance_type = "t3.medium"
runtime = "default"

[source.aws-us-east-1a.spot]
count = 4
instance_type = "t3.medium"
runtime = "default"

[source.office]
type = "ssh"
user = "aether"
key = "~/.ssh/id_ed25519"
databases.default = "postgresql://user@office-pg:5432/app"

[source.office.worker]
hosts = ["10.0.1.10", "10.0.1.11", "10.0.1.12"]
runtime = "ember"

[runtime.default]
type = "container"
image = "ghcr.io/pragmaticalabs/aether-node:1.0.0"
jvm_args = "-Xmx2g -XX:+UseZGC"

[runtime.ember]
type = "ember"

[infrastructure.networking]
type = "manual"

[operations]
auto_heal = true
tls_auto_generate = true
```

Derived core count: 3 (1 per Hetzner zone + 1 AWS) — odd, quorum-safe, spread across three sources so CL-13 does not warn.

### 14.4 On-Prem SSH

```toml
config_version = "1.0.0"

[cluster]
name = "datacenter"
version = "1.0.0"

[cluster.core]
min = 3

[source.dc-primary]
type = "ssh"
user = "aether"
key = "~/.ssh/id_ed25519"
load_balancer = "external"
load_balancer_endpoint = "https://lb.internal"
databases.default = "postgresql://aether@db.internal:5432/aether"

[source.dc-primary.core]
hosts = ["10.0.1.1", "10.0.1.2", "10.0.1.3", "10.0.1.4", "10.0.1.5"]
runtime = "default"

[source.dc-primary.worker]
hosts = ["10.0.2.1", "10.0.2.2", "10.0.2.3"]
runtime = "default"

[runtime.default]
type = "container"
image = "ghcr.io/pragmaticalabs/aether-node:1.0.0"
jvm_args = "-Xmx4g -XX:+UseZGC"

[infrastructure.networking]
type = "manual"
```

SSH sources must use `load_balancer = "external"` or `"none"` (KL-13).

### 14.5 Full Reference (All Sections)

```toml
config_version = "1.0.0"

include = ["profiles/runtimes.toml"]

[cluster]
name = "production"
version = "1.0.0"

[cluster.core]
min = 3
max = 15
max_unavailable = 1

[cluster.workers]
# Workers are derived from [source.X.worker] sub-tables; this block is
# present only so the schema can be extended with worker policy fields
# in future releases.

[template.hetzner-base]
type = "cloud"
provider = "hetzner"
credentials = "${env:HCLOUD_TOKEN}"
databases.default = "postgresql://user@rds.example.com:5432/app"
databases.analytics = "postgresql://user@analytics.example.com:5432/analytics"

[template.aws-base]
type = "cloud"
provider = "aws"
credentials = "${env:AWS_ACCESS_KEY}"
databases.default = "postgresql://user@us-rds.example.com:5432/app"

[source.hetzner-eu-fsn1-dc14]
inherit = "hetzner-base"
region = "fsn1"
zone = "fsn1-dc14"
load_balancer = "elected"
load_balancer_ips = ["138.201.1.1", "138.201.1.2"]

[source.hetzner-eu-fsn1-dc14.core]
count = 3
instance_type = "cx23"
runtime = "default"

[source.hetzner-eu-fsn1-dc14.worker]
count = 5
instance_type = "cx33"
runtime = "large"

[source.hetzner-eu-fsn1-dc14.firewall]
allow_ingress = [
    { port = 8070, source_cidr = "0.0.0.0/0", description = "public app traffic" },
]

[source.aws-us-east-1a]
inherit = "aws-base"
region = "us-east-1"
zone = "us-east-1a"
load_balancer = "external"
load_balancer_endpoint = "https://us-alb.example.com"

[source.aws-us-east-1a.core]
count = 2
instance_type = "t3.medium"
runtime = "default"

[source.aws-us-east-1a.spot]
count = 8
instance_type = "t3.large"
runtime = "default"

[source.office]
type = "ssh"
user = "aether"
key = "~/.ssh/id_ed25519"
load_balancer = "none"
databases.default = "postgresql://user@office-pg:5432/app"

[source.office.worker]
hosts = ["10.0.1.10", "10.0.1.11", "10.0.1.12"]
runtime = "ember"

[source.dev-forge]
type = "forge"
databases.default = "postgresql://forge:forge@localhost:5432/forge"

[source.dev-forge.core]
count = 3

[source.dev-docker]
type = "docker"
databases.default = "postgresql://aether:aether@host.docker.internal:5432/aether"

[source.dev-docker.core]
count = 3

[infrastructure.networking]
type = "manual"

[operations]
tls_auto_generate = true
cert_ttl = "720h"

[operations.auto_heal]
enabled = true
retry_interval = "10s"
startup_cooldown = "15s"

[operations.timeouts]
health_check = "300s"
quorum_formation = "600s"
drain = "120s"

[operations.ports]
cluster = 8090
management = 8080
app_http = 8070
```

---

## 15. Migration from Current Model

### 15.1 Current Model (to be replaced)

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

This model supports only a **single deployment type** and a **single runtime** per cluster. The new model supports multiple source profiles with role sub-tables and templates.

### 15.2 Type Mapping

| Old Type                                    | New Equivalent                                                        |
|---------------------------------------------|-----------------------------------------------------------------------|
| `DeploymentType.HETZNER`                    | `[source.X] type = "cloud", provider = "hetzner"`                     |
| `DeploymentType.HETZNER` + multiple `zones` | One `[source.X]` per zone (use `[template.X]` to factor shared fields) |
| `DeploymentType.AWS`                        | `[source.X] type = "cloud", provider = "aws"`                         |
| `DeploymentType.ON_PREMISES`                | `[source.X] type = "ssh"`                                             |
| `DeploymentType.EMBEDDED`                   | `[source.X] type = "forge"`                                           |
| `DeploymentType.DOCKER`                     | `[source.X] type = "docker"`                                          |
| `RuntimeType.CONTAINER`                     | `[runtime.X] type = "container"`                                      |
| `RuntimeType.JVM`                           | `[runtime.X] type = "jvm"`                                            |
| `[[groups]]` array                          | `[source.X.core]` / `[source.X.worker]` / `[source.X.spot]` sub-tables |
| `AutoHealSpec`                              | `[operations.auto_heal]`                                              |
| `UpgradeSpec`                               | **Deleted** — migration emits a warning and drops the field. Runtime upgrades are the §9.3 apply-diff runtime-change path. |
| `DistributionConfig`                        | **Deleted** — migration emits a warning and drops the field. Slice distribution is a blueprint-level concern, not a cluster-level one. |

### 15.3 Classes to Rewrite

| Existing Class              | Action                                                                                                    |
|-----------------------------|-----------------------------------------------------------------------------------------------------------|
| `ClusterManagementConfig`   | Replace with new source-centric config model (sources + role sub-tables + templates)                      |
| `DeploymentSpec`            | Split into `SourceProfile` + `RuntimeProfile` + `RoleSubTable`                                             |
| `ClusterConfigParser`       | Rewrite for new TOML schema, including template inheritance resolver                                       |
| `ClusterConfigValidator`    | Rewrite with per-source + per-role + cross-source validation                                               |
| `ClusterConfigDiff`         | Rewrite for `(source, role)` granularity and wave-based apply                                              |
| `ConfigReferenceResolver`   | Extend with `include` file support and template resolution                                                 |
| `CloudProvider` SPI         | Gains `checkQuota`, `openIngress`, `closeIngress`, `supportsPreemptible`, `provisionSpot`                  |
| `EnvironmentIntegration`    | Gains `Option<FloatingIpProvider> floatingIp()` plus per-provider implementations                          |
| `ManagementServer`          | Constructor wires `HttpForwarder.forwardManagement()` universally so every node can forward management requests |

### 15.4 Classes to Keep / Extend / Delete

| Existing Class / Module          | Action                                                             |
|----------------------------------|--------------------------------------------------------------------|
| `ConfigReferenceResolver`        | Extend (add include + template resolution)                         |
| `ClusterRegistry`                | Keep as-is                                                         |
| `HetznerComputeProvider`         | Wrap with `CloudProvider` SPI adapter; add ingress + spot methods (spot errors) |
| `HetznerEnvironmentIntegration`  | Keep; add `floatingIp()` wired to `HetznerClient`                  |
| `RemoteCommandRunner`            | Keep, used by SSH provisioning                                     |
| `HttpForwarder` (`aether/aether-invoke`) | Keep; now the single forwarding implementation for management + data paths |
| `aether/lb` module               | **Deleted entirely** — no separate LB library. Forwarding logic lives in `HttpForwarder`, which `aether/node` already depends on. |

---

## 16. Non-Goals

| Item                              | Rationale                                    |
|-----------------------------------|----------------------------------------------|
| Database provisioning (RDS, Cloud SQL) | Always operator-provided. Aether passes URLs through. |
| VPN/mesh networking provisioning  | User-managed for v1. `type = "manual"` placeholder. |
| Kubernetes operator               | `managed-container` is a future runtime type. |
| Multi-cluster federation          | Separate concern, separate spec.             |
| Certificate authority management  | TLS auto-generation is a runtime concern; Aether generates self-signed certs. |
| GUI/web-based cluster management  | CLI-only for v1.                             |
| Per-slice placement policies      | Blueprint-level concern, not cluster-level.  |
| Upgrade verification windows and auto-rollback for runtime upgrades | V2 feature.  |
| Spot bid management (AWS max-price tuning) | GCP's flat-rate preemptible and Hetzner's absence of spot are sufficient coverage; operators who need bid tuning can manage it outside Aether. |
| Spot-aware slice tolerance hints  | Blueprint-level concern, not cluster-level.  |
| Custom node roles beyond `{core, worker, spot}` | The closed role set is a spec invariant. New roles are a spec-version bump. |
| SSH-source elected load balancer  | VRRP / keepalived integration is out of scope in v1; operators use `external` or `none`. |

---

## 17. Known Limitations

| ID    | Limitation                                                                                 | Impact                                                     |
|-------|--------------------------------------------------------------------------------------------|------------------------------------------------------------|
| KL-1  | Only Hetzner cloud provider fully implemented                                              | AWS/GCP/Azure bootstrap fails at Phase 2                   |
| KL-2  | `managed-container` runtime not implemented                                                | Kubernetes deployments not supported                       |
| KL-3  | JVM runtime partially implemented                                                          | SSH-based JVM deployment path untested                     |
| KL-4  | No database URL validation at bootstrap                                                    | Runtime failure if database unreachable                    |
| KL-5  | Source-level field migration in `apply` is destroy+recreate for region/zone changes        | Downtime during cross-zone moves; replace-before-retire keeps same-zone moves safe |
| KL-6  | No SWIM port in new schema                                                                 | SWIM is multiplexed over the existing cluster QUIC connection, so there is no second port to configure. Old `swim_port` values are silently ignored during migration (see §15.3). |
| KL-7  | Auto-heal retry count, backoff schedule, and max-concurrent-replacements are hardcoded     | Only `retry_interval` and `startup_cooldown` are operator-tunable in v1 |
| KL-8  | Forge integration tests require rewrite to use elected-LB model                            | The old `aether-lb:local` separate process is gone; tests must exercise the in-cluster LB task group |
| KL-9  | Upgrade verification windows and auto-rollback for runtime upgrades not implemented        | V2 feature                                                 |
| KL-10 | Spot provisioning is schema-recognized but not implemented for AWS/GCP/Azure               | Operators using `spot` will see pre-flight errors for Hetzner (unsupported) and Phase 2 errors for AWS/GCP/Azure (stub `provisionSpot`) |
| KL-11 | Elected LB failover SLO is ~3–5 s (SWIM detect + re-election + `attach` call)              | Long-lived connections break during failover; clients must reconnect |
| KL-12 | Cloud quota pre-flight checks (PF-04) are best-effort when the provider API hides quota data | Pre-flight may not catch quota exhaustion before Phase 2 |
| KL-13 | SSH-source elected LB not supported                                                        | Operators on bare metal must use `external` or `none`      |
| KL-14 | Single-zone on-prem clusters lack floating-IP failover                                     | Operator manually repoints ingress on hardware failure     |

---

## 18. References

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
