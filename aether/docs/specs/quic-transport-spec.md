# QUIC Transport Layer Specification

## Version: 1.0
## Status: Implementation-Ready
## Target: Pre V1.0.0 (v0.24.0)
## Last Updated: 2026-03-24

---

## 1. Overview

Replace TCP-based inter-node cluster networking with QUIC transport. Same message protocol, same consensus layer, different transport. Full switch — no TCP/QUIC coexistence in one cluster.

### 1.1 Motivation

- **0-RTT reconnection** — QUIC resumes connections without 3-way handshake, critical for partition healing
- **Stream multiplexing** — consensus messages can't be starved by bulk DHT transfers
- **No head-of-line blocking** — packet loss on one stream doesn't block others
- **Built-in TLS 1.3** — mandatory encryption, simplifies security configuration
- **Connection migration** — handles IP changes (cloud VM migration, container rescheduling)

### 1.2 Scope

- `QuicClusterNetwork` implementing existing `ClusterNetwork` interface
- Stream-per-message-type allocation strategy
- Config switch: `transport = "quic"` in TOML
- Same port number (UDP instead of TCP)

### 1.3 Non-Goals

- TCP/QUIC dual-stack coexistence (full switch only)
- SWIM transport change (already UDP, separate concern)
- Message protocol changes
- Consensus algorithm changes

---

## 2. Architecture

### 2.1 Component Overview

```
QuicClusterNetwork implements ClusterNetwork
├── QuicServer (listens on UDP port, accepts QUIC connections)
├── QuicPeerConnection (per peer)
│   ├── QUIC Connection (single logical connection over UDP)
│   ├── Stream 0: Consensus (Rabia proposals, votes, decisions, sync)
│   ├── Stream 1: KV-Store (Put, Get, Remove commands)
│   ├── Stream 2: HTTP forwarding (ForwardApplyRequest/Response)
│   └── Stream 3: DHT relay (DHTRelayMessage)
├── Encoder (existing — reused)
└── Decoder (existing — reused)
```

### 2.2 Stream Allocation Strategy

One QUIC connection per peer with dedicated streams per message type:

| Stream ID | Purpose | Lifecycle | Priority |
|-----------|---------|-----------|----------|
| 0 | Consensus (Rabia) | Long-lived | Highest |
| 1 | KV-Store commands | Long-lived | High |
| 2 | HTTP forwarding | Short-lived (per request) | Normal |
| 3 | DHT relay | Short-lived (per transfer) | Normal |

**Rationale:** Consensus must never be starved. A large DHT replication or HTTP forward should not delay a vote. QUIC's per-stream flow control enforces this automatically.

Streams 0-1 are opened once per connection and reused. Streams 2-3 are opened per exchange and closed after response.

---

## 3. Implementation

### 3.1 QuicClusterNetwork

**File:** `integrations/consensus/net/quic/QuicClusterNetwork.java`

Implements `ClusterNetwork` interface. Same contract as `NettyClusterNetwork`:
- `send(NodeId, Message)` — route to correct stream on peer's QUIC connection
- `broadcast(Message)` — send to all peers
- `start()` / `stop()` — lifecycle

**Message routing to streams:**
```java
private int streamIdFor(Message message) {
    return switch (message) {
        case RabiaProtocolMessage _ -> 0;
        case KVCommand _ -> 1;
        case ForwardApplyRequest _, ForwardApplyResponse _ -> 2;
        case DHTRelayMessage _ -> 3;
        default -> 0; // consensus stream as fallback
    };
}
```

### 3.2 QuicPeerConnection

**File:** `integrations/consensus/net/quic/QuicPeerConnection.java`

Wraps a QUIC connection to a single peer:
```java
record QuicPeerConnection(NodeId peerId,
                          QuicChannel connection,
                          QuicStreamChannel[] streams) {
    // streams[0] = consensus, streams[1] = kvstore (long-lived)
    // streams[2], [3] created on demand (short-lived)
}
```

### 3.3 QuicServer

**File:** `integrations/consensus/net/quic/QuicServer.java`

Listens on UDP port, accepts incoming QUIC connections:
```java
var quicCodec = Http3.newQuicServerCodecBuilder()
    .sslContext(quicSslContext)
    .maxIdleTimeout(30, TimeUnit.SECONDS)
    .initialMaxData(10_000_000)
    .initialMaxStreamDataBidirectionalLocal(1_000_000)
    .initialMaxStreamsBidirectional(16)
    .handler(connectionHandler)
    .build();

var bootstrap = new Bootstrap()
    .group(workerGroup)
    .channel(NioDatagramChannel.class)
    .handler(quicCodec);
bootstrap.bind(port).sync();
```

### 3.4 Framing

**Remove:** `LengthFieldBasedFrameDecoder` and `LengthFieldPrepender` — QUIC handles message boundaries via stream framing.

**Keep:** `Encoder` and `Decoder` — same serialization, write to `QuicStreamChannel` instead of `Channel`.

Each message is a complete QUIC frame. The decoder reads the full frame and deserializes.

### 3.5 Hello Handshake

Same protocol, different transport:
1. QUIC connection established (TLS 1.3 handshake included)
2. Initiator opens stream 0 (consensus stream)
3. Sends `NetworkMessage.Hello` with node ID and role
4. Responder sends Hello back on same stream
5. Connection registered in `peerLinks`

The Hello timeout (15s) and duplicate connection detection remain unchanged.

### 3.6 TLS

QUIC mandates TLS 1.3. Use existing `QuicSslContextFactory`:
- Reads from same `TlsConfig` (CertificateProvider SPI)
- mTLS: both sides present certificates
- Self-signed mode for development
- `clusterSecret` for deterministic CA generation (same as TCP mTLS)

**Simplification:** No need for separate TLS on/off config. QUIC always uses TLS. The `[tls]` TOML section configures certificate source only.

### 3.7 Reconnection

QUIC simplifies reconnection:
- **0-RTT resumption:** After first connection, subsequent connections skip full TLS handshake
- **Connection migration:** If a node's IP changes, QUIC transparently migrates
- **Idle timeout:** 30s default. If no activity, connection closes. Reopened on next send.

The current reconnection logic in `NettyClusterNetwork` (retry with backoff) is largely unnecessary. Replace with:
1. On send failure: reopen QUIC connection
2. On idle timeout: let QUIC close naturally, reconnect on next message
3. No explicit retry loop — QUIC's built-in mechanisms handle transient failures

### 3.8 Configuration

```toml
[cluster]
transport = "quic"   # "tcp" (default) or "quic"

[cluster.ports]
cluster = 8090       # same port, UDP instead of TCP

[tls]
# Same TLS config — QUIC always uses it
auto_generate = true
cluster_secret = "my-cluster-secret"
```

---

## 4. What Changes vs What Stays

| Component | Change | Details |
|-----------|--------|---------|
| `ClusterNetwork` interface | None | New implementation, same contract |
| `Encoder`/`Decoder` | None | Same serialization |
| All message types | None | Same protocol messages |
| Hello handshake | Transport only | Same protocol, QUIC stream instead of TCP channel |
| TLS | Simplifies | QUIC mandates TLS 1.3, no on/off toggle |
| Framing | Removes | QUIC built-in, no length-prefix needed |
| Reconnection | Simplifies | QUIC 0-RTT + connection migration |
| SWIM | None | Already on separate UDP |
| `peerLinks` | Type change | `Map<NodeId, QuicPeerConnection>` |
| Topology/discovery | None | Same mechanism |
| Consensus (Rabia) | None | Transport-agnostic |

---

## 5. Implementation Layers

Per evolutionary implementation protocol:

| Layer | Scope | Duration | Gate |
|-------|-------|----------|------|
| 1 | Types: `QuicPeerConnection`, stream ID mapping, config enum | 0.5 day | Compile, unit tests |
| 2 | `QuicServer`: accept QUIC connections, Hello handshake | 1 day | Unit test: 2 nodes connect and exchange Hello |
| 3 | `QuicClusterNetwork`: send/broadcast, stream routing, peer tracking | 1.5 days | Forge integration: 5 nodes form quorum over QUIC |
| 4 | Remove TCP framing, wire config switch in node assembly | 0.5 day | Full Forge test suite passes with `transport = "quic"` |
| 5 | Hardening: idle timeout tuning, reconnection edge cases | 0.5 day | Docker scaling test passes with QUIC |

**Total: 4 days**

---

## 6. Testing Strategy

### 6.1 Unit Tests
- Stream ID mapping for all message types
- QuicPeerConnection lifecycle (create, send, close)
- Config parsing (`transport = "quic"`)

### 6.2 Integration Tests (Forge)
- 5-node cluster formation over QUIC
- Consensus round completion
- KV-Store put/get
- HTTP forwarding between nodes
- Node failure and rejoin (QUIC reconnection)

### 6.3 Docker Scaling Test
- Existing 12-node scaling test with `transport = "quic"` in TOML
- Same pass criteria: 0% failure rate, SWIM stable

### 6.4 Performance Benchmark
- Latency comparison: TCP vs QUIC for consensus round-trip
- Throughput comparison: DHT replication under concurrent consensus load
- Reconnection time: TCP 3-way handshake vs QUIC 0-RTT

---

## 7. Risks

1. **QUIC tuning:** Default flow control parameters may not suit consensus patterns (small frequent messages). May need `initialMaxStreamData` tuning.
2. **Firewall/NAT:** Some corporate firewalls block UDP. Document requirement for UDP port access.
3. **Netty QUIC maturity:** Netty 4.2.9 promoted QUIC to stable, but real-world edge cases may exist. HTTP/3 server experience provides confidence.
4. **Debugging:** QUIC traffic is encrypted — harder to inspect with tcpdump/Wireshark. Document QUIC-specific debugging tools (qlog, Wireshark QUIC dissector with SSLKEYLOGFILE).

---

## 8. Prerequisites

- `QuicSslContextFactory` — complete (v0.23.1)
- `Http3Server` — complete (v0.23.1), proves QUIC server pattern
- `NettyHttpOperations` — complete (v0.23.1), proves QUIC client pattern
- `@CodecFor` validation — complete (v0.23.1), ensures serialization safety
