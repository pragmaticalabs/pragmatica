# Docker Deployment Guide

Deploy Aether clusters using Docker containers for development, testing, and production.

## Prerequisites

- Docker 20.10+
- Docker Compose 2.0+ (for multi-node clusters)
- Java 25 (for building from source)

## Container Images

Two container images are available:

| Image | Description | Port |
|-------|-------------|------|
| `aether-node` | Runtime node for Aether cluster | 8080 (API), 8090 (cluster) |
| `aether-forge` | Testing simulator with dashboard | 8888 |

## Quick Start

### Build Images

```bash
# Build from project root
cd aetherx

# Build JARs first
mvn package -DskipTests

# Build Docker images
docker build -f docker/aether-node/Dockerfile -t aether-node:latest .
docker build -f docker/aether-forge/Dockerfile -t aether-forge:latest .
```

### Run 3-Node Cluster

```bash
cd docker
docker-compose up --build
```

This starts:
- `aether-node-1` on ports 8080 (API), 8090 (cluster)
- `aether-node-2` on ports 8081 (API), 8091 (cluster)
- `aether-node-3` on ports 8082 (API), 8092 (cluster)

### Run with Forge Simulator

```bash
docker-compose --profile forge up --build
```

Access Forge dashboard at http://localhost:8888

## Docker Compose Configuration

The `docker/docker-compose.yml` defines a 3-node cluster:

```yaml
version: "3.9"

services:
  aether-node-1:
    build:
      context: ..
      dockerfile: docker/aether-node/Dockerfile
    container_name: aether-node-1
    hostname: aether-node-1
    environment:
      NODE_ID: "node-1"
      CLUSTER_PORT: "8090"
      MANAGEMENT_PORT: "8080"
      PEERS: "node-1:aether-node-1:8090,node-2:aether-node-2:8090,node-3:aether-node-3:8090"
      JAVA_OPTS: "-Xmx256m -XX:+UseZGC -XX:+ZGenerational"
    ports:
      - "8080:8080"
      - "8090:8090"
    networks:
      - aether-network
    healthcheck:
      test: ["CMD", "wget", "--spider", "-q", "http://localhost:8080/health"]
      interval: 5s
      timeout: 3s
      retries: 10
      start_period: 20s

  # node-2 and node-3 similar...

networks:
  aether-network:
    driver: bridge
```

## Environment Variables

### Aether Node

| Variable | Default | Description |
|----------|---------|-------------|
| `NODE_ID` | (auto) | Unique node identifier |
| `CLUSTER_PORT` | 8090 | Port for cluster communication |
| `MANAGEMENT_PORT` | 8080 | Port for REST API |
| `PEERS` | (required) | Cluster peer list |
| `JAVA_OPTS` | `-Xmx512m` | JVM options |

### Peer List Format

```
node-id-1:hostname-1:port,node-id-2:hostname-2:port,...
```

Example:
```
node-1:aether-node-1:8090,node-2:aether-node-2:8090,node-3:aether-node-3:8090
```

### Aether Forge

| Variable | Default | Description |
|----------|---------|-------------|
| `FORGE_PORT` | 8888 | Dashboard port |
| `CLUSTER_SIZE` | 5 | Simulated cluster size |
| `LOAD_RATE` | 1000 | Requests per second |
| `JAVA_OPTS` | `-Xmx1g` | JVM options |

## Running Individual Containers

### Single Node

```bash
docker run -d \
  --name aether-node-1 \
  -e NODE_ID=node-1 \
  -e CLUSTER_PORT=8090 \
  -e PEERS="node-1:localhost:8090" \
  -p 8080:8080 \
  -p 8090:8090 \
  aether-node:latest
```

### Multi-Node with Docker Network

```bash
# Create network
docker network create aether-net

# Start nodes
docker run -d --name node1 --network aether-net \
  -e NODE_ID=node-1 \
  -e PEERS="node-1:node1:8090,node-2:node2:8090,node-3:node3:8090" \
  -p 8080:8080 aether-node:latest

docker run -d --name node2 --network aether-net \
  -e NODE_ID=node-2 \
  -e PEERS="node-1:node1:8090,node-2:node2:8090,node-3:node3:8090" \
  -p 8081:8080 aether-node:latest

docker run -d --name node3 --network aether-net \
  -e NODE_ID=node-3 \
  -e PEERS="node-1:node1:8090,node-2:node2:8090,node-3:node3:8090" \
  -p 8082:8080 aether-node:latest
```

## Health Checks

Containers include health checks:

```dockerfile
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=3 \
    CMD wget --spider -q http://localhost:8080/health || exit 1
```

Check health status:
```bash
docker inspect --format='{{.State.Health.Status}}' aether-node-1
```

## Production Considerations

### Resource Limits

```yaml
services:
  aether-node-1:
    deploy:
      resources:
        limits:
          cpus: '2'
          memory: 1G
        reservations:
          cpus: '0.5'
          memory: 512M
```

### Persistent Storage

```yaml
services:
  aether-node-1:
    volumes:
      - aether-data-1:/data
      - aether-config-1:/config

volumes:
  aether-data-1:
  aether-config-1:
```

### TLS Configuration

For production, enable TLS:

```yaml
services:
  aether-node-1:
    environment:
      TLS_ENABLED: "true"
      TLS_CERT_PATH: "/config/cert.pem"
      TLS_KEY_PATH: "/config/key.pem"
    volumes:
      - ./certs:/config:ro
```

### Logging

```yaml
services:
  aether-node-1:
    logging:
      driver: "json-file"
      options:
        max-size: "10m"
        max-file: "3"
```

## Kubernetes Deployment

For Kubernetes, use the Docker images with a deployment:

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: aether-cluster
spec:
  serviceName: "aether"
  replicas: 3
  selector:
    matchLabels:
      app: aether
  template:
    metadata:
      labels:
        app: aether
    spec:
      containers:
      - name: aether-node
        image: ghcr.io/siy/aether-node:latest
        ports:
        - containerPort: 8080
          name: api
        - containerPort: 8090
          name: cluster
        env:
        - name: NODE_ID
          valueFrom:
            fieldRef:
              fieldPath: metadata.name
        - name: PEERS
          value: "aether-cluster-0:aether-cluster-0.aether:8090,aether-cluster-1:aether-cluster-1.aether:8090,aether-cluster-2:aether-cluster-2.aether:8090"
        resources:
          limits:
            memory: "1Gi"
            cpu: "2"
```

## CI/CD Integration

GitHub Actions workflow for building and pushing images:

```yaml
name: Docker Build

on:
  push:
    branches: [main]
    tags: ['v*']

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
    - uses: actions/checkout@v4

    - name: Set up JDK 25
      uses: actions/setup-java@v4
      with:
        java-version: '25'
        distribution: 'temurin'

    - name: Build JARs
      run: mvn package -DskipTests

    - name: Login to GHCR
      uses: docker/login-action@v3
      with:
        registry: ghcr.io
        username: ${{ github.actor }}
        password: ${{ secrets.GITHUB_TOKEN }}

    - name: Build and push
      uses: docker/build-push-action@v5
      with:
        context: .
        file: docker/aether-node/Dockerfile
        push: true
        tags: ghcr.io/${{ github.repository }}/aether-node:latest
```

## Troubleshooting

### Container Won't Start

1. Check logs: `docker logs aether-node-1`
2. Verify JAR exists in image: `docker run --rm aether-node ls -la /app/`
3. Check memory limits aren't too low

### Cluster Not Forming

1. Verify all nodes can reach each other:
   ```bash
   docker exec aether-node-1 wget -q -O- http://aether-node-2:8080/health
   ```
2. Check PEERS environment variable format
3. Ensure health checks are passing

### Performance Issues

1. Increase memory: `-e JAVA_OPTS="-Xmx1g"`
2. Enable ZGC: `-XX:+UseZGC -XX:+ZGenerational`
3. Check container resource limits

## Image Details

### Base Image

Both images use `eclipse-temurin:25-alpine` for:
- Small image size (~200MB)
- Latest Java 25 features
- Security patches

### Security

- Non-root user (`aether:aether`)
- Minimal base image
- No shell access in production builds

### Ports

| Port | Protocol | Usage |
|------|----------|-------|
| 8080 | HTTP | Management API, Dashboard |
| 8090 | TCP | Cluster communication |
| 8888 | HTTP | Forge dashboard |
