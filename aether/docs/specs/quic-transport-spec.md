# QUIC Transport Layer Specification

## Version: 1.1
## Status: Implementation-Ready
## Target: Pre V1.0.0 (v0.24.0)
## Last Updated: 2026-03-24

---

## 1. Overview

Replace TCP-based inter-node cluster networking with QUIC transport. Same message protocol, same consensus layer, different transport. Full switch — TCP transport removed entirely. No coexistence, no config toggle, no migration complexity.

### 1.1 Motivation

- **0-RTT reconnection** — QUIC resumes connections without 3-way handshake, critical for partition healing
- **Stream multiplexing** — consensus messages can't be starved by bulk DHT transfers
- **No head-of-line blocking** — packet loss on one stream doesn't block others
- **Built-in TLS 1.3** — mandatory encryption, eliminates TLS on/off config divergence
- **Connection migration** — handles IP changes (cloud VM migration, container rescheduling)
- **Framing built-in** — removes `LengthFieldBasedFrameDecoder`/`LengthFieldPrepender` entirely

### 1.2 Scope

- `QuicClusterNetwork` replaces `NettyClusterNetwork`
- Stream-per-message-type allocation strategy
- QUIC is the only cluster transport — no `transport` config key
- Same port number (UDP instead of TCP)
- Auto-generated self-signed certs for development (zero config)
- SWIM stays on its own separate UDP port (unchanged)

### 1.3 Non-Goals

- TCP fallback or dual-stack coexistence
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
├── Serializer (direct call — no Encoder handler)
└── Deserializer (direct call — no Decoder handler)
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

### 2.3 Worker Connection Model

Workers connect to all core nodes. Each worker opens one QUIC connection per core node with:
- Stream 1 (KV-Store) — for forwarded consensus writes
- Stream 2 (HTTP forwarding) — for slice invocation routing
- Stream 3 (DHT relay) — for worker-to-worker via governor relay

Workers do NOT use stream 0 (consensus) — they don't participate in Rabia.

### 2.4 Connection Initiation — NodeId Ordering

To prevent simultaneous-open race conditions:
- **Lower NodeId always initiates** (QUIC client role)
- **Higher NodeId always accepts** (QUIC server role)

This eliminates duplicate connection detection entirely. Each pair has exactly one connection with a deterministic initiator.

---

## 3. Implementation

### 3.1 QuicClusterNetwork

**File:** `integrations/consensus/net/quic/QuicClusterNetwork.java`

Replaces `NettyClusterNetwork`. Same `ClusterNetwork` contract:
- `send(NodeId, Message)` — serialize directly, write to correct QUIC stream
- `broadcast(Message)` — send to all peers
- `start()` / `stop()` — lifecycle

**Serialization:** Call `serializer.encode(message)` and `deserializer.decode(bytes)` directly. No Netty `Encoder`/`Decoder` handler pipeline — QUIC streams provide the framing.

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

Listens on UDP port, accepts incoming QUIC connections. Uses raw QUIC codec (NOT HTTP/3):

```java
var quicCodec = QuicServerCodecBuilder.create()
    .sslContext(quicSslContext)
    .maxIdleTimeout(30, TimeUnit.SECONDS)
    .initialMaxData(16_000_000)
    .initialMaxStreamDataBidirectionalLocal(4_000_000)
    .initialMaxStreamsBidirectional(64)
    .handler(connectionHandler)
    .streamHandler(streamHandler)
    .build();

var bootstrap = new Bootstrap()
    .group(workerGroup)
    .channel(NioDatagramChannel.class)
    .handler(quicCodec);
bootstrap.bind(port).sync();
```

**Note:** Uses `QuicServerCodecBuilder` (raw QUIC), NOT `Http3.newQuicServerCodecBuilder()` which adds HTTP/3 frame parsing. Cluster transport is raw QUIC with custom message framing.

### 3.4 Serialization — Direct Calls with Framing

**Remove:** `Encoder` and `Decoder` Netty channel handlers (TCP-specific).
**Keep:** `LengthFieldBasedFrameDecoder` and `LengthFieldPrepender` on QUIC stream pipelines.

QUIC streams are byte-oriented (ordered byte delivery), NOT message-oriented. Multiple writes can coalesce into a single read, or a single write can split. Length-prefix framing is still required to delimit individual messages within a stream.

**Serialization:** Call serializer/deserializer directly, with framing handled by the pipeline:
```java
// Send
var bytes = serializer.encode(message);
stream.writeAndFlush(Unpooled.wrappedBuffer(bytes));

// Receive (in stream handler)
var bytes = new byte[buf.readableBytes()];
buf.readBytes(bytes);
var message = deserializer.decode(bytes);
```

No abstraction layer — direct calls are clearer and simpler.

### 3.5 Hello Handshake

Same protocol, different transport:
1. Lower NodeId initiates QUIC connection to higher NodeId
2. TLS 1.3 handshake completes (mutual authentication)
3. Initiator opens stream 0 (consensus stream)
4. Sends `NetworkMessage.Hello` with node ID and role
5. Responder sends Hello back on same stream
6. Connection registered in `peerLinks`

The Hello timeout (15s) remains. Duplicate connection detection is eliminated by NodeId ordering rule.

### 3.6 TLS — Always On

QUIC mandates TLS 1.3. No on/off toggle.

**Development (Forge, Docker):**
- No TLS config in TOML → auto-generate ephemeral self-signed certs
- All nodes in the same cluster derive the same CA from `clusterSecret` seed
- `QuicSslContextFactory.generateSelfSigned()` already exists
- Zero config required

**Production:**
- `[tls]` section configures certificate source (files, provider SPI)
- `clusterSecret` for deterministic CA generation
- mTLS: both sides present certificates

```toml
[tls]
# Optional — if absent, auto-generate self-signed
auto_generate = true
cluster_secret = "my-cluster-secret"
```

### 3.7 Reconnection — Simplified

QUIC's built-in mechanisms replace the current retry-with-backoff logic:
- **0-RTT resumption:** Subsequent connections skip full TLS handshake
- **Connection migration:** IP changes handled transparently
- **Idle timeout:** 30s default. Connection closes naturally, reopened on next send.

Implementation:
1. On send failure: reopen QUIC connection immediately
2. On idle timeout: let QUIC close naturally, reconnect on next message
3. No explicit retry loop — no `SharedScheduler.schedule()` for reconnection

### 3.8 Configuration

No `transport` config key — QUIC is the only transport:

```toml
[cluster.ports]
cluster = 8090       # UDP port (same number as old TCP port)

[tls]
# Optional — auto-generates if absent
auto_generate = true
cluster_secret = "my-cluster-secret"
```

### 3.9 UDP Port Documentation

**Operator requirement:** Aether requires UDP port access between all cluster nodes on the cluster port. This is the same requirement SWIM already has (SWIM uses cluster_port + 1). Firewalls and security groups must allow UDP traffic.

Docker compose, Hetzner integration, and cloud provider firewall configs must be updated to explicitly open UDP on the cluster port.

---

## 4. What Changes vs What Stays

| Component | Change | Details |
|-----------|--------|---------|
| `ClusterNetwork` interface | None | New implementation, same contract |
| `NettyClusterNetwork` | Removed | Replaced by `QuicClusterNetwork` |
| `Encoder`/`Decoder` handlers | Removed | Direct serializer/deserializer calls |
| `LengthFieldBasedFrameDecoder` | Kept | QUIC streams are byte-oriented — framing still needed |
| `LengthFieldPrepender` | Kept | Length-prefix framing on QUIC stream pipeline |
| All message types | None | Same protocol messages |
| Hello handshake | Simplified | Same protocol, no duplicate detection needed (NodeId ordering) |
| TLS | Always on | No toggle, auto-generate for dev |
| Reconnection | Simplified | QUIC 0-RTT + connection migration, no retry loop |
| SWIM | None | Stays on separate UDP port |
| `peerLinks` | Type change | `Map<NodeId, QuicPeerConnection>` |
| Topology/discovery | None | Same mechanism |
| Consensus (Rabia) | None | Transport-agnostic |
| Docker compose | Minor | Document UDP port in compose files |
| Hetzner/cloud integration | Minor | Firewall rules include UDP |

---

## 5. Implementation Layers

Per evolutionary implementation protocol:

| Layer | Scope | Duration | Gate |
|-------|-------|----------|------|
| 1 | Types: `QuicPeerConnection`, stream ID mapping, auto-TLS wiring | 0.5 day | Compile, unit tests |
| 2 | `QuicServer`: accept QUIC connections, Hello handshake, NodeId ordering | 1 day | Unit test: 2 nodes connect and exchange Hello |
| 3 | `QuicClusterNetwork`: send/broadcast, direct serialization, stream routing, peer tracking | 1.5 days | Forge integration: 5 nodes form quorum over QUIC |
| 4 | Remove TCP transport (`NettyClusterNetwork`, `Encoder`/`Decoder`, framing), update Docker compose | 0.5 day | Full Forge test suite passes |
| 5 | Hardening: idle timeout tuning, reconnection edge cases, UDP port documentation | 0.5 day | Docker scaling test passes |

**Total: 4 days**

---

## 6. Testing Strategy

### 6.1 Unit Tests
- Stream ID mapping for all message types
- QuicPeerConnection lifecycle (create, send, close)
- NodeId ordering: verify lower ID always initiates
- Auto-TLS: verify self-signed cert generation when no TLS config

### 6.2 Integration Tests (Forge)
- 5-node cluster formation over QUIC
- Consensus round completion
- KV-Store put/get
- HTTP forwarding between nodes
- Node failure and rejoin (QUIC reconnection)
- Worker node connection (streams 1-3 only, no stream 0)

### 6.3 Docker Scaling Test
- Existing 12-node scaling test (updated for UDP)
- Same pass criteria: 0% failure rate, SWIM stable

### 6.4 Performance Benchmark
- Latency comparison: TCP vs QUIC for consensus round-trip
- Throughput comparison: DHT replication under concurrent consensus load
- Reconnection time: TCP 3-way handshake vs QUIC 0-RTT

---

## 7. Risks

1. **QUIC tuning:** Default flow control parameters may not suit consensus patterns (small frequent messages). May need `initialMaxStreamData` tuning.
2. **Firewall/NAT:** Some corporate firewalls block UDP. Document clearly: "Aether requires UDP port access between nodes." Same requirement as SWIM, but now more visible since all inter-node communication uses UDP.
3. **Netty QUIC maturity:** Netty 4.2.9 promoted QUIC to stable. HTTP/3 server experience in v0.23.1 provides confidence.
4. **Debugging:** QUIC traffic is encrypted — harder to inspect with tcpdump/Wireshark. Document QUIC-specific debugging: qlog, Wireshark QUIC dissector with `SSLKEYLOGFILE`.

---

## 8. Prerequisites

All complete in v0.23.1:
- `QuicSslContextFactory` — QUIC TLS context creation
- `Http3Server` — proves QUIC server pattern with Netty
- `NettyHttpOperations` — proves QUIC client pattern with Netty
- `@CodecFor` validation — ensures serialization safety
- Self-signed cert auto-generation — `QuicSslContextFactory.generateSelfSigned()`
