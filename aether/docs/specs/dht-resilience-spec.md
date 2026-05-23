<!-- SPDX-License-Identifier: BUSL-1.1 -->
<!-- Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko -->
<!-- Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0. -->

---
title: DHT Resilience — Layered Architecture for Chaos-Safe Writes
status: approved
target: RC1
related: aether/docs/specs/membership-architecture-spec.md, RFC-0012-resource-provisioning.md
---

# DHT Resilience: Layered Architecture for Chaos-Safe Writes

## Problem statement

Under chaos (peer kill, network partition, sustained write storms), DHT operations stall on quorum collectors waiting for responses from unreachable replicas. Failure modes observed at session 2026-05-17:

- **1MB artifact push hangs 49+ minutes**: 16-chunk fan-out, one chunk targets a half-broken QUIC channel, `writeIfWritable` silently drops, `QuorumCollector` waits for its per-op timeout (10s), `Promise.allOf` waits for slowest chunk — and the stuck chunk's failure surface never propagates upward fast enough.
- **08-resources/Deploy_SQL_app**: blueprint deploy takes 4s → 196s after a related transport regression. Resource KV writes lose to silent drops; route registration never reaches non-owner nodes.
- **Cluster B chaos cascade**: each kill-leader / kill-node test leaves a dying QUIC channel with thousands of `Channel ... not writable on stream CONSENSUS — dropping message` log lines.

Root cause is **layered**: every layer in the write stack has timeout-based failure detection instead of signal-based, so failures propagate at the slowest layer's rate. Stack:

```
ArtifactStore.deploy (30s outer)
  → Promise.allOf chunk fan-out (waits for ALL)
    → DistributedDHTClient.put (10s per op via promise.timeout(operationTimeout))
      → QuorumCollector (waits for W of N responses)
        → network.send (returns Unit — fire-and-forget, no failure signal)
          → writeIfWritable (silent drop on backpressure)
            → Netty writeAndFlush (async future, only logs failures)
```

Each layer can wait its full timeout before the layer above learns of failure. 16 chunks × 10s tail = 160s worst case for a 1MB push.

## How real DHTs solve this

| System | Strategy | Aether parallel |
|---|---|---|
| Cassandra | **Hinted handoff** — coordinator stores hint when target is down, replays on recovery | RC2 |
| Riak | **Sloppy quorum** — write to N reachable replicas, fallbacks tagged for owner | RC2 |
| etcd / CockroachDB | **Raft-per-shard** — each range is a consensus group; writes are consensus-bound | Different model |

Common thread: **writes target currently-alive replicas**, not the static hash ring. The ring describes ownership; runtime reachability determines actual targets.

## Architectural principle

**At every layer, failures must be EXPLICIT (synchronously propagated) and BOUNDED (timeout-protected at each level, with intermediate retry budgets).**

The fix is not "more buffering" or "longer timeouts" — both make the problem worse. The fix is to make every layer signal failure to the layer above, immediately, so decisions happen fast.

## Layered design (three layers, RC1 ships all three)

### Layer 1 — Transport signals failures synchronously

`writeIfWritable` (and its callers up through `network.send`) must report a verdict.

```java
public sealed interface WriteOutcome {
    record Sent() implements WriteOutcome {}              // queued in netty, send in progress
    record BackpressureRefused() implements WriteOutcome {} // channel at netty high-watermark — transient
    record ConnectionDead() implements WriteOutcome {}     // stream/connection gone — permanent
    record NoPeerState() implements WriteOutcome {}        // peer not in topology — permanent
}
```

- `Sent` → upstream waits for the receiver's response (existing behaviour)
- `BackpressureRefused` → upstream may retry or fail-fast against this target
- `ConnectionDead` / `NoPeerState` → upstream excludes this target from further attempts

**No silent drops.** Every caller gets a verdict immediately.

### Layer 2 — DHT routes only to currently-reachable replicas

`DistributedDHTClient` computes targets from `node.ring().targetNodes(key)`. Today this returns the static consistent-hash owners regardless of liveness. Change to:

```java
var ringTargets = node.ring().targetNodes(key);
var liveTargets = ringTargets.stream()
                             .filter(t -> network.connectedPeers().contains(t) || t.equals(node.nodeId()))
                             .toList();
// If liveTargets.size() < quorum: fail immediately with InsufficientReplicas
// Else: proceed with quorum collector sized to liveTargets.size()
```

The ring continues to describe **ownership** (which nodes are responsible for a key). The network describes **reachability** (which nodes can currently be addressed). DHT picks the intersection.

This restores correctness without the rebalance-storm of aggressive ring-pruning: the ring stays stable, but quorum is calculated against a smaller set when peers are temporarily unreachable.

### Layer 3 — QuorumCollector fails fast on synchronous refusals

`QuorumCollector` already has `onFailure(Cause)` which short-circuits when `failures > total - quorum`. The change is the **call site**: when `network.send` returns a non-`Sent` outcome, `DistributedDHTClient.sendRemote*` calls `collector.onFailure` immediately (not after the 10s timeout).

```java
private void sendRemotePut(NodeId target, byte[] key, byte[] value, long version,
                            QuorumCollector<Unit> collector) {
    var correlationId = KSUID.ksuid().toString();
    pendingOps.put(correlationId, new PendingOperation<>(collector));
    network.sendOutcome(target, new DHTMessage.PutRequest(...))
           .onSuccess(outcome -> {
               if (!(outcome instanceof WriteOutcome.Sent)) {
                   pendingOps.remove(correlationId);
                   collector.onFailure(toCause(outcome));
               }
           });
}
```

When 3 of 5 replicas are reachable but quorum=3, a refusal from any of the unreachable 2 immediately fails-fast the operation. Without waiting 10s.

## What this fix does NOT include

- **Hinted handoff** (Cassandra-style): deferred to RC2. Requires durable hint store + replay coordination.
- **Sloppy quorum with fallback nodes**: deferred to RC2. Requires fallback marking and read-repair.
- **Per-peer write-failure circuit breaker**: deferred to RC2. Not required for chaos-test correctness — the live-replica filter already routes around unreachable peers.

## Why this solves the observed failures

| Failure | Before | After |
|---|---|---|
| 1MB push (16 chunks, one stuck quorum) | 10-160s tail latency | <1s fail-fast OR success on live replicas |
| 08-resources/Deploy_SQL_app | 196s blueprint deploy | <10s (live targeting + fast-fail on dead replicas) |
| Cluster B chaos cascade | Stale Rabia replay confuses new peers | No global buffer — each write either lands or is reported failed |
| ArtifactStore.deploy hang | indefinite | Bounded by 30s outer timeout, but typically completes in <2s |

The architectural change converts a wait-and-mask pipeline into a signal-and-route-around pipeline.

## Implementation order (this session)

1. **Layer 1: `WriteOutcome` type + `writeIfWritable` returns it** — `QuicClusterNetwork.java`, ~30 LOC
2. **Layer 1 cont'd: propagation through `writeToStream` and `dispatchSerialized` SendNow** — `QuicClusterNetwork.java`, ~25 LOC
3. **Layer 1 cont'd: `ClusterNetwork.sendOutcome` API** — add new method (don't break existing `send`); implement in QuicClusterNetwork; NettyClusterNetwork stub-impls returning `Sent` — `ClusterNetwork.java` + impls, ~50 LOC
4. **Layer 3: `DistributedDHTClient.sendRemote*` reacts to refusals** — `DistributedDHTClient.java`, ~30 LOC
5. **Layer 2: live-replica filter in `DistributedDHTClient.put/get/remove/exists`** — `DistributedDHTClient.java`, ~20 LOC
6. **Unit tests** — `QuorumCollector`, `DistributedDHTClient`, write-outcome surface — ~150 LOC
7. **Integration validation** — full suite run on TARGET_HOST

## Verification

1. `mvn -pl integrations/consensus,integrations/dht test` — all green
2. `mvn -pl aether/node install -am -DskipTests` — rebuild succeeds
3. Integration suite on `--env remote`:
   - 09-artifacts: 1MB push completes successfully OR fails in <5s (no 49min hang)
   - 08-resources/Deploy_SQL_app: <10s duration
   - 02-chaos: still ~4p/0f for non-stale-id tests
   - Cluster B suites: no cascade from a single broken test

## Risks

- **`sendOutcome` is an additive API** but Rabia and other consensus consumers should NOT switch to it — they rely on broadcast-and-retry semantics. Only DHT uses it.
- **Live-replica filter** narrows the quorum set during partial unavailability. If quorum=3 and 2 replicas reachable, the put fails fast (deliberate). Callers must understand that "DHT unavailable for this key" is a real outcome under chaos, not a system bug. Rabia consensus has its own quorum logic and isn't affected.
- **`connectedPeers()` is a snapshot** — race between filter computation and dispatch is acceptable (worst case: send to a peer that just disconnected; outcome surface returns `ConnectionDead`; collector handles it).

## Out of scope

- Buffering / queueing under backpressure (the failed experiment) — explicitly rejected as not architecturally sound for chaos scenarios.
- Cross-stream-type prioritization — single CONSENSUS stream remains the dominant carrier.
