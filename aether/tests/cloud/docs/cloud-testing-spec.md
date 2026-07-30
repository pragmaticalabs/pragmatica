# Aether Cloud Integration Testing Specification

**Version:** 1.0
**Date:** 2026-04-07
**Status:** Draft
**Branch:** release-1.0.0-rc1

> **Superseded in part — 2026-07-30 (rc3).** This spec was written against an in-house
> `aether-lb` deployable that no longer exists: `aether/lb` is not a module in this
> repository, no workflow builds an `aether-lb` image, and `ghcr.io/pragmaticalabs/aether-lb`
> is not published. The harness therefore no longer runs an LB on the bastion.
>
> What actually happens now, per `deploy-cloud.sh`:
> - **Management traffic** goes straight to a core node's own management port
>   (`CORE_MGMT` = `<core-node-public-ip>:8080`, i.e. `[deployment.ports].management`).
>   Any core node is a valid entry point, because a node forwards management requests it
>   does not own to the leader or task-group owner.
> - **Application traffic** goes through the managed Hetzner load balancer (`HLB_IP`),
>   which the harness already provisions and which targets all core nodes.
> - The `cloud-test-lb` VM still exists, but purely as an SSH jump host and Docker host
>   (`TARGET_HOST`). It runs no Aether process.
>
> Sections below that describe an `aether-lb` container, or management on port `8081`,
> describe the retired design and not current behaviour. The environment-variable tables in
> §REQ-T01/T02 and the variable-mapping table have been corrected; the surrounding prose has
> not been rewritten. Whether an LB should return at all — and if so as a *mode* of
> `aether-node` built on `PassiveNode` rather than a separate deployable — is tracked
> separately.

---

## 1. Overview

### 1.1 Purpose

Validate the Aether distributed runtime in a production-like cloud environment on Hetzner Cloud. The primary goals are:

1. **CLI Bootstrap Validation** -- prove that `aether cluster bootstrap` provisions a working cluster from a TOML config file against real Hetzner Cloud APIs.
2. **End-to-End Cloud Operations** -- run the existing 15-suite / 279-test integration test framework against a cloud-deployed cluster behind a Hetzner managed load balancer.
3. **Network Realism** -- verify inter-node communication (QUIC, SWIM, Rabia consensus) over a real private network with real latency, not Docker bridge networking.

### 1.2 Non-Goals

- Performance benchmarking (covered by soak tests separately).
- Multi-region / multi-datacenter testing.
- Automated CI pipeline integration (manual trigger for now; CI integration is a future milestone).
- Testing the `aether cluster destroy` command against Hetzner (it only does drain + shutdown of nodes; VM termination is handled by the teardown script).

### 1.3 Architecture

```
                Internet
                   |
          +--------+--------+
          |   Hetzner LB    |  Public IP -- single entry point
          |  :443 -> nodes  |  App HTTP (round-robin, health-checked)
          |  :9091 -> LB    |  Management API -> Aether passive LB
          +--------+--------+
                   |
         ---- Private Network (10.0.1.0/24) ----
                   |
      node-1..5  (cx22, no public IP, 8080/8070/6000)
      aether-lb  (cx22, public IP for SSH bastion, 8080/8081)
      postgres   (cx22, no public IP, 5432)
```

**7 cx22 VMs** (2 vCPU / 4 GB RAM each) in `fsn1` (Falkenstein, Germany).
**1 Hetzner managed load balancer** -- public entry point.
**1 private network** `10.0.1.0/24` -- all inter-node traffic.

| VM | Role | Public IP | Ports |
|----|------|-----------|-------|
| `cloud-test-node-1` .. `cloud-test-node-5` | Core nodes | None | 8080 (mgmt), 8070 (app), 6000 (cluster), 6100 (SWIM) |
| `cloud-test-lb` | Aether passive LB + SSH bastion | Yes | 8080 (app HTTP), 8081 (mgmt), 7000 (cluster) |
| `cloud-test-postgres` | PostgreSQL 17 | None | 5432 |

---

## 2. Bootstrap TOML Configuration

### 2.1 Config File: `aether-cloud.toml`

This file is consumed by `aether cluster bootstrap aether-cloud.toml --yes --wait --timeout 600`.

The CLI flow is:
1. `ClusterBootstrapCommand.call()` reads the file and passes content through `ConfigReferenceResolver.resolveAll()` (resolves `${env:...}` and `${secrets:...}` references).
2. `ClusterConfigParser.parse()` maps TOML sections to `ClusterManagementConfig`.
3. `BootstrapOrchestrator.bootstrap()` validates, resolves credentials from `HCLOUD_TOKEN` env var, provisions VMs via Hetzner REST API, waits for health/quorum, stores config + API key in the cluster, and registers the cluster locally.

**Key constraint:** The `BootstrapOrchestrator` currently provisions **core nodes only**. It does NOT provision the Aether LB, the postgres VM, or the Hetzner managed LB. Those are handled by the deploy script (section 3).

```toml
# aether-cloud.toml -- Cloud integration test cluster on Hetzner
#
# Usage:
#   export HCLOUD_TOKEN=<token>
#   aether cluster bootstrap aether-cloud.toml --yes --wait --timeout 600

[cluster]
name = "cloud-test"
version = "1.0.0-rc1"

[cluster.core]
count = 5
min = 3
max = 5

[cluster.auto_heal]
enabled = true
retry_interval = "30s"
startup_cooldown = "15s"

[deployment]
type = "hetzner"

[deployment.instances]
core = "cx22"

[deployment.runtime]
type = "container"
image = "ghcr.io/pragmaticalabs/aether-node:1.0.0-rc1"

[deployment.zones]
zone-1 = "fsn1-dc14"

[deployment.ports]
cluster = 6000
management = 8080
app-http = 8070
swim = 6100

[deployment.tls]
auto_generate = true
cert_ttl = "720h"
```

### 2.2 Config Field Mapping

| TOML Path | Java Type | Value | Notes |
|-----------|-----------|-------|-------|
| `deployment.type` | `DeploymentType` | `hetzner` | Triggers `BootstrapOrchestrator.bootstrapHetzner()` |
| `deployment.instances.core` | `Map<String,String>` | `cx22` | Passed to `provisionSingleNode()`, also used by `UserDataTemplate.deriveHeap()` to set JVM heap to `2g` |
| `deployment.runtime.type` | `RuntimeType` | `container` | Triggers Docker install in cloud-init |
| `deployment.runtime.image` | `Option<String>` | `ghcr.io/...` | If absent, defaults to `ghcr.io/pragmaticalabs/aether-node:<version>` |
| `deployment.zones.zone-1` | `Map<String,String>` | `fsn1-dc14` | `firstZoneLocation()` extracts `fsn1` as the location |
| `deployment.ports.*` | `PortMapping` | See above | Default SWIM port = cluster + 100 if not specified |
| `deployment.tls.auto_generate` | `boolean` | `true` | Self-signed certs sufficient for testing |
| `deployment.tls.cluster_secret` | `Option<String>` | (auto-generated) | If absent, `resolveClusterSecret()` generates a random 32-byte Base64 secret |
| `cluster.name` | `String` | `cloud-test` | Used as label `aether-cluster=cloud-test` on VMs |
| `cluster.version` | `String` | `1.0.0-rc1` | Informational |
| `cluster.core.count` | `int` | `5` | Number of VMs to provision |

### 2.3 What Bootstrap Does NOT Provision

The `BootstrapOrchestrator` provisions only the 5 core node VMs. The deploy script must handle:

1. **Private network** -- must be created BEFORE bootstrap so nodes can use private IPs for peer discovery. [QUESTION: Does the current `BootstrapOrchestrator` create a private network? No -- it uses `--network host` and public IPs. This is a gap.]
2. **Aether passive LB VM** -- separate VM with public IP for bastion access.
3. **Postgres VM** -- separate VM running PostgreSQL in Docker.
4. **Hetzner managed load balancer** -- created via `hcloud` CLI after all VMs are up.
5. **SSH key** -- must exist in Hetzner before VM creation.

### 2.4 Bootstrap Gap: Private Network

**[CRITICAL]** The current `BootstrapOrchestrator` provisions VMs with public IPs and uses `--network host` in the cloud-init script. For the cloud testing architecture (private network, no public IPs on core nodes), two approaches exist:

**Option A (Recommended): Pre-create network, bootstrap adds VMs to it.**
The deploy script creates the private network first, then the bootstrap command provisions VMs. Post-provisioning, the deploy script attaches each VM to the private network via `hcloud server attach-to-network`. The cloud-init template already uses `--network host`, so the container will bind to all interfaces including the private one.

**Option B: Extend BootstrapOrchestrator to support private networks.**
Add `[deployment.network]` config section. This requires code changes and is out of scope for the initial cloud testing setup.

Decision: **Option A**. The deploy script handles network setup outside the bootstrap flow.

---

## 3. Deploy Script: `deploy-cloud.sh`

### 3.1 Location

`aether/tests/cloud/deploy-cloud.sh`

### 3.2 Prerequisites

- `hcloud` CLI installed and on PATH.
- `HCLOUD_TOKEN` environment variable set (never echoed, logged, or written to files).
- Project built (`aether-node.jar` exists, or `--build` flag triggers a build).
- `aether` CLI installed and on PATH.
- Docker image `ghcr.io/pragmaticalabs/aether-node:1.0.0-rc1` published to GHCR (or use `--local-image` to build and push from local JAR).
- SSH key pair available locally (default: `~/.ssh/id_ed25519`).

### 3.3 Execution Flow

The script executes 10 phases. Each phase is idempotent -- re-running after a failure resumes from where it left off.

#### Phase 1: Validate Environment

```
REQ-D01: Verify HCLOUD_TOKEN is set (test -n, never echo).
REQ-D02: Verify hcloud CLI is installed.
REQ-D03: Verify aether CLI is installed.
REQ-D04: Optionally build the project (mvn clean install -DskipTests) if --build flag is set.
REQ-D05: Log estimated cost: "Estimated cost: ~$0.067/hr ($1.61/day) for 7x cx22 + 1 LB."
```

#### Phase 2: Create SSH Key

```
REQ-D06: Generate a temporary SSH key pair if not provided via CLOUD_SSH_KEY.
REQ-D07: Register the public key with Hetzner: hcloud ssh-key create --name cloud-test-key --public-key-from-file <path>.
REQ-D08: If key already exists (exit code indicates duplicate), skip creation.
REQ-D09: Store the key name for teardown.
```

#### Phase 3: Create Private Network

```
REQ-D10: Create network: hcloud network create --name cloud-test-net --ip-range 10.0.1.0/24.
REQ-D11: Create subnet: hcloud network subnet add cloud-test-net --type cloud --network-zone eu-central --ip-range 10.0.1.0/24.
REQ-D12: If network already exists, skip creation.
```

#### Phase 4: Bootstrap Core Nodes

```
REQ-D13: Run: aether cluster bootstrap aether-cloud.toml --yes
         This provisions 5 cx22 VMs via Hetzner API (cloud-test-1 through cloud-test-5).
         Each VM gets cloud-init that installs Docker and starts the Aether container.
REQ-D14: Do NOT use --wait yet -- we need to attach VMs to the network first.
REQ-D15: Wait for all 5 VMs to reach "running" status via hcloud server list.
```

#### Phase 5: Attach VMs to Private Network

```
REQ-D16: For each core VM (cloud-test-1 through cloud-test-5):
         hcloud server attach-to-network cloud-test-<i> --network cloud-test-net --ip 10.0.1.1<i>
         (cloud-test-1 -> 10.0.1.11, cloud-test-2 -> 10.0.1.12, ..., cloud-test-5 -> 10.0.1.15)
REQ-D17: Remove public IPs from core nodes:
         hcloud server remove-public-ip cloud-test-<i>  [ASSUMPTION: not needed if firewall blocks all inbound]
         Alternative: Create a firewall that blocks all inbound on core nodes except from the private network.
```

[QUESTION: The `BootstrapOrchestrator` currently waits for health using public IPs (`pollHealth(nodeEndpoint(node, managementPort))`). If we remove public IPs, the health poll will fail. Two options:
- **Option A**: Keep public IPs on core nodes but block inbound via firewall (except from private network). Health check from bootstrap uses public IP. After bootstrap completes, remove public IPs or just leave firewall in place.
- **Option B**: Skip `--wait` on bootstrap. Attach to network manually, then poll health via bastion SSH tunnel.

Decision: **Option A** -- keep public IPs initially for bootstrap health checks. After the cluster forms, the firewall ensures only private network traffic reaches core nodes. The Hetzner managed LB routes via private network anyway.]

#### Phase 6: Provision Aether LB VM

```
REQ-D18: Create LB VM: hcloud server create --name cloud-test-lb --type cx22 --image ubuntu-24.04
         --location fsn1 --ssh-key cloud-test-key --label aether-cluster=cloud-test --label aether-role=lb
REQ-D19: Attach to network: hcloud server attach-to-network cloud-test-lb --network cloud-test-net --ip 10.0.1.20
REQ-D20: SSH into LB VM, install Docker, build/pull Aether LB image.
REQ-D21: Start Aether LB container with:
         - LB_HTTP_PORT=8080
         - LB_MANAGEMENT_PORT=8081
         - LB_MANAGEMENT_MAX_CONTENT_LENGTH=16777216
         - LB_CLUSTER_PORT=7000
         - PEERS=node-1:10.0.1.11:6000,node-2:10.0.1.12:6000,...,node-5:10.0.1.15:6000
         - --network host
REQ-D22: The LB VM's public IP serves as SSH bastion for all other nodes.
```

#### Phase 7: Provision Postgres VM

```
REQ-D23: Create postgres VM: hcloud server create --name cloud-test-postgres --type cx22 --image ubuntu-24.04
         --location fsn1 --ssh-key cloud-test-key --label aether-cluster=cloud-test --label aether-role=db
REQ-D24: Attach to network: hcloud server attach-to-network cloud-test-postgres --network cloud-test-net --ip 10.0.1.30
REQ-D25: SSH into postgres VM (via bastion), install Docker, run:
         docker run -d --name forge-postgres --network host -e POSTGRES_USER=forge
           -e POSTGRES_PASSWORD=forge -e POSTGRES_DB=forge postgres:17-alpine
REQ-D26: Wait for pg_isready to succeed.
REQ-D27: Remove public IP from postgres VM (or create without one using --without-public-ip if available).
```

[ASSUMPTION: `hcloud server create` supports `--without-ipv4` or we can detach the IPv4 after creation. The `hcloud` CLI may not support `--without-public-ip` directly. Alternative: create with public IP, then SSH via bastion to set up, then the firewall blocks inbound.]

#### Phase 8: Create Hetzner Managed Load Balancer

```
REQ-D28: Create LB:
         hcloud load-balancer create --name cloud-test-lb-public --type lb11 --location fsn1
         --label aether-cluster=cloud-test
REQ-D29: Attach LB to private network:
         hcloud load-balancer attach-to-network cloud-test-lb-public --network cloud-test-net
REQ-D30: Add app HTTP service (port 443 -> 8070 on core nodes):
         hcloud load-balancer add-service cloud-test-lb-public --protocol tcp
           --listen-port 443 --destination-port 8070
           --health-check-port 8080 --health-check-protocol http
           --health-check-http-path /health/live
           --health-check-interval 10 --health-check-timeout 5
           --health-check-retries 3
REQ-D31: Add management service (port 9091 -> 8081 on LB VM):
         hcloud load-balancer add-service cloud-test-lb-public --protocol tcp
           --listen-port 9091 --destination-port 8081
REQ-D32: Add core node targets (for app HTTP service):
         For each core node:
           hcloud load-balancer add-target cloud-test-lb-public --server cloud-test-<i> --use-private-ip
REQ-D33: Add LB VM as target (for management service):
         hcloud load-balancer add-target cloud-test-lb-public --server cloud-test-lb --use-private-ip
REQ-D34: Record the Hetzner LB public IP:
         LB_PUBLIC_IP=$(hcloud load-balancer describe cloud-test-lb-public -o json | jq -r '.public_net.ipv4.ip')
```

[QUESTION: Hetzner LB services are port-based but targets are server-based (not port-specific). This means ALL targets receive traffic for ALL services. We need to verify that:
- Core nodes listen on 8070 (app HTTP) but NOT 8081 (LB mgmt) -- so traffic to :9091 hitting a core node will get connection refused. The LB health check should handle this.
- The LB VM listens on 8081 (mgmt) and 8080 (app HTTP) -- traffic to :443 hitting the LB VM will work since the LB also proxies app traffic.

Alternative: Use separate target groups if Hetzner supports them. If not, use label selectors on targets.

Decision: Hetzner LB does NOT support per-service target groups. ALL targets receive traffic for ALL services. This means:
- For the :443 -> :8070 service: core nodes serve app HTTP on 8070; LB VM also serves app HTTP on 8080 (which is its LB HTTP port, not 8070). This mismatch means the LB VM will NOT serve app traffic correctly via the Hetzner LB.
- For the :9091 -> :8081 service: only the LB VM listens on 8081; core nodes do not. Health checks will fail for core nodes on this service, and Hetzner LB will correctly route only to the LB VM.

Revised approach: Add ONLY core nodes as targets. Use a SEPARATE Hetzner LB (or a single LB with careful service design) for management traffic to the Aether LB VM. OR: use a single LB with two target groups if the API supports label-based filtering.]

**Revised Design:**

Since Hetzner managed LBs do not support per-service target routing, we use the Aether LB VM's public IP directly for management traffic:

| Traffic | Entry Point | Destination |
|---------|-------------|-------------|
| App HTTP | `https://<hetzner-lb-ip>:443` | Core nodes :8070 (via Hetzner LB, round-robin, health-checked) |
| Management API | `http://<aether-lb-public-ip>:8081` | Aether passive LB :8081 (direct, no Hetzner LB) |
| SSH bastion | `ssh root@<aether-lb-public-ip>` | Aether LB VM (jump host to private network) |

This simplifies the Hetzner LB to a single service:

```
REQ-D30-R: Single service on Hetzner LB:
           hcloud load-balancer add-service cloud-test-lb-public --protocol tcp
             --listen-port 443 --destination-port 8070
             --health-check-port 8080 --health-check-protocol http
             --health-check-http-path /health/live
REQ-D32-R: Add ONLY core nodes as targets:
           hcloud load-balancer add-target cloud-test-lb-public --server cloud-test-<i> --use-private-ip
```

Management traffic goes directly to the Aether LB VM's public IP on port 8081.

#### Phase 9: Wait for Cluster Health

```
REQ-D35: Poll cluster health via management endpoint:
         curl -sf http://<aether-lb-public-ip>:8081/api/health
         Wait up to 300 seconds.
REQ-D36: Verify 5 core nodes visible:
         curl -sf http://<aether-lb-public-ip>:8081/api/cluster/topology | jq '.coreCount'
REQ-D37: Verify leader elected:
         aether -c <aether-lb-public-ip>:8081 status --format value --field cluster.leaderId
```

#### Phase 10: Push Test Artifacts and Deploy Baseline

```
REQ-D38: Push example blueprint artifacts (url-shortener v1 and v2) via CLI:
         aether -c <aether-lb-public-ip>:8081 artifact push org.pragmatica.aether.example:url-shortener:1.0.0
         aether -c <aether-lb-public-ip>:8081 artifact push org.pragmatica.aether.example:url-shortener:1.0.1
REQ-D39: Deploy v1 as baseline:
         aether -c <core-node-public-ip>:8080 blueprint deploy org.pragmatica.aether.example:url-shortener:1.0.0
REQ-D40: Wait for slices to become active (at least 1 instance running).
REQ-D41: Export environment for test runner:
         echo "CLUSTER_ENDPOINT=http://<core-node-public-ip>:8080"
         echo "APP_ENDPOINT=https://<hetzner-lb-public-ip>:443"
         Write these to aether/tests/cloud/.cloud-env for sourcing.
```

### 3.4 Error Handling

```
REQ-D42: On ANY failure, print the current state of provisioned resources for debugging.
REQ-D43: Do NOT auto-teardown on deploy failure. Leave resources up for investigation.
         (Teardown is a separate explicit step.)
REQ-D44: All hcloud commands use --poll-interval 5 for long-running operations.
REQ-D45: All SSH commands use: -o StrictHostKeyChecking=no -o ConnectTimeout=10
REQ-D46: SSH to private-network-only VMs goes through the bastion:
         ssh -J root@<aether-lb-public-ip> root@10.0.1.1<i> <command>
```

### 3.5 Idempotency

Each phase checks whether its resources already exist before creating them. This allows re-running the script after a partial failure.

```
REQ-D47: Check hcloud ssh-key list before creating SSH key.
REQ-D48: Check hcloud network list before creating network.
REQ-D49: Bootstrap --yes with existing cluster detection (BootstrapOrchestrator.checkNoExistingCluster
         detects VMs with aether-cluster=cloud-test label and resumes).
REQ-D50: Check hcloud server list before creating LB/postgres VMs.
REQ-D51: Check hcloud load-balancer list before creating Hetzner LB.
```

---

## 4. Cloud Test Runner: `run-cloud-tests.sh`

### 4.1 Location

`aether/tests/cloud/run-cloud-tests.sh`

### 4.2 Environment Setup

```
REQ-T01: Source .cloud-env to get CLUSTER_ENDPOINT and APP_ENDPOINT.
REQ-T02: Export:
         TARGET_HOST=<bastion-public-ip>       (for common.sh; bastion is a jump host only)
         MGMT_PORT=8080                        (core node management port)
         LB_PORT=80                            (managed Hetzner LB, app traffic)
         LB_MGMT_PORT=8080                     (same node management port; kept for set -u)
         APP_PORT=443                          (Hetzner LB public port)
         CLUSTER_ENDPOINT=http://<core-node-public-ip>:8080
         APP_ENDPOINT=https://<hetzner-lb-public-ip>:443
         AETHER_SSH_USER=root
         AETHER_SSH_KEY=<path-to-ssh-key>
         NODE_COUNT=5
         CLOUD_MODE=true                       (new flag for test adaptations)
REQ-T03: Verify connectivity: curl -sf ${CLUSTER_ENDPOINT}/health/live
```

### 4.3 Suite Execution

```
REQ-T04: Run suites sequentially using the existing run-suite.sh mechanism.
REQ-T05: Between suites, restore cluster to baseline:
         - Complete/rollback any active deployments.
         - Restart killed nodes (via SSH through bastion, not Docker commands).
         - Wait for 5-node cluster health.
         - Re-deploy baseline blueprint if needed.
REQ-T06: Skip soak tests by default (SKIP_SOAK=true).
REQ-T07: Log suite start/end times for cost tracking.
REQ-T08: On suite failure, continue to next suite (do not abort).
```

### 4.4 Suite Compatibility Matrix

| Suite | Cloud Compatible | Adaptations Required |
|-------|-----------------|---------------------|
| `00-smoke` | Yes | None -- uses CLI and HTTP helpers which work via CLUSTER_ENDPOINT |
| `01-stability` | Partial | Soak tests skipped by default; streaming soak works if timeouts increased |
| `02-chaos` | Yes | `kill_node` / `start_node` must use SSH-to-bastion-to-private-IP (see section 5) |
| `03-scaling` | Yes | Same SSH adaptation for node operations |
| `04-streaming` | Yes | None |
| `05-security` | Yes | None -- cert rotation tests work since TLS is enabled |
| `06-deployment` | Yes | None -- uses CLI/HTTP; schema migration needs postgres connectivity |
| `07-cluster-mgmt` | Partial | `test-bootstrap.sh` and `test-destroy.sh` may conflict with the running cluster |
| `08-resources` | Yes | SQL connector test needs `POSTGRES_HOST=10.0.1.30` |
| `09-artifacts` | Yes | None |
| `10-database` | Yes | Needs `POSTGRES_HOST=10.0.1.30` in env |
| `11-observability` | Yes | None |
| `12-network` | Yes | None -- QUIC/SWIM/gossip work over private network |
| `13-edge-cases` | Yes | None |
| `14-storage` | Yes | None |
| `15-delegation` | Yes | None |

### 4.5 Suites to Skip in Cloud

```
REQ-T09: Skip 07-cluster-mgmt/test-bootstrap.sh (would create a second cluster).
REQ-T10: Skip 07-cluster-mgmt/test-destroy.sh (would destroy the test cluster).
REQ-T11: These are skipped via: SKIP_SUITES="07-cluster-mgmt/test-bootstrap.sh,07-cluster-mgmt/test-destroy.sh"
```

---

## 5. Test Script Adaptations

### 5.1 Node Operations via SSH Bastion

The local Docker tests use `remote_exec "docker kill aether-node-$i"` where `remote_exec` SSHes to TARGET_HOST. In cloud mode, core nodes have no public IP. Node operations must go through the bastion.

**Adaptation in `lib/common.sh`:**

```
REQ-A01: When CLOUD_MODE=true, override remote_exec to:
         - If target is a private IP (10.0.1.*), use SSH proxy jump:
           ssh -J root@${BASTION_IP} root@<private-ip> <command>
         - If target is the bastion itself, SSH directly.
REQ-A02: The BASTION_IP is the Aether LB VM's public IP (from .cloud-env).
```

**Adaptation in `lib/cluster.sh`:**

```
REQ-A03: kill_node() in cloud mode:
         Map node-1..5 to private IPs 10.0.1.11..15.
         SSH via bastion: ssh -J root@${BASTION_IP} root@10.0.1.1${node_num} "docker kill aether-node"
         Note: container name is "aether-node" (not "aether-node-1") because each VM runs a single container.
REQ-A04: start_node() in cloud mode:
         ssh -J root@${BASTION_IP} root@10.0.1.1${node_num} "docker start aether-node"
REQ-A05: restart_all_nodes() in cloud mode:
         For each node 1..5: SSH via bastion and restart the container.
REQ-A06: Provide a node_id_to_ip() mapping function:
         node-1 -> 10.0.1.11, node-2 -> 10.0.1.12, ..., node-5 -> 10.0.1.15
```

### 5.2 Direct Node Access

The local tests use `direct_api_get` which hits `http://${TARGET_HOST}:${MGMT_PORT+offset}`. In cloud mode, core nodes are behind a private network. Direct node access requires SSH tunnel or proxy.

```
REQ-A07: In cloud mode, direct_api_get() uses the Aether passive LB for all management queries.
         The LB already forwards to the correct node based on task-group routing.
         Fall back to SSH-tunneled curl only when the LB is unavailable.
REQ-A08: direct_api_post() same adaptation as direct_api_get().
REQ-A09: leader_api_post() in cloud mode:
         Since we cannot directly reach the leader's management port, use the LB's
         task-group-aware forwarding. The management LB routes CTM-related requests
         to the leader automatically.
         If the route is not leader-aware, open an SSH tunnel:
           ssh -L <local-port>:10.0.1.1${leader_num}:8080 root@${BASTION_IP} -N -f
           curl http://localhost:<local-port>/<path>
```

### 5.3 Port Mapping Differences

| Variable | Local Docker | Cloud |
|----------|-------------|-------|
| `TARGET_HOST` | localhost or remote Docker host | Bastion public IP (jump host) |
| `MGMT_PORT` | 5150 | 8080 |
| `LB_PORT` | 9090 | 80 |
| `LB_MGMT_PORT` | 9091 | 8080 |
| `APP_PORT` | 8070 | 443 |
| `CLUSTER_ENDPOINT` | `http://<host>:9091` | `http://<core-node-ip>:8080` |
| `APP_ENDPOINT` | `http://<host>:9090` | `https://<hetzner-lb-ip>:443` |

```
REQ-A10: These are all configurable via environment variables in common.sh.
         No code changes needed -- just set the variables before sourcing.
```

### 5.4 Timeout Adjustments

Cloud operations have higher latency than Docker.

```
REQ-A11: Default timeout multiplier for cloud mode: CLOUD_TIMEOUT_MULTIPLIER=2
REQ-A12: wait_for_cluster timeout: 120s local -> 240s cloud
REQ-A13: wait_for_leader timeout: 60s local -> 120s cloud
REQ-A14: wait_for_slices_active timeout: 120s local -> 240s cloud
REQ-A15: Individual test step sleeps: multiply by CLOUD_TIMEOUT_MULTIPLIER
```

### 5.5 Metrics Collection

```
REQ-A16: collect_node_metrics() in cloud mode:
         SSH to each node via bastion and collect Docker container stats.
         ssh -J root@${BASTION_IP} root@10.0.1.1${i} "docker stats --no-stream aether-node"
```

### 5.6 App Endpoint TLS

```
REQ-A17: If APP_ENDPOINT uses https://, curl commands must use -k (insecure) since
         the Hetzner LB may use a self-signed certificate or TCP passthrough.
REQ-A18: Alternative: Use TCP mode on Hetzner LB (port 443 -> 8070 TCP, no TLS termination).
         Then APP_ENDPOINT can use http://<hetzner-lb-ip>:443 (HTTP on port 443).
         This is simpler and avoids TLS issues for testing.
         Decision: Use TCP mode. APP_ENDPOINT=http://<hetzner-lb-ip>:443
```

---

## 6. Teardown Script: `teardown-cloud.sh`

### 6.1 Location

`aether/tests/cloud/teardown-cloud.sh`

### 6.2 Execution Flow

Teardown is explicitly invoked. It is NOT automatic (to allow debugging after failures).

```
REQ-X01: Require confirmation unless --yes flag is passed.
REQ-X02: Delete Hetzner managed load balancer:
         hcloud load-balancer delete cloud-test-lb-public
REQ-X03: Delete all VMs with label aether-cluster=cloud-test:
         hcloud server list --selector aether-cluster=cloud-test -o noheader -o columns=id \
           | xargs -I{} hcloud server delete {}
REQ-X04: Delete private network (must remove subnets first):
         hcloud network delete cloud-test-net
REQ-X05: Delete SSH key:
         hcloud ssh-key delete cloud-test-key
REQ-X06: Remove local cluster registry entry:
         aether cluster unregister cloud-test   (or manually edit ~/.aether/clusters.json)
REQ-X07: Delete .cloud-env file.
REQ-X08: Print cost summary:
         Start time (from .cloud-env) to now, multiply by $0.067/hr.
```

### 6.3 Partial Teardown

```
REQ-X09: Each resource deletion is independent and wrapped in error handling.
         If one resource fails to delete, continue with the rest.
REQ-X10: Log each deletion: "Deleted server cloud-test-node-1 (id: 12345678)"
REQ-X11: At the end, list any remaining resources with the cloud-test label.
```

---

## 7. Cost Controls

### 7.1 Budget

| Resource | Count | Hourly Cost | Daily Cost |
|----------|-------|-------------|------------|
| cx22 VM | 7 | $0.0092 each | $0.154 |
| LB lb11 | 1 | $0.0066 | $0.158 |
| **Total** | | **~$0.071/hr** | **~$1.70/day** |

Maximum budget: **$100**. At $0.071/hr, this allows ~1,400 hours (~58 days) of continuous operation. In practice, clusters will run for 1-2 hours per test run.

### 7.2 Safety Mechanisms

```
REQ-C01: deploy-cloud.sh records start timestamp in .cloud-env.
REQ-C02: Maximum runtime guard: if .cloud-env is older than MAX_CLOUD_HOURS (default: 4),
         run-cloud-tests.sh refuses to start and prints a warning to teardown.
REQ-C03: teardown-cloud.sh prints elapsed time and estimated cost at the end.
REQ-C04: A cron-like safety net: a simple script that checks for cloud-test-labeled
         resources older than 6 hours and sends a warning (email/slack/stdout).
         Not automated teardown -- just a reminder.
REQ-C05: The deploy script prints the estimated cost at startup:
         "Cloud cluster cost: ~$0.07/hr. Remember to run teardown-cloud.sh when done."
```

---

## 8. File Layout

```
aether/tests/cloud/
  docs/
    cloud-testing-spec.md          # This document
  aether-cloud.toml                # Bootstrap config (section 2)
  deploy-cloud.sh                  # Deploy script (section 3)
  run-cloud-tests.sh               # Test runner (section 4)
  teardown-cloud.sh                # Teardown script (section 6)
  .cloud-env                       # Generated by deploy, sourced by runner (gitignored)
  .gitignore                       # Ignore .cloud-env, SSH keys, logs
```

The existing test suites at `aether/tests/integration/suites/` and libraries at `aether/tests/integration/lib/` are reused without modification. Cloud-specific behavior is controlled by environment variables (`CLOUD_MODE`, `BASTION_IP`, `CLOUD_TIMEOUT_MULTIPLIER`).

---

## 9. Sequence Diagram: Full Test Run

```
Developer                 deploy-cloud.sh           Hetzner Cloud            run-cloud-tests.sh
    |                          |                          |                          |
    |-- HCLOUD_TOKEN ---->|                          |                          |
    |                          |-- create SSH key ------->|                          |
    |                          |-- create network ------->|                          |
    |                          |-- aether bootstrap ----->| (5 core VMs)            |
    |                          |-- attach to network ---->|                          |
    |                          |-- create LB VM --------->|                          |
    |                          |-- create postgres VM --->|                          |
    |                          |-- create Hetzner LB ---->|                          |
    |                          |-- add targets ---------->|                          |
    |                          |-- wait for health ------>|                          |
    |                          |-- push artifacts ------->|                          |
    |                          |-- write .cloud-env       |                          |
    |                          |                          |                          |
    |-- run tests -------------------------------------->|                          |
    |                          |                          |<-- source .cloud-env ----|
    |                          |                          |<-- curl CLUSTER_ENDPOINT |
    |                          |                          |<-- run suites 00..15 ----|
    |                          |                          |<-- SSH bastion for chaos |
    |                          |                          |-- print results -------->|
    |                          |                          |                          |
    |-- teardown-cloud.sh --->|                          |                          |
    |                          |-- delete LB ------------>|                          |
    |                          |-- delete VMs ----------->|                          |
    |                          |-- delete network ------->|                          |
    |                          |-- delete SSH key ------->|                          |
```

---

## 10. Security Considerations

```
REQ-S01: HCLOUD_TOKEN is NEVER echoed, logged, written to files, or passed as CLI argument.
         The aether CLI reads it from the environment. The hcloud CLI reads it from
         HCLOUD_TOKEN (set via: export HCLOUD_TOKEN="$HCLOUD_TOKEN").
REQ-S02: SSH keys are ephemeral -- generated per test run, destroyed on teardown.
REQ-S03: Core nodes have no public IPs (or have firewall blocking all inbound).
REQ-S04: Postgres is accessible only from the private network (10.0.1.0/24).
REQ-S05: The Aether API key is auto-generated during bootstrap (32-byte Base64).
         It is printed once and stored in the cluster's KV-Store. For test scripts,
         it must be captured from bootstrap output and exported as AETHER_API_KEY.
REQ-S06: All test data is ephemeral. Teardown destroys everything.
REQ-S07: Hetzner LB uses TCP mode (no TLS termination). End-to-end encryption is
         handled by Aether's built-in TLS (auto_generate=true).
```

---

## 11. Open Questions

| ID | Question | Impact | Status |
|----|----------|--------|--------|
| Q1 | Does `BootstrapOrchestrator` need to be extended to support private networks (attach-to-network)? | Core nodes won't have private IPs unless manually attached | Deferred -- deploy script handles it |
| Q2 | Can `hcloud server create` use `--without-ipv4` to avoid public IP allocation? | Simplifies security model | Needs verification against hcloud CLI version |
| Q3 | How does the Aether container discover peers when using private network IPs instead of Docker hostnames? | PEERS env var must use private IPs: `node-1:10.0.1.11:6000,...` | The cloud-init template uses `--network host` and peers discover via SWIM. Initial peer list comes from the bootstrap config. |
| Q4 | Does the Aether passive LB image need to be published to GHCR, or can it be built on the VM? | Affects deploy script complexity | [ASSUMPTION: build on VM from local JAR, same as setup.sh does for Docker] |
| Q5 | How does the bootstrap API key get propagated to test scripts? | Test scripts need `AETHER_API_KEY` set | Bootstrap prints it; deploy script captures from stdout and writes to .cloud-env |

---

## 12. Implementation Checklist

| # | Task | Depends On | Estimated Effort |
|---|------|-----------|-----------------|
| 1 | Write `aether-cloud.toml` | None | 15 min |
| 2 | Write `deploy-cloud.sh` phases 1-3 (env, SSH key, network) | None | 1 hr |
| 3 | Write `deploy-cloud.sh` phase 4 (bootstrap) | 1, 2 | 30 min |
| 4 | Write `deploy-cloud.sh` phases 5-7 (attach network, LB VM, postgres) | 3 | 2 hr |
| 5 | Write `deploy-cloud.sh` phases 8-10 (Hetzner LB, health, artifacts) | 4 | 1.5 hr |
| 6 | Write `teardown-cloud.sh` | None | 45 min |
| 7 | Write `run-cloud-tests.sh` | 5 | 1 hr |
| 8 | Add CLOUD_MODE support to `lib/common.sh` (SSH bastion, timeouts) | None | 1.5 hr |
| 9 | Add CLOUD_MODE support to `lib/cluster.sh` (kill/start via bastion) | 8 | 1 hr |
| 10 | Test end-to-end on Hetzner | 1-9 | 2-3 hr |
| 11 | Document run procedure in README | 10 | 30 min |
| **Total** | | | **~12 hr** |

---

## References

### Hetzner Cloud
- [Hetzner Cloud API Documentation](https://docs.hetzner.cloud/) -- REST API reference for servers, networks, load balancers
- [Hetzner Cloud Load Balancers](https://docs.hetzner.com/cloud/load-balancers/) -- Load balancer concepts, pricing, limitations
- [Creating a Load Balancer](https://docs.hetzner.com/cloud/load-balancers/getting-started/creating-a-load-balancer/) -- Step-by-step LB setup
- [hcloud CLI](https://github.com/hetznercloud/cli) -- Command-line interface for Hetzner Cloud

### Internal References
- `aether/cli/src/main/java/org/pragmatica/aether/cli/cluster/ClusterBootstrapCommand.java` -- CLI entry point for bootstrap
- `aether/cli/src/main/java/org/pragmatica/aether/cli/cluster/BootstrapOrchestrator.java` -- 12-step bootstrap orchestration, Hetzner API calls
- `aether/cli/src/main/java/org/pragmatica/aether/cli/cluster/UserDataTemplate.java` -- Cloud-init script generation
- `aether/cli/src/main/java/org/pragmatica/aether/cli/cluster/ConfigReferenceResolver.java` -- `${env:...}` and `${secrets:...}` resolution
- `aether/aether-config/src/main/java/org/pragmatica/aether/config/cluster/ClusterConfigParser.java` -- TOML to config record mapping
- `aether/aether-config/src/main/java/org/pragmatica/aether/config/cluster/ClusterManagementConfig.java` -- Top-level config record
- `aether/aether-config/src/main/java/org/pragmatica/aether/config/cluster/DeploymentSpec.java` -- Deployment specification record
- `aether/aether-config/src/main/java/org/pragmatica/aether/config/cluster/PortMapping.java` -- Port configuration (cluster, management, app-http, swim)
- `aether/tests/integration/lib/common.sh` -- Shared test functions, HTTP helpers, assertions
- `aether/tests/integration/lib/cluster.sh` -- Cluster lifecycle operations (kill, start, drain, scale)
- `aether/tests/integration/scripts/setup.sh` -- Local Docker cluster setup
- `aether/tests/integration/scripts/deploy-cloud.sh` -- Existing raw-VM cloud deploy (pre-dates bootstrap command)
- `aether/tests/integration/docker-compose.yml` -- Local Docker cluster definition (5 nodes + LB + postgres)
- `aether/tests/integration/cluster-config.toml` -- Local cluster config reference
