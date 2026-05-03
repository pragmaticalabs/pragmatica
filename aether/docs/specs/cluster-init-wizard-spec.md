# Cluster Init Wizard — Design Spec

| Field   | Value                                       |
|---------|---------------------------------------------|
| Status  | Implemented (RC1 scope)                     |
| Date    | 2026-04-18                                  |
| Scope   | **RC1** — single-source, all deployment targets, essential config |
| Modules | `aether/cli`                                |
| Related | cluster-bootstrap-spec.md, node-config-composition-spec.md |

---

## 1. Overview

An interactive wizard for the `aether cluster init` CLI command that guides users through creating a valid `cluster-config.toml`. Supports all four deployment targets (Docker, SSH, Cloud, Forge), database configuration, firewall presets, load balancer, TLS, and secret management.

**RC1 scope:** Single-source configurations only. Full multi-source, spot roles, template inheritance, and cloud provisioning are RC2.

**Design principles:**
- Step-by-step with back navigation (same UX as JBCT scaffolding wizard)
- Validate at each step using existing `ClusterBootstrapConfigValidator`
- Auto-derive topology (core/worker split) from total node count
- Firewall presets for common scenarios (avoid manual port arithmetic)
- Output: single `cluster-config.toml` file, ready for `aether cluster bootstrap`
- Batch mode via flags for CI/scripting

---

## 2. User Experience

### 2.1 Interactive Mode

```
$ aether cluster init

Step 1/8: Cluster Identity
  Cluster name [my-cluster]: production
  Version [1.0.0]: ↵

Step 2/8: Deployment Target
  1. Docker (local development / CI)
  2. SSH (bare metal / VMs)
  3. Cloud
  4. Forge (embedded dev/test)
  Select: 3

Step 3/8: Cloud Provider
  1. Hetzner
  2. AWS
  3. GCP
  4. Azure
  Select: 1
  Region [fsn1]: ↵
  Zone (optional): ↵
  Credentials env var [HCLOUD_TOKEN]: ↵

Step 4/8: Topology
  Total planned nodes [5]: 7
  → Proposed: 3 core + 4 worker
  Accept? [Y/n]: n
  Core nodes [3]: 5
  → Adjusted: 5 core + 2 worker
  Accept? [Y/n]: ↵
  Instance type [cx22]: cx32

Step 5/8: Database
  Configure database connection? [Y/n]: ↵
  Host [localhost]: db.internal
  Port [5432]: ↵
  Database name [aether]: production
  Username [aether]: app_user
  Password source:
    1. Environment variable
    2. Enter directly (stored in config)
  Select [1]: ↵
  Password env var [DB_PASSWORD]: ↵

Step 6/8: Networking
  Firewall preset:
    1. Standard (management + app + cluster + SWIM, from any)
    2. Restrictive (management + app from your IP, cluster internal)
    3. Open (no firewall rules)
    4. Custom
  Select [1]: 2
  Your IP / CIDR [auto-detect]: 203.0.113.0/24

  Load balancer:
    1. None
    2. External (provide endpoint)
    3. Elected (cluster-managed)
  Select [3]: ↵

Step 7/8: Security
  TLS mode:
    1. Auto-generate (self-signed, recommended for internal clusters)
    2. Manual certificates
  Select [1]: ↵
  Cluster secret source:
    1. Auto-generate at bootstrap time (recommended)
    2. Environment variable
  Select [1]: ↵

Step 8/8: Review
  ┌─────────────────────────────────────────────┐
  │ Cluster: production v1.0.0                  │
  │ Target:  Hetzner Cloud (fsn1)               │
  │ Nodes:   5 core + 2 worker (cx32)           │
  │ Database: db.internal:5432/production       │
  │ Firewall: Restrictive (203.0.113.0/24)      │
  │ LB:      Elected                            │
  │ TLS:     Auto-generate                      │
  │ Secret:  Auto-generate at bootstrap         │
  └─────────────────────────────────────────────┘

  Generate? [Y/n/b]: ↵

Generated: cluster-config.toml
Next: aether cluster bootstrap cluster-config.toml
```

### 2.2 Batch Mode (CLI Flags)

```bash
# Minimal Docker dev cluster
aether cluster init --name dev --target docker --nodes 3

# SSH cluster with database
aether cluster init --name staging \
  --target ssh --hosts 10.0.1.1,10.0.1.2,10.0.1.3 \
  --ssh-user deploy --ssh-key ~/.ssh/id_ed25519 \
  --db-host db.staging --db-name aether --db-user app \
  --db-password-env DB_PASSWORD

# Cloud cluster
aether cluster init --name production \
  --target cloud --provider hetzner --region fsn1 \
  --nodes 7 --cores 5 --instance-type cx32 \
  --firewall restrictive --firewall-cidr 203.0.113.0/24 \
  --lb elected --tls auto \
  --db-host db.internal --db-name production
```

When flags are insufficient, the wizard prompts for missing values. When all required values are provided via flags, no prompts — pure batch.

---

## 3. Topology Auto-Derivation

### 3.1 Rules

Given total node count N, derive core count C and worker count W:

| Total (N) | Core (C) | Worker (W) | Rationale |
|-----------|----------|------------|-----------|
| 1 | 1* | 0 | Dev only — below quorum, warn |
| 3 | 3 | 0 | Minimum quorum, no workers |
| 5 | 3 | 2 | Standard small cluster |
| 7 | 5 | 2 | Or 3+4 — ask user |
| 9 | 5 | 4 | Or 3+6 — ask user |
| 11+ | 5 | N-5 | 5 cores sufficient for most workloads |

*N=1 generates a warning: "Single-node cluster cannot form quorum. Suitable for development only."

### 3.2 Ambiguous Cases

When multiple valid core/worker splits exist (N=7: 3+4 or 5+2), the wizard proposes the default and lets the user override:

```
Total planned nodes [5]: 7
→ Proposed: 3 core + 4 worker
Accept? [Y/n]: n
Core nodes (must be odd, ≥ 3): 5
→ Adjusted: 5 core + 2 worker
```

### 3.3 Validation

- Core count must be odd and ≥ 3 (except N=1 dev mode)
- Core count ≤ total nodes
- Worker count = total - core (non-negative)
- Uses `ClusterBootstrapConfigValidator` rules internally

---

## 4. Deployment Target Details

### 4.1 Docker

Collected:
- Core node count

Defaults applied:
- Ports: cluster=6000, management=8080, app_http=8070, swim=6100 (offset for local)
- Runtime: type=docker, implicit
- No firewall, no LB, no TLS (local dev)

Skipped steps: Cloud provider, Firewall, Load balancer, TLS (defaults to off for Docker)

### 4.2 SSH

Collected:
- Host list (comma-separated IPs/hostnames)
- SSH user, key path, port (default 22)
- Core node designation (first N hosts are core, rest are worker)

Derived:
- Core count from number of hosts designated as core
- Worker count from remaining hosts

### 4.3 Cloud

Collected:
- Provider (Hetzner/AWS/GCP/Azure)
- Region, zone (optional)
- Credentials env var (provider-specific default: `HCLOUD_TOKEN`, `AWS_ACCESS_KEY_ID`, etc.)
- Instance type
- Node count (total, then core/worker split)

Provider-specific credential defaults:

| Provider | Default env var |
|----------|----------------|
| Hetzner | `HCLOUD_TOKEN` |
| AWS | `AWS_ACCESS_KEY_ID` + `AWS_SECRET_ACCESS_KEY` |
| GCP | `GOOGLE_APPLICATION_CREDENTIALS` |
| Azure | `AZURE_CLIENT_ID` + `AZURE_CLIENT_SECRET` + `AZURE_TENANT_ID` |

### 4.4 Forge

Collected:
- Core node count

Defaults applied:
- Runtime: type=ember
- Ports: defaults
- No firewall, elected LB, no TLS

Skipped steps: Cloud provider, Firewall, TLS

---

## 5. Database Configuration

### 5.1 Connection Details

```
Host [localhost]: db.internal
Port [5432]: ↵
Database name [aether]: production
Username [aether]: app_user
Password source:
  1. Environment variable
  2. Enter directly
Select [1]: ↵
Password env var [DB_PASSWORD]: ↵
```

### 5.2 Generated Config

```toml
[source.primary.databases]
database_url = "postgresql://${env:DB_PASSWORD}@db.internal:5432/production"
database_host = "db.internal"
database_port = 5432
database_name = "production"
database_username = "app_user"
database_password = "${env:DB_PASSWORD}"
```

### 5.3 Password Handling

- **Environment variable** (recommended): stores `${env:DB_PASSWORD}` — resolved at bootstrap/runtime
- **Direct entry**: stores plaintext in config — wizard warns: "Password will be stored in plaintext. Use environment variable for production."

---

## 6. Firewall Presets

### 6.1 Standard

Opens all Aether ports from any source:

```toml
[[source.primary.firewall.allow_ingress]]
port = 8080
protocol = "tcp"
source_cidr = "0.0.0.0/0"
description = "Management API"

[[source.primary.firewall.allow_ingress]]
port = 8070
protocol = "tcp"
source_cidr = "0.0.0.0/0"
description = "Application HTTP"

[[source.primary.firewall.allow_ingress]]
port = 8090
protocol = "udp"
source_cidr = "0.0.0.0/0"
description = "Cluster QUIC"

[[source.primary.firewall.allow_ingress]]
port = 8190
protocol = "udp"
source_cidr = "0.0.0.0/0"
description = "SWIM health detection"
```

### 6.2 Restrictive

Management + app from user's CIDR, cluster ports from private network only:

```toml
[[source.primary.firewall.allow_ingress]]
port = 8080
protocol = "tcp"
source_cidr = "203.0.113.0/24"
description = "Management API (admin)"

[[source.primary.firewall.allow_ingress]]
port = 8070
protocol = "tcp"
source_cidr = "203.0.113.0/24"
description = "Application HTTP (admin)"

[[source.primary.firewall.allow_ingress]]
port = 8090
protocol = "udp"
source_cidr = "10.0.0.0/8"
description = "Cluster QUIC (internal)"

[[source.primary.firewall.allow_ingress]]
port = 8190
protocol = "udp"
source_cidr = "10.0.0.0/8"
description = "SWIM health detection (internal)"
```

### 6.3 Open

No firewall rules generated. Wizard warns: "No firewall rules. Ensure network-level security is configured externally."

### 6.4 Custom

Add rules one at a time:

```
Add firewall rule (or 'done'):
  Port: 443
  Protocol [tcp]: ↵
  Source CIDR [0.0.0.0/0]: ↵
  Description: HTTPS
Add another? [Y/n]:
```

---

## 7. Security Configuration

### 7.1 TLS

**Auto-generate (default):**
```toml
[operations.tls]
auto_generate = true
```
Cluster secret is generated at bootstrap time or from env var.

**Manual certificates:**
```toml
[operations.tls]
auto_generate = false
cert_path = "${env:TLS_CERT_PATH}"
key_path = "${env:TLS_KEY_PATH}"
ca_path = "${env:TLS_CA_PATH}"
```

### 7.2 Cluster Secret

- **Auto-generate**: no config entry — CLI generates at bootstrap time
- **Environment variable**: `cluster_secret = "${env:AETHER_CLUSTER_SECRET}"`

### 7.3 Skipped for Docker/Forge

Docker and Forge targets skip TLS configuration (defaults to off for local dev). Generated config includes a commented-out example:

```toml
# TLS (uncomment for production):
# [operations.tls]
# auto_generate = true
# cluster_secret = "${env:AETHER_CLUSTER_SECRET}"
```

---

## 8. Generated Output

### 8.1 Complete Example (Cloud)

```toml
# Generated by: aether cluster init
# Bootstrap with: aether cluster bootstrap cluster-config.toml

config_version = "1.0.0"

[cluster]
name = "production"
version = "1.0.0"

[cluster.core]
max_unavailable = 1

[source.primary]
type = "cloud"
provider = "hetzner"
region = "fsn1"
credentials = "${env:HCLOUD_TOKEN}"
load_balancer = "elected"

[source.primary.core]
count = 5
instance_type = "cx32"

[source.primary.worker]
count = 2
instance_type = "cx32"

[source.primary.databases]
database_host = "db.internal"
database_port = 5432
database_name = "production"
database_username = "app_user"
database_password = "${env:DB_PASSWORD}"

[[source.primary.firewall.allow_ingress]]
port = 8080
protocol = "tcp"
source_cidr = "203.0.113.0/24"
description = "Management API"

[[source.primary.firewall.allow_ingress]]
port = 8070
protocol = "tcp"
source_cidr = "203.0.113.0/24"
description = "Application HTTP"

[[source.primary.firewall.allow_ingress]]
port = 8090
protocol = "udp"
source_cidr = "10.0.0.0/8"
description = "Cluster QUIC (internal)"

[[source.primary.firewall.allow_ingress]]
port = 8190
protocol = "udp"
source_cidr = "10.0.0.0/8"
description = "SWIM health detection (internal)"

[runtime.default]
type = "container"
image = "ghcr.io/pragmaticalabs/aether-node:1.0.0"

[operations.tls]
auto_generate = true

[operations.ports]
cluster = 8090
management = 8080
app_http = 8070
swim = 8190

# Advanced configuration (uncomment as needed):
#
# [operations.auto_heal]
# enabled = true
# retry_interval = "60s"
# startup_cooldown = "15s"
#
# [operations.timeouts]
# health_check = "300s"
# quorum_formation = "600s"
# drain = "120s"
#
# Per-source node config overrides:
# [source.primary.node_config.logging]
# level = "INFO"
#
# [source.primary.node_config.app-http]
# timeout = "30s"
```

### 8.2 Minimal Example (Docker)

```toml
# Generated by: aether cluster init
# Bootstrap with: aether cluster bootstrap cluster-config.toml

config_version = "1.0.0"

[cluster]
name = "dev"
version = "1.0.0"

[source.docker]
type = "docker"

[source.docker.core]
count = 3

[operations.ports]
cluster = 6000
management = 8080
app_http = 8070
swim = 6100

# Database (uncomment and configure):
# [source.docker.databases]
# database_host = "localhost"
# database_port = 5432
# database_name = "aether"
# database_username = "aether"
# database_password = "aether"
#
# TLS (uncomment for secure clusters):
# [operations.tls]
# auto_generate = true
```

---

## 9. Validation

### 9.1 Per-Step Validation

Each step validates input before proceeding:

| Step | Validation |
|------|-----------|
| Cluster name | Regex `^[a-z][a-z0-9-]{0,62}$` |
| Version | Valid semver X.Y.Z |
| Provider | Must be known enum value |
| Region | Non-empty for cloud |
| Credentials env var | Non-empty, valid env var name |
| Node count | ≥ 1 (warn if 1), ≥ 3 for production |
| Core count | Odd, ≥ 3, ≤ total |
| Instance type | Non-empty for cloud |
| DB host/port | Non-empty, port 1-65535 |
| Firewall CIDR | Valid CIDR notation |
| SSH hosts | Non-empty, valid hostname/IP format |

### 9.2 Final Validation

After generating the TOML, parse it back through `ClusterBootstrapConfigParser` and `ClusterBootstrapConfigValidator` to catch any composition errors. Display warnings (CL-13, CL-14) if triggered.

---

## 10. Implementation

### 10.1 New Command

`ClusterInitCommand` in `aether/cli/src/main/java/org/pragmatica/aether/cli/cluster/`:
- Registered as `aether cluster init`
- Delegates to `ClusterConfigWizard` for interactive flow
- Delegates to `ClusterConfigGenerator` for TOML output
- Validates output via existing `ClusterBootstrapConfigParser` + `ClusterBootstrapConfigValidator`

### 10.2 Wizard Flow

`ClusterConfigWizard`:
- Step-by-step prompt sequence
- Each step returns a `StepResult` (proceed / back / abort)
- Collects answers into a `ClusterConfigAnswers` record
- Steps are conditionally included based on deployment target (Docker skips firewall/TLS)

### 10.3 Config Generator

`ClusterConfigGenerator`:
- Takes `ClusterConfigAnswers`, produces TOML string
- Uses `StringBuilder` with section templates
- Includes commented-out advanced examples
- Validates output by parsing through existing infrastructure

### 10.4 Files

| File | Purpose |
|------|---------|
| `ClusterInitCommand.java` | CLI entry point, flag definitions |
| `ClusterConfigWizard.java` | Interactive step-by-step flow |
| `ClusterConfigAnswers.java` | Collected user input record |
| `ClusterConfigGenerator.java` | TOML output generation |
| `TopologyDeriver.java` | Core/worker auto-derivation logic |
| `FirewallPresets.java` | Standard/restrictive/open preset definitions |

---

## 11. RC1 Limitations

| Feature | RC1 | RC2 |
|---------|-----|-----|
| Single source | Yes | Multi-source |
| Docker target | Yes | Yes |
| SSH target | Yes | Yes |
| Cloud target | Yes (config only) | With provisioning |
| Forge target | Yes | Yes |
| Core + worker roles | Yes | + spot role |
| Database config | Yes (connection only) | + provisioning |
| Firewall presets | Yes | + per-source firewall |
| Load balancer | Yes (none/external/elected) | + cloud LB provisioning |
| TLS auto-generate | Yes | + manual cert management |
| Cluster secret | Yes (auto/env) | + Vault integration |
| Template inheritance | No | Yes |
| Multi-source | No | Yes |
| Import existing cluster | No | Yes |
| Cloud provisioning | No (config only) | Yes |

---

## 12. Decisions Locked

| Decision | Value | Rationale |
|----------|-------|-----------|
| Output format | Single `cluster-config.toml` | Matches existing `aether cluster bootstrap` input |
| Interactive UX | Step-by-step with back nav | Same as JBCT wizard, works in any terminal |
| Topology derivation | Auto-propose core/worker split | Reduces error, user can override |
| Firewall | Presets + custom | Avoid manual port arithmetic |
| Database | Config only, no provisioning (RC1) | User manages their own DB |
| Password handling | `${env:VAR}` recommended, plaintext with warning | Security-first default |
| Docker defaults | No TLS, no firewall, offset ports | Local dev convenience |
| Validation | Per-step + final parse-back | Fail early, catch composition errors |
| Source naming | "primary" (single-source RC1) | Simple default, multi-source RC2 |
| Batch mode | All fields available as flags | CI/scripting support |

---

## 13. RC2 Enhancements

- **Multi-source wizard** — add/configure multiple failure domains in one session
- **Spot role configuration** — cloud spot instances with pricing/interruption tolerance
- **Template inheritance** — reference shared templates for common configurations
- **Cloud provisioning integration** — wizard triggers actual infrastructure creation
- **Import existing cluster** — reverse-engineer config from running cluster via management API
- **Database provisioning** — create managed databases as part of bootstrap
- **Vault integration** — configure HashiCorp Vault for secret resolution
- **Config drift detection** — compare running cluster against stored config
