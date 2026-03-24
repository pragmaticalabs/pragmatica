# Networking Requirements

Aether uses QUIC (UDP) for all inter-node cluster communication, providing stream
multiplexing, built-in TLS 1.3, and zero head-of-line blocking between message types.

## Required Ports (per node)

| Port | Protocol | Purpose |
|------|----------|---------|
| 8090 | UDP | Cluster transport (QUIC -- consensus, KV-Store, HTTP forwarding, DHT) |
| 8091 | UDP | SWIM failure detection |
| 8080 | TCP (+ optional UDP for HTTP/3) | Management API |
| 8070 | TCP (+ optional UDP for HTTP/3) | App HTTP (slice endpoints) |

## Firewall Rules

All cluster nodes must be able to reach each other on **UDP ports 8090 and 8091**.
Ensure your firewall, security groups, or network policies allow bidirectional UDP
traffic between all nodes on these ports.

This is the same UDP requirement that SWIM health detection already has. With QUIC
transport, all inter-node communication (consensus, state replication, HTTP forwarding)
also uses UDP on port 8090.

## Cloud Provider Notes

### AWS

Security group inbound rules:

```
Type: Custom UDP    Port: 8090    Source: <cluster security group>
Type: Custom UDP    Port: 8091    Source: <cluster security group>
Type: Custom TCP    Port: 8080    Source: <management CIDR>
Type: Custom TCP    Port: 8070    Source: <load balancer security group>
```

### GCP

Firewall rule:

```bash
gcloud compute firewall-rules create aether-cluster \
    --allow udp:8090,udp:8091 \
    --source-tags aether-node \
    --target-tags aether-node
```

### Docker / Docker Compose

Within a Docker bridge network, all ports are accessible between containers without
explicit mapping. Port mappings in `docker-compose.yml` are only needed for host access.

For inter-container cluster communication, no port exposure is required -- the Docker
network handles it. The `CLUSTER_PORT` environment variable (default 8090) configures
the UDP port used for QUIC transport.

## QUIC Transport Details

### TLS

QUIC mandates TLS 1.3 -- there is no plaintext mode. When no TLS configuration is
provided (development/Forge), ephemeral self-signed certificates are generated
automatically for zero-config startup.

### Connection Model

Each pair of nodes maintains exactly one QUIC connection. The node with the lower
NodeId always initiates (QUIC client role). This eliminates duplicate connection
detection and simultaneous-open race conditions.

### Flow Control Parameters

| Parameter | Value | Purpose |
|-----------|-------|---------|
| Max idle timeout | 30s | Keeps connections alive during brief quiet periods |
| Connection-level flow control | 16 MB | Accommodates large DHT transfers |
| Per-stream flow control | 4 MB | Handles bulk data on individual streams |
| Max bidirectional streams | 64 | Headroom for short-lived HTTP forward and DHT relay streams |

### Stream Allocation

| Stream | Purpose | Lifecycle |
|--------|---------|-----------|
| 0 | Consensus (Rabia proposals, votes, decisions) | Long-lived |
| 1 | KV-Store commands | Long-lived |
| 2 | HTTP forwarding | Short-lived (per request) |
| 3 | DHT relay | Short-lived (per transfer) |

Consensus messages (stream 0) cannot be starved by bulk transfers on other streams
thanks to QUIC's per-stream flow control.
