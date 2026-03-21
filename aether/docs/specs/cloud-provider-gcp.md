# Provider: Google Cloud Platform (GCP)
# Module: aether/environment/gcp/
# Tier: 1
# Status: Planned

## Overview

Google Cloud Platform is a Tier 1 cloud provider offering global infrastructure with fine-grained
IAM, managed load balancing, and a native secrets service. GCP's resource model is hierarchical:
Organization > Folder > Project > Resources. All Aether operations target a single GCP project.

**Java SDK artifacts:**
- `com.google.cloud:google-cloud-compute` (Compute Engine)
- `com.google.cloud:google-cloud-secretmanager` (Secret Manager)
- `com.google.cloud:libraries-bom` (version management via BOM)

All client libraries use gRPC under the hood and support Application Default Credentials (ADC).

---

## Authentication Model
- Authentication method: Service account (preferred on GCE/GKE), Workload Identity Federation (non-GCP), or service account key file (JSON fallback)
- Credential source: Instance metadata server (automatic on GCE/GKE) | `GOOGLE_APPLICATION_CREDENTIALS` env var pointing to key file | TOML config `cloud.gcp.credentials_file` | Workload Identity Federation config
- Token refresh: Automatic (ADC handles OAuth2 token refresh transparently; metadata server tokens have ~3600s TTL and are refreshed by the SDK)

### Application Default Credentials (ADC) Resolution Order
1. `GOOGLE_APPLICATION_CREDENTIALS` environment variable (path to JSON key file)
2. User credentials from `gcloud auth application-default login`
3. Attached service account (GCE metadata server at `169.254.169.254`)
4. Workload Identity Federation (for non-GCP environments)

**Recommendation:** Use attached service account on GCE. Use Workload Identity Federation for hybrid deployments. Avoid distributing JSON key files in production.

---

## API Mapping

### ComputeProvider

| SPI Method | Cloud API | Notes |
|------------|-----------|-------|
| provision(InstanceType) | `POST compute.instances.insert` (project, zone) | Maps InstanceType to machine type (e.g., `e2-standard-4`). Requires zone, network, subnet, source image. Returns Operation; poll `operations.get` until DONE. |
| provision(ProvisionSpec) | `POST compute.instances.insert` (project, zone) | Full spec: `spec.instanceSize` -> machine type, `spec.imageId` -> source image, `spec.spot` -> `scheduling.provisioningModel=SPOT`. Attach service account for node identity. Set labels from spec.tags. |
| terminate(InstanceId) | `DELETE compute.instances.delete` (project, zone, instance) | Returns Operation. Instance name is the ID. Must resolve zone from instance metadata or tag convention. |
| restart(InstanceId) | `POST compute.instances.reset` (project, zone, instance) | Hard reset (like pressing reset button). For graceful reboot, could use `instances.stop` + `instances.start` sequence instead. |
| listInstances() | `GET compute.instances.aggregatedList` (project) | Returns instances across all zones in the project. Paginated via `pageToken`. Filter by `labels.aether-managed=true` to scope to Aether instances only. |
| instanceStatus(InstanceId) | `GET compute.instances.get` (project, zone, instance) | Returns instance resource with `status` field: PROVISIONING, STAGING, RUNNING, STOPPING, STOPPED, TERMINATED, SUSPENDED. Map to Aether InstanceStatus enum. |
| applyTags(InstanceId, tags) | `POST compute.instances.setLabels` (project, zone, instance) | GCP uses `labels` (key-value metadata, max 64 per resource). Requires current `labelFingerprint` for optimistic concurrency; read-then-write pattern. |
| listInstances(TagSelector) | `GET compute.instances.aggregatedList` with `filter` | Filter syntax: `labels.aether-cluster=mycluster AND labels.aether-role=worker`. Supports AND/OR/NOT operators. Single API call across all zones. |

**Machine Type Families (InstanceType mapping):**

| Aether Size | GCP Machine Type | vCPUs | RAM | Use Case |
|-------------|-----------------|-------|-----|----------|
| SMALL | `e2-standard-2` | 2 | 8 GB | Dev/test, light workloads |
| MEDIUM | `e2-standard-4` | 4 | 16 GB | General purpose |
| LARGE | `n2-standard-8` | 8 | 32 GB | Production workloads |
| XLARGE | `c2-standard-16` | 16 | 64 GB | Compute-intensive |
| MEMORY | `m2-ultramem-208` | 208 | 5.75 TB | Memory-intensive (rarely needed) |

### LoadBalancerProvider

| SPI Method | Cloud API | Notes |
|------------|-----------|-------|
| onRouteChanged(RouteChange) | `POST compute.networkEndpointGroups.attachNetworkEndpoints` | Use zonal NEG for fine-grained IP:port target management. Add endpoint with `{ipAddress, port}`. Preferred over instance groups for Aether because NEGs allow individual endpoint control. |
| onNodeRemoved(String) | `POST compute.networkEndpointGroups.detachNetworkEndpoints` | Remove endpoint by IP:port from NEG. |
| reconcile(LoadBalancerState) | `GET compute.networkEndpointGroups.listNetworkEndpoints` + diff | List current NEG endpoints, compute add/remove diff against desired state, apply changes. Also reconcile health check config and backend service weights. |
| createLoadBalancer(spec) | Multiple calls (see below) | GCP LB is a composite resource. Creation requires: (1) health check, (2) NEG (zonal), (3) backend service referencing NEG, (4) URL map, (5) target HTTP(S) proxy, (6) global forwarding rule with IP. See creation sequence below. |
| deleteLoadBalancer(id) | Multiple calls (reverse order) | Delete: forwarding rule -> target proxy -> URL map -> backend service -> NEG -> health check. Must delete in dependency order. |
| loadBalancerInfo() | `GET compute.backendServices.get` + `GET compute.globalForwardingRules.get` | Aggregate info from backend service (backends, health), forwarding rule (IP, port), and URL map (routing). |
| configureHealthCheck(config) | `PATCH compute.healthChecks.patch` | Supports HTTP, HTTPS, TCP health checks. Fields: `port`, `requestPath`, `checkIntervalSec`, `timeoutSec`, `healthyThreshold`, `unhealthyThreshold`. |
| syncWeights(weightsByIp) | `PATCH compute.backendServices.patch` (maxRatePerEndpoint per backend) | GCP supports weighted traffic splitting via `maxRatePerEndpoint` on backend service backends. For NEGs, set capacity scaler per backend. More granular than Hetzner. |
| deregisterWithDrain(ip, timeout) | `PATCH compute.backendServices.patch` (connectionDraining.drainingTimeoutSec) + detach | GCP has native connection draining. Set `connectionDraining.drainingTimeoutSec` on backend service, then detach endpoint. GCP drains existing connections for the specified duration. |
| configureTls(config) | `POST compute.sslCertificates.insert` + `PATCH compute.targetHttpsProxies.setSslCertificates` | Supports Google-managed certificates (automatic provisioning and renewal via `compute.sslCertificates.insert` with `managed` type) and self-managed (uploaded PEM). |

**Load Balancer Creation Sequence (createLoadBalancer):**
1. `compute.healthChecks.insert` - Create health check
2. `compute.networkEndpointGroups.insert` - Create zonal NEG (per zone with instances)
3. `compute.backendServices.insert` - Create backend service, attach NEGs as backends
4. `compute.urlMaps.insert` - Create URL map pointing to backend service
5. `compute.targetHttpProxies.insert` (or `targetHttpsProxies`) - Create target proxy
6. `compute.globalForwardingRules.insert` - Create forwarding rule (allocates external IP)

**Load Balancer Topology Options:**

| Type | Scope | Layer | Use Case |
|------|-------|-------|----------|
| External HTTP(S) LB | Global | L7 | Public-facing Aether endpoints |
| Regional Internal TCP/UDP LB | Regional | L4 | Inter-cluster communication |
| Internal HTTP(S) LB | Regional | L7 | Internal service mesh |

**Recommendation:** Use External HTTP(S) LB (global) for ingress, Regional Internal TCP/UDP LB for cluster-internal traffic. NEG-based backends for both.

### DiscoveryProvider

| SPI Method | Cloud API | Notes |
|------------|-----------|-------|
| discoverPeers() | `GET compute.instances.aggregatedList` with filter `labels.aether-cluster=<name>` | Returns all instances across zones matching cluster label. Extract `networkInterfaces[0].networkIP` for private IPs. Single API call, no zone enumeration needed. |
| watchPeers(callback) | Poll `compute.instances.aggregatedList` at configurable interval | No native push API. Poll-based with configurable interval (default: 15s). Detect changes by comparing instance sets. Efficient: filter reduces response size. |
| registerSelf(addr, metadata) | `POST compute.instances.setLabels` on self | Set labels: `aether-cluster=<name>`, `aether-role=<role>`, `aether-port=<port>`. Requires self-identification via metadata server (`GET http://metadata.google.internal/computeMetadata/v1/instance/name`). |
| deregisterSelf() | `POST compute.instances.setLabels` on self | Remove aether-* labels from self. Preserves non-Aether labels. Requires read-then-write with `labelFingerprint`. |

**Discovery approach:** Label-based discovery on Compute Engine instances. This is simpler and more consistent with other providers (Hetzner, AWS) than GCP Service Directory. Service Directory is an option for hybrid/multi-cloud but adds complexity.

**Self-identification via metadata server:**
```
GET http://metadata.google.internal/computeMetadata/v1/instance/name
GET http://metadata.google.internal/computeMetadata/v1/instance/zone
GET http://metadata.google.internal/computeMetadata/v1/project/project-id
Headers: Metadata-Flavor: Google
```

### SecretsProvider

| SPI Method | Cloud API | Notes |
|------------|-----------|-------|
| resolveSecret(path) | `secretmanager.versions.access` (projects/*/secrets/*/versions/latest) | Path format: `gcp/secret-name` maps to `projects/{project}/secrets/{secret-name}/versions/latest`. Returns payload bytes. Max secret size: 64 KiB. |
| resolveSecretWithMetadata(path) | `secretmanager.secrets.get` + `secretmanager.versions.access` | Get secret metadata (labels, create time, rotation config) and payload in two calls. Map to Aether SecretMetadata. |
| resolveSecrets(paths) | Batch `secretmanager.versions.access` (parallel) | No native batch API. Issue parallel requests. SDK supports async calls. Respect rate limits. |
| watchRotation(path, callback) | Pub/Sub subscription on secret rotation topic | Configure secret with `rotation.next_rotation_time` and `topics` (Pub/Sub topic ARN). On rotation, Secret Manager publishes to topic. Aether subscribes via Pub/Sub pull subscription. Alternative: poll `secretmanager.versions.list` for new versions. |

**Secret path mapping:**
- Aether path: `${secrets:gcp/database-password}` or `${secrets:gcp/database-password@3}` (pinned version)
- GCP resource name: `projects/{project}/secrets/database-password/versions/latest` or `projects/{project}/secrets/database-password/versions/3`
- The `gcp/` prefix is stripped; remainder is the secret name; `@N` suffix pins to version N.

**Rotation workflow:**
1. Configure secret with rotation schedule and Pub/Sub topic
2. Cloud Functions (or Cloud Run) triggers on schedule to generate new secret value
3. New version added via `secretmanager.versions.addSecretVersion`
4. Pub/Sub notification fires to configured topic
5. Aether SecretsProvider receives notification, invokes callback with new value

---

## Rate Limits and Quotas

| API | Rate Limit | Quota | Notes |
|-----|-----------|-------|-------|
| Compute Engine (read operations) | 20 req/sec/project (default) | Varies by method group | Rate quotas are per-project, per-method-group. `instances.list`/`aggregatedList` share a read quota. Increasable via Cloud Quotas console. |
| Compute Engine (mutate operations) | 20 req/sec/project (default) | Varies by method group | `instances.insert`, `instances.delete`, `instances.setLabels` share a mutate quota. Throttle provisioning bursts. |
| Load Balancing (backend service ops) | 20 req/sec/project (default) | Combined with Compute Engine quota | Backend services, NEGs, health checks, forwarding rules all under Compute Engine API quota. |
| Secret Manager (access) | 90,000 req/min/project | Soft limit, increasable | High throughput. Unlikely to be a bottleneck for Aether. |
| Secret Manager (mutations) | 6,000 req/min/project | Soft limit, increasable | `addSecretVersion`, `updateSecret`, etc. |
| Pub/Sub (pull) | 10,000 req/sec/project | Per-subscription | For secret rotation notifications. Well within limits. |

**Error handling:**
- HTTP 429 (Too Many Requests): Exponential backoff with jitter. `Retry-After` header may be present.
- HTTP 403 (Quota Exceeded): Check `reason` field for `rateLimitExceeded` vs `quotaExceeded`. Rate limits are transient (retry); quota exceeded may require quota increase request.
- All Compute Engine mutate calls return `Operation` objects. Poll `operations.get` until `status=DONE`. Check `error` field on completion.

---

## Regional Considerations

| Feature | Regional? | Zone? | Notes |
|---------|-----------|-------|-------|
| Compute | No | Yes (zonal) | Instances live in zones (e.g., `us-central1-a`). Zone must be specified on create. Use multiple zones within a region for HA. |
| Load Balancer | Global or Regional | No | External HTTP(S) LB is global (anycast IP, spans all regions). Internal TCP/UDP LB is regional. NEGs are zonal but can be aggregated into a global backend service. |
| Discovery | Global (aggregatedList) | N/A | `aggregatedList` returns instances across all zones. Filter by label. No need to enumerate zones. |
| Secrets | Global | N/A | Secret Manager is a global service. Secrets are replicated automatically. Optional: restrict replication to specific regions for data residency. |

**Zone-aware placement for HA:**
- Provision Aether nodes across >= 2 zones in the same region (e.g., `us-central1-a`, `us-central1-b`, `us-central1-c`)
- Store zone in instance labels (`aether-zone=us-central1-a`) for topology-aware scheduling
- NEG-based LB naturally handles multi-zone backends

---

## Provider-Specific Features

| Feature | Supported | Notes |
|---------|-----------|-------|
| Spot/Preemptible instances | Yes | Set `scheduling.provisioningModel=SPOT` on instance creation. Spot VMs can be preempted with 30s notice. Use for non-critical workers only. Register preemption handler via metadata server (`/computeMetadata/v1/instance/preempted`). |
| Placement groups | No (not user-configurable) | GCP uses automatic spread placement. No explicit placement group API like AWS. Use sole-tenant nodes for dedicated hardware if needed. |
| Custom images/snapshots | Yes | Create custom image from disk snapshot (`compute.images.insert` from source snapshot). Use for fast Aether node boot with pre-baked JVM + application. |
| VPC/private networking | Yes | Instances in a VPC with auto-mode or custom subnets. Firewall rules control inter-node traffic. Use internal IPs for cluster communication. |
| Weighted LB targets | Yes | Backend service supports `capacityScaler` (0.0-1.0) per backend and `maxRatePerEndpoint` for rate-based distribution. More flexible than Hetzner. |
| LB health check customization | Yes | Configurable protocol (HTTP/HTTPS/TCP/gRPC), port, path, interval (5-300s), timeout, thresholds. Separate health check resource, reusable across backend services. |
| Native discovery service | Yes (Service Directory) | Available but not recommended for Aether. Label-based discovery via `instances.aggregatedList` is simpler and consistent with other providers. Service Directory adds cost and complexity. |
| Secret rotation notification | Yes | Native via Pub/Sub topics configured on secrets. Automatic rotation via Cloud Functions trigger. Pub/Sub delivers notification within seconds of rotation. |
| Google-managed SSL certificates | Yes | Automatic provisioning and renewal. No manual cert management. Requires DNS validation. Configure via `compute.sslCertificates.insert` with `type=MANAGED`. |
| Service account identity | Yes | Each Aether node runs with an attached service account. Scopes IAM permissions per-node. No need to distribute credentials. |

---

## Networking Configuration

**Firewall rules required for Aether cluster:**

| Rule | Direction | Protocol/Port | Source | Purpose |
|------|-----------|--------------|--------|---------|
| aether-internal | Ingress | TCP/7000-7010 | Cluster instances (by label) | Consensus, gossip, data replication |
| aether-management | Ingress | TCP/8080 | Operator IP ranges | Management API access |
| aether-health | Ingress | TCP/8080 | Health check ranges (130.211.0.0/22, 35.191.0.0/16) | GCP health check probes |
| aether-lb | Ingress | TCP/443 | 0.0.0.0/0 | External LB traffic (if public) |

**GCP health check source ranges** (must be allowed in firewall): `130.211.0.0/22` and `35.191.0.0/16`. These are fixed Google-owned ranges.

---

## Estimated Effort

| Facet | Effort | Notes |
|-------|--------|-------|
| Compute | 5-7 days | Full implementation from scratch. Operation polling, zone management, label-based tagging, Spot VM support, machine type mapping, metadata server integration. |
| Load Balancer | 6-8 days | Most complex facet. Composite resource creation (6-step sequence), NEG management, health checks, TLS with managed certs, connection draining, weight sync. GCP LB is significantly more complex than Hetzner. |
| Discovery | 2-3 days | Label-based discovery via aggregatedList. Poll-based watch. Self-registration via setLabels. Metadata server for self-identification. |
| Secrets | 3-4 days | Secret Manager access + versioning. Pub/Sub integration for rotation notifications. Path mapping with version pinning. |
| Total | **16-22 days** | New implementation, no existing code to extend. Compute + LB are the critical path. |

---

## Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| `com.google.cloud:google-cloud-compute` | via BOM | Compute Engine client (instances, NEGs, backend services, health checks, forwarding rules) |
| `com.google.cloud:google-cloud-secretmanager` | via BOM | Secret Manager client |
| `com.google.cloud:google-cloud-pubsub` | via BOM | Pub/Sub client for secret rotation notifications |
| `com.google.cloud:libraries-bom` | 26.x | Version management BOM (keeps all google-cloud-* versions aligned) |

---

## References

### GCP Documentation
- [Compute Engine REST API v1](https://cloud.google.com/compute/docs/reference/rest/v1) - Instance, NEG, backend service, health check APIs
- [Compute Engine Rate Quotas](https://docs.cloud.google.com/compute/api-quota) - Per-project API rate limits
- [Cloud Load Balancing NEGs Overview](https://docs.cloud.google.com/load-balancing/docs/negs) - Network Endpoint Group concepts and types
- [Cloud Load Balancing Quotas](https://docs.cloud.google.com/load-balancing/docs/quotas) - LB resource limits
- [Secret Manager Quotas](https://docs.cloud.google.com/secret-manager/quotas) - Access and mutation rate limits
- [Secret Manager Best Practices](https://docs.cloud.google.com/secret-manager/docs/best-practices) - Rotation, versioning, IAM patterns
- [Backend Services Overview](https://docs.cloud.google.com/load-balancing/docs/backend-service) - Backend service configuration and traffic splitting
- [Application Default Credentials](https://cloud.google.com/docs/authentication/application-default-credentials) - ADC resolution order and configuration

### Java Client Libraries
- [google-cloud-compute on Maven Central](https://mvnrepository.com/artifact/com.google.cloud/google-cloud-compute) - Compute Engine Java client
- [Compute Engine Client Libraries](https://cloud.google.com/compute/docs/api/libraries) - Official setup guide

### Internal References
- [Cloud Integration SPI Spec](cloud-integration-spi-spec.md) - SPI architecture and template (sections 7-8)
- [Hetzner Provider Sheet](cloud-integration-spi-spec.md#8-hetzner-provider-sheet-reference) - Reference implementation (section 8)
