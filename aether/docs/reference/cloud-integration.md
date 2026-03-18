# Cloud Integration

How to configure Aether for cloud deployment with automatic provisioning, discovery, load balancing, and secrets management.

## Overview

Aether supports four cloud providers for automated cluster operations:

| Provider | Module | Provider Name |
|----------|--------|---------------|
| Hetzner | `aether/environment/hetzner` | `hetzner` |
| AWS | `aether/environment/aws` | `aws` |
| GCP | `aether/environment/gcp` | `gcp` |
| Azure | `aether/environment/azure` | `azure` |

Key design points:

- **TOML `[cloud]` section** in `aether.toml` configures the provider and all subsystems
- **ServiceLoader-based provider selection** -- each provider registers an `EnvironmentIntegrationFactory` via `META-INF/services`
- **No cloud SDKs** -- all providers use raw HTTP API clients (`org.pragmatica.cloud.*`)
- **Faceted integration** -- each provider exposes optional facets: compute, secrets, load balancer, discovery
- **Environment variable interpolation** -- credential values support `${env:VAR_NAME}` syntax

## Quick Start

Add the provider module to your classpath (it registers via ServiceLoader automatically), then configure `aether.toml`:

**Hetzner:**
```toml
[cloud]
provider = "hetzner"

[cloud.credentials]
api_token = "${env:HETZNER_API_TOKEN}"

[cloud.compute]
server_type = "cx22"
image = "ubuntu-24.04"
region = "fsn1"
```

**AWS:**
```toml
[cloud]
provider = "aws"

[cloud.credentials]
access_key_id = "${env:AWS_ACCESS_KEY_ID}"
secret_access_key = "${env:AWS_SECRET_ACCESS_KEY}"
region = "us-east-1"

[cloud.compute]
ami_id = "ami-0abcdef1234567890"
instance_type = "t3.medium"
subnet_id = "subnet-abc123"
```

**GCP:**
```toml
[cloud]
provider = "gcp"

[cloud.credentials]
project_id = "my-project"
zone = "us-central1-a"
service_account_email = "aether@my-project.iam.gserviceaccount.com"
private_key_pem = "${env:GCP_PRIVATE_KEY}"

[cloud.compute]
machine_type = "e2-medium"
source_image = "projects/ubuntu-os-cloud/global/images/family/ubuntu-2404-lts-amd64"
network = "default"
subnetwork = "default"
```

**Azure:**
```toml
[cloud]
provider = "azure"

[cloud.credentials]
tenant_id = "${env:AZURE_TENANT_ID}"
client_id = "${env:AZURE_CLIENT_ID}"
client_secret = "${env:AZURE_CLIENT_SECRET}"
subscription_id = "${env:AZURE_SUBSCRIPTION_ID}"
resource_group = "aether-rg"
location = "eastus"

[cloud.compute]
vm_size = "Standard_B2s"
image = "Canonical:0001-com-ubuntu-server-jammy:24_04-lts:latest"
admin_username = "aether"
ssh_public_key = "${env:SSH_PUBLIC_KEY}"
vnet_subnet_id = "/subscriptions/.../subnets/default"
```

## TOML Configuration Reference

All cloud configuration lives under the `[cloud]` section of `aether.toml`.

### `[cloud]`

| Key | Type | Required | Description |
|-----|------|----------|-------------|
| `provider` | string | Yes | Provider name: `hetzner`, `aws`, `gcp`, or `azure` |

The presence of `[cloud]` with a `provider` value triggers cloud integration. If omitted, Aether runs without cloud features.

### `[cloud.credentials]`

Provider-specific authentication keys. All values support `${env:VAR_NAME}` interpolation for environment variable injection. See provider sections below for required keys.

### `[cloud.compute]`

Instance/VM parameters for auto-provisioning. See provider sections below for supported keys. All providers accept a `user_data` key for cloud-init or startup scripts.

### `[cloud.load_balancer]`

Optional. Load balancer registration parameters. If omitted, no LB integration is configured.

### `[cloud.discovery]`

Optional. Peer discovery parameters.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `cluster_name` | string | -- | Tag/label value used to identify cluster members |
| `poll_interval_ms` | string (long) | provider-dependent | Polling interval in milliseconds for discovery refresh |

### `[cloud.secrets]`

Optional. Secrets backend configuration. If omitted, `${secrets:...}` placeholders in config are not resolved.

### Environment Variable Interpolation

Any value in `[cloud.credentials]` or `[cloud.compute]` that matches the pattern `${env:VAR_NAME}` is resolved from the process environment at config load time. If the environment variable is not set, the raw placeholder string is passed through.

```toml
[cloud.credentials]
api_token = "${env:HETZNER_API_TOKEN}"   # Resolved from HETZNER_API_TOKEN env var
```

## Provider Configuration

### Hetzner

Provider name: `hetzner`

#### Credentials

| Key | Required | Description |
|-----|----------|-------------|
| `api_token` | Yes | Hetzner Cloud API token |

#### Compute

| Key | Required | Description |
|-----|----------|-------------|
| `server_type` | Yes | Server type (e.g., `cx22`, `cx32`) |
| `image` | Yes | OS image name or ID (e.g., `ubuntu-24.04`) |
| `region` | Yes | Datacenter location (e.g., `fsn1`, `nbg1`) |
| `user_data` | No | Cloud-init user data script |
| `ssh_key_ids` | No | Comma-separated SSH key IDs |
| `network_ids` | No | Comma-separated network IDs |
| `firewall_ids` | No | Comma-separated firewall IDs |

#### Load Balancer

| Key | Required | Description |
|-----|----------|-------------|
| `load_balancer_id` | Yes | Hetzner Load Balancer ID |
| `destination_port` | Yes | Backend port for target registration |

#### Discovery

Uses Hetzner server labels. Servers with label `aether-cluster=<cluster_name>` are discovered as peers.

| Key | Required | Description |
|-----|----------|-------------|
| `cluster_name` | Yes | Label value for cluster membership |
| `poll_interval_ms` | No | Discovery poll interval in ms |

#### Secrets

Hetzner uses environment variable-based secrets. Secrets are resolved from environment variables prefixed with `AETHER_SECRET_`. The secret path maps to the variable name: `${secrets:my_key}` resolves to the value of `AETHER_SECRET_MY_KEY`.

#### Full Example

```toml
[cloud]
provider = "hetzner"

[cloud.credentials]
api_token = "${env:HETZNER_API_TOKEN}"

[cloud.compute]
server_type = "cx22"
image = "ubuntu-24.04"
region = "fsn1"
user_data = "#!/bin/bash\ncurl -sSL https://example.com/install.sh | bash"
ssh_key_ids = "12345,67890"
network_ids = "11111"
firewall_ids = "22222"

[cloud.load_balancer]
load_balancer_id = "99999"
destination_port = "8090"

[cloud.discovery]
cluster_name = "production"
poll_interval_ms = "15000"
```

### AWS

Provider name: `aws`

Authentication uses IAM access keys. All AWS API calls use SigV4 request signing (handled internally by the `org.pragmatica.cloud.aws` HTTP client).

#### Credentials

| Key | Required | Description |
|-----|----------|-------------|
| `access_key_id` | Yes | AWS IAM access key ID |
| `secret_access_key` | Yes | AWS IAM secret access key |
| `region` | Yes | AWS region (e.g., `us-east-1`) |

#### Compute

| Key | Required | Description |
|-----|----------|-------------|
| `ami_id` | Yes | AMI ID for EC2 instances |
| `instance_type` | Yes | EC2 instance type (e.g., `t3.medium`) |
| `key_name` | No | EC2 key pair name for SSH access |
| `security_group_ids` | No | Comma-separated security group IDs |
| `subnet_id` | Yes | VPC subnet ID |
| `user_data` | No | Base64-encoded or plain text user data |

#### Load Balancer

Uses ELBv2 (ALB/NLB) target group registration.

| Key | Required | Description |
|-----|----------|-------------|
| `target_group_arn` | Yes | ELBv2 Target Group ARN |

#### Discovery

Uses EC2 tag-based instance discovery. Instances with tag `aether-cluster=<cluster_name>` in `running` state are discovered as peers.

| Key | Required | Description |
|-----|----------|-------------|
| `cluster_name` | Yes | Tag value for cluster membership |
| `poll_interval_ms` | No | Discovery poll interval in ms |

#### Secrets

Uses AWS Secrets Manager. The secret path is used as the Secrets Manager secret name.

#### Full Example

```toml
[cloud]
provider = "aws"

[cloud.credentials]
access_key_id = "${env:AWS_ACCESS_KEY_ID}"
secret_access_key = "${env:AWS_SECRET_ACCESS_KEY}"
region = "us-east-1"

[cloud.compute]
ami_id = "ami-0abcdef1234567890"
instance_type = "t3.medium"
key_name = "aether-key"
security_group_ids = "sg-abc123,sg-def456"
subnet_id = "subnet-abc123"
user_data = "#!/bin/bash\nyum install -y java-21"

[cloud.load_balancer]
target_group_arn = "arn:aws:elasticloadbalancing:us-east-1:123456789:targetgroup/aether-tg/abc123"

[cloud.discovery]
cluster_name = "production"
poll_interval_ms = "15000"
```

### GCP

Provider name: `gcp`

Authentication uses service account credentials with JWT-based OAuth2 token management (handled internally by the `org.pragmatica.cloud.gcp` HTTP client).

#### Credentials

| Key | Required | Description |
|-----|----------|-------------|
| `project_id` | Yes | GCP project ID |
| `zone` | Yes | Compute Engine zone (e.g., `us-central1-a`) |
| `service_account_email` | Yes | Service account email |
| `private_key_pem` | Yes | PEM-encoded private key for JWT signing |

#### Compute

| Key | Required | Description |
|-----|----------|-------------|
| `machine_type` | Yes | VM machine type (e.g., `e2-medium`) |
| `source_image` | Yes | Source image URL or family reference |
| `network` | Yes | VPC network name |
| `subnetwork` | Yes | Subnetwork name |
| `user_data` | No | Startup script |

#### Load Balancer

Uses Network Endpoint Groups (NEGs) for load balancing.

| Key | Required | Description |
|-----|----------|-------------|
| `neg_name` | Yes | Network Endpoint Group name |
| `port` | Yes | Endpoint port |

#### Discovery

Uses Compute Engine instance labels. Instances with label `aether-cluster=<cluster_name>` are discovered as peers.

| Key | Required | Description |
|-----|----------|-------------|
| `cluster_name` | Yes | Label value for cluster membership |
| `poll_interval_ms` | No | Discovery poll interval in ms |

#### Secrets

Uses GCP Secret Manager. The secret path is used as the Secret Manager secret name.

#### Full Example

```toml
[cloud]
provider = "gcp"

[cloud.credentials]
project_id = "my-project"
zone = "us-central1-a"
service_account_email = "aether@my-project.iam.gserviceaccount.com"
private_key_pem = "${env:GCP_PRIVATE_KEY}"

[cloud.compute]
machine_type = "e2-medium"
source_image = "projects/ubuntu-os-cloud/global/images/family/ubuntu-2404-lts-amd64"
network = "default"
subnetwork = "default"
user_data = "#!/bin/bash\napt-get install -y openjdk-21-jre"

[cloud.load_balancer]
neg_name = "aether-neg"
port = "8090"

[cloud.discovery]
cluster_name = "production"
poll_interval_ms = "15000"
```

### Azure

Provider name: `azure`

Authentication uses service principal (OAuth2 client credentials). Azure requires dual OAuth2 tokens -- one for ARM management APIs and one for Key Vault -- both handled internally by the `org.pragmatica.cloud.azure` HTTP client.

#### Credentials

| Key | Required | Description |
|-----|----------|-------------|
| `tenant_id` | Yes | Azure AD tenant ID |
| `client_id` | Yes | Service principal client ID |
| `client_secret` | Yes | Service principal client secret |
| `subscription_id` | Yes | Azure subscription ID |
| `resource_group` | Yes | Resource group name |
| `location` | Yes | Azure region (e.g., `eastus`) |

#### Compute

| Key | Required | Description |
|-----|----------|-------------|
| `vm_size` | Yes | VM size (e.g., `Standard_B2s`) |
| `image` | Yes | VM image reference (publisher:offer:sku:version) |
| `admin_username` | Yes | VM admin username |
| `ssh_public_key` | Yes | SSH public key for VM access |
| `vnet_subnet_id` | Yes | Full ARM resource ID of the VNet subnet |
| `user_data` | No | Custom data for cloud-init |

#### Load Balancer

Uses Azure Load Balancer with backend address pools.

| Key | Required | Description |
|-----|----------|-------------|
| `load_balancer_name` | Yes | Azure Load Balancer name |
| `backend_pool_name` | Yes | Backend address pool name |
| `vnet_id` | Yes | VNet resource ID for backend pool membership |

#### Discovery

Uses Azure Resource Graph queries. VMs with tag `aether-cluster=<cluster_name>` in the configured resource group are discovered as peers.

| Key | Required | Description |
|-----|----------|-------------|
| `cluster_name` | Yes | Tag value for cluster membership |
| `poll_interval_ms` | No | Discovery poll interval in ms |

#### Secrets

Uses Azure Key Vault. The secret path format is `vaultName/secretName` -- the vault name and secret name are extracted from the path.

#### Full Example

```toml
[cloud]
provider = "azure"

[cloud.credentials]
tenant_id = "${env:AZURE_TENANT_ID}"
client_id = "${env:AZURE_CLIENT_ID}"
client_secret = "${env:AZURE_CLIENT_SECRET}"
subscription_id = "${env:AZURE_SUBSCRIPTION_ID}"
resource_group = "aether-rg"
location = "eastus"

[cloud.compute]
vm_size = "Standard_B2s"
image = "Canonical:0001-com-ubuntu-server-jammy:24_04-lts:latest"
admin_username = "aether"
ssh_public_key = "${env:SSH_PUBLIC_KEY}"
vnet_subnet_id = "/subscriptions/xxx/resourceGroups/aether-rg/providers/Microsoft.Network/virtualNetworks/aether-vnet/subnets/default"
user_data = "#!/bin/bash\napt-get install -y openjdk-21-jre"

[cloud.load_balancer]
load_balancer_name = "aether-lb"
backend_pool_name = "aether-backend"
vnet_id = "/subscriptions/xxx/resourceGroups/aether-rg/providers/Microsoft.Network/virtualNetworks/aether-vnet"

[cloud.discovery]
cluster_name = "production"
poll_interval_ms = "15000"
```

## Features

### Auto-Heal

The CDM (ClusterDeploymentManager) monitors cluster size and automatically provisions replacement nodes when a deficit is detected. The `ComputeProvider` facet handles instance creation with proper tagging, network configuration, and user data injection. The `NodeLifecycleManager` uses tag-based server lookup for instance termination.

### Peer Discovery

Tag/label-based discovery replaces static peer lists in cloud deployments. Each provider queries its respective API (Hetzner labels, AWS EC2 tags, GCP instance labels, Azure Resource Graph tags) to find instances tagged with the configured `cluster_name`. The discovery provider polls at the configured interval and automatically registers/deregisters peers.

Configure via `[cloud.discovery]`:
```toml
[cloud.discovery]
cluster_name = "production"
poll_interval_ms = "15000"
```

### Load Balancer Integration

When `[cloud.load_balancer]` is configured, Aether automatically manages load balancer membership:

- **Registration** -- nodes are added to the load balancer target/backend pool on join
- **Deregistration** -- nodes are removed from the pool on departure
- **Reconciliation** -- on leader change, the new leader reconciles LB state
- **State query** -- `loadBalancerInfo()` returns current LB targets and health

Each provider uses its native LB mechanism:
- Hetzner: Load Balancer target management
- AWS: ELBv2 target group registration (with drain support via `deregisterWithDrain`)
- GCP: Network Endpoint Group endpoint management
- Azure: Backend address pool membership via ARM API

### Secrets Resolution

Config values using `${secrets:path}` syntax are resolved at startup via the provider's secrets backend.

| Provider | Backend | Path Format |
|----------|---------|-------------|
| Hetzner | Environment variables | `${secrets:key}` resolves `AETHER_SECRET_KEY` |
| AWS | AWS Secrets Manager | `${secrets:secret-name}` |
| GCP | GCP Secret Manager | `${secrets:secret-name}` |
| Azure | Azure Key Vault | `${secrets:vaultName/secretName}` |

The `CachingSecretsProvider` wraps all secrets backends with an in-memory TTL cache (configurable, evicts expired entries on access) to avoid repeated API calls for frequently accessed secrets.

### CDM Terminate-on-Drain

After a graceful drain completes (all slices evicted respecting disruption budgets), the CDM terminates the underlying cloud instance via the `ComputeProvider`. The node is identified by its `aether-node-id` tag/label. This prevents billing for drained VMs that are no longer serving traffic.

## Architecture

### ServiceLoader SPI

Each provider module contains a `META-INF/services/org.pragmatica.aether.environment.EnvironmentIntegrationFactory` file pointing to its factory class. At startup, `Main.wireCloudIfConfigured` reads the `[cloud]` config, calls `EnvironmentIntegrationFactory.createFromConfig(cloudConfig)`, which uses ServiceLoader to find the factory matching the configured provider name.

### Faceted Design

`EnvironmentIntegration` exposes four optional facets via `Option<T>`:

- `compute()` -- `ComputeProvider` for instance lifecycle
- `secrets()` -- `SecretsProvider` for secret resolution
- `loadBalancer()` -- `LoadBalancerProvider` for LB target management
- `discovery()` -- `DiscoveryProvider` for peer discovery

Each provider returns only the facets it supports. The rest of Aether checks for facet presence before using cloud features, so partial implementations work correctly.

### CloudConfig

The generic `CloudConfig` record holds provider name plus five string maps (`credentials`, `compute`, `loadBalancer`, `discovery`, `secrets`). Each factory knows how to interpret its provider-specific keys from these maps. This keeps the config layer provider-agnostic.
