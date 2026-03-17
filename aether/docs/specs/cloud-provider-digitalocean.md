# DigitalOcean Cloud Provider Implementation Sheet

## Overview

DigitalOcean is a **Tier 2** cloud provider targeting developers and small-to-medium workloads.
Its API surface is small, consistent, and well-documented, making it the architecturally closest
provider to Hetzner in the Aether SPI model. Implementation follows the same pattern as the
Hetzner module: direct JDK HttpClient calls against the DigitalOcean REST API v2 (base URL:
`https://api.digitalocean.com/v2`).

- **SDK:** No official Java SDK. Use JDK `HttpClient` directly (same pattern as `aether/environment/hetzner/`).
- **Module:** `aether/environment/digitalocean/`
- **Tier:** 2 (secondary cloud provider)
- **Closest sibling:** Hetzner -- simplest to implement after Hetzner is complete.

---

```
# Provider: DigitalOcean
# Module: aether/environment/digitalocean/
# Tier: 2
# Status: Planned

## Authentication Model
- Authentication method: Personal Access Token (Bearer)
- Credential source: TOML config `cloud.digitalocean.api_token` or `${secrets:digitalocean/api_token}`
- Token refresh: None (static token, manually rotated in DigitalOcean control panel)
- Scoped tokens: Read-only or read-write scopes available at token creation time
- Note: No IAM roles, no service accounts, no instance metadata credentials

## API Mapping

### ComputeProvider

| SPI Method | Cloud API | Notes |
|------------|-----------|-------|
| provision(InstanceType) | POST /v2/droplets | Maps InstanceType to `size` slug (e.g., `s-1vcpu-1gb`, `s-2vcpu-2gb`). Region from config. Returns droplet ID as InstanceId. |
| provision(ProvisionSpec) | POST /v2/droplets | Use spec.instanceSize for `size`, spec.imageId for `image` (snapshot ID or slug), spec.region for `region`. Supports `vpc_uuid` for private networking, `tags` for initial tagging. |
| terminate(InstanceId) | DELETE /v2/droplets/{id} | Droplet ID is numeric. Returns 204 on success. Destroys associated volumes only if `destroy_associated_resources` is set. |
| restart(InstanceId) | POST /v2/droplets/{id}/actions | Body: `{"type": "reboot"}`. Returns action object with status. Use `power_cycle` as fallback for hard reboot. |
| listInstances() | GET /v2/droplets | Paginated (default 20/page, max 200). Must handle `links.pages.next` for full enumeration. |
| instanceStatus(InstanceId) | GET /v2/droplets/{id} | Maps droplet `status` field: `new` -> Provisioning, `active` -> Running, `off` -> Stopped, `archive` -> Terminated. |
| applyTags(InstanceId, tags) | POST /v2/tags/{tag_name}/resources | Body: `{"resources": [{"resource_id": "id", "resource_type": "droplet"}]}`. One API call per tag. Tags are flat strings (no key=value labels like Hetzner). |
| listInstances(TagSelector) | GET /v2/droplets?tag_name={tag} | Native tag-based filtering. Only supports single tag per query; intersect client-side for multi-tag selectors. |

### LoadBalancerProvider

| SPI Method | Cloud API | Notes |
|------------|-----------|-------|
| onRouteChanged(RouteChange) | POST /v2/load_balancers/{id}/droplets | Body: `{"droplet_ids": [id]}`. Adds droplet to LB. Alternative: use tag-based targeting (LB auto-discovers droplets with matching tag). |
| onNodeRemoved(String) | DELETE /v2/load_balancers/{id}/droplets | Body: `{"droplet_ids": [id]}`. Removes droplet from LB backend pool. |
| reconcile(LoadBalancerState) | GET /v2/load_balancers/{id} + diff | Fetch current LB state, compute diff of droplet_ids vs. desired state, issue add/remove calls. Tag-based LBs self-reconcile. |
| createLoadBalancer(spec) | POST /v2/load_balancers | Specify `name`, `region`, `forwarding_rules`, `health_check`, and either `droplet_ids` or `tag` for target selection. Tag-based preferred for Aether. |
| deleteLoadBalancer(id) | DELETE /v2/load_balancers/{id} | Returns 204 on success. LB ID is UUID string. |
| loadBalancerInfo() | GET /v2/load_balancers/{id} | Returns full LB state: IP, status, forwarding rules, health check config, droplet IDs, tag. |
| configureHealthCheck(config) | PUT /v2/load_balancers/{id} | Update `health_check` object: `protocol` (http/https/tcp), `port`, `path`, `check_interval_seconds`, `response_timeout_seconds`, `unhealthy_threshold`, `healthy_threshold`. |
| syncWeights(weightsByIp) | Not supported | DigitalOcean LBs use round-robin only (no per-target weights). Degrade gracefully: log warning, skip. Same behavior as Hetzner. |
| deregisterWithDrain(ip, timeout) | DELETE /v2/load_balancers/{id}/droplets | No native connection draining. Implement via: enable `disable_lets_encrypt_dns_records` if needed, remove target, sleep(timeout). Same pattern as Hetzner. |
| configureTls(config) | PUT /v2/load_balancers/{id} | Forwarding rule with `entry_protocol: https`. Supports Let's Encrypt auto-cert (`enable_proxy_protocol`, `redirect_http_to_https`) or uploaded certificates via `certificate_id`. |

### DiscoveryProvider

| SPI Method | Cloud API | Notes |
|------------|-----------|-------|
| discoverPeers() | GET /v2/droplets?tag_name=aether-cluster-{name} | Filter by cluster tag. Extract `networks.v4[type=private].ip_address` for peer addresses. Paginate for large clusters. |
| watchPeers(callback) | Poll GET /v2/droplets?tag_name=... at interval | No native push/webhook for droplet lifecycle. Poll-based with configurable interval (default 30s). Diff against last known set. |
| registerSelf(addr, metadata) | POST /v2/tags/{tag}/resources | Tag self-droplet with `aether-cluster-{name}` and `aether-role-{role}`. Droplet ID from instance metadata: `curl http://169.254.169.254/metadata/v1/id`. |
| deregisterSelf() | DELETE /v2/tags/{tag}/resources | Remove cluster and role tags from self-droplet. Body: `{"resources": [{"resource_id": "id", "resource_type": "droplet"}]}`. |

### SecretsProvider

| SPI Method | Cloud API | Notes |
|------------|-----------|-------|
| resolveSecret(path) | N/A | DigitalOcean has no secrets service. Use env vars or external backend (Vault). |
| resolveSecretWithMetadata(path) | N/A | Not applicable. |
| resolveSecrets(paths) | N/A | Not applicable. |
| watchRotation(path, callback) | N/A | Not applicable. |

## Rate Limits and Quotas

| API | Rate Limit | Quota | Notes |
|-----|-----------|-------|-------|
| All endpoints | 5,000 req/hour | Per OAuth token | HTTP 429 with `Retry-After` header. Headers: `ratelimit-limit`, `ratelimit-remaining`, `ratelimit-reset` (Unix epoch). |
| Droplet creation | Part of global limit | 25 droplets/account (default) | Increase via support ticket. |
| LB operations | Part of global limit | 10 LBs/account (default) | Increase via support ticket. Max 40 forwarding rules per LB. |
| Tags | Part of global limit | 500 tags/account | Tag names max 255 chars. |

## Regional Considerations

| Feature | Regional? | Zone? | Notes |
|---------|-----------|-------|-------|
| Compute | Yes (region) | No zones | Regions: nyc1, nyc3, sfo3, ams3, sgp1, lon1, fra1, blr1, tor1, syd1. Legacy regions (nyc2, sfo1, sfo2, ams2) restricted. |
| Load Balancer | Yes (region) | No zones | Must be same region as target droplets. Regional LBs only. |
| Discovery | Global (tag filter) | N/A | Tags are global; filter by region in tag naming convention or client-side. |
| Secrets | N/A | N/A | No native service. |

## Provider-Specific Features

| Feature | Supported | Notes |
|---------|-----------|-------|
| Spot/Preemptible instances | No | DigitalOcean has no spot market or preemptible instances. |
| Placement groups | No | No placement group API. HA achieved via multi-region deployment. |
| Custom images/snapshots | Yes | Create snapshots from droplets (POST /v2/droplets/{id}/actions type=snapshot). Use snapshot ID as image in provision. |
| VPC/private networking | Yes | VPC API (POST /v2/vpcs). Assign droplets to VPC via `vpc_uuid` on creation. Private IPs for intra-cluster communication. |
| Weighted LB targets | No | Round-robin only. Same limitation as Hetzner. |
| LB health check customization | Yes | HTTP, HTTPS, TCP protocols. Configurable path, port, interval, timeout, thresholds. |
| LB sticky sessions | Yes | Cookie-based sticky sessions (`stick_sessions` config on LB). |
| LB Let's Encrypt auto-cert | Yes | Automatic TLS certificate provisioning via Let's Encrypt integration. |
| Tag-based LB targeting | Yes | LB auto-discovers droplets by tag. Preferred over explicit droplet ID lists. |
| Native discovery service | No | Use tag-based filtering on /v2/droplets endpoint. |
| Secret rotation notification | No | No secrets service. |
| Droplet metadata service | Yes | http://169.254.169.254/metadata/v1/ -- provides droplet ID, region, interfaces, tags. Useful for self-registration. |
| Firewalls | Yes | Cloud Firewalls API (POST /v2/firewalls). Tag-based rules. Useful for restricting Aether cluster traffic. |

## Estimated Effort

| Facet | Effort | Notes |
|-------|--------|-------|
| Compute | 3-4 days | New module from scratch. Simple REST API, mirrors Hetzner structure. Tag model differs (flat tags vs. key=value labels). |
| Load Balancer | 3-4 days | Straightforward API. Tag-based targeting simplifies reconciliation. No weighted targets (same as Hetzner). |
| Discovery | 1-2 days | Native tag filtering. Poll-based watch. Metadata service for self-identification. |
| Secrets | 0 days | Not applicable (use external backend). |
| Total | **7-10 days** | New module, no existing code to extend. Architecturally identical to Hetzner; can use Hetzner module as implementation template. |
```

---

## References

### DigitalOcean API Documentation
- [DigitalOcean API Reference](https://docs.digitalocean.com/reference/api/digitalocean/) -- Official REST API v2 documentation
- [Regional Availability](https://docs.digitalocean.com/platform/regional-availability/) -- Datacenter regions and product availability matrix
- [Load Balancer Features](https://docs.digitalocean.com/products/networking/load-balancers/details/features/) -- LB capabilities including tag-based targets, sticky sessions, TLS
- [Load Balancer Limits](https://docs.digitalocean.com/products/networking/load-balancers/details/limits/) -- LB quotas and forwarding rule limits
- [DigitalOcean API Slugs](https://slugs.do-api.dev/) -- Auto-updated list of droplet sizes, images, and region slugs

### Internal References
- [Cloud Integration SPI Spec](cloud-integration-spi-spec.md) -- SPI architecture, template (section 7), Hetzner reference (section 8)
- [Hetzner Module](../../environment/hetzner/) -- Reference implementation; DigitalOcean module mirrors this structure
