// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.fence.OwnershipDomain;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.stream.replication.ReplicaSetController;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.wal.PartitionWal;
import org.pragmatica.aether.stream.wal.PartitionWal.WalRecord;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.NullReturn;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.TerminalOperation;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageReceiver;

import java.nio.file.Path;
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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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

    /// Default placement-role supplier (#265 increment 1): reports [ReplicaSetController.Role#OWNER]
    /// for every `(stream, partition)` — the always-materialize behavior that preserves the pre-seam
    /// semantics exactly. `AetherNode` late-binds the real [ReplicaSetController#roleFor].
    private static final PlacementRoleSupplier ALWAYS_OWNER = (_, _) -> ReplicaSetController.Role.OWNER;

    private final ConcurrentHashMap<String, StreamEntry> streams = new ConcurrentHashMap<>();
    private final AtomicLong totalAllocatedBytes = new AtomicLong(0);
    private final long maxTotalBytes;
    private final EvictionListener evictionListener;
    private final ReplicationManager replicationManager;
    private final Option<ClusterNode<KVCommand<AetherKey>>> clusterNode;
    /// Per-`(stream, partition)` epoch high-water gate (#345 item 1d-ii). [Option#none] = fence-free
    /// (the non-cluster / legacy factories), mirroring the DHT engine's `OwnerEpochGate.noOp`. When
    /// present, an append whose presented owner epoch is STRICTLY older than the partition high-water
    /// is rejected at this replica's commit point (before `buffer.append`) with a
    /// [StreamError.StaleEpochAppend] — a deposed owner is rejected everywhere.
    private final Option<OwnershipEpochHighWater> epochHighWater;
    /// Source of THIS node's current owner epoch for stamping a LOCAL publish (`publishLocal`). The
    /// floor source ([StreamOwnerEpochSource#zero]) leaves non-fenced callers stamping [Epoch#ZERO],
    /// which a fresh high-water never rejects and which never advances it.
    private final StreamOwnerEpochSource ownerEpochSource;
    /// Per-partition crash-durable write-ahead log root (streaming-persistence W3/W6). [Option#none]
    /// = no WAL ⇒ exactly the pre-WAL behavior (Forge/unit/legacy factories). When present, each
    /// partition opens its own [PartitionWal] under `<walBaseDir>/<streamName>/<partition>.wal` at
    /// ring-create time, and an OWNER publish (`publishLocal`) does not ack until the event is
    /// fsync-durable in that WAL. The replica-receive path (`appendRecovered`) never writes the WAL.
    private final Option<Path> walBaseDir;
    /// Source of the durable last-sealed offset per `(stream, partition)` (streaming-persistence W4).
    /// Bounds WAL replay when a partition ring is (re)built: sealed segments already serve
    /// `[0, lastSealedOffset]`, so a recovered ring is seeded above that bound and replays only the
    /// un-sealed WAL tail at its original offsets. The floor source ([LastSealedOffsetSource#none] →
    /// `-1`) leaves Forge/unit/legacy callers replaying the whole log from offset 0; the aether-level
    /// wiring binds it to the node's [org.pragmatica.aether.stream.segment.SegmentIndex].
    private final LastSealedOffsetSource lastSealedOffset;
    private volatile Consumer<Exhaustion> exhaustionSink = NOOP_SINK;
    /// Placement-role seam (#265 increment 1): consulted per `(stream, partition)` so a later increment
    /// can gate ring materialization on placement (materialize iff OWNER/REPLICA). Defaults to
    /// [#ALWAYS_OWNER]; `AetherNode` late-binds [ReplicaSetController#roleFor] AFTER the controller is
    /// constructed (it is built after this manager — the same construction-order inversion the
    /// `streamPartitionManagerRef` seam resolves). Volatile: set once at wiring, read on the snapshot
    /// path. Increment 1 only plumbs it in ready-to-use; buildPartitions still materializes every ring.
    private volatile PlacementRoleSupplier placementRoleSupplier = ALWAYS_OWNER;

    private StreamPartitionManager(long maxTotalBytes,
                                   EvictionListener evictionListener,
                                   ReplicationManager replicationManager,
                                   Option<ClusterNode<KVCommand<AetherKey>>> clusterNode,
                                   Option<OwnershipEpochHighWater> epochHighWater,
                                   StreamOwnerEpochSource ownerEpochSource,
                                   Option<Path> walBaseDir,
                                   LastSealedOffsetSource lastSealedOffset) {
        this.maxTotalBytes = maxTotalBytes;
        this.evictionListener = evictionListener;
        this.replicationManager = replicationManager;
        this.clusterNode = clusterNode;
        this.epochHighWater = epochHighWater;
        this.ownerEpochSource = ownerEpochSource;
        this.walBaseDir = walBaseDir;
        this.lastSealedOffset = lastSealedOffset;
    }

    public static StreamPartitionManager streamPartitionManager() {
        return new StreamPartitionManager(DEFAULT_MAX_TOTAL_BYTES,
                                          EvictionListener.NOOP,
                                          ReplicationManager.NONE,
                                          Option.none(),
                                          Option.none(),
                                          StreamOwnerEpochSource.zero(),
                                          Option.none(),
                                          LastSealedOffsetSource.none());
    }

    public static StreamPartitionManager streamPartitionManager(long maxTotalBytes) {
        return new StreamPartitionManager(maxTotalBytes,
                                          EvictionListener.NOOP,
                                          ReplicationManager.NONE,
                                          Option.none(),
                                          Option.none(),
                                          StreamOwnerEpochSource.zero(),
                                          Option.none(),
                                          LastSealedOffsetSource.none());
    }

    public static StreamPartitionManager streamPartitionManager(long maxTotalBytes, EvictionListener evictionListener) {
        return new StreamPartitionManager(maxTotalBytes,
                                          evictionListener,
                                          ReplicationManager.NONE,
                                          Option.none(),
                                          Option.none(),
                                          StreamOwnerEpochSource.zero(),
                                          Option.none(),
                                          LastSealedOffsetSource.none());
    }

    public static StreamPartitionManager streamPartitionManager(long maxTotalBytes,
                                                                EvictionListener evictionListener,
                                                                ReplicationManager replicationManager) {
        return new StreamPartitionManager(maxTotalBytes,
                                          evictionListener,
                                          replicationManager,
                                          Option.none(),
                                          Option.none(),
                                          StreamOwnerEpochSource.zero(),
                                          Option.none(),
                                          LastSealedOffsetSource.none());
    }

    public static StreamPartitionManager streamPartitionManager(long maxTotalBytes,
                                                                EvictionListener evictionListener,
                                                                ReplicationManager replicationManager,
                                                                ClusterNode<KVCommand<AetherKey>> clusterNode) {
        return new StreamPartitionManager(maxTotalBytes,
                                          evictionListener,
                                          replicationManager,
                                          Option.some(clusterNode),
                                          Option.none(),
                                          StreamOwnerEpochSource.zero(),
                                          Option.none(),
                                          LastSealedOffsetSource.none());
    }

    /// Fence-enabled factory (#345 item 1d-ii): every local and replicated-receive append is
    /// owner-epoch-fenced against `epochHighWater` (the per-`(stream, partition)` domain high-water,
    /// CP-seeded and observe-advanced by 1d-i). Local publishes are stamped with this node's current
    /// owner epoch from `ownerEpochSource`; replicated batches carry the sending owner's epoch on the
    /// wire. The aether-level wiring supplies both.
    public static StreamPartitionManager streamPartitionManager(long maxTotalBytes,
                                                                EvictionListener evictionListener,
                                                                ReplicationManager replicationManager,
                                                                ClusterNode<KVCommand<AetherKey>> clusterNode,
                                                                OwnershipEpochHighWater epochHighWater,
                                                                StreamOwnerEpochSource ownerEpochSource,
                                                                Option<Path> walBaseDir,
                                                                LastSealedOffsetSource lastSealedOffset) {
        return new StreamPartitionManager(maxTotalBytes,
                                          evictionListener,
                                          replicationManager,
                                          Option.some(clusterNode),
                                          Option.some(epochHighWater),
                                          ownerEpochSource,
                                          walBaseDir,
                                          lastSealedOffset);
    }

    /// Test/standalone factory wiring a per-partition crash-durable WAL root (streaming-persistence
    /// W3/W6) with the no-op eviction / no-replication / no-cluster / fence-free defaults. When
    /// `walBaseDir` is [Option#none] this is byte-identical to {@link #streamPartitionManager(long)};
    /// when present every partition opens a [PartitionWal] and an owner publish is fsync-gated. The
    /// last-sealed source is the floor ([LastSealedOffsetSource#none] → `-1`), so a rebuilt partition
    /// replays its whole WAL from offset 0 (no sealed segments in this standalone path).
    public static StreamPartitionManager streamPartitionManager(long maxTotalBytes, Option<Path> walBaseDir) {
        return streamPartitionManager(maxTotalBytes, walBaseDir, LastSealedOffsetSource.none());
    }

    /// Test/standalone factory wiring BOTH a per-partition WAL root (W3/W6) and an explicit last-sealed
    /// source (streaming-persistence W4) with the no-op eviction / no-replication / no-cluster /
    /// fence-free defaults. On partition (re)build each ring seeds above `lastSealedOffset` and replays
    /// only the un-sealed WAL tail at its original offsets — letting a "restart" be simulated by building
    /// a second manager on the same `walBaseDir`. A `-1` source replays the full log from offset 0.
    public static StreamPartitionManager streamPartitionManager(long maxTotalBytes,
                                                                Option<Path> walBaseDir,
                                                                LastSealedOffsetSource lastSealedOffset) {
        return new StreamPartitionManager(maxTotalBytes,
                                          EvictionListener.NOOP,
                                          ReplicationManager.NONE,
                                          Option.none(),
                                          Option.none(),
                                          StreamOwnerEpochSource.zero(),
                                          walBaseDir,
                                          lastSealedOffset);
    }

    public static StreamPartitionManager streamPartitionManager(long maxTotalBytes,
                                                                ClusterNode<KVCommand<AetherKey>> clusterNode) {
        return new StreamPartitionManager(maxTotalBytes,
                                          EvictionListener.NOOP,
                                          ReplicationManager.NONE,
                                          Option.some(clusterNode),
                                          Option.none(),
                                          StreamOwnerEpochSource.zero(),
                                          Option.none(),
                                          LastSealedOffsetSource.none());
    }

    /// Placement-role supplier seam (#265 increment 1). Reports whether THIS node is the OWNER, a
    /// (non-owner) REPLICA, or NONE for a `(stream, partition)` under the current HRW placement, so a
    /// later increment can gate ring materialization on placement (materialize iff OWNER/REPLICA). The
    /// default ([#ALWAYS_OWNER]) reports OWNER for every partition — always-materialize, byte-identical
    /// to the pre-seam behavior. `AetherNode` late-binds [ReplicaSetController#roleFor].
    @FunctionalInterface
    public interface PlacementRoleSupplier {
        ReplicaSetController.Role roleFor(String stream, int partition);
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

    /// Late-bind the placement-role supplier (#265 increment 1). `AetherNode` wires this to
    /// [ReplicaSetController#roleFor] after the controller is constructed (it is built after this
    /// manager). Until then — and in Forge/unit/legacy managers — the default reports OWNER for every
    /// partition (always-materialize). Set once at wiring; read on the snapshot path.
    @Contract
    public void placementRoleSupplier(PlacementRoleSupplier supplier) {
        this.placementRoleSupplier = supplier;
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
                                      this::release,
                                      walBaseDir,
                                      lastSealedOffset)
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

        streams.compute(streamName, (_, existing) -> reconcileCommittedConfig(config, existing));
    }

    /// Reconcile a committed `StreamConfigKey` Put against the local map. Absent locally — hydrate the
    /// follower entry (unchanged materialization path; a native-OOM hydrate returns `null` so the
    /// [java.util.Map#compute] contract leaves no entry, exactly as the former `computeIfAbsent` did).
    /// Present — let a genuinely committed app/blueprint config become authoritative over a prior REST
    /// management default (see {@link #adoptIfMoreDurable}).
    @NullReturn
    private StreamEntry reconcileCommittedConfig(StreamConfig config, StreamEntry existing) {
        return option(existing).map(entry -> adoptIfMoreDurable(config, entry))
                     .or(() -> hydrateEntry(config));
    }

    /// A committed config for an ALREADY-materialized stream. The publish auto-create path
    /// (`StreamRoutes.ensureStreamExists`) can win a race and materialize a `replicas=1/min-sync=0`
    /// management DEFAULT before this committed app/blueprint config's notification is applied here; when
    /// the incoming config carries STRICTLY STRONGER durability (more `replicas` or a higher
    /// `minSyncReplicas`) AND the same partition count, its replication knobs are adopted onto the SAME
    /// partition rings / WALs — no data drop, no re-allocation. The comparison is monotonic-up so a stray
    /// later default never re-weakens an adopted app config (no ping-pong), and a live partition-count
    /// change — which cannot be re-shaped onto existing rings — is never adopted (the current entry is
    /// kept). Equal or weaker configs keep the existing entry (the `computeIfAbsent` idempotence this
    /// replaces).
    private StreamEntry adoptIfMoreDurable(StreamConfig config, StreamEntry existing) {
        return config.partitions() == existing.config().partitions() && strongerDurability(config, existing.config())
               ? adoptConfig(config, existing)
               : existing;
    }

    private StreamEntry adoptConfig(StreamConfig config, StreamEntry existing) {
        log.info("Adopting committed config for stream '{}' over prior local default: replicas {}->{}, minSyncReplicas {}->{}",
                 config.name(),
                 existing.config().replicas(),
                 config.replicas(),
                 existing.config().minSyncReplicas(),
                 config.minSyncReplicas());

        return existing.withConfig(config);
    }

    private static boolean strongerDurability(StreamConfig incoming, StreamConfig existing) {
        return incoming.replicas() > existing.replicas() || incoming.minSyncReplicas() > existing.minSyncReplicas();
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
                                      this::release,
                                      walBaseDir,
                                      lastSealedOffset)
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
        return publishLocal(streamName,
                            partition,
                            payload,
                            timestamp,
                            ownerEpochSource.currentOwnerEpoch(streamName, partition));
    }

    /// Owner-local publish stamped with an explicit `ownerEpoch` fencing token (#345 item 1d-ii). The
    /// append is fenced against the partition high-water before `buffer.append`; on accept the event is
    /// replicated to the registered replica set carrying the SAME `ownerEpoch` so every replica fences
    /// the deposed owner identically. The no-epoch overload above stamps the node's current owner epoch
    /// from the injected [StreamOwnerEpochSource] (floor [Epoch#ZERO] when unowned/non-fenced).
    ///
    /// Crash durability (streaming-persistence W3): when a per-partition WAL is configured the ring
    /// assigns the offset first, then the event is appended to that partition's [PartitionWal] and the
    /// publish does NOT resolve as success until the WAL `append` is fsync-durable. A crash after the
    /// ring append but before fsync loses the event AND fails the publish (the caller was never acked,
    /// so it retries) — only WAL-durable events ack. With no WAL configured this is a no-op gate and
    /// behavior is exactly as before.
    public Result<Long> publishLocal(String streamName,
                                     int partition,
                                     byte[] payload,
                                     long timestamp,
                                     Epoch ownerEpoch) {
        return resolveStreamEntry(streamName).flatMap(entry -> appendToPartition(entry,
                                                                                 streamName,
                                                                                 partition,
                                                                                 payload,
                                                                                 timestamp,
                                                                                 ownerEpoch))
                                 .flatMap(offset -> durablyLog(streamName, partition, offset, payload, timestamp))
                                 .onSuccess(offset -> replicationManager.replicateEvent(streamName,
                                                                                        partition,
                                                                                        offset,
                                                                                        payload,
                                                                                        timestamp,
                                                                                        ownerEpoch));
    }

    /// Gate the publish ack on WAL fsync (streaming-persistence W3). With no WAL configured for
    /// `(streamName, partition)` this returns the offset unchanged. With a WAL present, the record is
    /// appended and the GROUP-COMMIT fsync is awaited at this durability barrier — the event is acked
    /// only once it survives `kill -9`. [TerminalOperation]: the blocking await IS the durability
    /// contract (publish does not resolve until fsync), and the WAL's group-commit batches concurrent
    /// publishers into a single fsync so the barrier does not serialize throughput.
    @TerminalOperation
    private Result<Long> durablyLog(String streamName, int partition, long offset, byte[] payload, long timestamp) {
        return walFor(streamName, partition).map(wal -> wal.append(offset, payload, timestamp)
                                                           .await()
                                                           .map(_ -> offset))
                     .or(() -> success(offset));
    }

    /// The configured [PartitionWal] for `(streamName, partition)`, or [Option#none] when no WAL base
    /// dir is wired (the steady-state legacy/Forge path) or the partition is out of range.
    private Option<PartitionWal> walFor(String streamName, int partition) {
        return option(streams.get(streamName)).flatMap(entry -> entry.walFor(partition));
    }

    public Promise<Unit> awaitReplication(String streamName, int partition, long offset, int minAcks) {
        return replicationManager.awaitReplication(streamName, partition, offset, minAcks);
    }

    /// The configured `min-sync-replicas` write-ack requirement for `streamName` (in-sync count incl.
    /// owner), or `0` when the stream is unknown. `<= 1` means no peer-ack barrier; `>= 2` means a
    /// publish must await `minSyncReplicas - 1` distinct non-self replica acks. Read straight from the
    /// stream's committed config so the REST publish path can gate on the stream's durability setting.
    public int minSyncReplicasFor(String streamName) {
        return option(streams.get(streamName)).map(entry -> entry.config().minSyncReplicas()).or(0);
    }

    /// Append a backfilled event into the local partition ring WITHOUT re-triggering replication.
    /// Used by the A4 catch-up path: a freshly-assigned replica receiving events from an up-to-date
    /// source must land them locally but must NOT re-emit them onto the replication stream (it is the
    /// receiver, not an owner). Offsets are preserved because the ring assigns sequential offsets and
    /// catch-up replays the source's events in order into an empty partition.
    public Result<Long> appendRecovered(String streamName, int partition, byte[] payload, long timestamp) {
        return appendRecovered(streamName, partition, payload, timestamp, Epoch.ZERO);
    }

    /// Append a backfilled/replicated event stamped with the SENDING owner's `ownerEpoch` fencing
    /// token (#345 item 1d-ii). The replica fences this append against its own partition high-water
    /// before `buffer.append` (§6 enforce-at-replica): a batch from a deposed owner — whose epoch is
    /// strictly older than the high-water this replica has observed from the committed ownership change
    /// — is rejected with [StreamError.StaleEpochAppend] and nothing is landed. The no-epoch overload
    /// above stamps the floor ([Epoch#ZERO]) for callers that carry no epoch (non-fenced backfill).
    public Result<Long> appendRecovered(String streamName,
                                        int partition,
                                        byte[] payload,
                                        long timestamp,
                                        Epoch ownerEpoch) {
        return resolveStreamEntry(streamName).flatMap(entry -> appendToPartition(entry,
                                                                                 streamName,
                                                                                 partition,
                                                                                 payload,
                                                                                 timestamp,
                                                                                 ownerEpoch));
    }

    /// The offset the NEXT contiguous append would be assigned for `(streamName, partition)` — the
    /// local ring head + 1, or 0 when the partition is empty/absent (the ring's head is `-1` before the
    /// first append). Used by {@link org.pragmatica.aether.stream.replication.ReplicationReceiveHandler}
    /// to verify an incoming replicated batch's owner-frame `fromOffset` against the replica's own
    /// position (S1 / #260), so a dropped/reordered batch is detected instead of silently shifting every
    /// subsequent local offset.
    public long nextExpectedOffset(String streamName, int partition) {
        return resolvePartitionBuffer(streamName, partition).map(buffer -> buffer.headOffset() + 1)
                                     .or(0L);
    }

    /// The earliest offset still retained locally for `(streamName, partition)` — the ring tail, or
    /// `-1` when the partition is absent. Used owner-side by the replication manager to decide whether
    /// an acking replica's confirmed offset actually reaches back to the partition's retained history
    /// (promotes to CAUGHT_UP) or only covers a post-join suffix (stays SYNCING) — #261.
    public long earliestRetainedOffset(String streamName, int partition) {
        return resolvePartitionBuffer(streamName, partition).map(OffHeapRingBuffer::tailOffset)
                                     .or(-1L);
    }

    private Result<Long> appendToPartition(StreamEntry entry,
                                           String streamName,
                                           int partition,
                                           byte[] payload,
                                           long timestamp,
                                           Epoch ownerEpoch) {
        return ensureNotStale(streamName, partition, ownerEpoch).flatMap(_ -> checkEventSize(entry, payload))
                             .flatMap(_ -> resolvePartitionBuffer(streamName, partition))
                             .flatMap(buffer -> buffer.append(payload, timestamp))
                             .onSuccess(_ -> entry.updateActivity());
    }

    /// The owner-epoch fence (#345 item 1d-ii, spec §5b/§6): reject the append when `ownerEpoch` is
    /// STRICTLY older than the `(stream, partition)` domain high-water — the writer is a deposed owner.
    /// Equal-or-newer passes (a genuinely-current owner is never spuriously fenced; its epoch equals
    /// the high-water). Fence-free managers ([Option#none]) always pass. The high-water advances ONLY
    /// by observing committed ownership values (1d-i), never from an append.
    private Result<Unit> ensureNotStale(String streamName, int partition, Epoch ownerEpoch) {
        return epochHighWater.fold(() -> success(unit()),
                                   highWater -> rejectIfStale(highWater, streamName, partition, ownerEpoch));
    }

    private static Result<Unit> rejectIfStale(OwnershipEpochHighWater highWater,
                                              String streamName,
                                              int partition,
                                              Epoch ownerEpoch) {
        var domain = OwnershipDomain.streamPartition(streamName, partition);

        return highWater.isStale(domain, ownerEpoch)
               ? new StreamError.StaleEpochAppend(streamName,
                                                  partition,
                                                  ownerEpoch,
                                                  highWater.highWater(domain).or(ownerEpoch)).result()
               : success(unit());
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

    /// Cheap point-in-time view of per-node hydration state (#265 increment 0 — the §6 regression
    /// sensor). Assembled ON REQUEST from the live `streams` map and the budget counters; adds NO
    /// hot-path accounting. Per stream it reports partitions declared, rings materialized, floor bytes
    /// allocated, and the placement-role counts under the current supplier; per node it reports total
    /// allocated / max budget and whether the pool is over budget.
    public HydrationSnapshot hydrationSnapshot() {
        var allocated = totalAllocatedBytes.get();
        var streamViews = streams.entrySet()
                                 .stream()
                                 .map(e -> streamHydration(e.getKey(), e.getValue()))
                                 .toList();

        return new HydrationSnapshot(allocated, maxTotalBytes, allocated > maxTotalBytes, streamViews);
    }

    /// Assemble one stream's hydration view. `ringsMaterialized` is the count of partition rings
    /// actually built locally (`entry.partitions().length`) — equal to `partitionsDeclared` today; a
    /// later increment gates materialization so non-replicas build fewer, and this diverges. Floor
    /// bytes are the per-partition floor times the materialized ring count.
    private StreamHydration streamHydration(String name, StreamEntry entry) {
        var declared = entry.config().partitions();
        var materialized = entry.partitions().length;

        return new StreamHydration(name,
                                   declared,
                                   materialized,
                                   perPartitionFloorBytes(entry.config()) * materialized,
                                   roleCounts(name, declared));
    }

    /// Placement-role tally across a stream's declared partitions under the current supplier (#265
    /// increment 1). Cheap — one supplier call per partition, absent roles simply do not appear.
    private Map<ReplicaSetController.Role, Long> roleCounts(String streamName, int partitions) {
        return IntStream.range(0, partitions)
                        .mapToObj(partition -> placementRoleSupplier.roleFor(streamName, partition))
                        .collect(Collectors.groupingBy(role -> role, Collectors.counting()));
    }

    /// Adapt this manager to the narrow {@link org.pragmatica.aether.stream.replication.StreamCatalog}
    /// consumed by `ReplicaSetController`. Exposes `(name, partitions, replicas, minSyncReplicas)` per
    /// stream — placement uses `replicas` (the replication factor), while `minSyncReplicas` (the write-
    /// ack requirement) is carried for the in-sync gate. Neither is carried by {@link StreamInfo}, so
    /// the controller cannot be fed by `listStreams()` alone; this accessor reads them straight from
    /// each stream's config.
    public org.pragmatica.aether.stream.replication.StreamCatalog replicaCatalog() {
        return new org.pragmatica.aether.stream.replication.StreamCatalog() {
            @Override
            public List<org.pragmatica.aether.stream.replication.StreamCatalog.StreamSpec> streams() {
                return StreamPartitionManager.this.streams.values()
                                             .stream()
                                             .map(entry -> entry.config())
                                             .map(config -> new StreamSpec(config.name(),
                                                                           config.partitions(),
                                                                           config.replicas(),
                                                                           config.minSyncReplicas()))
                                             .toList();
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

    /// Periodically reclaim WAL disk by truncating each partition's write-ahead log up to its DURABLE
    /// last-sealed offset (streaming-persistence W5). For every live stream and each partition that has a
    /// [PartitionWal], `base = lastSealedOffset.lastSealedOffset(stream, partition)` is computed and, when
    /// `base >= 0`, `wal.truncate(base)` discards records with `offset <= base`. Those records are already
    /// durable in cold segments (served post-restart by the tiered reader), so dropping them from the WAL
    /// loses nothing — recovery serves them from segments and the un-sealed tail (`offset > base`) stays in
    /// the WAL. Driving off the DURABLE sealed bound (rather than hooking the void eviction→seal listener)
    /// avoids any "truncated before the segment was durable" window. Best-effort: a `truncate` failure on
    /// one partition is logged and never aborts the others; a `-1` bound (nothing sealed) is a no-op for
    /// that partition; the no-WAL path ([Option#none] `walBaseDir`) holds no [PartitionWal] and is untouched.
    @Contract
    public void truncateWalsToSealed() {
        streams.forEach(this::truncateStreamWals);
    }

    @Contract
    private void truncateStreamWals(String streamName, StreamEntry entry) {
        var wals = entry.wals();

        for (int partition = 0; partition < wals.size(); partition++) {
            truncatePartitionToSealed(streamName, partition, wals.get(partition));
        }
    }

    @Contract
    private void truncatePartitionToSealed(String streamName, int partition, Option<PartitionWal> wal) {
        wal.onPresent(w -> truncateWalToSealed(streamName, partition, w));
    }

    @Contract
    private void truncateWalToSealed(String streamName, int partition, PartitionWal wal) {
        var base = lastSealedOffset.lastSealedOffset(streamName, partition);

        if (base >= 0) {
            wal.truncate(base).onFailure(cause -> log.warn("WAL truncate to sealed offset {} failed for {}/{}: {}",
                                                           base,
                                                           streamName,
                                                           partition,
                                                           cause.message()));
        }
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

    /// Close a genuinely-removed stream (destroy / config-remove / idle-reap) and reclaim its budget,
    /// then DELETE its per-partition WAL files — the stream is gone, so its crash-recovery log must go
    /// too. Deletion lives HERE (not in {@link #releaseEntry}/{@link StreamEntry#close}) on purpose:
    /// `releaseEntry` is also called by the put-if-absent loser, whose WAL paths COLLIDE with the
    /// winner's (same `<base>/<stream>/<partition>.wal`); deleting there would erase the winner's log.
    /// Process-shutdown `close()` likewise must keep the files for a later replay. Only true removal
    /// deletes.
    private Result<Unit> closeAndRelease(StreamEntry entry) {
        releaseEntry(entry);
        entry.deleteWals();

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
        return perPartitionFloorBytes(config) * config.partitions();
    }

    /// Per-partition floor = `OffHeapRingBuffer.floorBytes(maxCount, maxBytes)` = `(header + index +
    /// first-data-segment)` — the bytes one partition ring allocates at construction. Shared by the
    /// per-stream floor admission and the #265 hydration snapshot's per-materialized-ring byte tally.
    private static long perPartitionFloorBytes(StreamConfig config) {
        var retention = config.retention();

        return OffHeapRingBuffer.floorBytes(retention.maxCount(), retention.maxBytes());
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

    /// Per-node hydration snapshot (#265 increment 0). `totalAllocatedBytes` / `maxTotalBytes` are the
    /// live budget counters; `overBudget` is `totalAllocatedBytes > maxTotalBytes` (the follower
    /// over-subscribe condition, spec §6). `streams` carries one [StreamHydration] per live stream.
    public record HydrationSnapshot(long totalAllocatedBytes,
                                    long maxTotalBytes,
                                    boolean overBudget,
                                    List<StreamHydration> streams) {}

    /// Per-stream hydration view (#265 increment 0). `partitionsDeclared` is the configured partition
    /// count; `ringsMaterialized` the number of partition rings actually built locally (equal today, a
    /// later increment gates materialization so non-replicas build fewer); `floorBytesAllocated` the
    /// per-partition floor times the materialized ring count; `roleCounts` the placement-role tally
    /// under the current supplier (default: all OWNER).
    public record StreamHydration(String name,
                                  int partitionsDeclared,
                                  int ringsMaterialized,
                                  long floorBytesAllocated,
                                  Map<ReplicaSetController.Role, Long> roleCounts) {}

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
                       List<Option<PartitionWal>> wals,
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
        /// On full success the rings are paired with their per-partition WAL (`walBaseDir`, W6) into a
        /// committed-ready entry; a WAL-open failure closes the built rings and propagates with its own
        /// cause (it is NOT collapsed to `STREAM_MEMORY_EXCEEDED`). When a partition has a WAL the
        /// un-sealed tail is replayed back into its fresh ring at the ORIGINAL offsets (streaming-
        /// persistence W4), bounded by `lastSealedOffset` so already-sealed records (served by the tiered
        /// reader) are NOT re-added; a replay failure likewise closes the built rings and propagates.
        static Result<StreamEntry> fromConfig(StreamConfig config,
                                              EvictionListener listener,
                                              LongPredicate reserve,
                                              LongConsumer release,
                                              Option<Path> walBaseDir,
                                              LastSealedOffsetSource lastSealedOffset) {
            var results = buildPartitions(config, listener, reserve, release);

            return Result.allOf(results)
                         .mapError(_ -> StreamError.General.STREAM_MEMORY_EXCEEDED)
                         .flatMap(buffers -> openEntryWals(config, buffers, walBaseDir, lastSealedOffset))
                         .onFailure(_ -> closeBuilt(results));
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

        private static StreamEntry entryOf(StreamConfig config,
                                           OffHeapRingBuffer[] buffers,
                                           List<Option<PartitionWal>> wals) {
            var now = System.currentTimeMillis();

            return new StreamEntry(config, buffers, wals, now, new AtomicLong(now), new AtomicBoolean(false));
        }

        /// Pair the freshly-built rings with a per-partition [PartitionWal] (streaming-persistence W6)
        /// and replay each WAL's un-sealed tail back into its ring (W4). A [Option#none] `walBaseDir`
        /// yields a partition-aligned list of [Option#none] (no WAL ⇒ unchanged behavior); a present base
        /// dir opens `<base>/<stream>/<partition>.wal` for each partition and recovers its tail. A WAL-open
        /// or replay failure closes the WALs already opened and propagates, leaving the caller to free the
        /// rings.
        private static Result<StreamEntry> openEntryWals(StreamConfig config,
                                                         List<OffHeapRingBuffer> buffers,
                                                         Option<Path> walBaseDir,
                                                         LastSealedOffsetSource lastSealedOffset) {
            return openWals(config, walBaseDir).flatMap(wals -> recoverWals(config, buffers, wals, lastSealedOffset))
                           .map(wals -> entryOf(config,
                                                buffers.toArray(OffHeapRingBuffer[]::new),
                                                wals));
        }

        /// Replay every partition's un-sealed WAL tail into its fresh ring (streaming-persistence W4),
        /// returning the same partition-aligned WAL list on success so the entry can be assembled. Runs
        /// ONCE at build time, before the partition is published/serving, so the ring is empty and the
        /// replayed events land at their original offsets. On any partition's replay failure the WALs are
        /// closed and the failure propagates (the rings are freed by the caller).
        private static Result<List<Option<PartitionWal>>> recoverWals(StreamConfig config,
                                                                      List<OffHeapRingBuffer> buffers,
                                                                      List<Option<PartitionWal>> wals,
                                                                      LastSealedOffsetSource lastSealedOffset) {
            var results = new ArrayList<Result<Unit>>(buffers.size());

            for (int i = 0; i < buffers.size(); i++) {
                results.add(recoverPartition(config.name(), i, buffers.get(i), wals.get(i), lastSealedOffset));
            }

            return Result.allOf(results)
                         .map(_ -> wals)
                         .onFailure(_ -> wals.forEach(StreamEntry::closeWal));
        }

        /// Replay one partition's WAL tail into its ring, or a no-op when the partition has no WAL
        /// ([Option#none]). The fresh ring is seeded above the durable last-sealed offset and only records
        /// with `offset > lastSealedOffset` are appended (PartitionWal.replay already filters them).
        private static Result<Unit> recoverPartition(String streamName,
                                                     int partition,
                                                     OffHeapRingBuffer ring,
                                                     Option<PartitionWal> wal,
                                                     LastSealedOffsetSource lastSealedOffset) {
            return wal.map(w -> replayTail(streamName, partition, ring, w, lastSealedOffset))
                      .or(() -> success(unit()));
        }

        /// Seed the fresh ring above the partition's durable last-sealed offset (so reads at or below it
        /// cleanly miss and fall through to the tiered reader), then append the WAL's un-sealed tail in
        /// order. The ring assigns `base + 1, base + 2, …`, exactly matching the records' original
        /// offsets. A `base` of `-1` (nothing sealed) leaves the fresh ring un-seeded and replays the
        /// whole log from offset 0.
        private static Result<Unit> replayTail(String streamName,
                                               int partition,
                                               OffHeapRingBuffer ring,
                                               PartitionWal wal,
                                               LastSealedOffsetSource lastSealedOffset) {
            var base = lastSealedOffset.lastSealedOffset(streamName, partition);
            var records = new ArrayList<WalRecord>();

            return seedRing(ring, base).flatMap(_ -> wal.replay(base, records::add))
                           .flatMap(_ -> appendTail(ring, records));
        }

        /// Position the fresh ring so the next append is `base + 1` when sealed segments already cover
        /// `[0, base]`; a no-op when nothing is sealed (`base < 0`).
        private static Result<Unit> seedRing(OffHeapRingBuffer ring, long base) {
            return base >= 0
                   ? ring.seedHead(base)
                   : success(unit());
        }

        /// Append the recovered records into the ring in scan order (their original offsets), failing the
        /// recovery if any append fails. The events are the un-sealed tail, which fits the fresh ring;
        /// a normal `append` is used (no quiet/recovered variant exists), so a recovered event may
        /// re-trigger the eviction→seal listener — idempotent for the tail being recovered.
        private static Result<Unit> appendTail(OffHeapRingBuffer ring, List<WalRecord> records) {
            var results = records.stream().map(record -> appendRecord(ring, record)).toList();

            return Result.allOf(results).mapToUnit();
        }

        private static Result<Long> appendRecord(OffHeapRingBuffer ring, WalRecord record) {
            return ring.append(record.payload(), record.timestampMillis());
        }

        private static Result<List<Option<PartitionWal>>> openWals(StreamConfig config, Option<Path> walBaseDir) {
            return walBaseDir.map(baseDir -> openAllWals(config, baseDir))
                             .or(() -> success(noWals(config.partitions())));
        }

        private static Result<List<Option<PartitionWal>>> openAllWals(StreamConfig config, Path baseDir) {
            var results = new ArrayList<Result<Option<PartitionWal>>>(config.partitions());

            for (int i = 0; i < config.partitions(); i++) {
                results.add(openPartitionWal(baseDir, config.name(), i).map(Option::some));
            }

            return Result.allOf(results).onFailure(_ -> closeOpenedWals(results));
        }

        private static Result<PartitionWal> openPartitionWal(Path baseDir, String streamName, int partition) {
            return PartitionWal.open(baseDir.resolve(streamName).resolve(partition + ".wal"));
        }

        private static List<Option<PartitionWal>> noWals(int count) {
            var wals = new ArrayList<Option<PartitionWal>>(count);

            for (int i = 0; i < count; i++) {
                wals.add(Option.none());
            }

            return wals;
        }

        @Contract
        private static void closeOpenedWals(List<Result<Option<PartitionWal>>> results) {
            results.forEach(r -> r.onSuccess(StreamEntry::closeWal));
        }

        @Contract
        private static void closeWal(Option<PartitionWal> wal) {
            wal.onPresent(PartitionWal::close);
        }

        /// The [PartitionWal] for `partition`, or [Option#none] when no WAL is configured (or the
        /// partition index is out of range). The list is always partition-aligned with `partitions`.
        Option<PartitionWal> walFor(int partition) {
            return partition >= 0 && partition < wals.size()
                   ? wals.get(partition)
                   : Option.none();
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

        /// Adopt a stronger committed config onto THIS entry's live rings / WALs and durability state: a
        /// copy that swaps ONLY the [StreamConfig], reusing the SAME partition buffers, WAL handles,
        /// commit latch and activity clock so no buffered data is dropped and no off-heap bytes are
        /// re-reserved. Called only from the notification-thread config reconcile
        /// ({@link StreamPartitionManager#adoptConfig}) with a partition-count-compatible config.
        StreamEntry withConfig(StreamConfig newConfig) {
            return new StreamEntry(newConfig, partitions, wals, createdAt, lastActivityRef, configCommitted);
        }

        private static EvictionPolicy deriveEvictionPolicy(StreamConfig config) {
            return config.consistencyMode() == ConsistencyMode.STRONG
                   ? EvictionPolicy.REJECT_WHEN_FULL
                   : EvictionPolicy.DROP_OLDEST;
        }

        /// Close every partition ring and its WAL channel (flush + fsync + close). Process-shutdown
        /// safe: this only closes channels and KEEPS the WAL files on disk for a later replay — file
        /// deletion happens exclusively on genuine stream removal via {@link #deleteWals}.
        @Contract
        @Override
        public void close() {
            for (var buffer : partitions) {
                buffer.close();
            }

            wals.forEach(StreamEntry::closeWal);
        }

        /// Delete every partition's WAL file — called ONLY when the stream is genuinely removed
        /// (destroy / config-remove / idle-reap), never on process shutdown or a put-if-absent loser.
        /// The channels are already closed by {@link #close}; deletion is best-effort and a failure is
        /// logged, not propagated.
        @Contract
        void deleteWals() {
            wals.forEach(StreamEntry::deleteWalFile);
        }

        @Contract
        private static void deleteWalFile(Option<PartitionWal> wal) {
            wal.onPresent(w -> FileOps.deleteIfExists(w.path()).onFailure(cause -> log.warn("Failed to delete WAL file {}: {}",
                                                                                            w.path(),
                                                                                            cause.message())));
        }
    }
}
