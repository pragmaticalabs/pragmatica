# Streaming Read Forwarding — Implementation Spec

Status: Approved for implementation
Target: 1.0.0-rc1
Tracking: —
Related: feature #141 (ReadPreference), #137 (cross-node publish routing), #134 (cluster zone support parent), #135 (zone-aware NEAREST, deferred), #136 (read-your-writes, deferred), #137 (chunking, deferred)

Source doc convention: every method or code block that implements a numbered section of this spec carries a `// SPEC: §N.M` comment referencing this file. A grep for `SPEC: §` across `aether-stream/` and related modules must round-trip against the sections below.

---

## §1. Goal

Make `ReadPreference` (`ANY_REPLICA` / `NEAREST`) actually route stream reads to a remote replica node. Today the code selects a replica and silently reads locally; the REST layer parses the parameter and discards it. Both paths must be wired end-to-end.

## §2. Non-goals

- Chunked / streaming multi-message responses — deferred, tracked in #137
- Topology / RTT-aware NEAREST scoring — deferred, tracked in #135
- Read-your-writes or monotonic read guarantees — deferred, tracked in #136
- Cross-cluster read forwarding
- Changes to `GOVERNOR` path (unchanged)

## §3. Wire protocol — `StreamForwardMessage`

Add to the existing sealed interface (file: `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/forward/StreamForwardMessage.java`).

```java
record ReadForward(NodeId sender,
                   String correlationId,
                   String streamName,
                   int partition,
                   long fromOffset,
                   int maxEvents) implements StreamForwardMessage {}

record ReadForwardResponse(NodeId sender,
                           String correlationId,
                           boolean success,
                           List<RawEventDto> events,
                           boolean truncated,
                           String errorMessage) implements StreamForwardMessage {}
```

And a new codec record in the same package:

```java
@Codec
record RawEventDto(long offset, long timestamp, byte[] data) {}
```

**§3.1 DTO vs RawEvent.** `OffHeapRingBuffer.RawEvent` is tightly coupled to buffer ownership and may wrap ByteBuffer slices. `RawEventDto` is a plain wire value — one-way conversion `RawEvent → RawEventDto` via helper `RawEventDto.fromRawEvent(RawEvent)`. Keeps wire format stable across buffer implementation changes.

**§3.2 `truncated` flag.** Server sets this to `true` when the defensive size cap (§10.5) trimmed the event list. Caller propagates the flag to observability but otherwise treats the response as a normal successful read with fewer events than requested.

## §4. Caller side — `StreamForwardClient`

File: `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/forward/StreamForwardClient.java`

**§4.1 New interface methods:**

```java
Promise<ReadForwardResult> readRemote(NodeId replicaId,
                                       String streamName,
                                       int partition,
                                       long fromOffset,
                                       int maxEvents);

@MessageReceiver
void onReadForwardResponse(ReadForwardResponse response);
```

**§4.2 `ReadForwardResult` record** (new, package-private):

```java
record ReadForwardResult(List<RawEventDto> events, boolean truncated) {}
```

Wrapping the list + truncated flag keeps the `Promise<T>` type clean (avoids `Promise<Pair<...>>` or nested Options).

**§4.3 Parallel pending map.** Add
`ConcurrentHashMap<String, Promise<ReadForwardResult>> pendingReads`
alongside existing `pendingRequests`. **Do not** unify the maps — Java type erasure makes a heterogeneous map unsafe and the duplication is ~25 LOC. Justified by simplicity.

**§4.4 Constructor signature change.**

```java
DefaultStreamForwardClient(NodeId selfNodeId,
                           StreamForwardTransport transport,
                           TimeSpan publishTimeout,
                           TimeSpan readTimeout)
```

Factory adds a split-timeout variant; the legacy single-timeout variant delegates with `readTimeout = publishTimeout` for backward compatibility within this change.

**§4.5 NOOP client.** `readRemote` returns `StreamForwardError.General.STREAM_FORWARD_UNAVAILABLE.promise()`. `onReadForwardResponse` is a no-op.

**§4.6 No self-routing in the client.** The client is dumb: whoever calls it must already know the target node is remote. `PartitionedStreamAccess` enforces self-skip (§6.2).

## §5. Receiver side — `StreamForwardHandler`

File: `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/forward/StreamForwardHandler.java`

**§5.1 New interface method:**

```java
@MessageReceiver
void onReadForward(ReadForward request);
```

**§5.2 Flow.**

1. Call `partitionManager.readLocal(streamName, partition, fromOffset, maxEvents)`
2. On success: convert `List<RawEvent>` → `List<RawEventDto>`, apply defensive cap (§10.5), build `ReadForwardResponse.successResponse(... truncated)`
3. On failure: build `ReadForwardResponse.failureResponse(... cause.message())`
4. Send via transport

**§5.3 Authorization.** Same trust model as publish forwarding: cluster nodes trust each other via mTLS-authenticated QUIC. No per-request API-key or principal check. Caveat: future RBAC may need to propagate caller identity — out of scope, noted in issue #136.

## §6. PartitionedStreamAccess wiring

File: `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/PartitionedStreamAccess.java`

**§6.1 New fields:**

```java
private final Option<StreamForwardClient> forwardClient;
private final NodeId selfNodeId;
```

Added to the full constructor. All `streamAccess(...)` factory overloads are updated: overloads without replica/preference support pass `none()` for forwardClient and a sentinel `NodeId.empty()` for selfNodeId (never consulted because the code path is guarded by `replicaRegistry.isPresent()`).

**§6.2 Rewritten `selectReplicaAndRead`** (pseudocode; final form lives in the file):

```java
private Promise<List<StreamEvent<T>>> selectReplicaAndRead(ReplicaRegistry registry,
                                                           int partition,
                                                           long fromOffset,
                                                           int maxEvents) {
    // SPEC: §6.2
    var caughtUp = registry.replicasFor(streamName, partition).stream()
                           .filter(PartitionedStreamAccess::isCaughtUp)
                           .toList();
    if (caughtUp.isEmpty()) return readPartition(...);            // SPEC: §6.2.a zero replicas
    return readWithPrimaryAndRetry(caughtUp, partition, fromOffset, maxEvents);
}
```

**§6.2.a** Zero caught-up replicas: fall back to local read. This is "nothing to try", not "tried and failed".

**§6.2.b** Self-selection skip: if the chosen replica is `selfNodeId`, call `readPartition(...)` directly — no wire hop.

**§6.2.c** No `forwardClient` (NOOP): fall back to local read immediately. Should only happen in test configurations.

**§6.2.d** Failure policy (per Q1 answer — b+c):

- Primary read fails or times out → try exactly one other caught-up replica (excluding the one that just failed)
- Second read fails or times out → return error to the caller (do **not** silently fall back to local)
- If only one caught-up replica exists → no retry, single attempt → error on failure

**§6.2.e** Self filtering: the replica set passed to `pickReplica` excludes `selfNodeId` first. If the set becomes empty after filtering, §6.2.a applies.

**§6.3 Helper shape:**

```java
private Promise<List<StreamEvent<T>>> readWithPrimaryAndRetry(List<ReplicaDescriptor> caughtUp,
                                                              int partition,
                                                              long fromOffset,
                                                              int maxEvents) {
    // SPEC: §6.2.d
    var remote = caughtUp.stream()
                         .filter(r -> !r.nodeId().equals(selfNodeId))
                         .toList();
    if (remote.isEmpty()) return readPartition(...);              // SPEC: §6.2.a post-filter
    var primary = pickReplica(remote);
    return forwardClient.map(c -> attemptRead(c, primary, remote, partition, fromOffset, maxEvents))
                        .or(() -> readPartition(...));            // SPEC: §6.2.c
}

private Promise<List<StreamEvent<T>>> attemptRead(StreamForwardClient client,
                                                  ReplicaDescriptor primary,
                                                  List<ReplicaDescriptor> pool,
                                                  int partition, long fromOffset, int maxEvents) {
    // SPEC: §6.2.d primary attempt
    return client.readRemote(primary.nodeId(), streamName, partition, fromOffset, maxEvents)
                 .map(result -> decodeAll(result.events()))
                 .recover(cause -> retryOrFail(client, primary, pool, partition, fromOffset, maxEvents, cause));
}
```

Retry shape selects one other replica from the pool (excluding primary), makes one more attempt, and on second failure returns the error.

## §7. AetherNode wiring

File: `aether/node/src/main/java/org/pragmatica/aether/node/AetherNode.java`

**§7.1** Register two new message routes:
```java
allEntries.add(MessageRouter.Entry.route(StreamForwardMessage.ReadForward.class,
                                         streamForwardHandler::onReadForward));
allEntries.add(MessageRouter.Entry.route(StreamForwardMessage.ReadForwardResponse.class,
                                         streamForwardClient::onReadForwardResponse));
```

**§7.2** Read split timeouts from cluster config (see §8) and pass both into `StreamForwardClient.streamForwardClient(self, transport, publishTimeout, readTimeout)`.

**§7.3** Thread `streamForwardClient` + `config.self()` into the place where `PartitionedStreamAccess` is built. Today that is `StreamingCoordinator` and/or slice resource providers — the concrete wiring site is discovered during implementation (documented in commit message).

## §8. Configuration — `[streaming]` section in cluster config

**§8.1** New TOML keys:

```toml
[streaming]
publish_forward_timeout = "5s"
read_forward_timeout = "2s"
max_read_response_bytes = "28MB"
```

**§8.2** Parser changes: add a `StreamingConfig` record (or equivalent) to cluster config parsing, with the three fields above. Defaults apply if section or keys are omitted.

**§8.3** Backward compatibility: absence of `[streaming]` block uses defaults — no breaking change for existing `aether.toml` files.

**§8.4** Wiring: `StreamingConfig` lives in the cluster config object, read at `AetherNode` startup, passed into client constructors.

## §9. REST layer — `StreamRoutes`

File: `aether/node/src/main/java/org/pragmatica/aether/api/routes/StreamRoutes.java`

**§9.1** `readEvents` currently parses `ReadPreference` and ignores it at L233-242. Fix the full path.

**§9.2** Wire through: either (a) add `readLocal(name, partition, fromOffset, maxEvents, preference)` overload to `StreamPartitionManager` that resolves the per-stream `PartitionedStreamAccess.readWithPreference(...)`, or (b) acquire the per-stream `StreamAccess` directly in `StreamRoutes` via the stream registry and call its `fetch(partition, ..., preference)` path.

**§9.3** Chosen approach (to be finalized during implementation): (a) is preferred because it keeps `StreamRoutes` thin and avoids leaking stream-registry details into REST routing. Final decision recorded in commit message.

## §10. Edge cases & future work

### §10.1 Metrics counters — **in scope**

Four Micrometer counters in `DefaultStreamForwardClient`:
- `aether.streams.read.forward.attempts`
- `aether.streams.read.forward.success`
- `aether.streams.read.forward.fallback` (fires on retry-to-second-replica; distinct from failure)
- `aether.streams.read.forward.timeout`
- `aether.streams.read.forward.truncated` (server-side, fires on §10.5 cap)

Tags: `result` on the outcome counter only. **No stream-name tag** — cardinality risk. Registered in the handler & client constructors.

### §10.2 Zone-aware NEAREST — **deferred, issue #135**

Today `NEAREST == ANY_REPLICA` — both pick a random caught-up replica. The data to do better exists (`NodeInfo.labels.zone`). Deferred because adding multi-zone topology fixtures and picking a distance metric belongs in its own change with its own benchmarks. See issue #135.

### §10.3 Freshness drift — **server-side free, caller-side deferred**

A replica marked CAUGHT_UP can still trail past `fromOffset` by milliseconds. Server-side: `partitionManager.readLocal(...)` already returns whatever events exist ≥ fromOffset, possibly empty. No extra code. Caller treats empty response identically to a local empty read. No error.

Caller-side freshness preference (pick the freshest replica first) is deferred to issue #135 along with zone-aware routing.

### §10.4 Read-your-writes — **deferred, issue #136**

Documented in `ReadPreference` enum javadoc as not guaranteed when `preference != GOVERNOR`. On-demand, no target date.

### §10.5 Large response cap — **defensive cap in scope, chunking deferred to #137**

Server-side in `StreamForwardHandler.onReadForward`:

```java
// SPEC: §10.5 defensive cap
long total = 0;
var capped = new ArrayList<RawEventDto>();
for (var event : events) {
    var next = total + estimateFramedSize(event);
    if (next > maxReadResponseBytes) break;
    capped.add(event);
    total = next;
}
boolean truncated = capped.size() < events.size();
```

`maxReadResponseBytes` comes from `[streaming] max_read_response_bytes` (§8.1). Default `28MB` leaves headroom below the 32MB QUIC frame limit.

Truncation is not an error — caller sees fewer events than requested, same semantics as `partitionManager.readLocal` already has (segment boundaries). The `truncated=true` flag in `ReadForwardResponse` allows observability and optionally a caller-side follow-up request. The counter `aether.streams.read.forward.truncated` tracks hit rate.

Multi-message chunking (for reads > 28MB) is **deferred** to issue #137.

## §11. Tests

### §11.1 `StreamForwardClientTest`

New test methods:
- `readRemote_success_resolvesPromise`
- `readRemote_timeout_returnsForwardTimeout`
- `readRemote_failureResponse_returnsForwardFailed`
- `readRemote_orphanedResponse_isDropped`
- `readRemote_multiplePending_correlatedByCorrelationId`
- `readRemote_separateTimeoutFromPublish_honored`

### §11.2 `StreamForwardHandlerTest`

New test methods:
- `onReadForward_success_sendsResponseWithEvents`
- `onReadForward_partitionNotLocal_sendsFailureResponse`
- `onReadForward_readLocalFailure_sendsFailureResponse`
- `onReadForward_oversizedResponse_truncatesAndSetsFlag` (§10.5)

### §11.3 `PartitionedStreamAccessTest`

New test methods:
- `selectReplicaAndRead_zeroCaughtUpReplicas_readsLocal` (§6.2.a)
- `selectReplicaAndRead_selfIsOnlyReplica_readsLocal` (§6.2.a post-filter)
- `selectReplicaAndRead_noForwardClient_readsLocal` (§6.2.c)
- `selectReplicaAndRead_singleRemoteReplica_success` (primary attempt)
- `selectReplicaAndRead_singleRemoteReplica_failure_noRetry_returnsError` (§6.2.d edge)
- `selectReplicaAndRead_twoReplicas_primaryFails_secondSucceeds` (§6.2.d retry)
- `selectReplicaAndRead_twoReplicas_bothFail_returnsError` (§6.2.d both-fail)
- `selectReplicaAndRead_twoReplicas_retryExcludesPrimary` (correctness of retry pool)

### §11.4 Integration / forge

One multi-node forge test: 2-node cluster, publish from node A, consume from node B with `ReadPreference.ANY_REPLICA`, assert events delivered via the forward path (counter `aether.streams.read.forward.success > 0`).

### §11.5 Config parsing test

Add a cluster-config parse test: `[streaming]` section with custom values is honored; absence falls through to defaults.

## §12. Spec backreference audit

After implementation is complete:

1. Every section listed under §3–§10 that promises code must have at least one `// SPEC: §N.M` comment in the implementation file
2. Every `// SPEC: §N.M` comment in `aether-stream/`, `aether/node/`, and related must point to a real section in this doc
3. Grep command for audit:
   ```
   grep -rn "SPEC: §" aether/aether-stream aether/node aether/slice-api
   ```
4. The audit is a checklist item in the PR description

---

## Open decisions that can change during implementation

- **§7.3 concrete wiring site** — `StreamingCoordinator` vs slice resource factory vs both. Decided during implementation, documented in commit message
- **§9.3 REST wiring** — approach (a) vs (b) in §9.2. Decided during implementation, documented in commit message
- **§10.1 fallback vs failure counter naming** — may be `aether.streams.read.forward.retry` instead of `fallback`. Decided at metrics-emission site

Everything else is locked.
