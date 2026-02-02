# Cloud Infrastructure Management Design

Status: **PLANNED** (not yet implemented)

## Overview

This document describes the planned design for multi-cloud infrastructure management in Aether, enabling deployment to AWS, GCP, Azure, Oracle Cloud, and DigitalOcean.

---

## Current State

### Docker Infrastructure: Development Ready

| Component | Status | Notes |
|-----------|--------|-------|
| `docker/aether-node/Dockerfile` | Ready | eclipse-temurin:25-alpine, proper layering |
| `docker/aether-forge/Dockerfile` | Ready | Simulator container |
| `docker/docker-compose.yml` | Ready | 3-node cluster, healthchecks, networking |

### Generator Infrastructure: Extensible

| Generator | Location | Output |
|-----------|----------|--------|
| `LocalGenerator` | `aether-setup/.../generators/` | Shell scripts |
| `DockerGenerator` | `aether-setup/.../generators/` | docker-compose.yml |
| `KubernetesGenerator` | `aether-setup/.../generators/` | K8s manifests |

Pattern: `Generator.generate(AetherConfig, Path) -> Result<GeneratorOutput>`

### ManagementServer APIs

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/deploy` | POST | Deploy slice |
| `/api/scale` | POST | Scale instances |
| `/api/undeploy` | POST | Undeploy slice |
| `/api/blueprint` | POST | Apply blueprint |
| `/api/nodes` | GET | List nodes |
| `/health` | GET | Health check |

---

## Gap Analysis

| Category | Gap | Priority |
|----------|-----|----------|
| Cloud Generators | No AWS/GCP/Azure generators | HIGH |
| Infrastructure as Code | No Terraform integration | HIGH |
| Container Registry | No ECR/GCR/ACR push scripts | MEDIUM |
| Load Balancers | No cloud LB configuration | HIGH |
| DNS | No Route53/Cloud DNS integration | MEDIUM |
| VPC/Networking | No cloud networking templates | HIGH |

---

## Proposed Architecture

### Two-Layer Approach

```
+------------------------------------------------------------------+
|  Layer 2: Runtime Cloud Management (NEW)                          |
|  - Start/stop VMs dynamically                                     |
|  - Integrate with load balancers                                  |
|  - Update DNS records                                             |
|  - Triggered by: Controller decisions, API calls                  |
+------------------------------------------------------------------+
                              ^
                    Cloud Provider SDK
                              ^
+------------------------------------------------------------------+
|  Layer 1: Static Infrastructure Generation (EXTEND)               |
|  - Generate Terraform/CloudFormation/ARM templates                |
|  - VPC, subnets, security groups                                  |
|  - Container registry setup                                       |
|  - Initial cluster bootstrap                                      |
+------------------------------------------------------------------+
```

### Layer 1: Infrastructure Generators

Extend existing `Generator` pattern:

```
aether-setup/
  generators/
    Generator.java              # Existing interface
    LocalGenerator.java         # Existing
    DockerGenerator.java        # Existing
    KubernetesGenerator.java    # Existing
    aws/
      TerraformAwsGenerator.java    # VPC, ECS/EKS, ALB
    gcp/
      TerraformGcpGenerator.java    # VPC, GKE, Cloud LB
    azure/
      TerraformAzureGenerator.java  # VNet, AKS, Azure LB
    digitalocean/
      TerraformDoGenerator.java     # VPC, K8s, LB
```

Output per generator:
- `main.tf` - Provider config, networking, compute
- `variables.tf` - Input variables
- `outputs.tf` - Cluster endpoints
- `aether.tfvars` - Values from AetherConfig
- `apply.sh` / `destroy.sh` - Wrapper scripts

### Layer 2: Runtime Cloud Manager

New module for dynamic cloud operations:

```
aether-cloud/ (NEW MODULE)
  CloudManager.java           # Interface for cloud operations
  CloudError.java             # Error types
  CloudConfig.java            # Cloud-specific config
  aws/
    AwsCloudManager.java      # AWS SDK v2 implementation
  gcp/
    GcpCloudManager.java      # GCP SDK implementation
  azure/
    AzureCloudManager.java    # Azure SDK implementation
  digitalocean/
    DoCloudManager.java       # DigitalOcean API
```

CloudManager Interface:
```java
public interface CloudManager {
    // Node lifecycle
    Promise<NodeInstance> createNode(NodeSpec spec);
    Promise<Unit> terminateNode(String instanceId);
    Promise<List<NodeInstance>> listNodes();

    // Load balancer
    Promise<Unit> registerTarget(String instanceId);
    Promise<Unit> deregisterTarget(String instanceId);

    // DNS (optional)
    Promise<Unit> updateDnsRecord(DnsRecord record);
}
```

### Integration with Controller

```
Controller (Layer 1 - Lizard Brain)
         |
         +-> Blueprint changes (scale slices within cluster)
         |
         +-> CloudManager.createNode() (scale cluster itself)
                    |
                    +-> AWS: EC2 RunInstances + ALB RegisterTargets
                    +-> GCP: Compute InsertInstance + LB AddBackend
                    +-> Azure: VM Create + LB AddBackend
```

---

## Implementation Plan

### Phase 1: AWS Terraform Generator (MVP)

Files to create:
- `aether-setup/.../aws/TerraformAwsGenerator.java`
- `aether-config/.../AwsConfig.java`

Templates to generate:
- VPC with public/private subnets
- ECS Fargate cluster (simpler than EKS initially)
- ALB with health checks
- ECR repository
- Security groups
- IAM roles

### Phase 2: Runtime Cloud Manager

New module: `aether-cloud`

Dependencies:
- `software.amazon.awssdk:ec2`
- `software.amazon.awssdk:elasticloadbalancingv2`

Integration:
- Add `CloudManager` to `AetherNode`
- Extend `Controller` to trigger cloud scaling
- Add `/api/cloud/*` endpoints to ManagementServer

### Phase 3: GCP/Azure/DO Support

Add generators and CloudManager implementations for other providers.

---

## Configuration Schema

```toml
# aether.toml

[cloud]
provider = "aws"  # aws | gcp | azure | digitalocean

[cloud.aws]
region = "us-west-2"
vpc_cidr = "10.0.0.0/16"
instance_type = "m6i.large"
min_nodes = 3
max_nodes = 10
ecr_repository = "aether-node"
certificate_arn = "arn:aws:acm:..."  # For HTTPS

[cloud.gcp]
project = "my-project"
region = "us-central1"
zone = "us-central1-a"
machine_type = "e2-standard-4"

[cloud.azure]
subscription_id = "..."
resource_group = "aether-rg"
location = "westus2"
vm_size = "Standard_D4s_v3"
```

---

## Key Design Decisions

### 1. Terraform vs Native SDKs for Initial Setup

**Decision**: Terraform for Layer 1 (initial setup)

Rationale:
- State management built-in
- Dry-run with `terraform plan`
- Mature ecosystem, multi-cloud
- Operators familiar with it
- Decoupled from Java runtime

### 2. SDK vs Terraform for Runtime Operations

**Decision**: Native SDKs for Layer 2 (runtime)

Rationale:
- Faster execution (no terraform init/apply cycle)
- Better integration with async Promise model
- More fine-grained control
- No external process dependency

### 3. Kubernetes-first vs VM-first

**Decision**: Both, starting with ECS/Fargate (simpler)

Rationale:
- ECS Fargate = no cluster management overhead
- EKS can use existing KubernetesGenerator
- VMs needed for non-container deployments
- User choice based on expertise

### 4. Multi-Cloud Abstraction Level

**Decision**: Thin abstraction, cloud-specific config

Rationale:
- Different clouds have different concepts
- Trying to abstract too much loses functionality
- Let users configure cloud-specific settings
- Common interface for operations, cloud-specific config

---

## Open Questions

1. **Compute model**: ECS/Fargate first, then EKS/GKE?
2. **IaC tool**: Generate .tf files vs use CDK?
3. **Provider priority**: AWS -> GCP -> Azure -> DigitalOcean?
4. **Runtime operations**: Controller-driven or API-only?
