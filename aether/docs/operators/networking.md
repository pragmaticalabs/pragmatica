# Networking Requirements

Aether uses QUIC (UDP) for all inter-node cluster communication, providing stream
multiplexing, built-in TLS 1.3, and zero head-of-line blocking between message types.

## Required Ports (per node)

| Port | Protocol | Purpose |
|------|----------|---------|
| 8090 | UDP | Cluster transport (QUIC -- consensus, KV-Store, HTTP forwarding, DHT) |
| 8190 | UDP | SWIM failure detection |
| 8080 | TCP (+ optional UDP for HTTP/3) | Management API |
| 8070 | TCP (+ optional UDP for HTTP/3) | App HTTP (slice endpoints) |

## Firewall Rules

All cluster nodes must be able to reach each other on **UDP ports 8090 and 8190**.
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
Type: Custom UDP    Port: 8190    Source: <cluster security group>
Type: Custom TCP    Port: 8080    Source: <management CIDR>
Type: Custom TCP    Port: 8070    Source: <load balancer security group>
```

### GCP

Firewall rule:

```bash
gcloud compute firewall-rules create aether-cluster \
    --allow udp:8090,udp:8190 \
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

For comprehensive TLS certificate management -- including auto-generated certificates,
manual certificate files, rotation lifecycle, gossip encryption, and troubleshooting --
see [TLS Certificate Management](tls-certificates.md).

### Connection Model

Each pair of nodes settles on one QUIC connection. The node with the lower NodeId
normally initiates (QUIC client role), but after a link loss both ends can dial at
once, so two connections to one peer can briefly coexist. Every dial carries a
generation: one greater than the highest generation the dialing node itself has dialed
or attached for that peer (each node knows only its own view; the other end's counter is
not consulted). Both ends keep the connection with the greater generation (equal
generations go to the connection dialed by the lower NodeId) and close the other. The
decision reads no clock, so the two ends reach it independently and, if both see the same
two connections, keep the same one. That agreement does not cover a connection that
liveness closes while the decision runs: the pair then re-dials with a higher generation
and settles on the next round. A pair in which one end sends no generation (an earlier
version) is arbitrated by the previous age-based rule, which can leave the two ends on
different connections until the next reconnect.

### Flow Control Parameters

| Parameter | Value | Purpose |
|-----------|-------|---------|
| Max idle timeout | disabled (0) | Cluster connections are persistent; a dead link is found by write failure or by the receipt sweep (8 ping intervals with no inbound frame on any lane) |
| Connection-level flow control | 64,000,000 bytes | Accommodates large DHT transfers and state transfer |
| Per-stream flow control | 32,000,000 bytes | Handles bulk data on individual streams |
| Max bidirectional streams | 64 | Headroom above the eight lane streams of a connection |
| Max frame size | 32 MiB | Larger frames are refused by the length-field decoder |

### Stream Allocation

Each connection carries eight lanes, one QUIC stream per lane, all long-lived (opened once per connection).

| Lane index | Lane | Purpose |
|------------|------|---------|
| 0 | CONSENSUS | Rabia proposals, votes, decisions, batches, `SyncRejected`; forwarded apply requests |
| 1 | KV | Community placement, governor authority and community health messages; the KV-sync snapshot request and response |
| 2 | METRICS | ClusterSync ping/pong, deployment and source metrics |
| 3 | INVOKE | Cross-node slice invocation requests and responses |
| 4 | FORWARD | HTTP, entity and stream forwards, replication |
| 5 | DHT | DHT puts, gets, removes, digests, migration |
| 6 | CONTROL | Hello handshake, KeepAlive, node discovery, leader pre-vote, worker metadata manifests |
| 7 | SYNC | Rabia `SyncRequest` and `SyncResponse`, worker metadata chunks |

Consensus messages (lane 0) cannot be starved by bulk transfers on other streams
thanks to QUIC's per-stream flow control.
