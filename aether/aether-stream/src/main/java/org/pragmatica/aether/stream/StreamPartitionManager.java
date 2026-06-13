// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.TerminalOperation;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageReceiver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.LongPredicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;


public final class StreamPartitionManager implements AutoCloseable {
    private static final long DEFAULT_MAX_TOTAL_BYTES = 128 * 1024 * 1024L;
    private static final TimeSpan COMMIT_TIMEOUT = TimeSpan.timeSpan(10).seconds();
    private static final Logger log = LoggerFactory.getLogger(StreamPartitionManager.class);

    /// Default exhaustion sink — no-op. Wave 3 (`AetherNode`) replaces it with a binding to the
    /// cluster-event aggregator. See spec §4.5c.
    private static final Consumer<Exhaustion> NOOP_SINK = _ -> {};

    private final ConcurrentHashMap<String, StreamEntry> streams = new ConcurrentHashMap<>();
    private final AtomicLong totalAllocatedBytes = new AtomicLong(0);
    private final long maxTotalBytes;
    private final EvictionListener evictionListener;
    private final ReplicationManager replicationManager;
    private final Option<ClusterNode<KVCommand<AetherKey>>> clusterNode;
    private volatile Consumer<Exhaustion> exhaustionSink = NOOP_SINK;

    private StreamPartitionManager(long maxTotalBytes,
                                   EvictionListener evictionListener,
                                   ReplicationManager replicationManager,
                                   Option<ClusterNode<KVCommand<AetherKey>>> clusterNode) {
        this.maxTotalBytes = maxTotalBytes;
        this.evictionListener = evictionListener;
        this.replicationManager = replicationManager;
        this.clusterNode = clusterNode;
    }

    public static StreamPartitionManager streamPartitionManager() {
        return new StreamPartitionManager(DEFAULT_MAX_TOTAL_BYTES,
                                          EvictionListener.NOOP,
                                          ReplicationManager.NONE,
                                          Option.none());
    }

    public static StreamPartitionManager streamPartitionManager(long maxTotalBytes) {
        return new StreamPartitionManager(maxTotalBytes, EvictionListener.NOOP, ReplicationManager.NONE, Option.none());
    }

    public static StreamPartitionManager streamPartitionManager(long maxTotalBytes, EvictionListener evictionListener) {
        return new StreamPartitionManager(maxTotalBytes, evictionListener, ReplicationManager.NONE, Option.none());
    }

    public static StreamPartitionManager streamPartitionManager(long maxTotalBytes,
                                                                EvictionListener evictionListener,
                                                                ReplicationManager replicationManager) {
        return new StreamPartitionManager(maxTotalBytes, evictionListener, replicationManager, Option.none());
    }

    public static StreamPartitionManager streamPartitionManager(long maxTotalBytes,
                                                                EvictionListener evictionListener,
                                                                ReplicationManager replicationManager,
                                                                ClusterNode<KVCommand<AetherKey>> clusterNode) {
        return new StreamPartitionManager(maxTotalBytes, evictionListener, replicationManager, Option.some(clusterNode));
    }

    public static StreamPartitionManager streamPartitionManager(long maxTotalBytes,
                                                                ClusterNode<KVCommand<AetherKey>> clusterNode) {
        return new StreamPartitionManager(maxTotalBytes,
                                          EvictionListener.NOOP,
                                          ReplicationManager.NONE,
                                          Option.some(clusterNode));
    }

    public long totalAllocatedBytes() {
        return totalAllocatedBytes.get();
    }

    public long maxTotalBytes() {
        return maxTotalBytes;
    }

    /// Bytes available in the shared elastic pool: `maxTotalBytes − totalAllocatedBytes`. Telemetry
    /// and test accessor. See spec §4.4.
    public long availableBytes() {
        return maxTotalBytes - totalAllocatedBytes.get();
    }

    /// Install the budget-exhaustion sink (Wave 3 binds it to the cluster-event aggregator). Default
    /// is a no-op. See spec §4.5c / reconciliation #14.
    @Contract
    public void exhaustionSink(Consumer<Exhaustion> sink) {
        this.exhaustionSink = sink;
    }

    /// Atomically reserve `bytes` against the shared pool. Returns true iff the reservation fit under
    /// `maxTotalBytes`. CAS loop — correct under concurrent create and concurrent growth (replaces the
    /// former read-then-add TOCTOU). See spec §4.3.
    private boolean tryReserve(long bytes) {
        for (;;) {
            var current = totalAllocatedBytes.get();

            if (current + bytes > maxTotalBytes) {
                return false;
            }

            if (totalAllocatedBytes.compareAndSet(current, current + bytes)) {
                return true;
            }
        }
    }

    /// Return `bytes` to the shared pool. See spec §4.3.
    @Contract
    private void release(long bytes) {
        totalAllocatedBytes.addAndGet(-bytes);
    }

    /// Explicit STREAM_CREATE (`POST /api/streams`): create (materialize + publish) a stream, AWAITING
    /// the cluster-config consensus commit so the durability contract of an explicit create is preserved.
    /// The publish auto-create path uses {@link #ensureStreamMaterialized(StreamConfig)} instead, which
    /// shares all the materialization code below but fires the commit async. LOCAL materialization and
    /// CLUSTER-CONFIG publish are DECOUPLED so a transient consensus failure never destroys the
    /// already-materialized local partition (Fix #2). The already-materialized branch is split by COMMIT
    /// state so the hot per-publish path (`StreamApiRoutes.ensureStreamExists`, on EVERY publish) does
    /// not thrash consensus:
    ///
    ///   - **Materialized AND config committed** — return `STREAM_ALREADY_EXISTS` immediately, with NO
    ///     consensus round-trip. This is the steady-state path for every publish to an existing stream;
    ///     re-committing the (idempotent) config here floods consensus and — worse — surfaces a
    ///     transient commit failure as a publish failure to the caller, since
    ///     `recoverWhenAlreadyExists` only tolerates `STREAM_ALREADY_EXISTS`.
    ///   - **Materialized but NOT yet committed** — the first publish failed transiently (or this is a
    ///     fresh local ring whose config never committed). Re-attempt the idempotent publish so the
    ///     leader-pinned retry can commit it; success marks the entry committed and reports
    ///     `STREAM_ALREADY_EXISTS` (the retry "stop" signal), a transient failure surfaces for retry.
    ///     No re-materialization and no second byte allocation occur on this path.
    ///   - **Fresh stream** — validate (strong-mode, memory), materialize the local ring + reserve its
    ///     off-heap bytes, then publish the config. On publish failure the local ring is KEPT (NOT
    ///     rolled back): the owner can still serve/publish locally and the leader's reconcile retry can
    ///     re-publish the config. The reserved bytes are likewise kept (a later retry hits the
    ///     already-materialized path above and never re-reserves), so there is no double-allocation.
    ///
    /// Genuine, non-transient failures (`STREAM_MEMORY_EXCEEDED`, `AHSE_REQUIRED_FOR_STRONG`) are
    /// returned before any materialization, so they neither allocate nor publish.
    public Result<Unit> createStream(StreamConfig config) {
        return createStream(config, CommitMode.SYNC);
    }

    /// Publish-path create (Fix #2, option 1 — Hetzner 13-edge-cases Concurrent_deploy). Identical LOCAL
    /// materialization to {@link #createStream(StreamConfig)} — same STRONG/AHSE guard, floor admission,
    /// `StreamEntry.fromConfig` ring build, and put-if-absent race resolution, with every genuine
    /// local-materialization failure (`AHSE_REQUIRED_FOR_STRONG`, `STREAM_MEMORY_EXCEEDED`, native-OOM
    /// `fromConfig`) propagating UNCHANGED — but the cluster-config consensus `Put` is fired ASYNC (no
    /// `.await()`), so a still-catching-up leader's backpressured commit never stalls the publish HTTP
    /// path past its 5s forward timeout. The local ring is materialized and the entry is in `streams`
    /// (so the immediately-following `publishLocal` succeeds) before this returns. The decoupled commit
    /// latches the entry committed on success and is retried by the already-materialized-but-uncommitted
    /// path on the next publish if it fails. ONLY the consensus commit is decoupled — never a local
    /// failure. The committed-and-materialized steady state returns `Result.unitResult()` instantly with
    /// NO consensus round-trip (the same hot path `createStream` short-circuits, but as success rather
    /// than the `STREAM_ALREADY_EXISTS` sentinel the explicit-create contract requires).
    public Result<Unit> ensureStreamMaterialized(StreamConfig config) {
        return createStream(config, CommitMode.ASYNC);
    }

    private Result<Unit> createStream(StreamConfig config, CommitMode commitMode) {
        return option(streams.get(config.name())).fold(() -> createFreshStream(config, commitMode),
                                                       existing -> ensureConfigCommitted(config, existing, commitMode));
    }

    /// Already materialized. A committed config short-circuits with NO consensus: SYNC reports the
    /// `STREAM_ALREADY_EXISTS` sentinel the explicit-create duplicate contract requires, while ASYNC
    /// (publish path) reports plain success so the caller proceeds straight to `publishLocal`. A
    /// not-yet-committed entry re-attempts the idempotent publish so the leader-pinned retry can commit
    /// the config that a prior attempt failed to commit (SYNC awaits; ASYNC fires the retry async).
    private Result<Unit> ensureConfigCommitted(StreamConfig config, StreamEntry existing, CommitMode commitMode) {
        return existing.isCommitted()
               ? commitMode.alreadyCommitted()
               : republishExistingConfig(config, existing, commitMode);
    }

    /// Per-stream growth-admission seam. Wraps `tryReserve` so that a rejected growth segment (pool
    /// exhausted) fires the exhaustion sink tagged `phase=growth` for this stream. The buffer stays
    /// decoupled — it only sees a `LongPredicate`; the manager owns the event semantics. The buffer's
    /// own rate-of-fire is bounded by its growth attempts (once frozen it stops asking), and Wave 3
    /// adds the per-(stream,phase) 60s throttle on the sink side. See spec §4.5c / reconciliation #6.
    private boolean reserveForGrowth(StreamConfig config, long bytes) {
        if (tryReserve(bytes)) {
            return true;
        }

        exhaustionSink.accept(Exhaustion.growth(config, bytes, availableBytes(), maxTotalBytes));

        return false;
    }

    private Result<Unit> createFreshStream(StreamConfig config, CommitMode commitMode) {
        if (config.consistencyMode() == ConsistencyMode.STRONG && evictionListener == EvictionListener.NOOP) {
            return StreamError.General.AHSE_REQUIRED_FOR_STRONG.result();
        }

        var floorBytes = floorBytes(config);

        if (!tryReserve(floorBytes)) {
            return reportFloorExhaustion(config, floorBytes);
        }

        return StreamEntry.fromConfig(config,
                                      evictionListener,
                                      bytes -> reserveForGrowth(config, bytes),
                                      this::release)
                          .onFailure(_ -> release(floorBytes))
                          .flatMap(entry -> publishFreshEntry(config, entry, commitMode));
    }

    /// `fromConfig` succeeded — the local partitions are materialized. Resolve the put-if-absent race
    /// and publish/reconcile. A native-OOM failure of `fromConfig` never reaches here: the reserved
    /// floor budget was already released (bug #6) and the loud `STREAM_MEMORY_EXCEEDED` propagated.
    private Result<Unit> publishFreshEntry(StreamConfig config, StreamEntry entry, CommitMode commitMode) {
        return option(streams.putIfAbsent(config.name(), entry)).fold(() -> reserveAndPublish(config, entry, commitMode),
                                                                      winner -> closeLoserAndRepublish(config,
                                                                                                       entry,
                                                                                                       winner,
                                                                                                       commitMode));
    }

    /// Floor admission failed: release nothing (the floor reservation never succeeded), emit the
    /// exhaustion event to the sink, log WARN, and fail loud. No ring is built, nothing published.
    /// See spec §4.1 / §4.5.
    private Result<Unit> reportFloorExhaustion(StreamConfig config, long floorBytes) {
        log.warn("Off-heap budget exhausted creating stream '{}' ({} parts): need {} floor bytes, {} available of {}",
                 config.name(),
                 config.partitions(),
                 floorBytes,
                 availableBytes(),
                 maxTotalBytes);
        exhaustionSink.accept(Exhaustion.createFloor(config, floorBytes, availableBytes(), maxTotalBytes));

        return StreamError.General.STREAM_MEMORY_EXCEEDED.result();
    }

    /// Won the put-if-absent race: the floor bytes are already reserved by the atomic admission in
    /// `createFreshStream`. Publish the config; the commit (SYNC await / ASYNC fire) latches the entry
    /// committed on success so subsequent creates short-circuit. See spec §4.1.
    private Result<Unit> reserveAndPublish(StreamConfig config, StreamEntry entry, CommitMode commitMode) {
        return publishStreamConfig(config, entry, commitMode);
    }

    /// Lost the put-if-absent race: another thread already materialized the stream. Release this
    /// duplicate's floor reservation and close it (the buffer's seam-close releases its data bytes;
    /// the manager releases the control bytes it reserved), then reconcile against the winner.
    private Result<Unit> closeLoserAndRepublish(StreamConfig config,
                                                StreamEntry duplicate,
                                                StreamEntry winner,
                                                CommitMode commitMode) {
        releaseEntry(duplicate);

        return ensureConfigCommitted(config, winner, commitMode);
    }

    /// Re-publish the config for a stream whose local partition is already materialized but whose config
    /// never committed. The KV `Put` is idempotent; a successful (re-)commit latches the entry committed.
    /// SYNC awaits and reports `STREAM_ALREADY_EXISTS` (duplicate-create contract + retry "stop" signal),
    /// surfacing a transient publish failure so the leader-pinned retry can re-attempt; ASYNC fires the
    /// retry without blocking and reports plain success (the publish path proceeds to `publishLocal`; the
    /// next publish retries the commit if this one fails). Never re-materializes or re-reserves bytes.
    private Result<Unit> republishExistingConfig(StreamConfig config, StreamEntry entry, CommitMode commitMode) {
        return publishStreamConfig(config, entry, commitMode).flatMap(_ -> commitMode.republished());
    }

    public Result<Unit> destroyStream(String streamName) {
        return option(streams.remove(streamName)).toResult(new StreamError.StreamNotFound(streamName))
                     .flatMap(this::closeAndRelease)
                     .onSuccess(_ -> publishStreamConfigRemoval(streamName));
    }

    @Contract
    private void publishStreamConfigRemoval(String streamName) {
        clusterNode.onPresent(node -> applyRemoveCommand(node, streamName));
    }

    /// Publish the stream config to cluster KV, latching `entry` committed when the commit succeeds.
    /// With no cluster node (single-node / test manager) the config is "committed" instantly and the
    /// entry latched. SYNC awaits the consensus round-trip and surfaces a commit failure as
    /// `STREAM_CONFIG_COMMIT_FAILED` (explicit-create durability contract — unchanged). ASYNC fires the
    /// `Put` without blocking and returns `Result.unitResult()` immediately; the entry is latched by the
    /// async `onSuccess` callback, a transient failure is logged and retried by the next publish.
    private Result<Unit> publishStreamConfig(StreamConfig config, StreamEntry entry, CommitMode commitMode) {
        return clusterNode.fold(() -> latchCommitted(entry), node -> commitMode.publish(this, node, config, entry));
    }

    private Result<Unit> latchCommitted(StreamEntry entry) {
        entry.markCommitted();

        return success(unit());
    }

    @TerminalOperation
    private Result<Unit> applyPutCommand(ClusterNode<KVCommand<AetherKey>> node,
                                         StreamConfig config,
                                         StreamEntry entry) {
        return node.apply(putCommand(config))
                   .await(COMMIT_TIMEOUT)
                   .mapToUnit()
                   .onSuccess(_ -> entry.markCommitted())
                   .onFailure(cause -> log.debug("Failed to publish stream config for {}: {}",
                                                 config.name(),
                                                 cause.message()))
                   .mapError(_ -> StreamError.General.STREAM_CONFIG_COMMIT_FAILED);
    }

    /// Async sibling of {@link #applyPutCommand}: fire the idempotent config `Put` WITHOUT awaiting
    /// consensus (the publish-path decoupling — Fix #2). Latch the entry committed on the async success
    /// and log a transient failure (retried by the next publish's already-materialized-but-uncommitted
    /// path). Returns `Result.unitResult()` immediately so the publish HTTP path is never blocked by a
    /// still-catching-up leader's backpressured commit.
    @Contract
    private void applyPutCommandAsync(ClusterNode<KVCommand<AetherKey>> node, StreamConfig config, StreamEntry entry) {
        node.apply(putCommand(config)).onSuccess(_ -> entry.markCommitted()).onFailure(cause -> log.debug("Async stream config publish for {} not yet committed: {}",
                                                                                                          config.name(),
                                                                                                          cause.message()));
    }

    private static List<KVCommand<AetherKey>> putCommand(StreamConfig config) {
        var key = StreamConfigKey.streamConfigKey(config.name());
        var value = StreamConfigValue.streamConfigValue(config);

        return List.of(new KVCommand.Put<AetherKey, AetherValue>(key, value));
    }

    /// Fire-and-forget wrapper around {@link #applyPutCommandAsync}: fire the decoupled config `Put`
    /// and return `Result.unitResult()` immediately. Keeps the publish path off the consensus critical
    /// path while preserving the `Result<Unit>` shape the create chain composes over.
    private Result<Unit> fireAsyncCommit(ClusterNode<KVCommand<AetherKey>> node,
                                         StreamConfig config,
                                         StreamEntry entry) {
        applyPutCommandAsync(node, config, entry);

        return success(unit());
    }

    /// Commit strategy threaded through the shared create/materialize chain so `createStream` (explicit
    /// STREAM_CREATE — durable, awaits the consensus commit) and `ensureStreamMaterialized` (the publish
    /// auto-create path — fires the commit async so a still-catching-up leader never stalls the publish)
    /// share ALL local-materialization code (strong guard, floor admission, ring build, put-if-absent
    /// race). Only the consensus commit and the already-committed/republished sentinels differ. See spec
    /// §4.1 / Fix #2.
    private enum CommitMode {
        /// Explicit STREAM_CREATE: await the commit (durable); already-committed and republished both
        /// report the `STREAM_ALREADY_EXISTS` duplicate-create sentinel.
        SYNC {
            @Override
            Result<Unit> publish(StreamPartitionManager mgr,
                                 ClusterNode<KVCommand<AetherKey>> node,
                                 StreamConfig config,
                                 StreamEntry entry) {
                return mgr.applyPutCommand(node, config, entry);
            }

            @Override
            Result<Unit> alreadyCommitted() {
                return StreamError.General.STREAM_ALREADY_EXISTS.result();
            }

            @Override
            Result<Unit> republished() {
                return StreamError.General.STREAM_ALREADY_EXISTS.result();
            }
        },
        /// Publish auto-create path: fire the commit async (never blocks); already-committed and
        /// republished both report success so the caller proceeds straight to `publishLocal`.
        ASYNC {
            @Override
            Result<Unit> publish(StreamPartitionManager mgr,
                                 ClusterNode<KVCommand<AetherKey>> node,
                                 StreamConfig config,
                                 StreamEntry entry) {
                return mgr.fireAsyncCommit(node, config, entry);
            }

            @Override
            Result<Unit> alreadyCommitted() {
                return success(unit());
            }

            @Override
            Result<Unit> republished() {
                return success(unit());
            }
        };
        abstract Result<Unit> publish(StreamPartitionManager mgr,
                                      ClusterNode<KVCommand<AetherKey>> node,
                                      StreamConfig config,
                                      StreamEntry entry);
        abstract Result<Unit> alreadyCommitted();
        abstract Result<Unit> republished();
    }

    private void applyRemoveCommand(ClusterNode<KVCommand<AetherKey>> node, String streamName) {
        var key = StreamConfigKey.streamConfigKey(streamName);
        var remove = new KVCommand.Remove<AetherKey>(key);

        node.apply(List.of(remove)).onFailure(cause -> log.warn("Failed to publish stream config removal for {}: {}",
                                                                streamName,
                                                                cause.message()));
    }

    @Contract
    @MessageReceiver
    public void onStreamConfigPut(ValuePut<StreamConfigKey, StreamConfigValue> put) {
        var streamName = put.cause().key().streamName();
        var config = put.cause().value().config();

        streams.computeIfAbsent(streamName, _ -> hydrateEntry(config));
    }

    @Contract
    @MessageReceiver
    public void onStreamConfigRemove(ValueRemove<StreamConfigKey, StreamConfigValue> remove) {
        var streamName = remove.cause().key().streamName();

        removeAndReleaseIfPresent(streamName);
    }

    @SuppressWarnings("JBCT-RET-03")
    private void removeAndReleaseIfPresent(String streamName) {
        option(streams.remove(streamName)).onPresent(this::closeAndRelease);
    }

    /// Follower (apply/notification-thread) materialization from a committed `StreamConfigKey` Put.
    /// Reserves only the FLOOR (keeps apply-thread allocation small — strictly less work than the old
    /// eager full allocation). Per spec §5.2.4 / §8 / decision #6: if the floor cannot be admitted
    /// within budget, the entry is created ANYWAY — a follower must NOT diverge from committed cluster
    /// config. In that case the floor is added UNCONDITIONALLY (`addAndGet`, transient over-subscription
    /// past `maxTotalBytes`) rather than dropped, so the reserve/release accounting stays SYMMETRIC:
    /// the buffers always seed + release their first-segment via the seam and the manager always
    /// releases the control bytes on destroy. Dropping the floor here would leave the buffer seam
    /// releasing bytes the pool never reserved (a NEGATIVE leak). The growth seam still gates every
    /// later segment against the pool normally. WARN + event make the over-subscription visible.
    private StreamEntry hydrateEntry(StreamConfig config) {
        var floorBytes = floorBytes(config);

        if (!tryReserve(floorBytes)) {
            totalAllocatedBytes.addAndGet(floorBytes);
            log.warn("Follower over-subscribed floor ({} bytes) for committed stream '{}' to avoid cluster divergence (now {} of {})",
                     floorBytes,
                     config.name(),
                     totalAllocatedBytes.get(),
                     maxTotalBytes);
            exhaustionSink.accept(Exhaustion.createFloor(config, floorBytes, availableBytes(), maxTotalBytes));
        }
        // fromConfig threads the per-partition floor-allocation Result (bug #6): a native-OOM on any
        // partition closes the built siblings and fails. On that (rare) failure we release the reserved
        // floor budget and insert NO entry (computeIfAbsent null) — the node physically cannot allocate
        // the memory, so there is no committed partition to diverge from. markCommitted on success keeps
        // the follower's later createStream short-circuiting (no re-publish).
        return StreamEntry.fromConfig(config,
                                      evictionListener,
                                      bytes -> reserveForGrowth(config, bytes),
                                      this::release)
                          .onSuccess(StreamEntry::markCommitted)
                          .onFailure(_ -> hydrationFailed(config, floorBytes))
                          .or((StreamEntry) null);
    }

    @Contract
    private void hydrationFailed(StreamConfig config, long floorBytes) {
        release(floorBytes);
        log.warn("Follower could not materialize committed stream '{}' — off-heap floor allocation failed; entry not created",
                 config.name());
    }

    public Result<Long> publishLocal(String streamName, int partition, byte[] payload, long timestamp) {
        return resolveStreamEntry(streamName).flatMap(entry -> appendToPartition(entry,
                                                                                 streamName,
                                                                                 partition,
                                                                                 payload,
                                                                                 timestamp))
                                 .onSuccess(offset -> replicationManager.replicateEvent(streamName,
                                                                                        partition,
                                                                                        offset,
                                                                                        payload,
                                                                                        timestamp));
    }

    public Promise<Unit> awaitReplication(String streamName, int partition, long offset, int minAcks) {
        return replicationManager.awaitReplication(streamName, partition, offset, minAcks);
    }

    /// Append a backfilled event into the local partition ring WITHOUT re-triggering replication.
    /// Used by the A4 catch-up path: a freshly-assigned replica receiving events from an up-to-date
    /// source must land them locally but must NOT re-emit them onto the replication stream (it is the
    /// receiver, not an owner). Offsets are preserved because the ring assigns sequential offsets and
    /// catch-up replays the source's events in order into an empty partition.
    public Result<Long> appendRecovered(String streamName, int partition, byte[] payload, long timestamp) {
        return resolveStreamEntry(streamName).flatMap(entry -> appendToPartition(entry,
                                                                                 streamName,
                                                                                 partition,
                                                                                 payload,
                                                                                 timestamp));
    }

    private Result<Long> appendToPartition(StreamEntry entry,
                                           String streamName,
                                           int partition,
                                           byte[] payload,
                                           long timestamp) {
        return checkEventSize(entry, payload).flatMap(_ -> resolvePartitionBuffer(streamName, partition))
                             .flatMap(buffer -> buffer.append(payload, timestamp))
                             .onSuccess(_ -> entry.updateActivity());
    }

    public Option<OffHeapRingBuffer> partitionBuffer(String streamName, int partition) {
        return resolvePartitionBuffer(streamName, partition).option();
    }

    public Result<List<OffHeapRingBuffer.RawEvent>> readLocal(String streamName,
                                                              int partition,
                                                              long fromOffset,
                                                              int maxEvents) {
        return resolvePartitionBuffer(streamName, partition).flatMap(buffer -> buffer.read(fromOffset, maxEvents));
    }

    public Option<StreamInfo> streamInfo(String streamName) {
        return option(streams.get(streamName)).map(entry -> buildStreamInfo(streamName, entry));
    }

    public List<StreamInfo> listStreams() {
        return streams.entrySet()
                      .stream()
                      .map(e -> buildStreamInfo(e.getKey(),
                                                e.getValue()))
                      .toList();
    }

    /// Adapt this manager to the narrow {@link org.pragmatica.aether.stream.replication.StreamCatalog}
    /// consumed by `ReplicaSetController`. Exposes `(name, partitions, minSyncReplicas)` per stream —
    /// the per-stream `minSyncReplicas` is not carried by {@link StreamInfo}, so the controller cannot
    /// be fed by `listStreams()` alone; this accessor reads it straight from each stream's config.
    public org.pragmatica.aether.stream.replication.StreamCatalog replicaCatalog() {
        return new org.pragmatica.aether.stream.replication.StreamCatalog() {
            @Override
            public List<org.pragmatica.aether.stream.replication.StreamCatalog.StreamSpec> streams() {
                return StreamPartitionManager.this.streams.values()
                                             .stream()
                                             .map(entry -> entry.config())
                                             .map(config -> new StreamSpec(config.name(),
                                                                           config.partitions(),
                                                                           config.minSyncReplicas()))
                                             .toList();
            }

            @Override
            public boolean partitionHasData(String streamName, int partition) {
                return resolvePartitionBuffer(streamName, partition).map(buffer -> buffer.eventCount() > 0)
                                             .or(false);
            }
        };
    }

    public int reapIdleStreams() {
        var now = System.currentTimeMillis();
        var reaped = new AtomicInteger(0);

        streams.forEach((name, entry) -> reapIfIdle(name, entry, now, reaped));

        return reaped.get();
    }

    private void reapIfIdle(String name, StreamEntry entry, long now, AtomicInteger reaped) {
        var maxAge = entry.config().retention().maxAgeMs();
        var isEmpty = java.util.Arrays.stream(entry.partitions()).allMatch(b -> b.eventCount() == 0);
        var isExpired = (now - entry.createdAt()) > maxAge;
        var isIdle = (now - entry.lastActivity()) > maxAge;

        if (isEmpty && isExpired && isIdle) {
            var capturedActivity = entry.lastActivity();

            streams.computeIfPresent(name, (_, current) -> removeIfStillIdle(current, capturedActivity, reaped));
        }
    }

    @SuppressWarnings("JBCT-RET-03")
    private StreamEntry removeIfStillIdle(StreamEntry current, long capturedActivity, AtomicInteger reaped) {
        if (current.lastActivity() == capturedActivity) {
            closeAndRelease(current);
            reaped.incrementAndGet();

            return null;
        }

        return current;
    }

    @Contract
    @Override
    public void close() {
        streams.values().forEach(StreamEntry::close);
        streams.clear();
        totalAllocatedBytes.set(0);
    }

    private Result<StreamEntry> resolveStreamEntry(String streamName) {
        return option(streams.get(streamName)).toResult(new StreamError.StreamNotFound(streamName));
    }

    private static Result<Unit> checkEventSize(StreamEntry entry, byte[] payload) {
        if (payload.length > entry.config().maxEventSizeBytes()) {
            return new StreamError.EventTooLarge(payload.length,
                                                 entry.config().maxEventSizeBytes()).result();
        }

        return success(unit());
    }

    private Result<OffHeapRingBuffer> resolvePartitionBuffer(String streamName, int partition) {
        return option(streams.get(streamName)).toResult(new StreamError.StreamNotFound(streamName))
                     .flatMap(entry -> resolvePartitionInEntry(streamName, partition, entry));
    }

    private static Result<OffHeapRingBuffer> resolvePartitionInEntry(String streamName,
                                                                     int partition,
                                                                     StreamEntry entry) {
        if (partition < 0 || partition >= entry.partitions().length) {
            return new StreamError.PartitionOutOfRange(streamName, partition, entry.partitions().length).result();
        }

        return success(entry.partitions() [partition]);
    }

    private static StreamInfo buildStreamInfo(String name, StreamEntry entry) {
        var totalEvents = 0L;
        var totalBytes = 0L;

        for (var buffer : entry.partitions()) {
            totalEvents += buffer.eventCount();
            totalBytes += buffer.allocatedBytes();
        }

        return StreamInfo.streamInfo(name, entry.partitions().length, totalEvents, totalBytes);
    }

    private Result<Unit> closeAndRelease(StreamEntry entry) {
        releaseEntry(entry);

        return success(unit());
    }

    /// Release a stream's live budget and close it WITHOUT double-counting the buffer seam.
    ///
    /// Composition (see spec §4.3): at create the manager floor-reserved `Σ (control + firstSegment)`
    /// per partition. Each `OffHeapRingBuffer` separately tracks its own seam-`accountedBytes` =
    /// `firstSegment + grown data segments`, which it releases on `close()`. The manager therefore must
    /// release ONLY the **control** bytes (header + index) it reserved beyond the buffer's seam —
    /// releasing the control bytes FIRST, then `entry.close()` releases the data bytes via the seam.
    /// Sum released = `control + firstSegment + grown` = the live allocation. No double-release, no leak.
    @Contract
    private void releaseEntry(StreamEntry entry) {
        release(entry.controlBytes());
        entry.close();
    }

    /// Per-stream floor = `Σ_partitions OffHeapRingBuffer.floorBytes(maxCount, maxBytes)` =
    /// `(header + index + first-data-segment)` per partition. This is exactly the bytes the buffer
    /// allocates at construction (which it does NOT gate through the seam), so the manager owns the
    /// floor admission. See spec §4.1.
    private static long floorBytes(StreamConfig config) {
        var retention = config.retention();

        return OffHeapRingBuffer.floorBytes(retention.maxCount(), retention.maxBytes()) * config.partitions();
    }

    public Result<PartitionInfo> partitionInfo(String streamName, int partition) {
        return resolvePartitionBuffer(streamName, partition).map(buffer -> PartitionInfo.partitionInfo(partition,
                                                                                                       buffer.headOffset(),
                                                                                                       buffer.tailOffset(),
                                                                                                       buffer.eventCount()));
    }

    public Result<List<PartitionInfo>> allPartitionInfo(String streamName) {
        return option(streams.get(streamName)).toResult(new StreamError.StreamNotFound(streamName))
                     .map(StreamPartitionManager::buildAllPartitionInfo);
    }

    private static List<PartitionInfo> buildAllPartitionInfo(StreamEntry entry) {
        var infos = new ArrayList<PartitionInfo>();

        for (int i = 0; i < entry.partitions().length; i++) {
            var buffer = entry.partitions() [i];

            infos.add(PartitionInfo.partitionInfo(i, buffer.headOffset(), buffer.tailOffset(), buffer.eventCount()));
        }

        return List.copyOf(infos);
    }

    public record StreamInfo(String name, int partitions, long totalEvents, long totalBytes) {
        public static StreamInfo streamInfo(String name, int partitions, long totalEvents, long totalBytes) {
            return new StreamInfo(name, partitions, totalEvents, totalBytes);
        }
    }

    public record PartitionInfo(int partition, long headOffset, long tailOffset, long eventCount) {
        public static PartitionInfo partitionInfo(int partition, long headOffset, long tailOffset, long eventCount) {
            return new PartitionInfo(partition, headOffset, tailOffset, eventCount);
        }
    }

    /// Off-heap budget exhaustion signal handed to the injected sink. Node-id-agnostic by design —
    /// the Wave 3 aggregator stamps the node id when it converts this into a `ClusterEvent`. `phase`
    /// distinguishes create-floor exhaustion (loud, fails the create) from growth exhaustion (the
    /// buffer could not grow). See spec §4.5c / reconciliation #14.
    public record Exhaustion(String streamName,
                             int partitions,
                             Phase phase,
                             long requestedBytes,
                             long availableBytes,
                             long maxTotalBytes,
                             ConsistencyMode consistencyMode) {
        public enum Phase {
            CREATE_FLOOR,
            GROWTH
        }

        static Exhaustion createFloor(StreamConfig config,
                                      long requestedBytes,
                                      long availableBytes,
                                      long maxTotalBytes) {
            return new Exhaustion(config.name(),
                                  config.partitions(),
                                  Phase.CREATE_FLOOR,
                                  requestedBytes,
                                  availableBytes,
                                  maxTotalBytes,
                                  config.consistencyMode());
        }

        static Exhaustion growth(StreamConfig config, long requestedBytes, long availableBytes, long maxTotalBytes) {
            return new Exhaustion(config.name(),
                                  config.partitions(),
                                  Phase.GROWTH,
                                  requestedBytes,
                                  availableBytes,
                                  maxTotalBytes,
                                  config.consistencyMode());
        }

        public String summary() {
            return "Off-heap budget exhausted (" + phase.name()
                                                        .toLowerCase()
                                                        .replace('_', '-')
                 + ") for stream '" + streamName
                 + "' (" + partitions
                 + " parts): need " + requestedBytes
                 + " bytes, " + availableBytes
                 + " available of " + maxTotalBytes;
        }

        public Map<String, String> details() {
            return Map.of("streamName",
                          streamName,
                          "partitions",
                          Integer.toString(partitions),
                          "phase",
                          phase.name().toLowerCase().replace('_', '-'),
                          "requestedBytes",
                          Long.toString(requestedBytes),
                          "availableBytes",
                          Long.toString(availableBytes),
                          "maxTotalBytes",
                          Long.toString(maxTotalBytes),
                          "consistencyMode",
                          consistencyMode.name());
        }
    }

    record StreamEntry(StreamConfig config,
                       OffHeapRingBuffer[] partitions,
                       long createdAt,
                       AtomicLong lastActivityRef,
                       AtomicBoolean configCommitted) implements AutoCloseable {
        /// Build all partition buffers, threading the per-partition floor-allocation `Result` (bug #6):
        /// if any partition's floor `arena.allocate` fails with native OOM, the buffers that DID build
        /// are CLOSED (releasing their seam-accounted data bytes) and the failure is returned so the
        /// manager releases the reserved floor budget — no Arena leak, no budget leak, no escaped error.
        /// The aggregated failure is collapsed back to the canonical `STREAM_MEMORY_EXCEEDED` enum (every
        /// partition failure is that same cause) so the downstream identity / `transientCapacity()`
        /// retry-classification (StreamError §1) is preserved rather than buried in an `allOf` composite.
        /// On full success a committed-ready entry is returned.
        static Result<StreamEntry> fromConfig(StreamConfig config,
                                              EvictionListener listener,
                                              LongPredicate reserve,
                                              LongConsumer release) {
            var results = buildPartitions(config, listener, reserve, release);

            return Result.allOf(results)
                         .map(buffers -> entryOf(config,
                                                 buffers.toArray(OffHeapRingBuffer[]::new)))
                         .onFailure(_ -> closeBuilt(results))
                         .mapError(_ -> StreamError.General.STREAM_MEMORY_EXCEEDED);
        }

        private static List<Result<OffHeapRingBuffer>> buildPartitions(StreamConfig config,
                                                                       EvictionListener listener,
                                                                       LongPredicate reserve,
                                                                       LongConsumer release) {
            var retention = config.retention();
            var policy = deriveEvictionPolicy(config);
            var results = new ArrayList<Result<OffHeapRingBuffer>>(config.partitions());

            for (int i = 0; i < config.partitions(); i++) {
                results.add(OffHeapRingBuffer.offHeapRingBuffer(config.name(),
                                                                i,
                                                                retention.maxCount(),
                                                                retention.maxBytes(),
                                                                listener,
                                                                policy,
                                                                reserve,
                                                                release));
            }

            return results;
        }

        private static StreamEntry entryOf(StreamConfig config, OffHeapRingBuffer[] buffers) {
            var now = System.currentTimeMillis();

            return new StreamEntry(config, buffers, now, new AtomicLong(now), new AtomicBoolean(false));
        }

        /// On a partial-build failure, close every partition buffer that DID allocate (the others never
        /// opened an Arena) via `closeWithoutRelease` — freeing its native Arena but NOT returning its
        /// first-segment bytes to the seam, because the manager releases the ENTIRE reserved floor lump
        /// in one place (`createFreshStream`/`hydrateEntry`). Routing through `close()` here would
        /// double-release. No Arena leak, no budget skew.
        @Contract
        private static void closeBuilt(List<Result<OffHeapRingBuffer>> results) {
            results.forEach(r -> r.onSuccess(OffHeapRingBuffer::closeWithoutRelease));
        }

        /// Live bytes allocated across all partitions (control + allocated data segments). Used by
        /// telemetry and as the release-on-destroy basis. See spec §4.3.
        long allocatedBytes() {
            var total = 0L;

            for (var buffer : partitions) {
                total += buffer.allocatedBytes();
            }

            return total;
        }

        /// Control-region bytes (header + index) summed across partitions. This is the portion of the
        /// floor the manager reserved but the buffer's growth/close seam does NOT account, so the
        /// manager releases exactly this on destroy (the buffer releases its data bytes itself). See
        /// spec §4.3.
        long controlBytes() {
            var total = 0L;

            for (var buffer : partitions) {
                total += buffer.controlBytes();
            }

            return total;
        }

        /// Whether this stream's config has been committed to the cluster KV. Once true, a duplicate
        /// `createStream` returns `STREAM_ALREADY_EXISTS` without touching consensus.
        boolean isCommitted() {
            return configCommitted.get();
        }

        @Contract
        void markCommitted() {
            configCommitted.set(true);
        }

        long lastActivity() {
            return lastActivityRef.get();
        }

        @Contract
        void updateActivity() {
            lastActivityRef.set(System.currentTimeMillis());
        }

        private static EvictionPolicy deriveEvictionPolicy(StreamConfig config) {
            return config.consistencyMode() == ConsistencyMode.STRONG
                   ? EvictionPolicy.REJECT_WHEN_FULL
                   : EvictionPolicy.DROP_OLDEST;
        }

        @Contract
        @Override
        public void close() {
            for (var buffer : partitions) {
                buffer.close();
            }
        }
    }
}
