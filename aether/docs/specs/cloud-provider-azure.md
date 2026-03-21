# Cloud Provider Implementation Sheet: Microsoft Azure

## Overview

Microsoft Azure is a Tier 1 cloud provider offering a comprehensive set of services for compute, networking, identity, and secrets management. Azure's resource model is organized around **subscriptions**, **resource groups**, and **regions**, with fine-grained RBAC via Azure Active Directory (Entra ID). The provider implementation leverages Azure's fluent Java SDK for resource management and Key Vault for secrets.

**SDK:** Azure SDK for Java
- `com.azure:azure-resourcemanager` (compute, network, resource graph)
- `com.azure:azure-resourcemanager-resourcegraph` (Resource Graph queries)
- `com.azure:azure-security-keyvault-secrets` (Key Vault secrets)
- `com.azure:azure-identity` (authentication via DefaultAzureCredential)

**Module:** `aether/environment/azure/`

---

```
# Provider: Microsoft Azure
# Module: aether/environment/azure/
# Tier: 1
# Status: Planned

## Authentication Model
- Authentication method: DefaultAzureCredential chain (tries Managed Identity -> Service Principal -> Azure CLI -> IntelliJ -> other)
- Credential source: Instance metadata (Managed Identity on VMs/AKS), environment variables (AZURE_CLIENT_ID, AZURE_CLIENT_SECRET, AZURE_TENANT_ID), Azure CLI login
- Token refresh: Automatic (SDK handles token acquisition and refresh via MSAL)
- Preferred method: Managed Identity (system-assigned or user-assigned) — zero credential storage
- Fallback: Service Principal with client secret or certificate — for non-Azure environments

## API Mapping

### ComputeProvider

| SPI Method | Cloud API | Notes |
|------------|-----------|-------|
| provision(InstanceType) | azure.virtualMachines().define(name).withRegion().withExistingResourceGroup().withExistingPrimaryNetwork().withSubnet().withPrimaryPrivateIPAddressDynamic().withExistingPrimaryPublicIPAddress().withPopularLinuxImage().withRootUsername().withSsh().withSize(VirtualMachineSizeTypes).create() | Fluent builder pattern. Maps InstanceType to Azure VM size (Standard_B2s, Standard_D2s_v5, Standard_E2s_v5). Requires pre-existing resource group, VNet, and subnet. NSG attached to subnet or NIC. |
| provision(ProvisionSpec) | Same fluent builder + spec.imageId maps to withStoredLinuxImage() or withGeneralizedLinuxCustomImage() (Shared Image Gallery) | Use ProvisionSpec.instanceSize for VM size, spec.imageId for custom image from Shared Image Gallery or managed image. Spot VMs: .withPriority(VirtualMachinePriorityTypes.SPOT).withMaxPrice(-1).withEvictionPolicy(VirtualMachineEvictionPolicyTypes.DEALLOCATE). |
| terminate(InstanceId) | azure.virtualMachines().deleteById(resourceId) | Resource ID is the full ARM resource ID: /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Compute/virtualMachines/{name}. Also deletes associated NIC/disk if configured. |
| restart(InstanceId) | azure.virtualMachines().getById(resourceId).restart() | Native restart action. Also available: powerOff(), deallocate(), start(). |
| listInstances() | azure.virtualMachines().listByResourceGroup(resourceGroup) | Returns paginated PagedIterable. Filter to Aether resource group. For cross-RG queries, use Resource Graph instead. |
| instanceStatus(InstanceId) | azure.virtualMachines().getById(resourceId).instanceView().statuses() | Instance view provides provisioning state + power state (PowerState/running, PowerState/stopped, PowerState/deallocated). Requires separate API call with $expand=instanceView. |
| applyTags(InstanceId, tags) | azure.virtualMachines().getById(resourceId).update().withTag(key, value).apply() | Azure resource tags: max 50 tags per resource, key max 512 chars, value max 256 chars. Tags are case-insensitive for keys. |
| listInstances(TagSelector) | Resource Graph query: `Resources \| where type =~ 'microsoft.compute/virtualmachines' \| where tags['aether-cluster'] == '{cluster}'` | Use azure-resourcemanager-resourcegraph SDK. More efficient than listing + filtering. Supports complex KQL queries across subscriptions. |

### LoadBalancerProvider

| SPI Method | Cloud API | Notes |
|------------|-----------|-------|
| onRouteChanged(RouteChange) | azure.loadBalancers().getById(lbId).update().updateBackendPool(poolName).withExistingVirtualMachines(vm).parent().apply() | For L4 (Azure Load Balancer): add VM NIC to backend pool. For L7 (Application Gateway): add to backendAddressPool. Backend pool supports IP-based or NIC-based membership. |
| onNodeRemoved(String) | azure.loadBalancers().getById(lbId).update().updateBackendPool(poolName) — remove VM from pool | Remove NIC from backend address pool. For Application Gateway, remove backend address from pool. |
| reconcile(LoadBalancerState) | GET loadBalancers/{id} + diff current backend pool members against desired state | Compute add/remove diff. Azure LB backend pools are eventually consistent; verify with GET after update. |
| createLoadBalancer(spec) | azure.loadBalancers().define(name).withRegion().withExistingResourceGroup().withFrontend().withBackendPool().withProbe().withLoadBalancingRule().create() | L4: Azure Load Balancer (Standard SKU for zone-redundancy). L7: Application Gateway with routing rules. Choose based on spec requirements. |
| deleteLoadBalancer(id) | azure.loadBalancers().deleteById(resourceId) | Deletes LB and associated rules. Backend VMs are not deleted. Public IP must be released separately if not needed. |
| loadBalancerInfo() | azure.loadBalancers().getById(resourceId) | Returns frontend IPs, backend pools, health probe status, load balancing rules. |
| configureHealthCheck(config) | azure.loadBalancers().getById(lbId).update().defineLoadBalancingProbe(name).withProtocol(protocol).withPort(port).withRequestPath(path).withIntervalInSeconds(interval).withNumberOfProbes(threshold).attach().apply() | L4 LB: TCP, HTTP, HTTPS probes. Application Gateway: custom probes with match conditions on status codes and response body. |
| syncWeights(weightsByIp) | Application Gateway: withBackendHttpSettingsCollection with weighted routing. Traffic Manager: Azure Traffic Manager profiles with weighted routing method. | Azure Load Balancer (L4) does NOT support per-target weights — uses hash-based distribution. Application Gateway supports weighted backends. Traffic Manager supports weighted routing at DNS level. Recommend Application Gateway for weighted traffic. |
| deregisterWithDrain(ip, timeout) | Application Gateway: ApplicationGatewayConnectionDraining.withDrainTimeoutInSec(seconds).withEnabled(true) | Application Gateway has native connection draining (1-3600 seconds). Azure Load Balancer (L4) has no native drain — implement via: update health probe to fail for target, wait(timeout), then remove. |
| configureTls(config) | Application Gateway: withSslCertificate().withPfxFromKeyVault(keyVaultSecretId) | Application Gateway supports TLS termination with certs from Key Vault (auto-renewal). Azure Load Balancer (L4) does not do TLS termination — pass-through only. |

### DiscoveryProvider

| SPI Method | Cloud API | Notes |
|------------|-----------|-------|
| discoverPeers() | Resource Graph: `Resources \| where type =~ 'microsoft.compute/virtualmachines' \| where tags['aether-cluster'] == '{cluster}' \| project name, properties.networkProfile.networkInterfaces` + resolve private IPs | Two-step: (1) Resource Graph query for tagged VMs, (2) resolve NIC private IPs. Alternative: use Azure Instance Metadata Service (IMDS) on each VM to get own IP, combine with Resource Graph for peer discovery. |
| watchPeers(callback) | Poll Resource Graph at interval | No native push for resource changes. Poll-based: query Resource Graph periodically (recommended 15-30s interval). Alternative: Azure Event Grid resource events (Microsoft.Resources.ResourceWriteSuccess) — more complex but push-based. |
| registerSelf(addr, metadata) | azure.virtualMachines().getById(selfId).update().withTag("aether-cluster", cluster).withTag("aether-role", role).withTag("aether-addr", addr).apply() | Tag self with cluster membership + metadata. Limited to 50 tags per resource; use prefixed keys (aether-*) to avoid conflicts. |
| deregisterSelf() | azure.virtualMachines().getById(selfId).update().withoutTag("aether-cluster").withoutTag("aether-role").withoutTag("aether-addr").apply() | Remove aether-prefixed tags from self. |

### SecretsProvider

| SPI Method | Cloud API | Notes |
|------------|-----------|-------|
| resolveSecret(path) | secretClient.getSecret(secretName) | Path format: `azure-kv://{vaultName}/{secretName}` or `azure-kv://{vaultName}/{secretName}/{version}`. Returns current version if version omitted. SecretClient from azure-security-keyvault-secrets SDK. |
| resolveSecretWithMetadata(path) | secretClient.getSecret(secretName) | KeyVaultSecret includes properties: version, createdOn, updatedOn, expiresOn, contentType, tags. Map to SPI metadata model. |
| resolveSecrets(paths) | Batch: multiple secretClient.getSecret() calls | No native batch API in Key Vault. Parallelize with async client: secretAsyncClient.getSecret(). Respect rate limits (see below). |
| watchRotation(path, callback) | Azure Event Grid: subscribe to Microsoft.KeyVault.SecretNewVersionCreated event | Event Grid publishes events when secrets change. Event types: SecretNewVersionCreated, SecretNearExpiry, SecretExpired. Subscribe via Event Grid webhook or Azure Functions trigger. Guarantees at-least-once delivery. |

## Rate Limits and Quotas

| API | Rate Limit | Quota | Notes |
|-----|-----------|-------|-------|
| Compute (reads) | 3,000 req/5 min per subscription per region | Varies by subscription type | Token bucket algorithm. 429 with Retry-After header. Different limits for read vs write. |
| Compute (writes) | 1,500 req/5 min per subscription per region | Varies by subscription type | Includes create, update, delete. Per-VM limit: 12 updates/min. |
| Resource Graph | 15 req/5 sec per tenant | 5,000 results per query | Queries counted per tenant, not subscription. Supports pagination for large result sets. |
| Load Balancer ops | Part of networking RP limits | 1,000 LBs per subscription per region (Standard) | Standard SKU recommended for production (zone-redundant). |
| Application Gateway | Part of networking RP limits | 1,000 per subscription per region | Separate from Load Balancer quota. |
| Key Vault (GET secrets) | 4,000 txn/10 sec per vault (software keys) | No hard limit on vaults per subscription | 2,000 txn/10 sec for HSM-backed. 429 with Retry-After. Subscription aggregate: 5x individual vault limit. |
| Key Vault (writes) | 300 txn/10 sec per vault | Same | CREATE/IMPORT operations share this limit. |
| Event Grid | 5,000 events/sec per topic | 500 event subscriptions per topic | At-least-once delivery. |

## Regional Considerations

| Feature | Regional? | Zone? | Notes |
|---------|-----------|-------|-------|
| Compute | Yes (e.g., eastus, westeurope, southeastasia) | Yes (zones 1, 2, 3) | VM placed in specific zone via withAvailabilityZone(). Zone-redundant deployments require VMs across zones. |
| Load Balancer | Yes (regional) | Yes (zone-redundant Standard SKU) | Standard LB is zone-redundant by default. Application Gateway v2 supports zone-redundancy. |
| Discovery | Global (Resource Graph queries span subscriptions) | N/A | Resource Graph is global; filter by location in query if needed. |
| Secrets | Yes (vault is regional) | Zone-redundant (automatic) | Key Vault is automatically zone-redundant in regions with availability zones. Vault URL: https://{name}.vault.azure.net. |

## Provider-Specific Features

| Feature | Supported | Notes |
|---------|-----------|-------|
| Spot/Preemptible instances | Yes | Spot VMs with priority=Spot, evictionPolicy=Deallocate or Delete. Up to 90% discount. 30-second eviction notice via Scheduled Events API (IMDS). |
| Placement groups | Yes | Proximity Placement Groups for low-latency. Availability Sets for fault/update domain separation. |
| Custom images/snapshots | Yes | Managed images, Shared Image Gallery (Azure Compute Gallery) for versioned, replicated images. Gallery images can be replicated across regions. |
| VPC/private networking | Yes | Virtual Network (VNet) with subnets. Network Security Groups (NSG) for traffic filtering. Private endpoints for Key Vault. Service endpoints. |
| Weighted LB targets | Partial | Azure Load Balancer (L4): no per-target weights. Application Gateway (L7): supports weighted backends. Traffic Manager (DNS): supports weighted routing globally. |
| LB health check customization | Yes | TCP, HTTP, HTTPS probes. Configurable interval (5-300s), threshold (1-20), timeout. Application Gateway adds response body matching. |
| Native discovery service | No | Use Resource Graph queries with tag filters. No dedicated service registry. Alternative: Azure DNS Private Zones for DNS-based discovery. |
| Secret rotation notification | Yes | Event Grid integration: SecretNewVersionCreated, SecretNearExpiry (30 days before expiry), SecretExpired events. Push-based via webhooks. |
| Managed Identity | Yes | System-assigned (tied to VM lifecycle) or user-assigned (independent lifecycle, shareable). Eliminates credential management. DefaultAzureCredential auto-detects. |
| Resource Groups | Yes | Logical grouping for lifecycle management. All Aether resources in a dedicated RG for easy cleanup. RBAC can be scoped to RG level. |

## Azure-Specific Implementation Notes

### Resource Naming & Organization
- All Aether resources should be placed in a dedicated resource group (e.g., `rg-aether-{cluster}-{region}`)
- Resource names must be globally unique for some services (Key Vault) and regionally unique for others (VMs)
- Use consistent tagging: `aether-cluster`, `aether-role`, `aether-env`, `aether-version`

### Networking Prerequisites
- VNet and subnet must exist before provisioning VMs
- NSG rules must allow: intra-cluster communication (Aether ports), health probe source IPs (168.63.129.16 for Azure LB), SSH for management
- Private endpoints recommended for Key Vault access (no public internet traversal)

### Managed Identity Wiring
- System-assigned identity: enabled at VM creation, identity lifecycle tied to VM
- User-assigned identity: created independently, assigned to VMs, survives VM deletion
- Key Vault access policy or RBAC role assignment grants identity access to secrets
- Recommended: RBAC model (`Key Vault Secrets User` role) over legacy access policies

### Error Handling
- All Azure SDK operations return `HttpResponseException` with status code
- 429 responses include `Retry-After` header (seconds) — must honor
- Long-running operations (VM create, LB create) return a Poller; use `.waitForCompletion()` or async `.subscribe()`
- Conflict (409) on concurrent updates — retry with fresh ETag

## Estimated Effort

| Facet | Effort | Notes |
|-------|--------|-------|
| Compute | 5-7 days | Full fluent SDK wiring, VM lifecycle, Spot VM support, Resource Graph tag queries, zone placement, Managed Identity setup |
| Load Balancer | 6-8 days | Dual support (Azure LB for L4 + Application Gateway for L7), backend pool management, health probes, connection draining, TLS with Key Vault certs, weighted routing via App Gateway |
| Discovery | 3-4 days | Resource Graph query integration, tag-based discovery, poll-based watch, IMDS self-identification |
| Secrets | 3-4 days | Key Vault SecretClient integration, Managed Identity auth, Event Grid subscription for rotation events, async batch resolution |
| Total | **17-23 days** | Significantly more surface area than Tier 2 providers due to dual LB options, RBAC wiring, and first-class zone support |
```

## References

### Azure SDK Documentation
- [Azure Resource Manager client library for Java](https://learn.microsoft.com/en-us/java/api/overview/azure/resourcemanager-readme?view=azure-java-stable) - Main SDK entry point
- [Azure SDK for Java samples](https://github.com/Azure/azure-sdk-for-java/blob/main/sdk/resourcemanager/docs/SAMPLE.md) - Fluent API usage examples
- [Azure Identity client library](https://learn.microsoft.com/en-us/java/api/overview/azure/identity-readme?view=azure-java-stable) - DefaultAzureCredential and Managed Identity

### Azure Service Documentation
- [Virtual Machines REST API](https://learn.microsoft.com/en-us/rest/api/compute/virtual-machines) - Compute API reference
- [Azure Load Balancer REST API](https://learn.microsoft.com/en-us/rest/api/load-balancer/) - L4 load balancer operations
- [Application Gateway backend pool management](https://github.com/Azure-Samples/application-gateway-java-manage-application-gateways) - Java sample for App Gateway
- [ApplicationGatewayConnectionDraining](https://learn.microsoft.com/en-us/java/api/com.azure.resourcemanager.network.models.ApplicationGatewayConnectionDraining?view=azure-java-stable) - Connection draining configuration
- [Azure Key Vault service limits](https://learn.microsoft.com/en-us/azure/key-vault/general/service-limits) - Rate limits and throttling
- [Azure Key Vault throttling guidance](https://learn.microsoft.com/en-us/azure/key-vault/general/overview-throttling) - Token bucket details
- [Azure Key Vault Event Grid integration](https://learn.microsoft.com/en-us/azure/key-vault/general/event-grid-overview) - Secret rotation events
- [Azure Key Vault event schema](https://learn.microsoft.com/en-us/azure/event-grid/event-schema-key-vault) - Event types and payload
- [Azure Resource Graph query samples](https://learn.microsoft.com/en-us/azure/governance/resource-graph/samples/starter) - KQL query examples
- [Azure compute throttling limits](https://docs.azure.cn/en-us/virtual-machines/compute-throttling-limits) - Per-subscription rate limits
- [Backend Pool Management](https://learn.microsoft.com/en-us/azure/load-balancer/backend-pool-management) - LB backend pool configuration

### Internal References
- [Cloud Integration SPI Spec](cloud-integration-spi-spec.md) - SPI architecture and template (sections 7-8)
- Module location: `aether/environment/azure/`
