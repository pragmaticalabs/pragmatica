# E2E Test Port Allocation

## Overview

E2E tests use **host networking** instead of Docker bridge networking. Each cluster instance gets unique ports to avoid conflicts between parallel test runs and between concurrent JVM processes.

## Port Allocation Scheme

```
Base port = 20000 + ((PID * 100 + counter * 100) % 40000)

Node N (1-indexed):
  Management port = base + N       (e.g., 20001, 20002, 20003)
  Cluster port    = base + 100 + N (e.g., 20101, 20102, 20103)
```

### Example

For PID=12345, first cluster (counter=1):
```
Base = 20000 + ((12345 * 100 + 1 * 100) % 40000)
     = 20000 + (1234600 % 40000)
     = 20000 + 34600
     = 54600

Node 1: management=54601, cluster=54701
Node 2: management=54602, cluster=54702
Node 3: management=54603, cluster=54703
```

### Port Range

- **Range:** [20000, 60000)
- **Why 20000+:** Avoids well-known ports (0-1023) and common application ports
- **Why <60000:** Avoids typical ephemeral port ranges (32768-60999 on Linux, 49152-65535 per IANA)
- **Gap of 100:** Between management and cluster port ranges allows up to 99 nodes per cluster

### Collision Avoidance

Two mechanisms prevent port collisions:

1. **PID component:** Different JVM processes (e.g., parallel Maven forks) get different base ports
2. **Counter component:** Multiple clusters within the same JVM get different base ports (AtomicInteger incremented per cluster)

## Why Host Networking

Bridge networking had reliability issues with failure detection:

- **Docker bridge NAT** adds latency and complexity to port mapping
- **IP-based peer discovery** was non-deterministic with `parallelStream()` startup
- **Network alias DNS** resolution was fragile across container runtimes (Docker vs Podman)
- **Host networking** provides direct localhost connectivity, matching production behavior more closely

## Peer Configuration

With host networking, all nodes communicate via `localhost` with unique ports:

```
node-1:localhost:54701,node-2:localhost:54702,node-3:localhost:54703
```

The `CLUSTER_PEERS` environment variable is set directly on each container. The Java application reads it via `Main.findEnv("CLUSTER_PEERS")`.

## Debugging

### Finding ports for a running test

Look for log lines like:
```
[DEBUG] Using base port: 54600 (management: 54601-54603, cluster: 54701-54703)
[DEBUG] Node node-1 started on management port 54601, cluster port 54701
```

### Port conflict symptoms

If you see `Address already in use` errors in container logs:
1. Check if another test run is using the same PID-derived ports
2. Kill orphaned containers: `podman ps -a | grep aether`
3. The counter mechanism should prevent same-JVM conflicts, but cross-JVM collisions are possible (statistically unlikely)

### Manual health check

```bash
curl http://localhost:<management-port>/api/health
curl http://localhost:<management-port>/api/status
```
