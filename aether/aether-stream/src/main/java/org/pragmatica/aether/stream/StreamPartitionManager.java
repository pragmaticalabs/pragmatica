// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import java.util.function.IntSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongPredicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.fence.OwnershipDomain;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.stream.replication.ReplicaPlacement;
import org.pragmatica.aether.stream.replication.ReplicaPlacement.StreamClass;
import org.pragmatica.aether.stream.replication.ReplicaSetController;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.wal.PartitionWal;
import org.pragmatica.aether.stream.wal.PartitionWal.WalRecord;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.lang.Cause;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;


public final class StreamPartitionManager implements AutoCloseable {
    private static final long DEFAULT_MAX_TOTAL_BYTES = 128 * 1024 * 1024L;
    private static final TimeSpan COMMIT_TIMEOUT = TimeSpan.timeSpan(10).seconds();

    private static final Logger log = LoggerFactory.getLogger(StreamPartitionManager.class);

    /// Absolute per-stream partition ceiling (#265 increment 4, spec §7/§10). Enforced PRE-COMMIT in
    /// {@link #createFreshStream} (mirroring the build-time `StreamConfigParser` check) and surfaced as the
    /// follower over-ceiling event + snapshot flag on {@link #hydrateEntry}. A fixed absolute guard (NOT the
    /// RAM-derived cap); spec §10 presents it as the tunable `[streams.limits]
    /// max_partitions_per_stream_ceiling` — this is its default. Kept in sync with the identically-named
    /// `StreamConfigParser` constant (the build-time half of the same gate).
    static final int MAX_PARTITIONS_PER_STREAM_CEILING = 1024;

    /// Cluster aggregate partition-guard factor (#265 increment 4, spec §7/§10): the guard is
    /// `CLUSTER_PARTITION_GUARD_FACTOR × clusterSize × maxDeclaredReplicas` and bounds the cluster's total
    /// materialized-ring count (Σ `partitions × replicas`) — the Kafka `100 × brokers × RF` heuristic.
    /// Enforced pre-commit ONLY where the cluster size is knowable ({@link #clusterSizeSupplier} > 0); a
    /// manager with no cluster context (Forge/unit/legacy) skips the aggregate guard and keeps only the
    /// per-stream ceiling.
    static final int CLUSTER_PARTITION_GUARD_FACTOR = 100;

    /// Reshuffle concurrency window (#265 increment 5, spec §14.2 decision 2): at most this many partitions
    /// per node may be in materialize+backfill state at once. Excess REPLICA materializations queue at the
    /// {@link #buildAndInstall} pacing seam (system streams first, then FIFO) and re-drive once a slot frees
    /// (a completed backfill or a release). OWNER materializations are NOT paced — an owner ring has no
    /// backfill (it is the source), so pacing it would only stall the owner write path with no throughput
    /// benefit. `2` is the spec default (one-at-a-time starves a large reshuffle; unbounded floods backfill).
    /// DEFAULT reshuffle concurrency. `2` is the spec default (one-at-a-time starves a large reshuffle;
    /// unbounded floods backfill). Overridable at wiring time via [#reshuffleConcurrency(int)], bound to
    /// `[streaming] reshuffle_concurrency`. Until 2026-08-16 this was a hard-coded `static final` with NO
    /// binding, while the paced-materialization error message named `reshuffle_concurrency` as though it
    /// were a knob — an operator hitting the message went looking for a setting that did not exist.
    static final int RESHUFFLE_CONCURRENCY = 2;

    /// Reconcile ticks a partition may hold a reshuffle slot before it is preempted to unblock a starving
    /// queue ([#preemptStalledSlots]). At the 5s reconcile interval this is ~30s — comfortably past
    /// `PartitionBackfill`'s own 20s bounded wait, so a healthy backfill completes or promotes long before it
    /// is ever eligible. Only applies while partitions are actually queued.
    static final int RESHUFFLE_SLOT_MAX_TICKS = 6;

    /// Flap-debounce grace, in reconcile ticks (#265 increment 5, spec §5.4 / §14.2). A materialized
    /// partition whose role transitions to NONE becomes a RELEASE CANDIDATE; it is released only after it has
    /// remained a candidate for this many {@link #reconcileReshuffle} ticks AND the catch-up + owner gates
    /// pass. A role regained within the window cancels the candidacy at zero cost (no release, no
    /// re-materialize). Tick-based (not wall-clock) so it is deterministic and test-controllable; the
    /// production scheduler runs the reconcile every {@code STREAM_RESHUFFLE_RECONCILE_INTERVAL} (5s), so
    /// `2` ticks ≈ the ~10s wall-clock debounce spec §5.4 suggests.
    static final int RELEASE_DEBOUNCE_TICKS = 2;

    /// The `system:*` namespace prefix (mirrors `ReplicaSetController.SYSTEM_NAMESPACE_PREFIX`). System
    /// streams are cluster-critical and full-cluster placed; per owner decision 2026-07-05 they BYPASS the
    /// off-heap budget reject (always materialize, emitting a named {@link Exhaustion.Phase#SYSTEM_OVERSUBSCRIBE}
    /// WARN when over budget) and drain FIRST in the reshuffle materialization queue.
    private static final String SYSTEM_STREAM_PREFIX = "system:";

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
    /// Placement-role seam (#265 increment 1/2): consulted per `(stream, partition)` to GATE ring
    /// materialization on placement — a ring is built iff `roleFor` reports OWNER/REPLICA (increment 2).
    /// Defaults to [#ALWAYS_OWNER] (materialize-everything, byte-identical to the pre-seam behavior);
    /// `AetherNode` late-binds [ReplicaSetController#roleFor] AFTER the controller is constructed (it is
    /// built after this manager — the same construction-order inversion the `streamPartitionManagerRef`
    /// seam resolves). Volatile: set once at wiring, read on the hydrate/create, snapshot, and lazy-
    /// materialize paths.
    private volatile PlacementRoleSupplier placementRoleSupplier = ALWAYS_OWNER;

    /// Live cluster-size source for the aggregate partition guard (#265 increment 4). Defaults to `() -> 0`
    /// (cluster size UNKNOWN — Forge/unit/legacy managers), which DISABLES the aggregate guard so only the
    /// per-stream ceiling applies; `AetherNode` late-binds this to the topology observer's live count — the
    /// SAME source [ReplicaSetController] uses for HRW placement, so the guard's node count matches placement.
    /// Volatile: set once at wiring, read on the create-admission and snapshot paths.
    private volatile IntSupplier clusterSizeSupplier = () -> 0;

    /// Committed-config source for the owner-side forwarded-publish race recovery (write-forward race fix,
    /// see {@link CommittedConfigSource} / {@link #publishForwarded}). Defaults to "not visible on this
    /// node" (Forge/unit/legacy managers and the pre-wiring window), which makes a forwarded publish for a
    /// stream this owner cannot yet see resolve to the retryable {@link StreamError.StreamConfigNotYetVisible}
    /// rather than a permanent failure; `AetherNode` late-binds the node's committed KV state. Volatile:
    /// set once at wiring, read only on the forwarded-publish recovery path.
    private volatile CommittedConfigSource committedConfigSource = _ -> Option.none();

    /// Default replica-catch-up source (#265 increment 5): reports "everyone caught up" — a large
    /// other-caught-up count (so the release catch-up gate always passes) and `selfCaughtUp = true` (so an
    /// in-flight slot frees on the next tick). Forge/unit/legacy managers keep this; `AetherNode` late-binds
    /// the real registry-backed view. The release gate never fires on those managers anyway, because the
    /// periodic {@link #reconcileReshuffle} is only scheduled on a real node.
    private static final ReplicaCatchupSource ALL_CAUGHT_UP = (_, _) -> new ReplicaCatchupSource.CatchupView(Integer.MAX_VALUE,
                                                                                                             true);

    /// Default owner-release guard (#265 increment 5): reports "committed owner is elsewhere" for every arc,
    /// so the owner rule never blocks release on a manager with no committed-ownership source. `AetherNode`
    /// late-binds the real committed-`StreamPartitionOwnershipValue` check.
    private static final OwnerReleaseGuard OWNER_ELSEWHERE = (_, _) -> true;

    /// Live catch-up view for the release catch-up gate + in-flight slot completion (#265 increment 5).
    /// `AetherNode` binds it to the [org.pragmatica.aether.stream.replication.ReplicaRegistry]. Default:
    /// [#ALL_CAUGHT_UP]. Volatile: set once at wiring, read on the reconcile tick.
    private volatile ReplicaCatchupSource catchupSource = ALL_CAUGHT_UP;

    /// Live committed-ownership guard for the owner release rule (#265 increment 5): a node never releases a
    /// partition whose COMMITTED owner is still itself. `AetherNode` binds it to the committed
    /// `StreamPartitionOwnershipValue`. Default: [#OWNER_ELSEWHERE]. Volatile: set once at wiring, read on
    /// the reconcile tick.
    private volatile OwnerReleaseGuard ownerReleaseGuard = OWNER_ELSEWHERE;

    /// Reshuffle-concurrency permits (#265 increment 5): [#reshuffleConcurrency] slots gating REPLICA
    /// materialize+backfill. Acquired in {@link #buildAndInstall} for a REPLICA partition, released when the
    /// partition reaches CAUGHT_UP / loses the role / is released. Fair=false (throughput over ordering; the
    /// queue provides the ordering). Replaced wholesale by [#reshuffleConcurrency(int)] at wiring time, which
    /// is why neither this nor the limit beside it is final.
    private volatile Semaphore reshuffleSlots = new Semaphore(RESHUFFLE_CONCURRENCY);

    /// The reshuffle-slot limit in force. Reported by [org.pragmatica.aether.stream.StreamError.ReshufflePaced]
    /// so the operator-facing message states the ACTUAL bound rather than a compile-time constant.
    private volatile int reshuffleConcurrency = RESHUFFLE_CONCURRENCY;

    /// Partitions currently holding a reshuffle slot (materialize+backfill in flight). Membership set paired
    /// with {@link #reshuffleSlots}: `add` is the "acquire", `remove`+release is the "free". Swept each tick
    /// so a completed/lost partition frees its slot.
    private final Set<PartitionRef> inFlightMaterializations = ConcurrentHashMap.newKeySet();
    /// System-stream reshuffle queue (drains FIRST — owner decision 2026-07-05). Refs enqueued when no slot
    /// is free; dequeued on the reconcile tick as slots free.
    private final Deque<PartitionRef> systemMaterializeQueue = new ConcurrentLinkedDeque<>();
    /// App-stream reshuffle queue (drains AFTER the system queue, FIFO). A queued app partition proceeds only
    /// when a slot AND off-heap budget headroom both exist (budget-AND).
    private final Deque<PartitionRef> appMaterializeQueue = new ConcurrentLinkedDeque<>();
    /// Dedup set across both queues + a fast "is queued" membership test — a repeat materialize request for an
    /// already-queued partition is a no-op (idempotent, like the lazy-materialize path itself).
    private final Set<PartitionRef> queuedMaterializations = ConcurrentHashMap.newKeySet();

    /// Reconcile tick at which each in-flight partition ACQUIRED its slot — the input to starvation
    /// preemption ([#preemptStalledSlots]). Removed when the slot is freed or preempted.
    private final Map<PartitionRef, Long> slotAcquiredTick = new ConcurrentHashMap<>();

    /// Partitions whose backfill is still running but which no longer hold a reshuffle slot, because they
    /// were preempted for starving the queue. Load-bearing for permit accounting, NOT bookkeeping: slot
    /// acquisition is idempotent VIA {@link #inFlightMaterializations} membership ("a ref already in flight
    /// returns true without a second permit"), so a preempted ref that re-entered the acquire path would take
    /// a SECOND permit and only ever release one — leaking the pool empty. A ref listed here reports its slot
    /// as already held and never takes another.
    private final Set<PartitionRef> preemptedSlots = ConcurrentHashMap.newKeySet();

    /// Release candidacy (#265 increment 5): a materialized partition whose role went NONE maps to the
    /// reconcile tick at which candidacy started. Debounced (survive [#RELEASE_DEBOUNCE_TICKS] ticks) then
    /// gated (catch-up + owner) before release. A role regained removes the entry (flap cancel).
    private final ConcurrentHashMap<PartitionRef, Long> releaseCandidacy = new ConcurrentHashMap<>();

    /// Monotonic reconcile-tick counter driving the debounce (one increment per {@link #reconcileReshuffle}).
    private final AtomicLong reconcileTick = new AtomicLong(0);
    /// Count of partition rings released on role loss since boot (#265 increment 5 observability).
    private final AtomicLong releasedSinceBoot = new AtomicLong(0);

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

    /// Live replica catch-up view for the reshuffle release gate + slot completion (#265 increment 5). One
    /// committed lookup answers both facts the manager cannot compute locally: `caughtUpReplicaCount` — how
    /// many OTHER (non-self) replicas of the current registered replica set have reached CAUGHT_UP (the
    /// release catch-up gate compares this against the effective, clamped RF), and `selfCaughtUp` — whether
    /// THIS node has finished backfilling the partition (so its reshuffle slot may free). `AetherNode` binds
    /// it to the [org.pragmatica.aether.stream.replication.ReplicaRegistry]; the default reports everyone
    /// caught up.
    @FunctionalInterface
    public interface ReplicaCatchupSource {
        CatchupView catchupView(String stream, int partition);

        record CatchupView(int caughtUpReplicaCount, boolean selfCaughtUp) {}
    }

    /// Committed-ownership release guard for the owner rule (#265 increment 5). Reports whether it is safe —
    /// on the ownership axis — to release `(stream, partition)`: `true` when the COMMITTED
    /// `StreamPartitionOwnershipValue.owner` names a DIFFERENT node than self (or no ownership record is
    /// committed), `false` while it still names self. A node that has lost the HRW OWNER role but is still
    /// the committed (fenced) owner must NOT release until the 1d-iii reshuffle driver commits the ownership
    /// change. `AetherNode` binds it to the committed ownership record; the default never blocks.
    @FunctionalInterface
    public interface OwnerReleaseGuard {
        boolean committedOwnerElsewhere(String stream, int partition);
    }

    /// Committed-config source for the owner-side forwarded-publish race recovery (write-forward race fix).
    /// Reports the LOCALLY-VISIBLE committed `StreamConfig` for a stream, read straight from applied KV
    /// state, so the {@link #publishForwarded} path can lazily materialize a partition whose config commit
    /// has not yet reached this owner's `onStreamConfigPut` notification. [Option#none] means "not visible
    /// on this node yet" (the bootstrap window / Forge/unit/legacy managers); `AetherNode` binds it to the
    /// node's committed KV state so the committed replication factor is preserved on materialization.
    @FunctionalInterface
    public interface CommittedConfigSource {
        Option<StreamConfig> committedConfig(String streamName);
    }

    /// Value key for a single `(stream, partition)` arc — the map/set key for the reshuffle in-flight set,
    /// materialization queues, and release candidacy (#265 increment 5). Record equality gives correct
    /// hashing without a hand-rolled key.
    private record PartitionRef(String streamName, int partition) {}

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

    /// Set the reshuffle-slot limit (`[streaming] reshuffle_concurrency`). Set ONCE at wiring, before any
    /// materialization: it replaces the permit pool wholesale, so calling it with slots in flight would
    /// desynchronise permits from {@link #inFlightMaterializations}. A value below 1 is ignored — a zero
    /// limit would stall every REPLICA materialization permanently, and silently accepting it here would
    /// reproduce the starvation this bound is meant to pace. `ConfigValidator` rejects it up front so the
    /// operator sees the error rather than this defensive floor.
    @Contract
    public void reshuffleConcurrency(int limit) {
        if (limit < 1) {
            log.warn("Ignoring reshuffle_concurrency={} — must be >= 1; keeping {}", limit, reshuffleConcurrency);

            return;
        }

        this.reshuffleConcurrency = limit;
        this.reshuffleSlots = new Semaphore(limit);
    }

    /// Late-bind the cluster-size source for the aggregate partition guard (#265 increment 4). `AetherNode`    /// wires this to the topology observer's live count (the SAME source [ReplicaSetController] uses for HRW
    /// placement). Until then — and in Forge/unit/legacy managers — the default `() -> 0` reports "cluster
    /// size unknown" and the aggregate guard is skipped (only the per-stream ceiling applies). Set once at
    /// wiring; read on the create-admission and snapshot paths.
    @Contract
    public void clusterSizeSupplier(IntSupplier supplier) {
        this.clusterSizeSupplier = supplier;
    }

    /// Late-bind the replica catch-up source (#265 increment 5). `AetherNode` wires this to the
    /// [org.pragmatica.aether.stream.replication.ReplicaRegistry] (self-excluded caught-up count + self
    /// caught-up flag). Until then — and in Forge/unit/legacy managers — the default reports everyone caught
    /// up. Set once at wiring; read on the reconcile tick.
    @Contract
    public void replicaCatchupSource(ReplicaCatchupSource source) {
        this.catchupSource = source;
    }

    /// Late-bind the committed-ownership release guard (#265 increment 5). `AetherNode` wires this to the
    /// committed `StreamPartitionOwnershipValue` (safe to release iff the committed owner is not self). Until
    /// then — and in Forge/unit/legacy managers — the default never blocks. Set once at wiring; read on the
    /// reconcile tick.
    @Contract
    public void ownerReleaseGuard(OwnerReleaseGuard guard) {
        this.ownerReleaseGuard = guard;
    }

    /// Late-bind the committed-config source for the owner-side forwarded-publish race recovery
    /// (write-forward race fix). `AetherNode` wires this to the node's committed KV state so a forwarded
    /// publish that races ahead of this owner's `onStreamConfigPut` can lazily materialize from the
    /// committed `StreamConfigKey` (preserving RF). Until then — and in Forge/unit/legacy managers — the
    /// default reports "not visible" so the recovery yields the retryable `StreamConfigNotYetVisible`. Set
    /// once at wiring; read only on the {@link #publishForwarded} recovery path.
    @Contract
    public void committedConfigSource(CommittedConfigSource source) {
        this.committedConfigSource = source;
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
        return checkPartitionCaps(config).flatMap(_ -> materializeFreshStream(config, commitMode));
    }

    /// Create-time admission gate (#265 increment 4, spec §7): reject a fresh stream that breaches the
    /// absolute per-stream partition ceiling or — where the cluster size is knowable — the cluster-wide
    /// aggregate guard, BEFORE any off-heap reservation or the `StreamConfigKey` commit. The parser applies
    /// the ceiling half at build time; this is the runtime pre-commit re-check plus the aggregate guard the
    /// parser cannot know. A follower observing the committed config never re-runs this — it alarms via
    /// {@link #reportOverCeilingIfViolating} instead (spec §7).
    private Result<Unit> checkPartitionCaps(StreamConfig config) {
        return checkPerStreamCeiling(config).flatMap(_ -> checkClusterAggregate(config));
    }

    private static Result<Unit> checkPerStreamCeiling(StreamConfig config) {
        return config.partitions() <= MAX_PARTITIONS_PER_STREAM_CEILING
               ? success(unit())
               : new StreamError.PartitionCeilingExceeded(config.name(),
                                                          config.partitions(),
                                                          MAX_PARTITIONS_PER_STREAM_CEILING).result();
    }

    /// The aggregate guard is enforced ONLY where the cluster size is knowable (a real node); a manager with
    /// no cluster context reports `0` and skips it (spec §7: enforce where the aggregate is knowable, never
    /// on a follower).
    private Result<Unit> checkClusterAggregate(StreamConfig config) {
        var clusterSize = clusterSizeSupplier.getAsInt();

        return clusterSize <= 0
               ? success(unit())
               : enforceAggregateGuard(config, clusterSize);
    }

    private Result<Unit> enforceAggregateGuard(StreamConfig config, int clusterSize) {
        var maxReplicas = Math.max(config.replicas(), maxDeclaredReplicas());
        var guard = (long) CLUSTER_PARTITION_GUARD_FACTOR * clusterSize * maxReplicas;
        var projected = currentAggregateSlots() + partitionSlots(config);

        return projected <= guard
               ? success(unit())
               : new StreamError.PartitionCapExceeded(config.name(), projected, guard, clusterSize, maxReplicas).result();
    }

    private Result<Unit> materializeFreshStream(StreamConfig config, CommitMode commitMode) {
        if (config.consistencyMode() == ConsistencyMode.STRONG && evictionListener == EvictionListener.NOOP) {
            return StreamError.General.AHSE_REQUIRED_FOR_STRONG.result();
        }

        var floorBytes = materializedFloorBytes(config);

        if (floorBytes > 0 && !tryReserve(floorBytes)) {
            if (!isSystemStream(config.name())) {
                return reportFloorExhaustion(config, floorBytes);
            }

            forceReserveForSystem(config, floorBytes);
        }

        return StreamEntry.fromConfig(config,
                                      evictionListener,
                                      bytes -> reserveForGrowth(config, bytes),
                                      this::release,
                                      partition -> shouldMaterialize(config.name(),
                                                                     partition),
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
        node.apply(putCommand(config))
            .onSuccess(_ -> entry.markCommitted())
            .onFailure(cause -> log.debug("Async stream config publish for {} not yet committed: {}",
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

        node.apply(List.of(remove))
            .onFailure(cause -> log.warn("Failed to publish stream config removal for {}: {}",
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
        return config.partitions() == existing.config()
                                              .partitions() && strongerDurability(config, existing.config())
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

    /// Follower (apply/notification-thread) materialization from a committed `StreamConfigKey` Put. With
    /// placement-gating (#265 increment 2) only the partitions THIS node is OWNER/REPLICA of are
    /// materialized, so the reserved floor is `perPartitionFloor × materializedCount` (not × declared) —
    /// a non-replica reserves ZERO and holds the stream metadata-only. Per spec §5.4/§6: when placement
    /// is not yet known (bootstrap window, `roleFor == NONE` for every partition) the entry is created
    /// metadata-only and the rings materialize later on the reconcile hook / owner-append safety valve.
    /// If the (non-zero) held floor cannot be admitted within budget the entry is DEFERRED (#265
    /// increment 3, spec §6): the former unconditional over-subscription is GONE — a follower still does
    /// NOT diverge (the committed config is present metadata-only, zero bytes past the cap), and the held
    /// partitions materialize later through the single deferred-retry entry point once budget frees (see
    /// {@link #deferHydration}). The growth seam still gates every later segment against the pool normally.
    private StreamEntry hydrateEntry(StreamConfig config) {
        reportOverCeilingIfViolating(config);
        var floorBytes = materializedFloorBytes(config);

        if (floorBytes > 0 && !tryReserve(floorBytes)) {
            if (!isSystemStream(config.name())) {
                return deferHydration(config, floorBytes);
            }

            forceReserveForSystem(config, floorBytes);
        }
        // fromConfig threads the per-partition floor-allocation Result (bug #6): a native-OOM on any
        // held partition closes the built siblings and fails. On that (rare) failure we release the
        // reserved floor budget and insert NO entry (computeIfAbsent null) — the node physically cannot
        // allocate the memory, so there is no committed partition to diverge from. markCommitted on
        // success keeps the follower's later createStream short-circuiting (no re-publish).
        return StreamEntry.fromConfig(config,
                                      evictionListener,
                                      bytes -> reserveForGrowth(config, bytes),
                                      this::release,
                                      partition -> shouldMaterialize(config.name(),
                                                                     partition),
                                      walBaseDir,
                                      lastSealedOffset)
                          .onSuccess(StreamEntry::markCommitted)
                          .onFailure(_ -> hydrationFailed(config, floorBytes))
                          .or((StreamEntry) null);
    }

    /// Budget-deferred hydration (#265 increment 3, spec §6). The held floor does NOT fit the off-heap
    /// budget, so — replacing the former unconditional over-subscription — NO ring is built: the stream
    /// is created METADATA-ONLY (present in the catalog so the follower does not diverge from committed
    /// config, ZERO off-heap bytes reserved past the cap) with every held partition DEFERRED, exactly like
    /// the pre-membership case. Each materializes later through the single deferred-retry entry point
    /// ({@link #buildAndInstall}) — the reconcile hook or the owner-append safety valve — once budget
    /// frees. A named budget event goes to the exhaustion sink (WARN + `CREATE_FLOOR` event) and the
    /// deferral is visible in the hydration snapshot (`partitionsDeferred`). The entry is marked committed
    /// so the follower's later createStream still short-circuits.
    private StreamEntry deferHydration(StreamConfig config, long floorBytes) {
        reportHydrationDeferred(config, floorBytes);
        var entry = StreamEntry.metadataOnly(config);

        entry.markCommitted();

        return entry;
    }

    /// Emit the budget-deferred hydration signal (#265 increment 3, spec §6/§11): WARN + a `CREATE_FLOOR`
    /// exhaustion event so the deferral is operator-visible via the existing sink. Unlike the removed
    /// `oversubscribeFloor`, the floor is NEVER added past `maxTotalBytes` — the held partitions stay
    /// metadata-only until budget frees.
    @Contract
    private void reportHydrationDeferred(StreamConfig config, long floorBytes) {
        log.warn("Off-heap budget exhausted hydrating committed stream '{}' ({} parts): need {} floor bytes, {} available of {} — held partitions deferred metadata-only",
                 config.name(),
                 config.partitions(),
                 floorBytes,
                 availableBytes(),
                 maxTotalBytes);
        exhaustionSink.accept(Exhaustion.createFloor(config, floorBytes, availableBytes(), maxTotalBytes));
    }

    /// System-stream budget bypass (#265 increment 5, owner decision 2026-07-05): reserve `floorBytes`
    /// UNCONDITIONALLY — past the cap if need be — for a cluster-critical `system:*` stream, then emit a named
    /// {@link Exhaustion.Phase#SYSTEM_OVERSUBSCRIBE} WARN so the oversubscription is operator-visible. This is
    /// the DELIBERATE, scoped restoration of the old over-subscribe behavior FOR SYSTEM STREAMS ONLY (app
    /// streams still defer): cluster-critical streams must not defer behind app-stream budget pressure, and
    /// their footprint is bounded (few streams, full-cluster placement deliberate). A later release/destroy of
    /// the stream returns exactly these bytes (`release` is symmetric even past the cap).
    @Contract
    private void forceReserveForSystem(StreamConfig config, long floorBytes) {
        totalAllocatedBytes.addAndGet(floorBytes);
        log.warn("System stream '{}' oversubscribed off-heap budget by {} floor bytes ({} of {} now allocated) — system streams bypass budget-reject (owner decision 2026-07-05)",
                 config.name(),
                 floorBytes,
                 totalAllocatedBytes.get(),
                 maxTotalBytes);
        exhaustionSink.accept(Exhaustion.systemOversubscribe(config, floorBytes, availableBytes(), maxTotalBytes));
    }

    private static boolean isSystemStream(String streamName) {
        return streamName.startsWith(SYSTEM_STREAM_PREFIX);
    }

    @Contract
    private void hydrationFailed(StreamConfig config, long floorBytes) {
        release(floorBytes);
        log.warn("Follower could not materialize committed stream '{}' — off-heap floor allocation failed; entry not created",
                 config.name());
    }

    /// Follower defense-in-depth (#265 increment 4, spec §7/§11): a COMMITTED config whose declared partition
    /// count is over the per-stream ceiling (committed before the guard existed, or a hand-edited config) does
    /// NOT reject the commit — a follower never diverges from committed cluster state. It emits a named
    /// `CommittedConfigOverCeiling` signal through the EXISTING exhaustion/event sink (operator-visible, with
    /// its own `(stream, CONFIG_OVER_CEILING)` throttle bucket) and surfaces as the snapshot's
    /// `configOverCeilingStreams` count + the per-stream `overCeiling` flag. Materialization still proceeds
    /// under the budget machinery (increments 2-3), which is the memory backstop — the guard is admission
    /// control, the budget is enforcement. NO early return.
    @Contract
    private void reportOverCeilingIfViolating(StreamConfig config) {
        if (config.partitions() > MAX_PARTITIONS_PER_STREAM_CEILING) {
            log.warn("Committed stream '{}' declares {} partitions, over the per-stream ceiling of {} — materializing under the budget backstop",
                     config.name(),
                     config.partitions(),
                     MAX_PARTITIONS_PER_STREAM_CEILING);
            exhaustionSink.accept(Exhaustion.overCeiling(config));
        }
    }

    /// Owner-side forwarded-publish entry (write-forward race fix). A metadata-only node auto-creates +
    /// commits a stream's `StreamConfigKey` and forwards the FIRST publish to the HRW owner IMMEDIATELY, so
    /// this owner's KV apply of that config can lag the forward — a plain {@link #publishLocal} then fails
    /// `StreamNotFound` for a stream that genuinely exists cluster-wide. This recovers ONLY that race: on
    /// `StreamNotFound` it lazily materializes from the LOCALLY-VISIBLE committed `StreamConfigKey`
    /// (preserving the committed RF) and retries the append ONCE; if the committed config is not yet visible
    /// on this node it returns the RETRYABLE {@link StreamError.StreamConfigNotYetVisible} so the forwarder
    /// backs off and retries rather than surfacing a spurious permanent failure. Any OTHER `publishLocal`
    /// failure (event too large, partition out of range, a genuine non-owner `PARTITION_NOT_LOCAL`, a stale
    /// epoch) propagates UNCHANGED — no lazy create, no retry.
    public Result<Long> publishForwarded(String streamName, int partition, byte[] payload, long timestamp) {
        return publishLocal(streamName, partition, payload, timestamp).fold(cause -> recoverForwardedPublish(cause,
                                                                                                             streamName,
                                                                                                             partition,
                                                                                                             payload,
                                                                                                             timestamp),
                                                                            Result::success);
    }

    private Result<Long> recoverForwardedPublish(Cause cause,
                                                 String streamName,
                                                 int partition,
                                                 byte[] payload,
                                                 long timestamp) {
        return cause instanceof StreamError.StreamNotFound
               ? materializeThenRetryPublish(streamName, partition, payload, timestamp)
               : cause.result();
    }

    /// Lazily materialize from the locally-visible committed config, then retry the append once. When the
    /// committed config is not yet visible on this node the append cannot be recovered here, so the
    /// retryable {@link StreamError.StreamConfigNotYetVisible} is returned for the forwarder to back off on.
    private Result<Long> materializeThenRetryPublish(String streamName, int partition, byte[] payload, long timestamp) {
        return committedConfigSource.committedConfig(streamName)
                                    .fold(() -> new StreamError.StreamConfigNotYetVisible(streamName).result(),
                                          config -> materializeThenPublish(config,
                                                                           streamName,
                                                                           partition,
                                                                           payload,
                                                                           timestamp));
    }

    private Result<Long> materializeThenPublish(StreamConfig config,
                                                String streamName,
                                                int partition,
                                                byte[] payload,
                                                long timestamp) {
        return ensureStreamMaterialized(config).flatMap(_ -> publishLocal(streamName, partition, payload, timestamp));
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
        return option(streams.get(streamName)).map(entry -> entry.config()
                                                                 .minSyncReplicas())
                     .or(0);
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
                             .flatMap(_ -> resolveAppendTarget(streamName, partition, entry))
                             .flatMap(buffer -> buffer.append(payload, timestamp))
                             .onSuccess(_ -> entry.updateActivity());
    }

    /// Resolve the ring to append into, materializing it lazily on the OWNER/REPLICA path (#265 increment
    /// 2 safety valve, spec §5.4). An already-materialized partition returns its ring directly. A
    /// metadata-only partition is materialized ONLY when this node is its OWNER/REPLICA (a publish/replica-
    /// receive that lands here because reconcile has not fired yet must not drop the write); a genuine
    /// non-replica (`NONE`) is rejected with `PARTITION_NOT_LOCAL` so the caller forwards to a holder (the
    /// read/write routers already fall back to owner-forward on an absent local buffer — spec §8). The
    /// READ path never materializes — it forwards.
    private Result<OffHeapRingBuffer> resolveAppendTarget(String streamName, int partition, StreamEntry entry) {
        if (partition < 0 || partition >= entry.declaredPartitions()) {
            return new StreamError.PartitionOutOfRange(streamName, partition, entry.declaredPartitions()).result();
        }

        return entry.ringFor(partition)
                    .fold(() -> materializeIfHeld(streamName, partition, entry),
                          buffer -> success(buffer));
    }

    private Result<OffHeapRingBuffer> materializeIfHeld(String streamName, int partition, StreamEntry entry) {
        return switch (placementRoleSupplier.roleFor(streamName, partition)) {
            case OWNER, REPLICA -> buildAndInstall(entry, partition);
            case NONE -> StreamError.General.PARTITION_NOT_LOCAL.result();
        };
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
        var streamViews = streams.entrySet().stream().map(e -> streamHydration(e.getKey(), e.getValue())).toList();
        var deferred = streamViews.stream().mapToLong(StreamHydration::partitionsDeferred).sum();
        var overCeiling = (int) streamViews.stream().filter(StreamHydration::overCeiling).count();
        var clusterSize = clusterSizeSupplier.getAsInt();
        var guard = aggregateGuard(clusterSize);
        var currentSlots = currentAggregateSlots();
        var headroom = guard < 0
                       ? -1L
                       : guard - currentSlots;

        return new HydrationSnapshot(allocated,
                                     maxTotalBytes,
                                     allocated > maxTotalBytes,
                                     deferred,
                                     MAX_PARTITIONS_PER_STREAM_CEILING,
                                     guard,
                                     currentSlots,
                                     headroom,
                                     overCeiling,
                                     releaseCandidacy.size(),
                                     releasedSinceBoot.get(),
                                     (long)(systemMaterializeQueue.size() + appMaterializeQueue.size()),
                                     streamViews);
    }

    /// Assemble one stream's hydration view. `ringsMaterialized` is the count of partition rings actually
    /// built locally ({@link StreamEntry#ringsMaterialized}) — with placement-gating (#265 increment 2) it
    /// diverges from `partitionsDeclared` on a node that is not OWNER/REPLICA of every partition.
    /// `partitionsDeferred` (#265 increment 3) is the count of partitions this node SHOULD hold
    /// (OWNER/REPLICA) but has NOT yet materialized — clamped at zero so the create-time-materialized /
    /// supplier-flipped-to-NONE gate/release asymmetry never reports negative. It unifies the two defer
    /// causes: budget-deferred (spec §6) and pre-membership (spec §5.4). Floor bytes are the per-partition
    /// floor times the materialized ring count (the REAL off-heap cost of this stream on this node).
    private StreamHydration streamHydration(String name, StreamEntry entry) {
        var declared = entry.config().partitions();
        var materialized = entry.ringsMaterialized();
        var deferred = Math.max(0, heldCount(name, declared) - materialized);

        return new StreamHydration(name,
                                   declared,
                                   materialized,
                                   deferred,
                                   perPartitionFloorBytes(entry.config()) * materialized,
                                   declared > MAX_PARTITIONS_PER_STREAM_CEILING,
                                   roleCounts(name, declared));
    }

    /// Placement-role tally across a stream's declared partitions under the current supplier (#265
    /// increment 1). Cheap — one supplier call per partition, absent roles simply do not appear.
    private Map<ReplicaSetController.Role, Long> roleCounts(String streamName, int partitions) {
        return IntStream.range(0, partitions)
                        .mapToObj(partition -> placementRoleSupplier.roleFor(streamName, partition))
                        .collect(Collectors.groupingBy(role -> role,
                                                       Collectors.counting()));
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
        var isEmpty = entry.materializedRings().stream().allMatch(b -> b.eventCount() == 0);
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
        for (int partition = 0; partition < entry.declaredPartitions(); partition++) {
            truncatePartitionToSealed(streamName, partition, entry.walFor(partition));
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
            wal.truncate(base)
               .onFailure(cause -> log.warn("WAL truncate to sealed offset {} failed for {}/{}: {}",
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
        inFlightMaterializations.clear();
        systemMaterializeQueue.clear();
        appMaterializeQueue.clear();
        queuedMaterializations.clear();
        releaseCandidacy.clear();
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

    /// Resolve the local ring for a READ (`readLocal` / `partitionBuffer` / offsets / info). An in-range
    /// but metadata-only partition (this node is not a materialized holder) yields `PARTITION_NOT_LOCAL`,
    /// which `partitionBuffer(...).option()` collapses to [Option#none] so the read routers forward to a
    /// holder (spec §8). The READ path never materializes — only the append path (safety valve) does.
    private static Result<OffHeapRingBuffer> resolvePartitionInEntry(String streamName,
                                                                     int partition,
                                                                     StreamEntry entry) {
        if (partition < 0 || partition >= entry.declaredPartitions()) {
            return new StreamError.PartitionOutOfRange(streamName, partition, entry.declaredPartitions()).result();
        }

        return entry.ringFor(partition)
                    .toResult(StreamError.General.PARTITION_NOT_LOCAL);
    }

    private static StreamInfo buildStreamInfo(String name, StreamEntry entry) {
        var totalEvents = 0L;
        var totalBytes = 0L;

        for (var buffer : entry.materializedRings()) {
            totalEvents += buffer.eventCount();
            totalBytes += buffer.allocatedBytes();
        }

        return StreamInfo.streamInfo(name, entry.declaredPartitions(), totalEvents, totalBytes);
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

    /// Held-floor = `perPartitionFloor × materializedCount` (#265 increment 2): the off-heap floor for
    /// ONLY the partitions THIS node materializes (OWNER/REPLICA under the current placement). A
    /// non-replica node reserves ZERO here and holds the stream metadata-only — the O(streams × partitions
    /// × nodes) blow-up becomes O(streams × partitions × RF). See spec §4/§6.
    private long materializedFloorBytes(StreamConfig config) {
        return perPartitionFloorBytes(config) * materializedCount(config);
    }

    /// Count of partitions THIS node materializes under the current placement supplier — the declared
    /// partitions for which {@link #shouldMaterialize} holds (OWNER/REPLICA). `0` on a non-replica node
    /// or during the pre-membership window when `roleFor` cannot yet resolve a role (spec §5.4 defer).
    private int materializedCount(StreamConfig config) {
        return heldCount(config.name(), config.partitions());
    }

    /// Count of the `[0, partitions)` this node HOLDS (OWNER/REPLICA) under the current supplier — the
    /// placement-decided cardinality shared by the floor admission ({@link #materializedCount}) and the
    /// hydration snapshot's `partitionsDeferred = held − materialized` (#265 increment 3).
    private int heldCount(String streamName, int partitions) {
        return (int) IntStream.range(0, partitions)
                              .filter(partition -> shouldMaterialize(streamName, partition))
                              .count();
    }

    /// The placement gate (#265 increment 2): materialize `(stream, partition)`'s ring iff this node is
    /// its OWNER or a (non-owner) REPLICA under the current supplier. `NONE` — a genuine non-replica OR
    /// the pre-membership window where placement is not yet known — is metadata-only; when a `NONE`
    /// partition later resolves to OWNER/REPLICA the ring materializes on the reconcile hook
    /// ({@link #materializePartition}) or the owner-append safety valve (spec §5.4 defer-then-materialize).
    private boolean shouldMaterialize(String streamName, int partition) {
        return switch (placementRoleSupplier.roleFor(streamName, partition)) {
            case OWNER, REPLICA -> true;
            case NONE -> false;
        };
    }

    /// Cluster-wide total of materialized-ring slots = Σ `partitions × replicas` across every committed stream
    /// this node knows (#265 increment 4, spec §7/§10). Each node hydrates every committed `StreamConfigKey`
    /// (metadata-only on non-replicas), so the local `streams` map is a full cluster view. Shared by the
    /// create-time aggregate guard ({@link #enforceAggregateGuard}) and the hydration snapshot's headroom.
    private long currentAggregateSlots() {
        return streams.values()
                      .stream()
                      .mapToLong(entry -> partitionSlots(entry.config()))
                      .sum();
    }

    private static long partitionSlots(StreamConfig config) {
        return (long) config.partitions() * config.replicas();
    }

    /// Largest declared `replicas` across known streams (min 1) — the `maxDeclaredReplicas` factor of the
    /// aggregate guard `100 × nodes × maxDeclaredReplicas` (spec §10).
    private int maxDeclaredReplicas() {
        return Math.max(1,
                        streams.values().stream().mapToInt(entry -> entry.config()
                                                                         .replicas()).max().orElse(1));
    }

    /// The aggregate partition guard `100 × clusterSize × maxDeclaredReplicas`, or `-1` when the cluster size
    /// is unknown ({@link #clusterSizeSupplier} == 0 — a Forge/unit/legacy manager) meaning the guard is not
    /// enforced. Shared by the snapshot's guard/headroom fields (#265 increment 4).
    private long aggregateGuard(int clusterSize) {
        return clusterSize <= 0
               ? -1L
               : (long) CLUSTER_PARTITION_GUARD_FACTOR * clusterSize * maxDeclaredReplicas();
    }

    /// Lazily materialize a single held partition's ring (#265 increment 2). Two callers: the
    /// materialize-on-reconcile hook (`AetherNode` binds this behind the controller's `onBecameReplica`
    /// seam, which fires for owner-or-replica the moment self joins a partition's replica set) and the
    /// owner-append safety valve ({@link #resolveAppendTarget}). IDEMPOTENT: an already-materialized
    /// partition returns its existing ring with no new allocation, so the hook firing after a create-time
    /// materialize is a no-op — a ring, once materialized, STAYS until release (increment 5). A
    /// `StreamNotFound` (config not yet hydrated) is a benign no-op; the config-put path materializes it.
    public Result<OffHeapRingBuffer> materializePartition(String streamName, int partition) {
        return resolveStreamEntry(streamName).flatMap(entry -> materializePartitionInEntry(streamName, partition, entry));
    }

    private Result<OffHeapRingBuffer> materializePartitionInEntry(String streamName, int partition, StreamEntry entry) {
        if (partition < 0 || partition >= entry.declaredPartitions()) {
            return new StreamError.PartitionOutOfRange(streamName, partition, entry.declaredPartitions()).result();
        }

        return entry.ringFor(partition)
                    .fold(() -> buildAndInstall(entry, partition),
                          buffer -> success(buffer));
    }

    /// Materialize ONE held partition's ring behind the SINGLE deferred-retry entry point (#265 increment
    /// 3 — the pacing seam increment 5's `reshuffle_concurrency` will throttle). Both callers funnel here:
    /// the materialize-on-reconcile hook ({@link #materializePartition}) and the owner-append safety valve
    /// ({@link #resolveAppendTarget}). Reserve the per-partition floor against the budget; if it does NOT
    /// fit the ring is NOT built (the former over-subscription is GONE) and a named
    /// {@link StreamError.MaterializeBudgetExceeded} is returned — the partition stays DEFERRED and the
    /// next reconcile tick (or the next owner-append) retries once budget frees. On a build failure the
    /// reserved floor is released; on a lost install race (a concurrent reconcile-hook / safety-valve
    /// materialize won) the duplicate is closed WITHOUT seam-release and its floor released, and the
    /// winner's ring is returned. Reserve/release stays symmetric on destroy.
    private Result<OffHeapRingBuffer> buildAndInstall(StreamEntry entry, int partition) {
        var config = entry.config();
        var role = placementRoleSupplier.roleFor(config.name(), partition);
        // OWNER materialize has no history backfill (it IS the source), so it is NEVER paced by the reshuffle
        // concurrency window (#265 increment 5) — pacing it would only stall the owner write path with zero
        // throughput benefit. Only REPLICA (or a not-yet-resolved) materialize, which triggers a history
        // backfill, consumes a reshuffle slot; excess queues at this seam and re-drives on the reconcile tick.
        return role == Role.OWNER
               ? materializeNow(entry, partition, false)
               : materializePaced(entry, partition);
    }

    /// Slot-gated REPLICA materialize (#265 increment 5). Off-heap headroom is peeked FIRST — an APP partition
    /// with no headroom defers via the existing budget path WITHOUT consuming a slot (the budget-AND; a system
    /// stream skips this and oversubscribes). Then a reshuffle slot is acquired; if all
    /// [#RESHUFFLE_CONCURRENCY] are taken the materialization is enqueued (system queue first) and a named
    /// {@link StreamError.ReshufflePaced} returned. With a slot in hand the ring is built.
    private Result<OffHeapRingBuffer> materializePaced(StreamEntry entry, int partition) {
        var config = entry.config();
        var ref = new PartitionRef(config.name(), partition);
        var system = isSystemStream(config.name());

        if (!system && availableBytes() < perPartitionFloorBytes(config)) {
            return reportMaterializeDeferred(config, partition, perPartitionFloorBytes(config));
        }

        if (!tryAcquireReshuffleSlot(ref)) {
            return enqueueMaterialize(ref, system);
        }

        return materializeNow(entry, partition, true);
    }

    /// Reserve the per-partition floor (a `system:*` stream OVERSUBSCRIBES past the cap — owner decision
    /// 2026-07-05) and build+install the ring. An APP budget shortfall defers (no ring) and frees any held
    /// slot; a build failure releases the floor and frees any held slot; a lost install race closes the
    /// duplicate WITHOUT seam-release and releases its floor, the slot staying with the winner. `slotHeld`
    /// tells the failure paths whether a reshuffle slot must be freed (REPLICA path) or not (OWNER path).
    private Result<OffHeapRingBuffer> materializeNow(StreamEntry entry, int partition, boolean slotHeld) {
        var config = entry.config();
        var ref = new PartitionRef(config.name(), partition);
        var floorBytes = perPartitionFloorBytes(config);

        if (!reserveMaterialize(config, floorBytes)) {
            return deferForBudget(ref, config, partition, floorBytes, slotHeld);
        }

        return StreamEntry.materializeOne(config,
                                          partition,
                                          evictionListener,
                                          bytes -> reserveForGrowth(config, bytes),
                                          this::release,
                                          walBaseDir,
                                          lastSealedOffset)
                          .onFailure(_ -> releaseFailedMaterialize(ref, floorBytes, slotHeld))
                          .map(candidate -> installOrRelease(entry, partition, candidate, floorBytes));
    }

    /// Reserve the per-partition floor. An APP stream returns false when the pool is exhausted (the caller
    /// defers); a `system:*` stream ALWAYS reserves — oversubscribing past the cap with a
    /// {@link Exhaustion.Phase#SYSTEM_OVERSUBSCRIBE} WARN (owner decision 2026-07-05) — and returns true.
    private boolean reserveMaterialize(StreamConfig config, long floorBytes) {
        if (tryReserve(floorBytes)) {
            return true;
        }

        if (!isSystemStream(config.name())) {
            return false;
        }

        forceReserveForSystem(config, floorBytes);

        return true;
    }

    private Result<OffHeapRingBuffer> deferForBudget(PartitionRef ref,
                                                     StreamConfig config,
                                                     int partition,
                                                     long floorBytes,
                                                     boolean slotHeld) {
        if (slotHeld) {
            freeReshuffleSlot(ref);
        }

        return reportMaterializeDeferred(config, partition, floorBytes);
    }

    @Contract
    private void releaseFailedMaterialize(PartitionRef ref, long floorBytes, boolean slotHeld) {
        release(floorBytes);
        if (slotHeld) {
            freeReshuffleSlot(ref);
        }
    }

    /// Acquire a reshuffle-concurrency slot for `ref`, or false when all [#RESHUFFLE_CONCURRENCY] are taken.
    /// Idempotent: a ref already in flight (its slot held) returns true without a second permit; a
    /// membership-add that cannot get a permit is rolled back so the ref is not falsely counted.
    private boolean tryAcquireReshuffleSlot(PartitionRef ref) {
        if (preemptedSlots.contains(ref)) {
            return true;
        }

        if (!inFlightMaterializations.add(ref)) {
            return true;
        }

        if (reshuffleSlots.tryAcquire()) {
            slotAcquiredTick.put(ref, reconcileTick.get());

            return true;
        }

        inFlightMaterializations.remove(ref);

        return false;
    }

    @Contract
    private void freeReshuffleSlot(PartitionRef ref) {
        slotAcquiredTick.remove(ref);
        preemptedSlots.remove(ref);
        if (inFlightMaterializations.remove(ref)) {
            reshuffleSlots.release();
        }
    }

    /// Enqueue a slot-starved materialization (system queue drains FIRST — owner decision 2026-07-05).
    /// Deduped across both queues via {@link #queuedMaterializations}; a repeat request for an already-queued
    /// partition is a no-op. Returns the named {@link StreamError.ReshufflePaced} so the caller retries
    /// (reconcile hook next tick / owner-append client retry), never forwards.
    private Result<OffHeapRingBuffer> enqueueMaterialize(PartitionRef ref, boolean system) {
        if (queuedMaterializations.add(ref)) {
            (system
             ? systemMaterializeQueue
             : appMaterializeQueue).add(ref);
        }

        return new StreamError.ReshufflePaced(ref.streamName(), ref.partition(), reshuffleConcurrency).result();
    }

    /// Periodic reshuffle-lifecycle reconcile (#265 increment 5) — the release state machine + slot pacing
    /// tick. `AetherNode` schedules it every `STREAM_RESHUFFLE_RECONCILE_INTERVAL` (5s); tests call it directly
    /// to advance the machine deterministically. One tick, in order: (1) free reshuffle slots for partitions
    /// that finished backfill (self CAUGHT_UP), became OWNER (no backfill), or lost the role; (1b) preempt a
    /// slot held past [#RESHUFFLE_SLOT_MAX_TICKS] while the queue is non-empty, so a stalled backfill cannot
    /// starve the queue forever; (2) evaluate
    /// release candidates — a materialized partition whose role is NONE debounces [#RELEASE_DEBOUNCE_TICKS]
    /// ticks, then releases IFF the catch-up gate (≥ effective, clamped RF other replicas CAUGHT_UP) AND the
    /// owner rule (committed owner elsewhere) both pass, freeing the ring + its budget (WAL kept); (3) drain
    /// the materialization queue (system first, then app with budget headroom) up to the slot limit — so the
    /// budget a release just freed can admit a queued partition in the same tick. A role regained before
    /// release cancels candidacy at zero cost (flap debounce).
    @Contract
    public void reconcileReshuffle() {
        reconcileTick.incrementAndGet();
        freeCompletedSlots();
        preemptStalledSlots();
        evaluateReleaseCandidates();
        drainMaterializeQueue();
    }

    /// Anti-starvation preemption. A slot was held for as long as a partition stayed a not-caught-up REPLICA,
    /// with NO upper bound — and [org.pragmatica.aether.stream.replication.PartitionBackfill] retries forever
    /// once its bounded wait elapses with a committed owner present (the #445 distrust gate). The release
    /// condition was therefore exactly the condition that would never become true, and a stalled backfill
    /// occupied its slot indefinitely.
    ///
    /// Measured 2026-08-16 (02y-stream-crash, remote cluster B): `entity:orders[4]` and `[6]` held BOTH of a
    /// node's slots for 4m55s with zero releases in the whole log, while `multipart-events[0]`/`[2]` — the
    /// partitions it was the designated replica for — sat queued behind them, never became in-sync, and were
    /// lost outright when their owner was killed.
    ///
    /// A backfill idle-waiting on an unreachable source performs no work, so it must not hold a work-pacing
    /// slot while others wait. Preemption does NOT abort the backfill: it keeps running and keeps retrying,
    /// it simply stops counting against `RESHUFFLE_CONCURRENCY`.
    ///
    /// TRADE, deliberate: this bounds tenure rather than detecting the stall, so a legitimately SLOW but
    /// progressing backfill can also be preempted. That is harmless (it continues) and costs only that one
    /// extra partition may materialize concurrently. Preemption is gated on a non-empty queue so a preempted
    /// worker is SWAPPED for a waiting one rather than multiplying concurrency — with no waiter, the flood
    /// pacing that this bound exists to preserve is untouched.
    @Contract
    private void preemptStalledSlots() {
        if (queuedMaterializations.isEmpty()) {
            return;
        }

        var tick = reconcileTick.get();

        Set.copyOf(inFlightMaterializations)
           .stream()
           .filter(ref -> tick - slotAcquiredTick.getOrDefault(ref, tick) >= RESHUFFLE_SLOT_MAX_TICKS)
           .forEach(this::preemptReshuffleSlot);
    }

    @Contract
    private void preemptReshuffleSlot(PartitionRef ref) {
        if (!inFlightMaterializations.remove(ref)) {
            return;
        }

        slotAcquiredTick.remove(ref);
        preemptedSlots.add(ref);
        reshuffleSlots.release();
        log.warn("Preempted reshuffle slot for {}[{}] after {} reconcile ticks — backfill continues but no longer counts against reshuffle concurrency ({} partitions were queued behind it)",
                 ref.streamName(),
                 ref.partition(),
                 RESHUFFLE_SLOT_MAX_TICKS,
                 queuedMaterializations.size());
    }

    private void freeCompletedSlots() {
        Set.copyOf(inFlightMaterializations).forEach(this::freeSlotIfComplete);
        Set.copyOf(preemptedSlots).stream().filter(this::slotComplete).forEach(preemptedSlots::remove);
    }

    @Contract
    private void freeSlotIfComplete(PartitionRef ref) {
        if (slotComplete(ref)) {
            freeReshuffleSlot(ref);
        }
    }

    /// A reshuffle slot frees when the partition is no longer materialized (released/destroyed elsewhere), its
    /// role is OWNER or NONE (an owner ring has no backfill; a NONE partition is being released), or this node
    /// has finished backfilling it (self CAUGHT_UP). A REPLICA still catching up keeps its slot.
    private boolean slotComplete(PartitionRef ref) {
        if (!isMaterialized(ref)) {
            return true;
        }

        return placementRoleSupplier.roleFor(ref.streamName(), ref.partition()) != Role.REPLICA || catchupSource.catchupView(ref.streamName(),
                                                                                                                             ref.partition())
                                                                                                                .selfCaughtUp();
    }

    private boolean isMaterialized(PartitionRef ref) {
        return option(streams.get(ref.streamName())).flatMap(entry -> entry.ringFor(ref.partition()))
                     .isPresent();
    }

    private void evaluateReleaseCandidates() {
        streams.forEach(this::evaluateStreamReleases);
    }

    @Contract
    private void evaluateStreamReleases(String name, StreamEntry entry) {
        entry.materializedPartitionIndexes().forEach(partition -> evaluatePartitionRelease(name, entry, partition));
    }

    /// One partition's release state machine (#265 increment 5): HELD (role OWNER/REPLICA) cancels any
    /// candidacy; role NONE starts (or continues) the debounce; past the window the catch-up + owner gates
    /// decide release. Held candidacy is re-checked every tick until the gates pass or the role returns.
    @Contract
    private void evaluatePartitionRelease(String name, StreamEntry entry, int partition) {
        var ref = new PartitionRef(name, partition);

        if (placementRoleSupplier.roleFor(name, partition) != Role.NONE) {
            releaseCandidacy.remove(ref);

            return;
        }

        var since = releaseCandidacy.putIfAbsent(ref, reconcileTick.get());

        if (since == null || reconcileTick.get() - since < RELEASE_DEBOUNCE_TICKS) {
            return;
        }

        if (releaseAllowed(name, partition)) {
            releasePartitionRing(name, entry, ref);
        }
    }

    /// The two release gates (#265 increment 5, owner decision B): the owner rule (committed owner is
    /// elsewhere) AND the catch-up gate (≥ effective, clamped RF OTHER replicas CAUGHT_UP). Both must pass —
    /// the CLAMPED RF means a cluster-shrink reshuffle does not demand copies the shrunk cluster cannot host,
    /// and an owner that lost the HRW role but is still the committed owner holds until ownership moves.
    private boolean releaseAllowed(String name, int partition) {
        return ownerReleaseGuard.committedOwnerElsewhere(name, partition) && caughtUpEnough(name, partition);
    }

    private boolean caughtUpEnough(String name, int partition) {
        return option(streams.get(name)).map(entry -> catchupSource.catchupView(name, partition)
                                                                   .caughtUpReplicaCount() >= effectiveReplicationFactor(entry.config()))
                     .or(false);
    }

    /// Effective (clamped) replication factor for the release catch-up gate: APP ⇒ `clamp(replicas, 1,
    /// clusterSize)`, SYSTEM ⇒ the full cluster — the SAME formula [ReplicaPlacement] uses for placement, so
    /// the gate's RF matches the placement RF. `0` when the cluster size is unknown (Forge/unit), which makes
    /// the gate trivially pass — those managers never run the reconcile.
    private int effectiveReplicationFactor(StreamConfig config) {
        var streamClass = isSystemStream(config.name())
                          ? StreamClass.SYSTEM
                          : StreamClass.APP;

        return ReplicaPlacement.replicationFactor(streamClass, config.replicas(), clusterSizeSupplier.getAsInt());
    }

    /// Release a single materialized partition's ring on confirmed role loss (#265 increment 5). Atomic remove
    /// from the entry's `materialized` map — the SAME discipline `reapIfIdle`/`close` use: an in-flight
    /// read/append that already resolved the ring finishes against it (its `append` then sees the named
    /// BUFFER_CLOSED), a new access gets [Option#none] → PARTITION_NOT_LOCAL and forwards. Control bytes return
    /// to the pool and the ring `close()` seam-releases its data bytes; the per-partition WAL FILE is KEPT on
    /// disk (cheap re-hydration on flap-back + crash-recovery value — reaper cleanup is future work).
    private void releasePartitionRing(String name, StreamEntry entry, PartitionRef ref) {
        entry.releasePartition(ref.partition()).onPresent(mp -> completeRelease(ref, mp));
    }

    @Contract
    private void completeRelease(PartitionRef ref, StreamEntry.MaterializedPartition mp) {
        var controlBytes = mp.ring().controlBytes();

        release(controlBytes);
        mp.close();
        releaseCandidacy.remove(ref);
        freeReshuffleSlot(ref);
        releasedSinceBoot.incrementAndGet();
        log.info("Released stream partition {}[{}] on role loss — ring + {} control floor bytes freed, WAL retained on disk",
                 ref.streamName(),
                 ref.partition(),
                 controlBytes);
    }

    /// Drain queued materializations as slots free (#265 increment 5): the system queue FIRST (owner decision
    /// 2026-07-05 — cluster-critical streams never wait behind app-stream pressure), then the app queue FIFO.
    /// Each class purges stale heads (stream gone / no longer held / already materialized) and then, while a
    /// slot is free and the head can proceed (app: budget headroom too — the budget-AND), dequeues + materializes.
    private void drainMaterializeQueue() {
        drainClass(systemMaterializeQueue, true);
        drainClass(appMaterializeQueue, false);
    }

    @Contract
    private void drainClass(Deque<PartitionRef> queue, boolean system) {
        queue.removeIf(this::purgeStaleQueued);
        while (!queue.isEmpty() && reshuffleSlots.availablePermits() > 0 && headCanProceed(queue.peekFirst(), system)) {
            materializeQueued(queue.removeFirst());
        }
    }

    /// Drop a queued ref no longer drainable — stream removed, this node no longer holds the partition (role
    /// NONE), or already materialized — clearing its dedup entry so a fresh request can re-queue.
    private boolean purgeStaleQueued(PartitionRef ref) {
        var stale = !drainEligible(ref);

        if (stale) {
            queuedMaterializations.remove(ref);
        }

        return stale;
    }

    private boolean drainEligible(PartitionRef ref) {
        return option(streams.get(ref.streamName())).map(entry -> drainEligibleIn(ref, entry))
                     .or(false);
    }

    private boolean drainEligibleIn(PartitionRef ref, StreamEntry entry) {
        return entry.ringFor(ref.partition())
                    .isEmpty() && shouldMaterialize(ref.streamName(), ref.partition());
    }

    /// A queued head may materialize when a slot is free (the caller's guard) and — for an APP stream — the
    /// pool has headroom for its floor (the budget-AND; a system stream bypasses this and oversubscribes if
    /// needed). App head-of-line: a FIFO head that cannot get budget blocks its class, preserving order.
    private boolean headCanProceed(PartitionRef head, boolean system) {
        return system || availableBytes() >= floorFor(head);
    }

    private long floorFor(PartitionRef ref) {
        return option(streams.get(ref.streamName())).map(entry -> perPartitionFloorBytes(entry.config()))
                     .or(0L);
    }

    @Contract
    private void materializeQueued(PartitionRef ref) {
        queuedMaterializations.remove(ref);
        materializePartition(ref.streamName(), ref.partition()).onFailure(cause -> log.debug("Queued materialize of {}[{}] did not complete this tick: {}",
                                                                                             ref.streamName(),
                                                                                             ref.partition(),
                                                                                             cause.message()));
    }

    /// Budget-deferred single-partition materialization (#265 increment 3, spec §6/§11): the per-partition
    /// floor does not fit, so NO ring is built (no over-subscription). Emit the named budget event to the
    /// sink + WARN and return {@link StreamError.MaterializeBudgetExceeded} — DISTINCT from
    /// {@link StreamError.General#PARTITION_NOT_LOCAL} (a genuine non-replica the caller FORWARDS): this
    /// node IS the holder, so the caller RETRIES (reconcile hook next tick / owner-append client retry),
    /// never a forward loop. The partition stays DEFERRED (metadata-only) until budget frees.
    private Result<OffHeapRingBuffer> reportMaterializeDeferred(StreamConfig config, int partition, long floorBytes) {
        log.warn("Off-heap budget exhausted materializing {}[{}]: need {} floor bytes, {} available of {} — partition deferred metadata-only",
                 config.name(),
                 partition,
                 floorBytes,
                 availableBytes(),
                 maxTotalBytes);
        exhaustionSink.accept(Exhaustion.createFloor(config, floorBytes, availableBytes(), maxTotalBytes));

        return new StreamError.MaterializeBudgetExceeded(config.name(),
                                                         partition,
                                                         floorBytes,
                                                         availableBytes(),
                                                         maxTotalBytes).result();
    }

    private OffHeapRingBuffer installOrRelease(StreamEntry entry,
                                               int partition,
                                               StreamEntry.MaterializedPartition candidate,
                                               long floorBytes) {
        var winner = entry.installPartition(partition, candidate);

        if (winner != candidate) {
            release(floorBytes);
            candidate.closeWithoutRelease();
        }

        return winner.ring();
    }

    /// Per-partition floor = `OffHeapRingBuffer.floorBytes(maxCount, maxBytes)` = `(header + index +
    /// first-data-segment)` — the bytes one partition ring allocates at construction. Shared by the
    /// per-stream held-floor admission and the #265 hydration snapshot's per-materialized-ring byte tally.
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

    /// One [PartitionInfo] per DECLARED partition, in index order (#265 increment 2). A materialized
    /// partition reports its live ring head/tail/count; a metadata-only partition (not held on this node)
    /// reports an empty `(-1, -1, 0)` so the listing still has one entry per declared partition.
    private static List<PartitionInfo> buildAllPartitionInfo(StreamEntry entry) {
        var infos = new ArrayList<PartitionInfo>();

        for (int i = 0; i < entry.declaredPartitions(); i++) {
            infos.add(partitionInfoFor(entry, i));
        }

        return List.copyOf(infos);
    }

    private static PartitionInfo partitionInfoFor(StreamEntry entry, int partition) {
        return entry.ringFor(partition)
                    .map(buffer -> PartitionInfo.partitionInfo(partition,
                                                               buffer.headOffset(),
                                                               buffer.tailOffset(),
                                                               buffer.eventCount()))
                    .or(PartitionInfo.partitionInfo(partition, -1L, -1L, 0L));
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
    /// live budget counters; `overBudget` is `totalAllocatedBytes > maxTotalBytes` — a follower can no
    /// longer over-subscribe as of increment 3 (spec §6), so this stays false in steady state and is
    /// retained as a belt-and-braces sensor. `deferredPartitions` (#265 increment 3) is the node-wide
    /// count of held-but-not-yet-materialized partitions across all streams — the budget-defer sensor
    /// (spec §6). `perStreamCeiling` / `clusterAggregateGuard` / `currentAggregatePartitionSlots` /
    /// `aggregateHeadroom` / `configOverCeilingStreams` (#265 increment 4, spec §7) are the partition-cap
    /// observability: the absolute per-stream ceiling, the `100 × nodes × maxDeclaredReplicas` aggregate guard
    /// (`-1` when the cluster size is unknown on a non-cluster manager), the current cluster ring-slot total
    /// (Σ `partitions × replicas`), the remaining headroom (`guard − current`, or `-1` when unenforced), and
    /// the count of streams whose committed config is over the ceiling (the follower-defense flag). `streams`
    /// carries one [StreamHydration] per live stream. `releaseCandidates` (#265 increment 5) is the number of
    /// materialized partitions currently DEBOUNCING toward release (role went NONE, not yet released);
    /// `releasedPartitionsSinceBoot` the running count of partition rings released on role loss since this node
    /// booted; `materializeQueueDepth` the number of partitions queued behind the `reshuffle_concurrency` slot
    /// limit (system + app queues) awaiting a free slot.
    public record HydrationSnapshot(long totalAllocatedBytes,
                                    long maxTotalBytes,
                                    boolean overBudget,
                                    long deferredPartitions,
                                    int perStreamCeiling,
                                    long clusterAggregateGuard,
                                    long currentAggregatePartitionSlots,
                                    long aggregateHeadroom,
                                    int configOverCeilingStreams,
                                    long releaseCandidates,
                                    long releasedPartitionsSinceBoot,
                                    long materializeQueueDepth,
                                    List<StreamHydration> streams) {}

    /// Per-stream hydration view (#265 increment 0). `partitionsDeclared` is the configured partition
    /// count; `ringsMaterialized` the number of partition rings actually built locally (a non-replica
    /// builds fewer, increment 2); `partitionsDeferred` (#265 increment 3) the held partitions NOT yet
    /// materialized (`max(0, held − materialized)` — budget-deferred per spec §6 or pre-membership per
    /// §5.4); `floorBytesAllocated` the per-partition floor times the materialized ring count;
    /// `overCeiling` (#265 increment 4, spec §7) whether this committed config declares more partitions than
    /// the per-stream ceiling (the follower-defense flag — materialization still proceeds under the budget
    /// backstop); `roleCounts` the placement-role tally under the current supplier (default: all OWNER).
    public record StreamHydration(String name,
                                  int partitionsDeclared,
                                  int ringsMaterialized,
                                  int partitionsDeferred,
                                  long floorBytesAllocated,
                                  boolean overCeiling,
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
            GROWTH,
            CONFIG_OVER_CEILING,
            SYSTEM_OVERSUBSCRIBE
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

        /// Follower over-ceiling signal (#265 increment 4, spec §7/§11): a committed config declares more
        /// partitions than the per-stream ceiling. Carries the declared partition count in `partitions`; the
        /// byte fields are 0 (a partition-count admission event, not an off-heap shortage). Routed through the
        /// SAME exhaustion sink so the aggregator stamps the node id and emits it, throttled on its own
        /// `(stream, CONFIG_OVER_CEILING)` bucket.
        static Exhaustion overCeiling(StreamConfig config) {
            return new Exhaustion(config.name(),
                                  config.partitions(),
                                  Phase.CONFIG_OVER_CEILING,
                                  0L,
                                  0L,
                                  0L,
                                  config.consistencyMode());
        }

        /// System-stream budget-oversubscribe signal (#265 increment 5, owner decision 2026-07-05): a
        /// cluster-critical `system:*` stream reserved its floor PAST the off-heap cap rather than defer.
        /// A DISTINCT phase from the app-stream `CREATE_FLOOR` deferral so operators can tell a deliberate,
        /// bounded system oversubscription apart from an app-stream budget shortage. Carries the same byte
        /// framing as the budget events.
        static Exhaustion systemOversubscribe(StreamConfig config,
                                              long requestedBytes,
                                              long availableBytes,
                                              long maxTotalBytes) {
            return new Exhaustion(config.name(),
                                  config.partitions(),
                                  Phase.SYSTEM_OVERSUBSCRIBE,
                                  requestedBytes,
                                  availableBytes,
                                  maxTotalBytes,
                                  config.consistencyMode());
        }

        public String summary() {
            return switch (phase) {
                case CONFIG_OVER_CEILING -> ceilingSummary();
                case CREATE_FLOOR, GROWTH, SYSTEM_OVERSUBSCRIBE -> budgetSummary();
            };
        }

        private String ceilingSummary() {
            return "Committed stream config over per-stream partition ceiling for stream '" + streamName
                 + "' (" + partitions
                 + " partitions, ceiling " + MAX_PARTITIONS_PER_STREAM_CEILING
                 + ")";
        }

        private String budgetSummary() {
            return "Off-heap budget exhausted (" + phaseLabel()
                 + ") for stream '" + streamName
                 + "' (" + partitions
                 + " parts): need " + requestedBytes
                 + " bytes, " + availableBytes
                 + " available of " + maxTotalBytes;
        }

        public Map<String, String> details() {
            return switch (phase) {
                case CONFIG_OVER_CEILING -> ceilingDetails();
                case CREATE_FLOOR, GROWTH, SYSTEM_OVERSUBSCRIBE -> budgetDetails();
            };
        }

        private Map<String, String> ceilingDetails() {
            return Map.of("streamName",
                          streamName,
                          "declaredPartitions",
                          Integer.toString(partitions),
                          "phase",
                          phaseLabel(),
                          "ceiling",
                          Integer.toString(MAX_PARTITIONS_PER_STREAM_CEILING),
                          "consistencyMode",
                          consistencyMode.name());
        }

        private Map<String, String> budgetDetails() {
            return Map.of("streamName",
                          streamName,
                          "partitions",
                          Integer.toString(partitions),
                          "phase",
                          phaseLabel(),
                          "requestedBytes",
                          Long.toString(requestedBytes),
                          "availableBytes",
                          Long.toString(availableBytes),
                          "maxTotalBytes",
                          Long.toString(maxTotalBytes),
                          "consistencyMode",
                          consistencyMode.name());
        }

        private String phaseLabel() {
            return phase.name()
                        .toLowerCase()
                        .replace('_', '-');
        }
    }

    record StreamEntry(StreamConfig config,
                       int declaredPartitions,
                       ConcurrentHashMap<Integer, MaterializedPartition> materialized,
                       long createdAt,
                       AtomicLong lastActivityRef,
                       AtomicBoolean configCommitted) implements AutoCloseable {
        /// A locally-materialized partition (#265 increment 2): its [OffHeapRingBuffer] plus the optional
        /// per-partition [PartitionWal]. Only OWNER/REPLICA partitions (under the current placement) are
        /// materialized; a metadata-only (non-replica) partition has NO entry in `materialized` — no ring,
        /// no reserved off-heap bytes. Rings are only ever ADDED this increment (at hydrate for held
        /// partitions, lazily via the reconcile hook / owner-append safety valve); release-on-role-loss is
        /// increment 5.
        record MaterializedPartition(OffHeapRingBuffer ring, Option<PartitionWal> wal) {
            /// Genuine removal / shutdown close: the ring `close()` seam-releases its first-segment +
            /// grown data bytes; the WAL channel is flushed + closed (the file is kept for a later replay).
            @Contract
            void close() {
                ring.close();
                closeWal(wal);
            }

            /// Close a duplicate that LOST the install race (or whose WAL open/recovery failed): free the
            /// native Arena WITHOUT seam-releasing (the manager releases the reserved floor lump itself)
            /// and close the WAL channel WITHOUT deleting the file (the install winner shares the same
            /// `<base>/<stream>/<partition>.wal`). Mirrors `closeBuilt`'s `closeWithoutRelease` contract.
            @Contract
            void closeWithoutRelease() {
                ring.closeWithoutRelease();
                closeWal(wal);
            }

            @Contract
            void deleteWal() {
                deleteWalFile(wal);
            }
        }

        /// Materialize the partitions THIS node holds (#265 increment 2). `shouldMaterialize` gates each
        /// declared partition on placement — a ring is built iff `roleFor(stream, partition) ∈ {OWNER,
        /// REPLICA}`; a non-replica partition is metadata-only (absent from `materialized`, no ring, no
        /// bytes). The selected rings thread the per-partition floor-allocation `Result` (bug #6): a
        /// native-OOM ring alloc closes the siblings already built and returns the canonical
        /// `STREAM_MEMORY_EXCEEDED` (preserving `transientCapacity()` retry-classification). Each built
        /// ring is paired with its per-partition WAL (`walBaseDir`, W6) and its un-sealed tail replayed at
        /// ORIGINAL offsets (W4, bounded by `lastSealedOffset`); a WAL-open/replay failure closes the built
        /// rings and propagates with its own cause. When NO partition is held (non-replica node) the entry
        /// is built with an EMPTY `materialized` map — metadata present, zero off-heap bytes reserved.
        static Result<StreamEntry> fromConfig(StreamConfig config,
                                              EvictionListener listener,
                                              LongPredicate reserve,
                                              LongConsumer release,
                                              IntPredicate shouldMaterialize,
                                              Option<Path> walBaseDir,
                                              LastSealedOffsetSource lastSealedOffset) {
            var selected = selectedPartitions(config, shouldMaterialize);
            var ringResults = buildRings(config, selected, listener, reserve, release);

            return Result.allOf(ringResults)
                         .mapError(_ -> StreamError.General.STREAM_MEMORY_EXCEEDED)
                         .flatMap(rings -> openEntryWals(config, selected, rings, walBaseDir, lastSealedOffset))
                         .onFailure(_ -> closeBuilt(ringResults));
        }

        /// Metadata-only entry (#265 increment 3): the committed config with an EMPTY `materialized` map —
        /// no ring, no reserved off-heap bytes, no WAL. Used when hydration is DEFERRED by budget (spec §6):
        /// the follower holds the stream metadata (does not diverge from committed config) while its held
        /// partitions materialize later through the deferred-retry entry point once budget frees. Cannot
        /// fail (nothing is allocated), so — unlike {@link #fromConfig} — it returns the entry directly.
        static StreamEntry metadataOnly(StreamConfig config) {
            var now = System.currentTimeMillis();

            return new StreamEntry(config,
                                   config.partitions(),
                                   new ConcurrentHashMap<>(),
                                   now,
                                   new AtomicLong(now),
                                   new AtomicBoolean(false));
        }

        /// The declared partitions THIS node materializes under the current placement — the ordered subset
        /// of `[0, partitions)` for which `shouldMaterialize` holds (OWNER/REPLICA). Empty on a non-replica
        /// node.
        private static List<Integer> selectedPartitions(StreamConfig config, IntPredicate shouldMaterialize) {
            return IntStream.range(0,
                                   config.partitions())
                            .filter(shouldMaterialize)
                            .boxed()
                            .toList();
        }

        private static List<Result<OffHeapRingBuffer>> buildRings(StreamConfig config,
                                                                  List<Integer> selected,
                                                                  EvictionListener listener,
                                                                  LongPredicate reserve,
                                                                  LongConsumer release) {
            return selected.stream()
                           .map(partition -> buildRing(config, partition, listener, reserve, release))
                           .toList();
        }

        private static Result<OffHeapRingBuffer> buildRing(StreamConfig config,
                                                           int partition,
                                                           EvictionListener listener,
                                                           LongPredicate reserve,
                                                           LongConsumer release) {
            var retention = config.retention();

            return OffHeapRingBuffer.offHeapRingBuffer(config.name(),
                                                       partition,
                                                       retention.maxCount(),
                                                       retention.maxBytes(),
                                                       listener,
                                                       deriveEvictionPolicy(config),
                                                       reserve,
                                                       release);
        }

        /// Lazily materialize ONE held partition (#265 increment 2 — the reconcile hook + owner-append
        /// safety valve). Builds the ring (collapsing a native-OOM to `STREAM_MEMORY_EXCEEDED`), opens its
        /// per-partition WAL and replays the un-sealed tail (W4/W6). On any failure the ring is freed
        /// WITHOUT seam-release (the manager releases the reserved floor lump) so there is no Arena leak.
        static Result<MaterializedPartition> materializeOne(StreamConfig config,
                                                            int partition,
                                                            EvictionListener listener,
                                                            LongPredicate reserve,
                                                            LongConsumer release,
                                                            Option<Path> walBaseDir,
                                                            LastSealedOffsetSource lastSealedOffset) {
            return buildRing(config, partition, listener, reserve, release).mapError(_ -> StreamError.General.STREAM_MEMORY_EXCEEDED)
                            .flatMap(ring -> openAndRecoverOne(config, partition, ring, walBaseDir, lastSealedOffset));
        }

        private static Result<MaterializedPartition> openAndRecoverOne(StreamConfig config,
                                                                       int partition,
                                                                       OffHeapRingBuffer ring,
                                                                       Option<Path> walBaseDir,
                                                                       LastSealedOffsetSource lastSealedOffset) {
            return openWal(config, partition, walBaseDir).onFailure(_ -> ring.closeWithoutRelease())
                          .flatMap(wal -> recoverOne(config, partition, ring, wal, lastSealedOffset));
        }

        private static Result<MaterializedPartition> recoverOne(StreamConfig config,
                                                                int partition,
                                                                OffHeapRingBuffer ring,
                                                                Option<PartitionWal> wal,
                                                                LastSealedOffsetSource lastSealedOffset) {
            return recoverPartition(config.name(),
                                    partition,
                                    ring,
                                    wal,
                                    lastSealedOffset).map(_ -> new MaterializedPartition(ring, wal))
                                   .onFailure(_ -> closeRingAndWal(ring, wal));
        }

        @Contract
        private static void closeRingAndWal(OffHeapRingBuffer ring, Option<PartitionWal> wal) {
            ring.closeWithoutRelease();
            closeWal(wal);
        }

        private static StreamEntry entryOf(StreamConfig config,
                                           List<Integer> selected,
                                           List<OffHeapRingBuffer> rings,
                                           List<Option<PartitionWal>> wals) {
            var map = new ConcurrentHashMap<Integer, MaterializedPartition>();

            for (int i = 0; i < selected.size(); i++) {
                map.put(selected.get(i),
                        new MaterializedPartition(rings.get(i), wals.get(i)));
            }

            var now = System.currentTimeMillis();

            return new StreamEntry(config, config.partitions(), map, now, new AtomicLong(now), new AtomicBoolean(false));
        }

        /// Pair the freshly-built held rings with their per-partition [PartitionWal] (W6) and replay each
        /// WAL's un-sealed tail back into its ring (W4). A [Option#none] `walBaseDir` yields a
        /// selected-aligned list of [Option#none] (no WAL ⇒ unchanged behavior); a present base dir opens
        /// `<base>/<stream>/<partition>.wal` for each SELECTED partition index and recovers its tail. A
        /// WAL-open or replay failure closes the WALs already opened and propagates, leaving the caller to
        /// free the rings.
        private static Result<StreamEntry> openEntryWals(StreamConfig config,
                                                         List<Integer> selected,
                                                         List<OffHeapRingBuffer> rings,
                                                         Option<Path> walBaseDir,
                                                         LastSealedOffsetSource lastSealedOffset) {
            return openWals(config, selected, walBaseDir).flatMap(wals -> recoverWals(config,
                                                                                      selected,
                                                                                      rings,
                                                                                      wals,
                                                                                      lastSealedOffset))
                           .map(wals -> entryOf(config, selected, rings, wals));
        }

        /// Replay every partition's un-sealed WAL tail into its fresh ring (streaming-persistence W4),
        /// returning the same partition-aligned WAL list on success so the entry can be assembled. Runs
        /// ONCE at build time, before the partition is published/serving, so the ring is empty and the
        /// replayed events land at their original offsets. On any partition's replay failure the WALs are
        /// closed and the failure propagates (the rings are freed by the caller).
        private static Result<List<Option<PartitionWal>>> recoverWals(StreamConfig config,
                                                                      List<Integer> selected,
                                                                      List<OffHeapRingBuffer> rings,
                                                                      List<Option<PartitionWal>> wals,
                                                                      LastSealedOffsetSource lastSealedOffset) {
            var results = new ArrayList<Result<Unit>>(rings.size());

            for (int i = 0; i < rings.size(); i++) {
                results.add(recoverPartition(config.name(), selected.get(i), rings.get(i), wals.get(i), lastSealedOffset));
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

        private static Result<List<Option<PartitionWal>>> openWals(StreamConfig config,
                                                                   List<Integer> selected,
                                                                   Option<Path> walBaseDir) {
            return walBaseDir.map(baseDir -> openSelectedWals(config, selected, baseDir))
                             .or(() -> success(noWals(selected.size())));
        }

        private static Result<List<Option<PartitionWal>>> openSelectedWals(StreamConfig config,
                                                                           List<Integer> selected,
                                                                           Path baseDir) {
            var results = selected.stream()
                                  .map(partition -> openPartitionWal(baseDir,
                                                                     config.name(),
                                                                     partition).map(Option::some))
                                  .toList();

            return Result.allOf(results).onFailure(_ -> closeOpenedWals(results));
        }

        /// Open (or no-WAL) the [PartitionWal] for a single lazily-materialized partition index. Mirrors
        /// {@link #openSelectedWals} for the one-partition materialize path ({@link #materializeOne}).
        private static Result<Option<PartitionWal>> openWal(StreamConfig config,
                                                            int partition,
                                                            Option<Path> walBaseDir) {
            return walBaseDir.map(baseDir -> openPartitionWal(baseDir,
                                                              config.name(),
                                                              partition).map(Option::some))
                             .or(() -> success(Option.none()));
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

        /// The [PartitionWal] for `partition`, or [Option#none] when the partition is metadata-only (not
        /// materialized on this node), out of range, or no WAL is configured.
        Option<PartitionWal> walFor(int partition) {
            return option(materialized.get(partition)).flatMap(MaterializedPartition::wal);
        }

        /// The materialized [OffHeapRingBuffer] for `partition`, or [Option#none] when the partition is
        /// metadata-only on this node (non-replica, or not yet materialized in the deferred window) or out
        /// of range. Every consumer routes through this — a metadata-only partition never yields a ring.
        Option<OffHeapRingBuffer> ringFor(int partition) {
            return option(materialized.get(partition)).map(MaterializedPartition::ring);
        }

        /// All rings actually materialized on this node (the OWNER/REPLICA partitions), in no particular
        /// order. Used by telemetry / release accounting, which sum only over held rings.
        List<OffHeapRingBuffer> materializedRings() {
            return materialized.values()
                               .stream()
                               .map(MaterializedPartition::ring)
                               .toList();
        }

        /// Count of partition rings actually built locally (`≤ declaredPartitions`). Diverges from the
        /// declared count on a node that is not OWNER/REPLICA of every partition (#265 increment 2).
        int ringsMaterialized() {
            return materialized.size();
        }

        /// Atomically remove and return the materialized partition for `partition` (#265 increment 5 release),
        /// or [Option#none] if it was not materialized. `ConcurrentHashMap.remove` is the CAS at the map slot —
        /// the SAME discipline `reapIfIdle` uses at stream granularity; the caller frees the budget + closes the
        /// ring (keeping the WAL file). A concurrent in-flight read/append that already resolved the ring
        /// finishes against it; a new access misses ([Option#none]) and forwards.
        Option<MaterializedPartition> releasePartition(int partition) {
            return option(materialized.remove(partition));
        }

        /// The indexes of the partitions materialized on this node — the release reconcile sweeps these
        /// (#265 increment 5). A copy so the sweep can release without a concurrent-modification hazard.
        Set<Integer> materializedPartitionIndexes() {
            return Set.copyOf(materialized.keySet());
        }

        /// Install a lazily-built [MaterializedPartition] iff `partition` is not already materialized,
        /// returning the WINNER — `candidate` when it won the race, or the already-installed partition when
        /// a concurrent materialize (reconcile hook vs owner-append safety valve) beat it. The caller
        /// closes + releases the losing duplicate. `putIfAbsent` makes this a CAS at the map slot.
        MaterializedPartition installPartition(int partition, MaterializedPartition candidate) {
            return option(materialized.putIfAbsent(partition, candidate)).or(candidate);
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

        /// Live bytes allocated across all MATERIALIZED partitions (control + allocated data segments).
        /// Used by telemetry and as the release-on-destroy basis. Metadata-only partitions contribute
        /// nothing. See spec §4.3.
        long allocatedBytes() {
            var total = 0L;

            for (var buffer : materializedRings()) {
                total += buffer.allocatedBytes();
            }

            return total;
        }

        /// Control-region bytes (header + index) summed across MATERIALIZED partitions. This is the portion
        /// of the floor the manager reserved but the buffer's growth/close seam does NOT account, so the
        /// manager releases exactly this on destroy (the buffer releases its data bytes itself). See
        /// spec §4.3.
        long controlBytes() {
            var total = 0L;

            for (var buffer : materializedRings()) {
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
        /// copy that swaps ONLY the [StreamConfig], reusing the SAME materialized-partition map, declared
        /// count, commit latch and activity clock so no buffered data is dropped and no off-heap bytes are
        /// re-reserved. Called only from the notification-thread config reconcile
        /// ({@link StreamPartitionManager#adoptConfig}) with a partition-count-compatible config.
        StreamEntry withConfig(StreamConfig newConfig) {
            return new StreamEntry(newConfig,
                                   declaredPartitions,
                                   materialized,
                                   createdAt,
                                   lastActivityRef,
                                   configCommitted);
        }

        private static EvictionPolicy deriveEvictionPolicy(StreamConfig config) {
            return config.consistencyMode() == ConsistencyMode.STRONG
                   ? EvictionPolicy.REJECT_WHEN_FULL
                   : EvictionPolicy.DROP_OLDEST;
        }

        /// Close every MATERIALIZED partition ring and its WAL channel (flush + fsync + close). Process-
        /// shutdown safe: this only closes channels and KEEPS the WAL files on disk for a later replay —
        /// file deletion happens exclusively on genuine stream removal via {@link #deleteWals}. Metadata-
        /// only partitions hold nothing and are untouched.
        @Contract
        @Override
        public void close() {
            materialized.values().forEach(MaterializedPartition::close);
        }

        /// Delete every MATERIALIZED partition's WAL file — called ONLY when the stream is genuinely removed
        /// (destroy / config-remove / idle-reap), never on process shutdown or a put-if-absent loser.
        /// The channels are already closed by {@link #close}; deletion is best-effort and a failure is
        /// logged, not propagated.
        @Contract
        void deleteWals() {
            materialized.values().forEach(MaterializedPartition::deleteWal);
        }

        @Contract
        private static void deleteWalFile(Option<PartitionWal> wal) {
            wal.onPresent(w -> FileOps.deleteIfExists(w.path()).onFailure(cause -> log.warn("Failed to delete WAL file {}: {}",
                                                                                            w.path(),
                                                                                            cause.message())));
        }
    }
}
