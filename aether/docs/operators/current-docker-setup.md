# Current Docker Infrastructure

Status: **Development/Staging Ready**

## Overview

Aether includes Docker infrastructure for local development and staging environments. This document describes the current setup and how to use it.

---

## Components

### Dockerfiles

#### `docker/aether-node/Dockerfile`

Node container based on `eclipse-temurin:25-alpine`:
- Multi-stage build for smaller images
- Proper Java options for containers
- Health check endpoint

#### `docker/aether-forge/Dockerfile`

Forge simulator container for load testing and chaos experiments.

### Docker Compose

#### `docker/docker-compose.yml`

3-node cluster configuration:

```yaml
services:
  aether-node-1:
    build: ...
    environment:
      NODE_ID: "node-1"
      CLUSTER_PORT: "8090"
      MANAGEMENT_PORT: "8080"
      PEERS: "node-1:aether-node-1:8090,node-2:aether-node-2:8090,node-3:aether-node-3:8090"
      JAVA_OPTS: "-Xmx256m -XX:+UseZGC -XX:+ZGenerational"
    ports:
      - "8080:8080"   # Management API
      - "8090:8090"   # Cluster port
    healthcheck:
      test: ["CMD", "wget", "--spider", "-q", "http://localhost:8080/health"]
      interval: 5s
      timeout: 3s
      retries: 10
      start_period: 20s
```

---

## Usage

### Start 3-Node Cluster

```bash
cd docker
docker-compose up --build
```

### Start with Forge Simulator

```bash
cd docker
docker-compose --profile forge up --build
```

### Access Points

| Service | Port | URL |
|---------|------|-----|
| Node 1 Management | 8080 | http://localhost:8080 |
| Node 2 Management | 8081 | http://localhost:8081 |
| Node 3 Management | 8082 | http://localhost:8082 |
| Node 1 Cluster | 8090 | - |
| Node 2 Cluster | 8091 | - |
| Node 3 Cluster | 8092 | - |
| Forge (optional) | 8888 | http://localhost:8888 |

### Health Check

```bash
curl http://localhost:8080/health
```

### Dashboard

Open http://localhost:8080/dashboard in browser.

---

## Generated Infrastructure

The `aether-setup` module provides generators that create deployment artifacts:

### LocalGenerator

Output: Shell scripts for single-machine deployment

### DockerGenerator

Output:
- `docker-compose.yml` - Service definitions
- `.env` - Environment variables
- `start.sh` - Cluster start script
- `stop.sh` - Cluster stop script
- `status.sh` - Status check script

### KubernetesGenerator

Output:
- `manifests/namespace.yaml`
- `manifests/configmap.yaml`
- `manifests/statefulset.yaml`
- `manifests/service.yaml`
- `manifests/service-headless.yaml`
- `manifests/pdb.yaml`
- `apply.sh` - Apply script
- `delete.sh` - Delete script

---

## Production Gaps

For production cloud deployment, the following are needed:

1. **Container Registry**: Push images to ECR/GCR/ACR
2. **Cloud VPC**: Network isolation
3. **Load Balancer**: ALB/NLB/Cloud LB for external access
4. **TLS Certificates**: HTTPS termination
5. **DNS**: Route53/Cloud DNS for service discovery
6. **Autoscaling**: ASG/MIG for elastic capacity

See `infrastructure-design.md` for the planned cloud infrastructure design.
