# AWS Provider Implementation Sheet

## Overview

Amazon Web Services (AWS) is a Tier 1 cloud provider with the broadest service catalog and deepest API surface
of any major cloud. The AWS provider implementation covers all four SPI facets natively — unlike smaller providers,
AWS has first-class services for compute (EC2), load balancing (ELBv2 ALB/NLB), discovery (EC2 tags or Cloud Map),
and secrets (Secrets Manager / SSM Parameter Store).

## SDK

**AWS SDK for Java 2.x** (async client preferred for non-blocking integration with Promise).

| Artifact | Purpose |
|----------|---------|
| `software.amazon.awssdk:bom` | Version management (import as BOM in dependencyManagement) |
| `software.amazon.awssdk:ec2` | EC2 compute operations |
| `software.amazon.awssdk:elasticloadbalancingv2` | ALB/NLB operations |
| `software.amazon.awssdk:servicediscovery` | Cloud Map (optional, if chosen over tag-based) |
| `software.amazon.awssdk:secretsmanager` | Secrets Manager |
| `software.amazon.awssdk:ssm` | SSM Parameter Store |
| `software.amazon.awssdk:sts` | STS for cross-account assume-role |

Maven BOM import:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>software.amazon.awssdk</groupId>
      <artifactId>bom</artifactId>
      <version>${aws.sdk.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

## Module

`aether/environment/aws/`

---

```
# Provider: Amazon Web Services (AWS)
# Module: aether/environment/aws/
# Tier: 1
# Status: Planned

## Authentication Model
- Authentication method: IAM role (preferred on EC2/ECS/EKS) or access key + secret key (fallback)
- Credential source: Instance metadata (IMDS v2), environment variables (AWS_ACCESS_KEY_ID,
  AWS_SECRET_ACCESS_KEY, AWS_SESSION_TOKEN), TOML config `cloud.aws.access_key_id` /
  `cloud.aws.secret_access_key`, or `${secrets:aws/credentials}`
- Token refresh: Automatic (SDK DefaultCredentialsProvider handles IMDS rotation, STS session
  token refresh, and assume-role credential renewal transparently)
- Cross-account: STS AssumeRole for accessing resources in other AWS accounts
- Credential chain: SDK DefaultCredentialsProvider searches in order: environment variables,
  system properties, web identity token (EKS), EC2 instance profile, ECS container credentials

## API Mapping

### ComputeProvider

| SPI Method | Cloud API | Notes |
|------------|-----------|-------|
| provision(InstanceType) | EC2 RunInstances | Maps InstanceType to EC2 instance type (e.g., t3.medium). Requires: AMI ID, subnet, security group from config. Returns instance ID. Sets `aether-managed=true` tag via TagSpecifications in the same call. |
| provision(ProvisionSpec) | EC2 RunInstances | Full spec: spec.instanceSize maps to instance type, spec.imageId maps to AMI ID, spec.zone maps to subnet (AZ-specific), spec.userData for cloud-init. Spot instances via InstanceMarketOptions when spec.spot=true. IAM instance profile attached for node identity. |
| terminate(InstanceId) | EC2 TerminateInstances | Instance ID is `i-xxxxx` string. Terminal operation — instance cannot be recovered. Verify instance is in `running` or `stopped` state before terminating. |
| restart(InstanceId) | EC2 RebootInstances | Native reboot action. Does not change instance ID or public IP (if Elastic IP). No-op if instance is not running. |
| listInstances() | EC2 DescribeInstances with filter `tag:aether-managed=true` | Paginated via NextToken. Must handle pagination to get all instances. Filter by region implicitly (client is regional). Map EC2 Instance to SPI InstanceInfo. |
| instanceStatus(InstanceId) | EC2 DescribeInstances (single instance) | Map EC2 instance state to SPI status: `pending`->Provisioning, `running`->Running, `stopping`/`stopped`->Stopped, `shutting-down`/`terminated`->Terminated. Also check DescribeInstanceStatus for system/instance status checks. |
| applyTags(InstanceId, tags) | EC2 CreateTags | Tags are key-value pairs. AWS limit: 50 user tags per resource. Prefix Aether tags with `aether-` to avoid conflicts. |
| listInstances(TagSelector) | EC2 DescribeInstances with tag filters | Map TagSelector to EC2 Filter: `tag:key=value`. Multiple filters are AND-ed. Paginated. |

### LoadBalancerProvider

| SPI Method | Cloud API | Notes |
|------------|-----------|-------|
| onRouteChanged(RouteChange) | ELBv2 RegisterTargets | Register instance IP:port in target group. One target group per service/port combination. ALB for HTTP (layer 7 routing, path/host rules), NLB for TCP (layer 4, raw throughput). |
| onNodeRemoved(String) | ELBv2 DeregisterTargets | Remove target from target group. Connection draining is automatic — configured via target group's `deregistration_delay.timeout_seconds` attribute (default 300s). |
| reconcile(LoadBalancerState) | ELBv2 DescribeTargetHealth + diff | Fetch current targets via DescribeTargetHealth. Compute add/remove diff against desired state. Execute RegisterTargets / DeregisterTargets. |
| createLoadBalancer(spec) | ELBv2 CreateLoadBalancer + CreateTargetGroup + CreateListener | Multi-step: (1) CreateLoadBalancer (ALB or NLB, internal or internet-facing, subnets, security groups), (2) CreateTargetGroup (protocol, port, VPC, health check), (3) CreateListener (protocol, port, default action -> target group). Async — LB enters `provisioning` state, poll until `active`. |
| deleteLoadBalancer(id) | ELBv2 DeleteLoadBalancer + DeleteTargetGroup | Delete listener, then LB, then target group. Must delete in order due to dependencies. |
| loadBalancerInfo() | ELBv2 DescribeLoadBalancers + DescribeTargetGroups | Map to SPI LoadBalancerInfo: DNS name, scheme, state, target groups, listener config. |
| configureHealthCheck(config) | ELBv2 ModifyTargetGroup | Health check settings: protocol (HTTP/HTTPS/TCP), path, port, interval (5-300s), timeout (2-120s), healthy threshold (2-10), unhealthy threshold (2-10). ALB supports HTTP response code matching (e.g., "200-299"). |
| syncWeights(weightsByIp) | ELBv2 RegisterTargets (with updated weights) or weighted target groups | ALB/NLB support weighted routing via multiple target groups on a listener with ForwardConfig weights (1-999). Per-target weights are not supported — use target group splitting. For fine-grained control: create per-node target groups with appropriate weights on the listener rule. |
| deregisterWithDrain(ip, timeout) | ELBv2 DeregisterTargets + ModifyTargetGroupAttributes | Set `deregistration_delay.timeout_seconds` to match requested timeout, then deregister. Existing connections drain until timeout expires or all complete. Native support — no simulation needed. |
| configureTls(config) | ELBv2 CreateListener (HTTPS/TLS) + ACM certificate ARN | ALB: HTTPS listener with ACM certificate ARN. NLB: TLS listener. ACM handles certificate renewal automatically. Specify security policy (e.g., ELBSecurityPolicy-TLS13-1-2-2021-06). |

### DiscoveryProvider

| SPI Method | Cloud API | Notes |
|------------|-----------|-------|
| discoverPeers() | EC2 DescribeInstances with filter `tag:aether-cluster=<name>` | **Recommended approach: tag-based.** Filter by `aether-cluster` tag + `instance-state-name=running`. Extract private IPs from NetworkInterfaces. Simpler than Cloud Map, no additional service to manage. **Alternative:** Cloud Map DiscoverInstances (HTTP namespace) — better for ECS/EKS but adds complexity and cost. |
| watchPeers(callback) | Poll EC2 DescribeInstances at interval | No native push for EC2 tag changes. Poll-based with configurable interval (default 30s). Compare instance list between polls, invoke callback on diff. **Alternative:** CloudWatch Events -> EventBridge for EC2 state changes, but adds infrastructure complexity. |
| registerSelf(addr, metadata) | EC2 CreateTags (tag self) | Tag own instance with `aether-cluster=<name>`, `aether-role=<role>`, `aether-addr=<addr>`. Retrieve own instance ID from IMDS v2 (`http://169.254.169.254/latest/meta-data/instance-id`). Metadata stored as additional tags (prefix `aether-meta-`). |
| deregisterSelf() | EC2 DeleteTags | Remove `aether-cluster`, `aether-role`, `aether-addr`, and `aether-meta-*` tags from self. |

**Discovery approach comparison:**

| Criteria | EC2 Tag-Based | AWS Cloud Map |
|----------|---------------|---------------|
| Setup complexity | None (EC2 tags exist) | Namespace + service creation |
| Additional cost | Free (EC2 API calls only) | $0.10/instance/month + API charges |
| DNS resolution | No | Yes (private DNS) |
| Cross-service discovery | No (Aether-only) | Yes (general purpose) |
| JVM DNS caching issue | N/A | Yes (TTL caching problem) |
| **Recommendation** | **Primary** | Alternative for ECS/EKS environments |

### SecretsProvider

| SPI Method | Cloud API | Notes |
|------------|-----------|-------|
| resolveSecret(path) | Secrets Manager GetSecretValue or SSM GetParameter (SecureString) | Path prefix determines backend: `sm://secret-name` -> Secrets Manager, `ssm://parameter-path` -> SSM Parameter Store. Default: Secrets Manager. SSM is cheaper ($0.05/10K API calls vs $0.40/secret/month) but lacks rotation automation. |
| resolveSecretWithMetadata(path) | Secrets Manager DescribeSecret + GetSecretValue | Returns value + metadata: creation date, last rotation date, rotation enabled, version stages, tags. SSM: GetParameter returns value + version, last modified date, data type. |
| resolveSecrets(paths) | Secrets Manager BatchGetSecretValue | Batch API retrieves up to 20 secrets in one call. Reduces API calls and latency. For SSM: GetParameters (batch, up to 10). Mix of SM and SSM paths: split into two batch calls. |
| watchRotation(path, callback) | Secrets Manager DescribeSecret (poll rotation date) or CloudWatch Events | Poll DescribeSecret.LastRotatedDate at interval. **Better:** CloudWatch Events rule on `aws.secretsmanager` `RotationSucceeded` event, pushed to SQS -> poll SQS. Rotation configured per-secret with Lambda function. |

**Secrets backend comparison:**

| Criteria | Secrets Manager | SSM Parameter Store |
|----------|----------------|---------------------|
| Cost | $0.40/secret/month + $0.05/10K API calls | Free tier: 10K params; $0.05/10K API calls |
| Rotation | Native (Lambda-based) | Manual or custom |
| Batch API | Yes (20/call) | Yes (10/call) |
| Max size | 64 KB | 8 KB (advanced: 64 KB) |
| Cross-account | Yes (resource policy) | No |
| **Use case** | Credentials, API keys, certificates | Config values, feature flags, non-rotating secrets |

## Rate Limits and Quotas

| API | Rate Limit | Quota | Notes |
|-----|-----------|-------|-------|
| EC2 RunInstances | Token bucket: 1000 resource tokens, refill 2/sec | 20 On-Demand instances per type (default) | Request limit increase via Service Quotas console |
| EC2 DescribeInstances | Token bucket: ~100 req/sec | N/A | Non-mutating; higher limit than mutating calls |
| EC2 TerminateInstances | Token bucket: mutating rate | N/A | Lower than describe; shared mutating bucket |
| EC2 CreateTags | Token bucket: mutating rate | 50 tags per resource | Shared with other mutating EC2 calls |
| ELBv2 non-mutating (Describe*) | 40 tokens, refill 10/sec | N/A | Per-account, per-region |
| ELBv2 mutating (Create*, Delete*, Modify*) | Lower than non-mutating | N/A | Resource-intensive actions (CreateLoadBalancer) have stricter limits |
| ELBv2 RegisterTargets/DeregisterTargets | Separate registration bucket | 1000 targets per target group | Throttled independently from other mutating actions |
| Secrets Manager GetSecretValue | 10,000 req/sec | N/A | Per-account, per-region |
| Secrets Manager DescribeSecret | 10,000 req/sec | N/A | Per-account, per-region |
| Secrets Manager ListSecrets | 100 req/sec | N/A | Per-account, per-region |
| SSM GetParameter | 1,000 req/sec (throughput) | 10,000 standard params | Higher throughput available at additional cost |

**Throttling strategy:** AWS SDK v2 includes built-in retry with exponential backoff and jitter (RetryPolicy).
Configure `numRetries` (default 3) and use `AdaptiveRetryStrategy` for automatic rate adjustment. Monitor
throttling via CloudWatch `ThrottledRequests` metric on EC2 and `HTTPCode_ELB_429` on ALB.

## Regional Considerations

| Feature | Regional? | Zone? | Notes |
|---------|-----------|-------|-------|
| Compute | Yes (region) | Yes (AZ) | Instances are AZ-specific. Subnet determines AZ. Spread across AZs for HA: use placement groups (spread strategy, max 7 per AZ) or simply provision into different subnets. Cross-AZ data transfer: $0.01/GB. |
| Load Balancer | Yes (region) | Yes (AZ) | ALB/NLB span multiple AZs (specify subnets from 2+ AZs). Cross-zone load balancing: enabled by default on ALB, disabled by default on NLB ($0.01/GB when enabled). |
| Discovery | Yes (region) | N/A | EC2 DescribeInstances is regional. For multi-region clusters: one discovery call per region, merge results. Cloud Map namespaces are regional. |
| Secrets | Yes (region) | N/A | Secrets are regional. For multi-region: use Secrets Manager replication (replica secrets in other regions). SSM parameters are regional, no built-in replication. |

## Provider-Specific Features

| Feature | Supported | Notes |
|---------|-----------|-------|
| Spot/Preemptible instances | Yes | RunInstances with InstanceMarketOptions (type=spot). 60-90% savings. 2-minute interruption notice via instance metadata. Implement spot interruption handler: poll `http://169.254.169.254/latest/meta-data/spot/instance-action`, trigger graceful drain on notice. Consider Spot Fleet or EC2 Fleet for mixed On-Demand + Spot. |
| Placement groups | Yes | Spread: max 7 instances per AZ (HA). Cluster: low-latency networking (same rack). Partition: large distributed workloads. Use spread for Aether clusters. |
| Custom images/snapshots | Yes | AMI-based. Create AMI from configured instance (CreateImage). Use for fast boot with pre-installed Aether runtime. Region-specific; copy across regions with CopyImage. |
| VPC/private networking | Yes | Required. All instances must be in a VPC. Subnets (public/private), security groups (firewall rules), NACLs. Aether cluster nodes should use private subnets with NAT gateway for outbound. Security group rules: allow intra-cluster traffic on Aether ports. |
| Weighted LB targets | Yes (via target groups) | No per-target weights. Use multiple target groups with weighted ForwardConfig on listener rule. Weights 1-999. Good for canary deployments and gradual traffic shifting. |
| LB health check customization | Yes | Full control: protocol, port, path, interval, timeout, thresholds (healthy/unhealthy), success codes. ALB supports gRPC health checks. |
| Native discovery service | Yes (Cloud Map) | Available but not recommended as primary. Use tag-based discovery for simplicity. Cloud Map adds cost and JVM DNS caching complications. |
| Secret rotation notification | Yes | Secrets Manager: native rotation via Lambda. CloudWatch Events on rotation success/failure. Rotation schedules configurable (days or cron expression). |
| IAM instance profiles | Yes | Attach IAM role to EC2 instance. No static credentials on the instance. Role grants permissions for EC2, ELBv2, Secrets Manager, SSM, Cloud Map APIs. **Strongly recommended** over access keys. |
| Auto-scaling integration | Future | EC2 Auto Scaling groups could replace direct RunInstances for managed scaling. Out of scope for initial implementation. |
| EBS volume management | Future | Persistent storage for stateful nodes. Out of scope for initial implementation. |

## Configuration

TOML configuration for the AWS provider:

```toml
[cloud.aws]
region = "us-east-1"                          # Required. AWS region.
# Authentication — prefer IAM role (no config needed on EC2).
# Fallback: static credentials.
# access_key_id = "${secrets:aws/access_key_id}"
# secret_access_key = "${secrets:aws/secret_access_key}"
# assume_role_arn = "arn:aws:iam::123456789012:role/AetherRole"  # Cross-account

[cloud.aws.compute]
default_instance_type = "t3.medium"           # Default EC2 instance type
ami_id = "ami-0abcdef1234567890"              # AMI with Aether runtime pre-installed
subnet_ids = ["subnet-abc123", "subnet-def456"]  # AZ-distributed subnets
security_group_ids = ["sg-12345678"]          # Aether cluster security group
iam_instance_profile = "AetherNodeProfile"    # IAM instance profile name
key_name = "aether-cluster-key"               # SSH key pair (optional, for debugging)
spot_enabled = false                          # Use spot instances
spot_max_price = ""                           # Max spot price (empty = on-demand price cap)

[cloud.aws.load_balancer]
type = "alb"                                  # "alb" (HTTP) or "nlb" (TCP)
scheme = "internal"                           # "internal" or "internet-facing"
subnet_ids = ["subnet-abc123", "subnet-def456"]
security_group_ids = ["sg-87654321"]          # ALB only; NLB does not use security groups
deregistration_delay_seconds = 30             # Connection drain timeout
health_check_path = "/health"
health_check_interval_seconds = 15
health_check_healthy_threshold = 2
health_check_unhealthy_threshold = 3
tls_certificate_arn = ""                      # ACM certificate ARN (empty = no TLS termination)
tls_security_policy = "ELBSecurityPolicy-TLS13-1-2-2021-06"

[cloud.aws.discovery]
method = "tags"                               # "tags" (recommended) or "cloudmap"
poll_interval_seconds = 30                    # Peer discovery poll interval
cluster_tag_key = "aether-cluster"            # Tag key for cluster membership
# Cloud Map settings (only if method = "cloudmap")
# cloudmap_namespace = "aether.local"
# cloudmap_service = "cluster"

[cloud.aws.secrets]
backend = "secretsmanager"                    # "secretsmanager" or "ssm"
cache_ttl_seconds = 300                       # Local cache TTL for secret values
rotation_poll_interval_seconds = 60           # Poll interval for rotation changes
```

## IAM Policy (Minimum Required)

The IAM role attached to Aether nodes needs these permissions:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "AetherCompute",
      "Effect": "Allow",
      "Action": [
        "ec2:RunInstances",
        "ec2:TerminateInstances",
        "ec2:RebootInstances",
        "ec2:DescribeInstances",
        "ec2:DescribeInstanceStatus",
        "ec2:CreateTags",
        "ec2:DeleteTags"
      ],
      "Resource": "*"
    },
    {
      "Sid": "AetherLoadBalancer",
      "Effect": "Allow",
      "Action": [
        "elasticloadbalancing:CreateLoadBalancer",
        "elasticloadbalancing:DeleteLoadBalancer",
        "elasticloadbalancing:DescribeLoadBalancers",
        "elasticloadbalancing:CreateTargetGroup",
        "elasticloadbalancing:DeleteTargetGroup",
        "elasticloadbalancing:DescribeTargetGroups",
        "elasticloadbalancing:DescribeTargetHealth",
        "elasticloadbalancing:ModifyTargetGroup",
        "elasticloadbalancing:ModifyTargetGroupAttributes",
        "elasticloadbalancing:RegisterTargets",
        "elasticloadbalancing:DeregisterTargets",
        "elasticloadbalancing:CreateListener",
        "elasticloadbalancing:DeleteListener",
        "elasticloadbalancing:ModifyListener"
      ],
      "Resource": "*"
    },
    {
      "Sid": "AetherSecrets",
      "Effect": "Allow",
      "Action": [
        "secretsmanager:GetSecretValue",
        "secretsmanager:DescribeSecret",
        "secretsmanager:BatchGetSecretValue",
        "secretsmanager:ListSecrets",
        "ssm:GetParameter",
        "ssm:GetParameters"
      ],
      "Resource": "*"
    },
    {
      "Sid": "AetherPassRole",
      "Effect": "Allow",
      "Action": "iam:PassRole",
      "Resource": "arn:aws:iam::*:role/AetherNodeProfile",
      "Condition": {
        "StringEquals": {
          "iam:PassedToService": "ec2.amazonaws.com"
        }
      }
    }
  ]
}
```

**Note:** Production deployments should scope `Resource` to specific ARNs rather than `"*"`.

## Estimated Effort

| Facet | Effort | Notes |
|-------|--------|-------|
| Compute | 5-7 days | EC2 RunInstances/Terminate/Describe/Reboot, tag operations, spot support, AMI config, subnet/AZ placement, IAM profile attachment. Well-documented SDK with async client. |
| Load Balancer | 5-7 days | Multi-step LB lifecycle (LB + target group + listener), ALB and NLB support, health check config, weighted target groups for traffic splitting, TLS with ACM, connection draining. Most complex facet due to ELBv2 multi-resource model. |
| Discovery | 2-3 days | Tag-based DescribeInstances with filters, poll-based watch, IMDS v2 for self-registration. Straightforward. Cloud Map alternative: add 2 days if needed. |
| Secrets | 3-4 days | Dual backend (Secrets Manager + SSM Parameter Store), batch resolution, local caching, rotation watch via polling or CloudWatch Events. |
| Total | **15-21 days** | Full-featured Tier 1 provider. Parallelizable: Compute and LB can develop concurrently with Discovery and Secrets. Realistic timeline with 2 developers: 10-12 days. |
```

## References

### AWS Documentation
- [EC2 API Reference](https://docs.aws.amazon.com/AWSEC2/latest/APIReference/) - RunInstances, TerminateInstances, DescribeInstances, CreateTags
- [ELBv2 API Reference](https://docs.aws.amazon.com/elasticloadbalancing/latest/APIReference/) - ALB/NLB operations, target groups, listeners
- [Secrets Manager API Reference](https://docs.aws.amazon.com/secretsmanager/latest/apireference/) - GetSecretValue, BatchGetSecretValue, DescribeSecret
- [SSM Parameter Store API Reference](https://docs.aws.amazon.com/systems-manager/latest/APIReference/) - GetParameter, GetParameters
- [AWS Cloud Map API Reference](https://docs.aws.amazon.com/cloud-map/latest/api/) - DiscoverInstances (alternative discovery)
- [EC2 API Throttling](https://docs.aws.amazon.com/ec2/latest/devguide/ec2-api-throttling.html) - Token bucket model, rate limits
- [ELB API Throttling](https://docs.aws.amazon.com/elasticloadbalancing/latest/userguide/elb-api-throttling.html) - Non-mutating vs mutating vs registration buckets
- [Secrets Manager Quotas](https://docs.aws.amazon.com/secretsmanager/latest/userguide/reference_limits.html) - 10,000 req/sec for GetSecretValue

### SDK
- [AWS SDK for Java 2.x Developer Guide](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/) - Setup, async clients, credential providers
- [AWS SDK for Java 2.x Maven BOM](https://mvnrepository.com/artifact/software.amazon.awssdk/bom) - Latest version
- [AWS SDK for Java 2.x GitHub](https://github.com/aws/aws-sdk-java-v2) - Source, examples, issues

### Internal References
- [Cloud Integration SPI Spec](cloud-integration-spi-spec.md) - SPI interface definitions, template, Hetzner reference
- [Hetzner Provider Sheet](cloud-integration-spi-spec.md#8-hetzner-provider-sheet-reference) - Section 8, reference implementation
