<!--
SPDX-License-Identifier: BUSL-1.1
Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
-->

# SWIM-Driven Topology Spec

> ⛔ **SUPERSEDED by [`cluster-topology-overhaul-spec.md`](cluster-topology-overhaul-spec.md) (2026-06-10).** SWIM→QUIC-direct mapping reversed by the June hardening wave; replaced by the FSM-mediated desired-set model. KEEP §5 (ANNOUNCE protocol — load-bearing wire format of record); §6 lifecycle table + Decisions 6–8 obsolete. Do not delete.

## 1. Status & Scope

- **Status:** Approved for RC1.
- **Date:** 2026-05-15.
- **Release target:** `1.0.0-rc1`.
- **Scope (in):** Peer lifecycle authority for the consensus transport. Defines a new SWIM ANNOUNCE message, the SWIM→QUIC lifecycle contract, and the removal of every alternative peer-introduction path that competes with SWIM.
- **Scope (out):** PEERS seed-list shaping (size limits, zone awareness, source awareness) — deferred to RC2 ticket #222. Rabia internals, persistence, slice routing — unchanged.
- **Audience:** Aether runtime contributors implementing the SWIM/QUIC/membership integration.

## 2. Problem Statement

`QuicClusterNetwork.evictStaleConnection()` (lines 897–902) reacts to a write failure by autonomously transitioning the peer back to `CONNECTING` and re-dialing. This decision is taken in the transport, with no input from SWIM and no consultation of the membership state machine.

In Docker (and in any environment where DNS entries outlive the resource), a killed container's hostname continues to resolve. UDP `connect()` succeeds against the stale address, the consensus send queue keeps draining into the void, and the backpressure window collapses. Once the queue is full, every outbound consensus message blocks. A CTM-provisioned replacement node that *should* take over the failed slot is starved of consensus progress and never reaches `ON_DUTY`.

A concrete observation from a chaos-recovery run on 2026-05-12: after `docker kill` of node-2 and node-3, the replacement node-4 (CTM-provisioned) flooded the QUIC backpressure queue toward the dead hostnames `aether-node-2` and `aether-node-3` for 30+ minutes. SWIM had declared both peers `FAULTY` within seconds; QUIC ignored that signal and kept reconnecting. The replacement stayed `OBSERVED`, never `ON_DUTY`.

Commit `e490be1ed` (2026-05-12) attempted to fix the cold-boot race in the opposite direction: it bridged `QUIC.PeerConnected` into `MembershipFsm` as a synthesised `SwimHealthy` observation. That bridge inverts causality. QUIC connections become a *source* of membership truth, so any transport-side reconnection loop — including the one this spec is meant to remove — can drag the FSM into thinking a dead peer is healthy. The bridge is incompatible with the new model and must be reverted before the rest of the design lands.

The two problems compose: QUIC self-reconnects to a dead address, the synthesised observation marks the peer healthy in the FSM, the FSM keeps the slot allocated, the replacement never gets the slot, and the cluster cannot recover.

## 3. Architecture Overview

```
   New node N joining cluster
   ──────────────────────────
                   ┌─────────────────────────┐
                   │  N.SwimDetector.start() │
                   └────────────┬────────────┘
                                │
                                ▼
              announceJoin(NodeInfo, clusterName, incarnation)
                                │
                ┌───────────────┼───────────────┐
                ▼               ▼               ▼
            seed[0]          seed[1]         seed[N]      (PEERS list)
            UDP :6100        UDP :6100       UDP :6100
                │               │               │
                └───────────────┼───────────────┘
                                ▼
                 ┌──────────────────────────────┐
                 │ Seed peer P: SwimMessage      │
                 │   .Announce decoder           │
                 │ - clusterName match? drop/warn│
                 │ - rate-limit per source IP    │
                 │ - duplicate NodeId? skip      │
                 └──────────────┬───────────────┘
                                ▼
                 SwimObservation.JoinAnnounced
                                │
                ┌───────────────┴────────────────┐
                ▼                                ▼
       MembershipFsm                     QuicClusterNetwork
       (state transition)                connect(NodeInfo)
                                                ▲
                                                │
                                Gossip piggyback: P → Q → R ...
                                (Ping/Ack already carry membership;
                                 Announce rides the same channel)

   Steady state
   ────────────
       SWIM observation                 QUIC action
       ────────────────                 ────────────
       JoinAnnounced                    connect(nodeInfo)
       HealthyObserved                  no-op
       FaultyObserved                   disconnect(nodeId)        ─ soft evict
       DepartedObserved                 departurePermanent(id)    ─ remove
       UnknownObserved                  no-op
```

The ANNOUNCE datagram is the only path by which a NodeId enters QUIC's address book. QUIC never adds, retains, or re-dials a peer of its own accord.

## 4. Design Decisions

Each decision is final. Rationale follows the bullet.

1. **ANNOUNCE payload = `NodeInfo` + `clusterName` + `incarnation`.**
   `NodeInfo` is the existing `org.pragmatica.consensus.net.NodeInfo` record (id, address, role, labels). Reusing it avoids a parallel "join descriptor" type. `clusterName` lets the receiver reject mismatched clusters at the UDP layer, before any TLS handshake and before any state-machine entry is allocated. `incarnation` lets a same-NodeId restart be distinguished from gossip echoes and lets the gossip layer keep the most recent version.

2. **PEERS mechanics stay as-is.**
   PEERS continues to be the static seed list used at boot. It is no longer the source of peer identity — only the source of *addresses to send ANNOUNCE to*. Shaping PEERS (size cap, zone/source awareness) is RC2 ticket #222.

3. **Retry ANNOUNCE to all PEERS seeds until quorum OR 30 s.**
   A joining node retransmits ANNOUNCE on a fixed cadence (1 Hz) to every PEERS entry until either (a) it has established QUIC connections to ⌈N/2⌉+1 peers — quorum is reachable, so SWIM gossip will carry the announcement the rest of the way — or (b) 30 s elapse. The 30 s timeout handles the quorum-restoring case: when the joiner *is* the third member of a 3-node cluster and the other live member depends on it to form quorum, no acknowledgement can come back, so the joiner must keep trying until consensus is rebuilt. After 30 s the joiner reports `JOIN_TIMEOUT` to the FSM and the supervisor decides retry/back-off.

4. **Rate limit: 10/s per source IP, using `core/.../RateLimiter.java`.**
   The token-bucket `RateLimiter` already exists in `core/src/main/java/org/pragmatica/lang/utils/RateLimiter.java`. Order of operations on receipt: parse → cluster name check → **known-NodeId check** → rate-limit check → enqueue observation. Filtering known NodeIds *before* the rate limiter means an already-known peer cannot consume the bucket of a noisy source; only genuinely new announcements pay.

5. **Cluster name mismatch: drop at UDP layer, WARN once per source IP.**
   A wrong-cluster ANNOUNCE is not a protocol error and not a security incident on its own; it is most often misconfiguration. A WARN log on the first occurrence per source IP makes the mistake visible without log-flooding. State is per-IP, not per-datagram.

6. **Revert commit `e490be1ed` (PeerConnected → SwimHealthy synthesis bridge).**
   The bridge was a point fix for the cold-boot probe-Ack race; in the new model that race is solved structurally by `JoinAnnounced` arriving *before* the QUIC dial. Keeping the synthesis would let any transport-level reconnection (including library-internal retries) corrupt SWIM's verdict. Full revert: `MembershipFsm.onPeerConnected`, `MembershipFsm.isKnownAliveClusterPeer`, the `REJECT_ALL_PEERS` predicate, and the `AetherNode` plumbing (`swimDetectorRef`, `isKnownStaticClusterPeer`, `isCurrentlySwimAlive`).

7. **Remove QUIC self-reconnect in `evictStaleConnection` (lines 897–902).**
   The transport must not initiate `CONNECTING` on its own. After a write failure it transitions to `EVICTED` and stops. Re-entry into `CONNECTING` only happens in response to a SWIM observation.

8. **QUIC missing-peer reconciler gates on SWIM health.**
   The existing 5 s reconciler tick that re-dials peers in `EVICTED` state must consult SWIM before dialing. Concretely: only re-dial when the peer's last SWIM observation is `HealthyObserved` or `JoinAnnounced`. `FaultyObserved` and `DepartedObserved` peers are not re-dialed by the reconciler.

9. **Remove KV-replay as a QUIC peer-seeding mechanism.**
   Today, KV-store replay on startup hands a peer list to QUIC, which then connects without SWIM ever validating those peers. This is a second, silent join path. After this spec lands, the only join path is SWIM ANNOUNCE. KV-replay still reconstructs FSM state, but it no longer talks to the transport.

## 5. ANNOUNCE Protocol

### 5.1 Message format

Two new types are introduced:

- **Transport datagram**
  `SwimMessage.Announce(NodeInfo nodeInfo, String clusterName, long incarnation)`
  Wire-encoded via the existing SWIM codec on UDP port 6100.

- **Observation**
  `SwimObservation.JoinAnnounced(NodeInfo nodeInfo, String clusterName, long incarnation)`
  Emitted by the SWIM detector after a successful receive (parse + cluster match + dedupe + rate-limit pass). Consumed by `MembershipFsm` and by `QuicClusterNetwork`'s SWIM observer.

### 5.2 Send path

```
new node startup
  ├─ SwimDetector.start()
  ├─ announceJoin(localNodeInfo, clusterName, incarnation = monotonic())
  │     ├─ schedule task at 1 Hz
  │     ├─ for each address in PEERS:
  │     │     send SwimMessage.Announce over UDP
  │     └─ stop when:
  │           - quorumConnections() >= ⌈N/2⌉+1, OR
  │           - elapsed >= 30 s  → report JOIN_TIMEOUT
  └─ on quorum reached: stop announce loop, normal SWIM Ping/Ack takes over
```

`incarnation` is a node-local monotonic counter persisted across restarts (reuses the existing incarnation track used by SWIM Ping). On same-NodeId restart, the higher incarnation wins; older gossip echoes are discarded.

### 5.3 Receive path

```
UDP packet on :6100
  ├─ decode → SwimMessage
  ├─ if not Announce → existing path (Ping/Ack/...)
  └─ if Announce:
        1. clusterName != self.clusterName → WARN once per source IP, drop
        2. nodeInfo.id ∈ membershipView (known) → drop (no observation emitted)
        3. RateLimiter.tryAcquire(sourceIp) == false → drop (DEBUG log)
        4. emit SwimObservation.JoinAnnounced(nodeInfo, clusterName, incarnation)
```

Ordering of steps 1–3 is load-bearing: see Decision 4. The receiver does not send a reply datagram. The sender's stop condition is its own QUIC connection count, not an ACK.

### 5.4 Gossip propagation

ANNOUNCE is delivered to seed peers only. Propagation to the rest of the cluster rides existing SWIM Ping/Ack piggybacking: the same channel that already carries `HealthyObserved`/`FaultyObserved` updates also carries the most-recent-known `JoinAnnounced` per NodeId, keyed by `(NodeId, incarnation)` with last-write-wins on incarnation. No new gossip mechanism is added.

### 5.5 Rate limiter integration

- Bucket: 10 tokens, refill 10/s, per source IP.
- Storage: `Map<InetAddress, RateLimiter>` in the SWIM UDP receiver, bounded by an LRU cap (existing pattern from request rate-limit on Management API).
- Drop policy: silent at INFO, DEBUG-log on drop for ops visibility.

## 6. SWIM → QUIC Lifecycle Mapping

`QuicClusterNetwork` subscribes to `SwimObservation` events. The mapping is exhaustive — every observation maps to exactly one transport action.

| SwimObservation       | QUIC action                          | Notes |
|-----------------------|--------------------------------------|-------|
| `JoinAnnounced`       | `connect(nodeInfo)`                  | Idempotent: connect-in-progress dedupe already exists. Address is taken from the gossip payload, not from PEERS or DNS. |
| `HealthyObserved`     | no-op                                | Steady-state membership; the connection (if any) is already correct. |
| `FaultyObserved`      | `disconnect(nodeId)` (soft evict)    | Drop the QUIC connection but keep the NodeId addressable. The reconciler will *not* re-dial (Decision 8). A subsequent `HealthyObserved` (peer recovered) re-introduces it via the same observation path used for joins. |
| `DepartedObserved`    | `departurePermanent(nodeId)`         | Terminal. Removes the NodeId from the address book entirely. Re-entry requires a fresh `JoinAnnounced` (new incarnation). |
| `UnknownObserved`     | no-op                                | Bootstrap-only transitional state; no transport action. |

Two invariants follow from this table:

- QUIC never holds a connection (or attempts one) for a NodeId that SWIM has not advertised.
- QUIC never *removes* a NodeId on its own; only `DepartedObserved` produces removal.

## 7. Startup Sequence

```
T0   AetherNode.start()
T0+  SwimDetector.start()                 — UDP :6100 bound, receive loop up
T0+  announceJoin(localNodeInfo,
                  clusterName,
                  incarnation)             — 1 Hz to all PEERS seeds
                                              (Decision 3)
T1   first seed receives Announce
       → emits JoinAnnounced               (Section 5.3)
       → MembershipFsm: UNKNOWN→OBSERVED
       → QuicClusterNetwork.connect(...)
T1+  QUIC handshake completes; backpressure window opens
T1+  Gossip piggyback carries the
       announcement to remaining peers     (Section 5.4)
T2   quorumConnections() >= ⌈N/2⌉+1
       → joiner stops announceJoin loop
T2+  Rabia proposals start flowing;
       FSM advances OBSERVED → ON_DUTY
       on first slot assignment

(failure path)
T0+30s  no quorum reached
        → JOIN_TIMEOUT
        → supervisor decides retry / backoff / surface to operator
```

This sequence makes the order explicit:
SWIM-up **before** announce; announce **before** QUIC connect; quorum **before** consensus participation; slot assignment **before** `ON_DUTY`.

## 8. What Was Removed and Why

**Commit `e490be1ed` synthesis bridge.**
The bridge let QUIC's `PeerConnected` callback inject a synthetic `SwimHealthy` observation into the membership FSM. It was a point fix for the cold-boot probe-Ack race, but it makes the transport a source of membership truth. Under the new model, the race is solved structurally — `JoinAnnounced` reaches the FSM *before* QUIC dials — and the bridge becomes actively harmful: any transport-level reconnection loop, including the one removed in this spec, could resurrect a dead peer in the FSM. Full revert.

**QUIC `evictStaleConnection` self-reconnect (lines 897–902).**
The transport was transitioning `EVICTED → CONNECTING` after a write failure, with no input from SWIM. Combined with DNS entries that survive container death, this produced an unbounded reconnect loop against dead hostnames and filled the consensus backpressure queue. The transport now stops at `EVICTED` and waits for a SWIM observation.

**KV-replay → QUIC peer seeding.**
On startup, KV-store replay handed a peer list directly to QUIC. This created a second join path that bypassed SWIM, ANNOUNCE, rate-limiting, and cluster-name validation. After this spec, KV-replay rebuilds FSM state only; the transport is seeded exclusively by `JoinAnnounced` observations.

## 9. Implementation Steps

| Step | Summary | Primary files |
|------|---------|---------------|
| **S1** | Add `SwimMessage.Announce` and `SwimObservation.JoinAnnounced` types; extend SWIM codec; route `Announce` through the receive path with cluster-name, dedupe, and rate-limit gates. | `integrations/swim/src/main/java/org/pragmatica/swim/SwimMessage.java`, `integrations/swim/src/main/java/org/pragmatica/swim/SwimObservation.java`, `integrations/swim/src/main/java/org/pragmatica/swim/...UdpReceiver.java`, `core/src/main/java/org/pragmatica/lang/utils/RateLimiter.java` (reuse) |
| **S2** | `announceJoin` send loop: 1 Hz to PEERS, stop on quorum or 30 s. | `integrations/swim/src/main/java/org/pragmatica/swim/SwimDetector.java`, `aether/aether-deployment/.../AetherNode.java` (wiring) |
| **S3** | Subscribe `QuicClusterNetwork` to `SwimObservation`; implement the lifecycle mapping table (Section 6); gate the 5 s reconciler on SWIM health. | `integrations/consensus/src/main/java/org/pragmatica/consensus/net/quic/QuicClusterNetwork.java` |
| **S4** | Remove `evictStaleConnection` self-reconnect (lines 897–902); remove KV-replay → QUIC peer seeding. | `integrations/consensus/src/main/java/org/pragmatica/consensus/net/quic/QuicClusterNetwork.java`, `aether/aether-deployment/.../AetherNode.java` |
| **S5** | Revert `e490be1ed`: drop `MembershipFsm.onPeerConnected`, `isKnownAliveClusterPeer`, `REJECT_ALL_PEERS`; drop `AetherNode.swimDetectorRef`, `isKnownStaticClusterPeer`, `isCurrentlySwimAlive`. | `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/membership/fsm/MembershipFsm.java`, `aether/aether-deployment/.../AetherNode.java` |
| **S6** | Gossip piggyback: extend SWIM Ping/Ack payload to carry most-recent `JoinAnnounced` per NodeId (LWW by incarnation). | `integrations/swim/src/main/java/org/pragmatica/swim/SwimMessage.java`, `integrations/swim/.../GossipState.java` |

Each step is independently testable. S1+S2 together let a node announce without changing QUIC behaviour (smoke). S3 alone, without S4/S5, is unsafe (two join paths). The expected commit order is S1, S2, S6, S3, S4, S5.

## 10. Postponed

- **RC2 ticket #222 — PEERS seed-list shaping.** Limiting the seed set size, zone-aware seed selection, and source-aware seed selection are deferred. The current PEERS mechanics are kept verbatim for RC1.

## References

### Internal

- `integrations/consensus/src/main/java/org/pragmatica/consensus/net/quic/QuicClusterNetwork.java` — transport whose lifecycle is being reshaped (self-reconnect at lines 897–902 to be removed).
- `integrations/consensus/src/main/java/org/pragmatica/consensus/net/NodeInfo.java` — payload record reused by ANNOUNCE.
- `integrations/swim/src/main/java/org/pragmatica/swim/SwimMessage.java` — extended with `Announce`.
- `integrations/swim/src/main/java/org/pragmatica/swim/SwimObservation.java` — extended with `JoinAnnounced`.
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/membership/fsm/MembershipFsm.java` — receives the new observation; bridge from `e490be1ed` to be removed.
- `core/src/main/java/org/pragmatica/lang/utils/RateLimiter.java` — token-bucket reused on the receive path.
- Commit `e490be1ed` — the synthesis bridge being reverted.
- Commit `9241cd0d9` — TTL-bounded DECOMMISSIONED revival, related but unchanged by this spec.
