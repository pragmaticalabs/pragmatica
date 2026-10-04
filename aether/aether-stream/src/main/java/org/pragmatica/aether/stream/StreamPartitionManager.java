// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
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
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.aether.slice.ReplicationFactorsError;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.fence.OwnershipDomain;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.PartitionRecoveryReasonKind;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.aether.stream.segment.StreamFootprint;
import org.pragmatica.aether.stream.replication.ReplicaPlacement;
import org.pragmatica.aether.stream.replication.ReplicaPlacement.StreamClass;
import org.pragmatica.aether.stream.replication.ReplicaSetController;
import org.pragmatica.aether.stream.replication.ReplicaDescriptor;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.aether.stream.replication.QuarantineView;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.aether.stream.topic.DurableTopicNames;
import org.pragmatica.aether.stream.provenance.LogProvenance;
import org.pragmatica.aether.stream.provenance.PartitionFlags;
import org.pragmatica.aether.stream.provenance.ProvenanceComparison;
import org.pragmatica.aether.stream.provenance.ProvenanceEntry;
import org.pragmatica.aether.stream.provenance.ProvenanceEpoch;
import org.pragmatica.aether.stream.replication.AlignedRecovery;
import org.pragmatica.storage.AppendLog;
import org.pragmatica.storage.AppendLog.WalRecord;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.NullReturn;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.TerminalOperation;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageReceiver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;


public final class StreamPartitionManager implements AutoCloseable {
    /// A held-back tick may be the ordinary race between the truncation tick and the snapshot interval; the
    /// second consecutive one is not (the interval has elapsed), so that is when the tick first WARNs.
    private static final long HELD_BACK_GRACE_TICKS = 2;
    /// After the first WARN, repeat every this many held-back ticks (5 min at the 30 s cadence).
    private static final long HELD_BACK_WARN_EVERY = 10;
    private static final int HELD_BACK_WARN_SAMPLE = 5;
    private static final long DEFAULT_MAX_TOTAL_BYTES = 128 * 1024 * 1024L;
    private static final TimeSpan COMMIT_TIMEOUT = TimeSpan.timeSpan(10).seconds();
    /// #1278 ruling F: how long the publish auto-create path waits for its proposed config to commit and apply here.
    /// Inside the 5 s forward timeout that made this path asynchronous; past it the publish refuses retriably.
    private static final TimeSpan PUBLISH_COMMIT_WAIT = TimeSpan.timeSpan(2).seconds();
    /// Bound on dropping or checking a stream's durable footprint (a local ref write and one forced snapshot).
    private static final TimeSpan FOOTPRINT_TIMEOUT = TimeSpan.timeSpan(30).seconds();
    private static final Logger log = LoggerFactory.getLogger(StreamPartitionManager.class);
    /// Distinct lost WAL heads (node-wide, since process start): WAL recoveries refused because the WAL started above
    /// the durable sealed watermark, which already counts every offset retention reclaimed (#1278,
    /// [StreamError.WalHeadLost]). Counted once per WAL file and lost range, however often the refused recovery is
    /// re-attempted; every attempt is logged at ERROR naming the range. Any non-zero value is lost records.
    private static final AtomicLong WAL_RECOVERY_HEADS_LOST = new AtomicLong();

    /// The lost-head refusals already counted, by WAL file and the lost range: a refused partition is re-attempted on
    /// every publish and every placement edge, and each re-attempt is the SAME loss, counted once.
    private static final java.util.Set<StreamError.WalHeadLost> COUNTED_HEADS_LOST = java.util.concurrent.ConcurrentHashMap.newKeySet();

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

    /// Reconcile ticks a partition may stay held but unmaterialized before the manager WARNs
    /// ([#redriveHeldUnmaterialized]). At the 5s reconcile interval this is ~60s — well past the ~30s a stalled slot
    /// takes to be preempted ([#RESHUFFLE_SLOT_MAX_TICKS]), so reaching it means the re-drive is not converging.
    /// Repeats every this many ticks while the condition holds.
    static final int HELD_UNMATERIALIZED_WARN_TICKS = 12;

    /// The `system:*` namespace prefix (mirrors `ReplicaSetController.SYSTEM_NAMESPACE_PREFIX`). System
    /// streams are cluster-critical and full-cluster placed; per owner decision 2026-07-05 they BYPASS the
    /// off-heap budget reject (always materialize, emitting a named {@link Exhaustion.Phase#SYSTEM_OVERSUBSCRIBE}
    /// WARN when over budget) and drain FIRST in the reshuffle materialization queue.
    private static final String SYSTEM_STREAM_PREFIX = "system:";
    /// The `entity:<keyspace>` stream-name prefix of entity keyspace logs. Mirrors
    /// `org.pragmatica.aether.dht.EntityPartitionArc.ARC_PREFIX` (aether-dht, which this module does not
    /// depend on); agreement is pinned end-to-end through the real entity substrate by
    /// `StreamEntityLogSubstrateTest.append_failsWithEventDropped_whenFrozenRingCannotFitRecord_evenAtReplicationFactorOneWithoutWal`.
    private static final String ENTITY_STREAM_PREFIX = "entity:";

    /// Default exhaustion sink — no-op. Wave 3 (`AetherNode`) replaces it with a binding to the
    /// cluster-event aggregator. See spec §4.5c.
    private static final Consumer<Exhaustion> NOOP_SINK = _ -> {};

    /// Default placement-role supplier (#265 increment 1): reports [ReplicaSetController.Role#OWNER]
    /// for every `(stream, partition)` — the always-materialize behavior that preserves the pre-seam
    /// semantics exactly. `AetherNode` late-binds the real [ReplicaSetController#roleFor].
    private static final PlacementRoleSupplier ALWAYS_OWNER = (_, _) -> ReplicaSetController.Role.OWNER;

    private final ConcurrentHashMap<String, StreamEntry> streams = new ConcurrentHashMap<>();
    /// #1564 (R8): the config of every `StreamConfigKey` Put this node has APPLIED, by stream name — the committed
    /// value exactly as KV holds it, independent of whether (or with which factors) a local entry exists. A local
    /// entry can lag or differ: a native-OOM hydrate leaves none, and [#adoptIfMoreDurable] keeps a stronger local
    /// config over a weaker committed one.
    private final ConcurrentHashMap<String, StreamConfig> appliedConfigs = new ConcurrentHashMap<>();
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
    private volatile StreamOwnerEpochSource ownerEpochSource;
    /// Per-partition crash-durable write-ahead log opener (streaming-persistence W3/W6; since #1567 the
    /// storage instance's [org.pragmatica.storage.StorageInstance#openLog]). [Option#none]
    /// = no WAL ⇒ exactly the pre-WAL behavior (Forge/unit/legacy factories). When present, each
    /// partition opens its own [AppendLog] `<streamName>/<partition>` at
    /// ring-create time, and an OWNER publish (`publishLocal`) does not ack until the event is
    /// fsync-durable in that WAL. The replica-receive path (`appendRecovered`) writes the same WAL and
    /// makes it durable at [#syncReplicated] (#634 item 1, #1244).
    private final Option<AppendLog.Opener> logs;
    /// The latest replicated WAL write per `(stream, partition)` key (#1244): what [#syncReplicated]
    /// commits, and the offset it then makes visible (#1235). Updated inside the partition's ordered append
    /// section, so it always holds the highest offset written.
    private final ConcurrentHashMap<String, ReplicatedWrite> lastReplicatedWalWrite = new ConcurrentHashMap<>();
    /// #1505 F2: per partition, the lowest offset at which this replica was found to hold a DIVERGENT entry. Read
    /// and written only inside the partition's ordered append section ([OffHeapRingBuffer#appendOrderedAt]).
    /// Kept for the life of this manager, so it survives a ring release and rebuild. It does NOT survive a
    /// process restart: persisting it would be a new persisted-state format, tracked as #1513. Cleared
    /// only when the stream is destroyed.
    private final ConcurrentHashMap<PartitionRef, Long> divergedAt = new ConcurrentHashMap<>();
    /// #1505 R3: serialises RECORDING a divergence ([PartitionQuarantine#recordDivergence], called inside a ring's
    /// ordered section) against a self-promotion and its completion ack ([#quarantineView]). A promotion that runs
    /// under it either completes before the divergence is recorded, or sees the record and refuses; no ack can
    /// leave in between. Lock order is always ring section → this lock, never the reverse, because a promotion
    /// never appends to a ring.
    private final Object quarantineLock = new Object();
    /// #1596: the durable partition flag, late-bound ([#partitionFlags(PartitionFlags)]).
    private volatile Option<PartitionFlags> partitionFlags = none();
    /// #1730 phase 2: replica partitions whose recovered tail has not been verified against the committed owner yet. Such
    /// a copy may hold a lineage the owner never had (an ex-owner's unacknowledged records), so nothing it recovered is
    /// visible to a read served here until [#markVerified] covers it; its own appends do not drag it into view either.
    private final Set<String> unverifiedReplicas = ConcurrentHashMap.newKeySet();
    /// #1730 phase 2: where a replica's divergent-tail truncation is reported to the operator, late-bound
    /// ([#operatorWarnings(OperatorWarningSink)]); log-only until then.
    private volatile OperatorWarningSink operatorWarnings = OperatorWarningSink.logOnly();
    /// #1596: the reasons this process has already raised, `stream#partition#kind#evidence`, so a condition met on
    /// every append raises one transaction, not one per append. A raise that fails is forgotten and retried at the
    /// next occurrence.
    private final Set<String> raisedLocally = ConcurrentHashMap.newKeySet();
    /// Per partition: the committed owner epoch this copy last judged itself against, and whether it may serve and
    /// acknowledge at and above that epoch's start ([#unverifiedFrom]).
    private final ConcurrentHashMap<String, EpochTrust> epochTrust = new ConcurrentHashMap<>();
    /// Where a committed epoch began, when the cluster records it (the epoch-validated fetch does, #1873). Until then every
    /// offset counts as at or above the start: a copy that holds anything is doubted until it has been compared.
    private volatile EpochStartSource epochStarts = (_, _, _) -> none();

    private record EpochTrust(Epoch epoch, boolean verified) {}

    /// The offset the owner of `epoch` began writing at.
    @FunctionalInterface
    public interface EpochStartSource {
        Option<Long> startOf(String streamName, int partition, Epoch epoch);
    }

    /// The offsets at which a PROVENANCE comparison found this copy divergent (N13), while the partition stays quarantined for
    /// it. The durable flag is raised only if the divergence is not repaired ([#flagUnrepaired]): a repair removes the entry.
    private final ConcurrentHashMap<PartitionRef, Long> provenanceMismatchAt = new ConcurrentHashMap<>();
    /// What the repair in progress of a partition has discarded so far; reported once when it settles ([#settleRepair]).
    private final ConcurrentHashMap<PartitionRef, TailCut> pendingCuts = new ConcurrentHashMap<>();
    /// The range already reported while its repair was unsettled ([#repairReportBound]); the settled report follows only if the
    /// range grew.
    private final ConcurrentHashMap<PartitionRef, TailCut> reportedUnsettled = new ConcurrentHashMap<>();
    private volatile Option<TimeSpan> repairReportBound = Option.none();

    /// Reports of repairs a previous process left unsettled that were found before the operator-warning sink was wired; made
    /// when it is.
    private final java.util.concurrent.CopyOnWriteArrayList<Runnable> deferredReports = new java.util.concurrent.CopyOnWriteArrayList<>();

    private volatile boolean operatorWarningsBound = false;
    /// #1638 F1: the catch-up installs recorded but not yet settled, per partition ([#installProvenance]). Each
    /// partition's list is also that partition's lock serialising recording an install against trimming one, so a trim
    /// never drops an entry a pending install has just recorded or skipped; partitions never wait on each other's
    /// sidecar fsyncs (#1638 N2). Lock order: a partition's list → its log's write lock, never the reverse. One list per
    /// partition that ever caught up here, kept for the manager's lifetime.
    private final Map<PartitionRef, List<InstalledSlice>> pendingInstalls = new ConcurrentHashMap<>();

    private static final long DURABLE_WATERMARK_TTL_NANOS = 10_000_000_000L;

    private final Map<PartitionRef, CachedWatermark> durableWatermarks = new ConcurrentHashMap<>();
    /// Bumped on every ring install and release; a watermark read that started before a bump is never cached, so a
    /// slow read cannot put a pre-install value back after the drop.
    private final AtomicLong watermarkGeneration = new AtomicLong();
    /// Source of the durable last-sealed offset per `(stream, partition)` (streaming-persistence W4).
    /// Bounds WAL replay when a partition ring is (re)built: sealed segments already serve
    /// `[0, lastSealedOffset]`, so a recovered ring is seeded above that bound and replays only the
    /// un-sealed WAL tail at its original offsets. The floor source ([LastSealedOffsetSource#none] →
    /// `-1`) leaves Forge/unit/legacy callers replaying the whole log from offset 0; the aether-level
    /// wiring binds it to the node's [org.pragmatica.aether.stream.segment.SegmentIndex].
    private final LastSealedOffsetSource lastSealedOffset;
    /// Bound for WAL truncation (#1345): the sealed watermarks a restart would REBUILD, not the live index.
    /// See [DurableSealedOffsetSource]. The production wiring derives it from the latest metadata snapshot on
    /// disk; the standalone/test factories treat their single source as durable.
    private final DurableSealedOffsetSource durableSealedOffset;
    /// Durable bound each WAL-backed partition saw at the previous truncation tick — see [#noteHeldBack].
    private final ConcurrentHashMap<String, Long> durableAtPreviousTick = new ConcurrentHashMap<>();
    /// See [#walReclamationHeldBackTicks]. Written only by the truncation tick.
    private volatile long walReclamationHeldBackTicks;
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

    /// Default owner-write admission (#1230): no committed ownership source, so no application append is
    /// refused on ownership grounds. Forge/unit/legacy managers keep this; `AetherNode` late-binds the real
    /// committed-`StreamPartitionOwnershipValue` check.
    private static final OwnerWriteAdmission ADMIT_ALL = (_, _) -> Option.none();
    /// Default owner promotion gate (#1555): Forge/unit/legacy managers act as owner without promotion.
    private static final OwnerServeGate ADMIT_OWNER = (_, _) -> Result.unitResult();
    /// Default owner promotion block source (#1555): no promotion gate, so nothing is ever blocked.
    private static final OwnerBlockSource NO_BLOCK = (_, _) -> Option.none();
    /// Replication receipt and backfill land the COMMITTED owner's events on a replica, so they carry no
    /// owner-write admission (#1230) — only the epoch fence applies to them.
    private static final Result<Unit> RECEIPT_NEEDS_NO_ADMISSION = Result.unitResult();
    /// A publish with no confirmation barrier: the floor check is trivially met.
    private static final int NO_REPLICA_FLOOR = 0;
    /// The base of every partition log's owner-epoch history (#1596, spec #1569 §7.5.2): 0 until operator
    /// resolution (AD14) can start a copy at a `BASE(d)` entry.
    private static final long PROVENANCE_BASE = 0L;

    /// Live committed-ownership admission for application appends (#1230). Consulted by [#publishLocal]
    /// ONLY — never by [#appendRecovered], which lands the committed owner's replicated/backfilled events on
    /// a replica. Default: [#ADMIT_ALL]. Volatile: set once at wiring, read on every owner-path append.
    private volatile OwnerWriteAdmission ownerWriteAdmission = ADMIT_ALL;
    /// Owner promotion gate (#1555), consulted by every application append (after the committed-owner
    /// admission) and by owner-role reads ([#readServing], [#mayServeAsOwner]). Default: [#ADMIT_OWNER].
    /// Volatile: set once at wiring.
    private volatile OwnerServeGate ownerServeGate = ADMIT_OWNER;
    /// Why this node's owner promotion of a partition waits for an operator (#1555), read by the partition status
    /// view. Default: [#NO_BLOCK]. Volatile: set once at wiring.
    private volatile OwnerBlockSource ownerBlockSource = NO_BLOCK;

    private volatile StreamFootprint streamFootprint = StreamFootprint.NONE;

    /// Reshuffle-concurrency permits (#265 increment 5): [#reshuffleConcurrency] slots gating REPLICA
    /// materialize+backfill. Acquired in {@link #buildAndInstall} for a REPLICA partition, released when the
    /// partition reaches CAUGHT_UP / loses the role / is released. Fair=false (throughput over ordering; the
    /// queue provides the ordering). Replaced wholesale by [#reshuffleConcurrency(int)] at wiring time, which
    /// is why neither this nor the limit beside it is final.
    private volatile Semaphore reshuffleSlots = new Semaphore(RESHUFFLE_CONCURRENCY);
    /// See [#segmentTierPressure(SegmentTierPressure)].
    private volatile SegmentTierPressure segmentTierPressure = SegmentTierPressure.NONE;

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
    /// First reconcile tick at which each partition was seen held (role OWNER/REPLICA) but unmaterialized (#1805).
    /// DIAGNOSTIC only — it times the WARN; the re-drive itself is level-triggered over the live state
    /// ([#redriveHeldUnmaterialized]) and needs no memory of past refusals. The queues above are TRANSIENT work
    /// lists (a stale-purge at role NONE or a failed drain removes the entry) and every producer of a materialize
    /// attempt is edge-triggered (the registry-add edge behind `onBecameReplica`, an owner append), so a partition
    /// whose queue entry was lost used to stay held-but-not-materialized forever. The clock restarts when the role
    /// goes NONE; entries are removed when the ring exists or the stream is gone.
    private final Map<PartitionRef, Long> heldUnmaterializedSince = new ConcurrentHashMap<>();
    /// WARNs raised for partitions held-but-unmaterialized past [#HELD_UNMATERIALIZED_WARN_TICKS] since boot.
    private final AtomicLong heldUnmaterializedWarnings = new AtomicLong(0);

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
    /// Best-effort-stream events dropped by a frozen ring since boot (#1233 observability).
    private final AtomicLong droppedEventsSinceBoot = new AtomicLong(0);
    /// Owner publishes refused because a frozen ring dropped the event on a durable stream (#1233).
    private final AtomicLong refusedPublishDropsSinceBoot = new AtomicLong(0);
    /// Replicated appends refused because this replica's frozen ring dropped the event (#1233).
    private final AtomicLong refusedReplicaDropsSinceBoot = new AtomicLong(0);
    /// Replica partitions quarantined since boot because a divergent held entry was found (#1505 F2).
    private final AtomicLong quarantinedPartitionsSinceBoot = new AtomicLong(0);

    private StreamPartitionManager(long maxTotalBytes,
                                   EvictionListener evictionListener,
                                   ReplicationManager replicationManager,
                                   Option<ClusterNode<KVCommand<AetherKey>>> clusterNode,
                                   Option<OwnershipEpochHighWater> epochHighWater,
                                   StreamOwnerEpochSource ownerEpochSource,
                                   Option<AppendLog.Opener> logs,
                                   LastSealedOffsetSource lastSealedOffset,
                                   DurableSealedOffsetSource durableSealedOffset) {
        this.maxTotalBytes = maxTotalBytes;
        this.evictionListener = evictionListener;
        this.replicationManager = replicationManager;
        this.clusterNode = clusterNode;
        this.epochHighWater = epochHighWater;
        this.ownerEpochSource = ownerEpochSource;
        this.logs = logs;
        this.lastSealedOffset = lastSealedOffset;
        this.durableSealedOffset = durableSealedOffset;
        replicationManager.observeAcks(this::onReplicaAck);
    }

    /// See [#WAL_RECOVERY_HEADS_LOST].
    public static long walRecoveryHeadsLost() {
        return WAL_RECOVERY_HEADS_LOST.get();
    }

    public static StreamPartitionManager streamPartitionManager() {
        return new StreamPartitionManager(DEFAULT_MAX_TOTAL_BYTES,
                                          EvictionListener.NOOP,
                                          ReplicationManager.NONE,
                                          Option.none(),
                                          Option.none(),
                                          StreamOwnerEpochSource.zero(),
                                          Option.none(),
                                          LastSealedOffsetSource.none(),
                                          DurableSealedOffsetSource.none());
    }

    public static StreamPartitionManager streamPartitionManager(long maxTotalBytes) {
        return new StreamPartitionManager(maxTotalBytes,
                                          EvictionListener.NOOP,
                                          ReplicationManager.NONE,
                                          Option.none(),
                                          Option.none(),
                                          StreamOwnerEpochSource.zero(),
                                          Option.none(),
                                          LastSealedOffsetSource.none(),
                                          DurableSealedOffsetSource.none());
    }

    public static StreamPartitionManager streamPartitionManager(long maxTotalBytes, EvictionListener evictionListener) {
        return new StreamPartitionManager(maxTotalBytes,
                                          evictionListener,
                                          ReplicationManager.NONE,
                                          Option.none(),
                                          Option.none(),
                                          StreamOwnerEpochSource.zero(),
                                          Option.none(),
                                          LastSealedOffsetSource.none(),
                                          DurableSealedOffsetSource.none());
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
                                          LastSealedOffsetSource.none(),
                                          DurableSealedOffsetSource.none());
    }

    /// Test seam: a manager whose partition logs are opened (and inspected) through `logs`, so a test can hold an
    /// inspect at a chosen point.
    static StreamPartitionManager streamPartitionManagerOverLogs(long maxTotalBytes,
                                                                 Option<AppendLog.Opener> logs,
                                                                 LastSealedOffsetSource lastSealedOffset) {
        return new StreamPartitionManager(maxTotalBytes,
                                          EvictionListener.NOOP,
                                          ReplicationManager.NONE,
                                          Option.none(),
                                          Option.none(),
                                          StreamOwnerEpochSource.zero(),
                                          logs,
                                          lastSealedOffset,
                                          DurableSealedOffsetSource.same(lastSealedOffset));
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
                                          LastSealedOffsetSource.none(),
                                          DurableSealedOffsetSource.none());
    }

    /// Fence-enabled factory (#345 item 1d-ii): every local and replicated-receive append is
    /// owner-epoch-fenced against `epochHighWater` (the per-`(stream, partition)` domain high-water,
    /// CP-seeded and observe-advanced by 1d-i). Local publishes are stamped with this node's current
    /// owner epoch from `ownerEpochSource`; replicated batches carry the sending owner's epoch on the
    /// wire. The aether-level wiring supplies both, and — the only factory that demands it — the DURABLE
    /// sealed bound WAL truncation uses (#1345): `lastSealedOffset` seeds recovery from the live index,
    /// `durableSealedOffset` is what a restart would rebuild, and the two differ by exactly the refs not yet
    /// snapshotted. Passing the live index for both re-creates the renumbering this parameter exists to end.
    public static StreamPartitionManager streamPartitionManager(long maxTotalBytes,
                                                                EvictionListener evictionListener,
                                                                ReplicationManager replicationManager,
                                                                ClusterNode<KVCommand<AetherKey>> clusterNode,
                                                                OwnershipEpochHighWater epochHighWater,
                                                                StreamOwnerEpochSource ownerEpochSource,
                                                                Option<AppendLog.Opener> logs,
                                                                LastSealedOffsetSource lastSealedOffset,
                                                                DurableSealedOffsetSource durableSealedOffset) {
        return new StreamPartitionManager(maxTotalBytes,
                                          evictionListener,
                                          replicationManager,
                                          Option.some(clusterNode),
                                          Option.some(epochHighWater),
                                          ownerEpochSource,
                                          logs,
                                          lastSealedOffset,
                                          durableSealedOffset);
    }

    /// Test/standalone factory wiring a per-partition crash-durable WAL root (streaming-persistence
    /// W3/W6) with the no-op eviction / no-replication / no-cluster / fence-free defaults. When
    /// `walBaseDir` is [Option#none] this is byte-identical to {@link #streamPartitionManager(long)};
    /// when present every partition opens a [AppendLog] and an owner publish is fsync-gated. The
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
                                          walBaseDir.map(AppendLog.Opener::directory),
                                          lastSealedOffset,
                                          DurableSealedOffsetSource.same(lastSealedOffset));
    }

    /// Test/standalone factory wiring an eviction listener (the segment sealer) together with a per-partition
    /// WAL root and a last-sealed source, with the no-replication / no-cluster / fence-free defaults — the
    /// seal → sealed-watermark → WAL-truncation → recovery chain end to end without a cluster (#1234). The
    /// source is treated as durable: truncation runs off it directly.
    public static StreamPartitionManager streamPartitionManager(long maxTotalBytes,
                                                                EvictionListener evictionListener,
                                                                Option<Path> walBaseDir,
                                                                LastSealedOffsetSource lastSealedOffset) {
        return streamPartitionManager(maxTotalBytes,
                                      evictionListener,
                                      walBaseDir,
                                      lastSealedOffset,
                                      DurableSealedOffsetSource.same(lastSealedOffset));
    }

    /// As above, with the DURABLE bound for WAL truncation supplied separately (#1345) — the seal →
    /// snapshot-lag → truncation → crash → recovery chain, where the live index and the refs on disk differ.
    public static StreamPartitionManager streamPartitionManager(long maxTotalBytes,
                                                                EvictionListener evictionListener,
                                                                Option<Path> walBaseDir,
                                                                LastSealedOffsetSource lastSealedOffset,
                                                                DurableSealedOffsetSource durableSealedOffset) {
        return new StreamPartitionManager(maxTotalBytes,
                                          evictionListener,
                                          ReplicationManager.NONE,
                                          Option.none(),
                                          Option.none(),
                                          StreamOwnerEpochSource.zero(),
                                          walBaseDir.map(AppendLog.Opener::directory),
                                          lastSealedOffset,
                                          durableSealedOffset);
    }

    /// As above, with a [ReplicationManager] instead of the explicit durable bound — the seal → WAL →
    /// recovery chain behind a REAL confirmation-factor acknowledgement gate, which is the only wiring in which a
    /// restart's visibility watermark is observable (#1387). An RF=1 or no-replication setup cannot see it:
    /// [org.pragmatica.aether.stream.replication.ReplicationManager#replicatedThrough] answers
    /// `Long.MAX_VALUE` for `confirmationFactor <= 1`, so visible and durable coincide there.
    public static StreamPartitionManager streamPartitionManager(long maxTotalBytes,
                                                                EvictionListener evictionListener,
                                                                ReplicationManager replicationManager,
                                                                Option<Path> walBaseDir,
                                                                LastSealedOffsetSource lastSealedOffset) {
        return new StreamPartitionManager(maxTotalBytes,
                                          evictionListener,
                                          replicationManager,
                                          Option.none(),
                                          Option.none(),
                                          StreamOwnerEpochSource.zero(),
                                          walBaseDir.map(AppendLog.Opener::directory),
                                          lastSealedOffset,
                                          DurableSealedOffsetSource.same(lastSealedOffset));
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
                                          LastSealedOffsetSource.none(),
                                          DurableSealedOffsetSource.none());
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

    /// Committed-ownership admission for application appends (#1230). Reports the COMMITTED
    /// `StreamPartitionOwnershipValue.owner` of `(stream, partition)` when it names a node OTHER than self;
    /// [Option#none] when self is the committed owner or no ownership record is committed (the cold-start
    /// window, where the fence is inert and HRW routing alone picks the writer). Holding the partition ring
    /// authorizes reads and replication receipt, never an application write: the epoch fence cannot tell a
    /// live replica from the owner, because both stamp the same committed epoch. `AetherNode` binds it to the
    /// committed ownership record; the default admits every append.
    @FunctionalInterface
    public interface OwnerWriteAdmission {
        Option<NodeId> remoteCommittedOwner(String stream, int partition);
    }

    /// Owner promotion gate (#1555): whether this node may ACT as owner of `(stream, partition)` — admitted, or
    /// refused with a transient cause while promotion (fresh ownership view + catch-up) is pending.
    /// `AetherNode` binds [OwnerActivation#admit]; the default admits everything.
    @FunctionalInterface
    public interface OwnerServeGate {
        Result<Unit> admit(String stream, int partition);
    }

    /// Owner promotion block (#1555): the reason this node's promotion of `(stream, partition)` cannot complete
    /// without an operator, if any. `AetherNode` binds [OwnerActivation#blockOf]; the default reports none.
    @FunctionalInterface
    public interface OwnerBlockSource {
        Option<OwnerActivation.ActivationBlock> blockOf(String stream, int partition);
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

    /// Late-bind the committed-ownership write admission (#1230). `AetherNode` wires this to the committed
    /// `StreamPartitionOwnershipValue` (refuse iff it names a node other than self). Until then — and in
    /// Forge/unit/legacy managers — the default admits every append. Set once at wiring; read on every
    /// owner-path append.
    @Contract
    public void ownerWriteAdmission(OwnerWriteAdmission admission) {
        this.ownerWriteAdmission = admission;
    }

    /// Late-bind the owner promotion gate (#1555). Set once at wiring.
    @Contract
    public void ownerServeGate(OwnerServeGate gate) {
        this.ownerServeGate = gate;
    }

    /// Late-bind the owner of this node's per-stream durable refs (#1278 review): a destroyed stream drops its sealed
    /// segment refs and reclaimed-through floors through it, and a stream about to materialize finishes a destroy of
    /// its name that a crash interrupted. Default: [StreamFootprint#NONE] (no storage of its own).
    @Contract
    public void streamFootprint(StreamFootprint footprint) {
        this.streamFootprint = footprint;
    }

    /// Late-bind the owner promotion block source (#1555). Set once at wiring.
    @Contract
    public void ownerBlockSource(OwnerBlockSource source) {
        this.ownerBlockSource = source;
    }

    /// Why this node's owner promotion of `(streamName, partition)` waits for an operator (#1555), if it does.
    public Option<OwnerActivation.ActivationBlock> ownerActivationBlock(String streamName, int partition) {
        return ownerBlockSource.blockOf(streamName, partition);
    }

    /// Whether this node may currently serve `(streamName, partition)` as its owner (#1555): the reported
    /// `servedByOwner` is true only when placement names self AND this holds.
    public boolean mayServeAsOwner(String streamName, int partition) {
        return ownerServeGate.admit(streamName, partition)
                             .isSuccess();
    }

    /// A client-facing local read (#1555). On the placement OWNER it passes the owner promotion gate first, so a
    /// node that has not refreshed its ownership view and caught up never answers as owner from a stale or
    /// short ring; a replica reads its ring as before.
    public Result<List<OffHeapRingBuffer.RawEvent>> readServing(String streamName,
                                                                int partition,
                                                                long fromOffset,
                                                                int maxEvents) {
        return ownerRoleGate(streamName, partition).flatMap(_ -> readLocal(streamName, partition, fromOffset, maxEvents))
                            .flatMap(events -> servedIfVerified(streamName, partition, fromOffset, events));
    }

    /// Replace the committed-epoch source after construction (a test seam; production passes it to the factory).
    @Contract
    public void ownerEpochSource(StreamOwnerEpochSource source) {
        this.ownerEpochSource = source;
    }

    /// Late-bind where committed epochs began ([EpochStartSource]). Set once at wiring.
    @Contract
    public void epochStarts(EpochStartSource source) {
        this.epochStarts = source;
    }

    /// B5 (#1730 phase 2): a copy that is not the owner serves what it holds at and above the start of the committed epoch only
    /// once it has been compared with that epoch's owner. A demoted owner still holding its old tail, or a replica whose epoch
    /// advanced while it was away, would otherwise answer a consumer correctly diverged to the new epoch's start with the OLD
    /// records at those offsets, labelled with the new epoch. Below the start nothing is held back.
    private Result<List<OffHeapRingBuffer.RawEvent>> servedIfVerified(String streamName,
                                                                      int partition,
                                                                      long fromOffset,
                                                                      List<OffHeapRingBuffer.RawEvent> events) {
        return unverifiedFrom(streamName, partition).fold(() -> success(events),
                                                          start -> fromOffset >= start
                                                                   ? new StreamError.ReplicaNotVerified(streamName,
                                                                                                        partition,
                                                                                                        start).<List<OffHeapRingBuffer.RawEvent>> result()
                                                                   : success(events.stream()
                                                                                   .filter(event -> event.offset() < start)
                                                                                   .toList()));
    }

    /// The offset from which this node must neither serve nor acknowledge, or none when it may: it is not the owner, there is a
    /// committed epoch, and this copy holds records at or above that epoch's start that it has not compared with its owner.
    /// Evaluated lazily at the first call after the committed epoch advances: a copy whose head is still below the start then
    /// holds nothing from the old lineage at or above it, and stays trusted; a head that reached the start by then is doubted
    /// (conservative: its backfill compare clears it). The owner itself is never doubted here.
    public Option<Long> unverifiedFrom(String streamName, int partition) {
        var epoch = ownerEpochSource.currentOwnerEpoch(streamName, partition);

        if (epoch.equals(Epoch.ZERO) || placementRoleSupplier.roleFor(streamName, partition) == Role.OWNER) {
            return none();
        }

        var start = epochStarts.startOf(streamName, partition, epoch).or(0L);

        return trusted(streamName, partition, epoch, start)
               ? none()
               : some(start);
    }

    private boolean trusted(String streamName, int partition, Epoch epoch, long start) {
        var head = resolvePartitionBuffer(streamName, partition).map(OffHeapRingBuffer::headOffset).or(-1L);

        return epochTrust.compute(partitionKeyOf(streamName, partition),
                                  (_, known) -> known != null && known.epoch()
                                                                      .equals(epoch)
                                                ? known
                                                : new EpochTrust(epoch, head < start))
                         .verified();
    }

    /// Whether this node may acknowledge to the owner what it holds ([#unverifiedFrom]).
    public boolean replicaVerified(String streamName, int partition) {
        return unverifiedFrom(streamName, partition).isEmpty();
    }

    /// A backfill compared this copy with the committed owner of `epoch` (captured BEFORE the compare, so an epoch that advanced
    /// meanwhile is not verified by a compare against the earlier one).
    @Contract
    public void markVerifiedForEpoch(String streamName, int partition, Epoch epoch) {
        epochTrust.compute(partitionKeyOf(streamName, partition),
                           (_, known) -> known != null && known.epoch()
                                                               .isStrictlyAfter(epoch)
                                         ? known
                                         : new EpochTrust(epoch, true));
    }

    private Result<Unit> ownerRoleGate(String streamName, int partition) {
        return placementRoleSupplier.roleFor(streamName, partition) == Role.OWNER
               ? ownerServeGate.admit(streamName, partition)
               : Result.unitResult();
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

    /// Late-bind the durable partition flag (#1596). `AetherNode` wires the KV-backed one. Until then -- and in
    /// Forge/unit/legacy managers -- a provenance failure is fenced locally (quarantine, refused appends) and
    /// logged at ERROR, but not recorded as cluster state. Set once at wiring.
    @Contract
    public void partitionFlags(PartitionFlags flags) {
        this.partitionFlags = some(flags);
    }

    /// Late-bind the operator-warning sink (#1730 phase 2). `AetherNode` wires the cluster one; unit and Forge managers
    /// keep the log-only default. Set once at wiring.
    @Contract
    public void operatorWarnings(OperatorWarningSink sink) {
        this.operatorWarnings = sink;
        operatorWarningsBound = true;
        deferredReports.forEach(Runnable::run);
        deferredReports.clear();
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

    /// #1564: create a DECLARED stream — a `[streams.X]` resource, a durable topic (and its dead-letter stream)
    /// or a durable entity's log — whose factors its caller resolved from the declaration through
    /// [org.pragmatica.aether.slice.ReplicationDeclaration]. Identical to [#createStream(StreamConfig)], except that
    /// a stream whose committed factors differ is refused ([ReplicationFactorsError.ChangedOnLiveResource], R8): a
    /// live resource's replication policy is not changed in place, and keeping the old one silently would leave
    /// the operator believing the new one holds. "Committed" is the value this node has APPLIED from KV
    /// ([#appliedConfigs]), else a locally committed entry — so a node that never materialized the stream, or holds
    /// it uncommitted, refuses rather than publishing its own factors over the committed ones; equal factors
    /// proceed and re-publish the identical value. Not covered: two nodes declaring different factors for a stream
    /// neither has yet seen committed — the later `Put` wins in KV (no compare-and-set exists for non-leader
    /// writers). Both declarations come from one blueprint, so this needs two blueprint versions activating at once.
    /// The management and system create paths keep [#createStream(StreamConfig)]: they carry no declaration.
    public Result<Unit> createDeclaredStream(StreamConfig config) {
        return committedReplication(config.name()).map(committed -> config.replication()
                                                                          .sameAsCommitted("stream '" + config.name()
                                                                                          + "'",
                                                                                           committed)
                                                                          .map(_ -> unit()))
                                   .or(success(unit()))
                                   .flatMap(_ -> createStream(config, CommitMode.SYNC));
    }

    private Option<ReplicationFactors> committedReplication(String streamName) {
        return option(appliedConfigs.get(streamName)).orElse(() -> option(streams.get(streamName)).filter(StreamEntry::isCommitted)
                                                                         .map(StreamEntry::config))
                     .map(StreamConfig::replication);
    }

    private Result<Unit> createStream(StreamConfig config, CommitMode commitMode) {
        return option(streams.get(config.name())).fold(() -> createAbsentStream(config, commitMode),
                                                       existing -> ensureConfigCommitted(config, existing, commitMode));
    }

    /// #1278 (rulings B/C): a name whose committed life this node has applied is ADOPTED — hydrated from the committed
    /// config, never re-proposed. Only a name with no applied life (never created, or destroyed here) gets a fresh
    /// proposal, which always carries a newly minted incarnation.
    private Result<Unit> createAbsentStream(StreamConfig config, CommitMode commitMode) {
        return option(appliedConfigs.get(config.name())).fold(() -> createFreshStream(config, commitMode),
                                                              committed -> adoptCommittedLife(committed, commitMode));
    }

    /// A hydration that built no entry (native OOM, WAL refusal) has already been flagged; the caller may retry.
    private Result<Unit> adoptCommittedLife(StreamConfig committed, CommitMode commitMode) {
        return option(streams.compute(committed.name(),
                                      (_, existing) -> reconcileCommittedConfig(committed, existing))).toResult(new StreamError.StreamConfigNotYetVisible(committed.name()))
                     .flatMap(_ -> commitMode.alreadyCommitted());
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

    private Result<Unit> createFreshStream(StreamConfig requested, CommitMode commitMode) {
        var config = withIncarnation(requested);

        return checkReplicationMinimum(config).flatMap(_ -> checkRetentionCapacity(config))
                                      .flatMap(_ -> checkPartitionCaps(config))
                                      .flatMap(_ -> adoptIfUnclustered(config))
                                      .flatMap(_ -> materializeFreshStream(config, commitMode));
    }

    /// #1278 (ruling B): through a cluster a fresh create is only a PROPOSAL, so it adopts nothing — the footprint
    /// moves to a life when that life's committed config applies ([#commitOwnLife], [#hydrateEntry]). Without a
    /// cluster the create IS the commit.
    private Result<Unit> adoptIfUnclustered(StreamConfig config) {
        return clusterNode.isEmpty()
               ? adoptIncarnation(config)
               : success(unit());
    }

    /// #1564 engine backstop (R5): whichever path minted the config, its factors satisfy `1 <= CF <= RF`
    /// ([ReplicationFactors#replicationFactors]). "RF below 3 only when declared, with a loud warning" is a
    /// DECLARATION rule ([org.pragmatica.aether.slice.ReplicationDeclaration]) — the engine cannot know what was
    /// declared. Applied on the create path only; a config already committed is adopted as-is.
    private static Result<Unit> checkReplicationMinimum(StreamConfig config) {
        return ReplicationFactors.replicationFactors(config.replicationFactor(),
                                                     config.confirmationFactor())
                                 .map(_ -> unit())
                                 .mapError(cause -> new StreamError.ReplicationRefused(config.name(),
                                                                                       cause));
    }

    /// #1549: every retention bound is at least 1, and the count is one the ring can index — refused before
    /// anything is reserved, instead of throwing out of the ring build (division by zero, a negative
    /// allocation, or an overflowed allocation size).
    private static Result<Unit> checkRetentionCapacity(StreamConfig config) {
        var retention = config.retention();

        return checkRetentionBound(config.name(),
                                   "max-count",
                                   retention.maxCount()).flatMap(_ -> checkRetentionBound(config.name(),
                                                                                          "max-bytes",
                                                                                          retention.maxBytes()))
                                  .flatMap(_ -> checkRetentionBound(config.name(),
                                                                    "max-age",
                                                                    retention.maxAgeMs()))
                                  .flatMap(_ -> checkIndexable(config.name(),
                                                               retention.maxCount()));
    }

    private static Result<Unit> checkRetentionBound(String streamName, String bound, long value) {
        return value >= 1
               ? success(unit())
               : new StreamError.RetentionBoundInvalid(streamName, bound, value).result();
    }

    private static Result<Unit> checkIndexable(String streamName, long maxCount) {
        return maxCount <= OffHeapRingBuffer.MAX_CAPACITY
               ? success(unit())
               : new StreamError.RetentionCountUnindexable(streamName, maxCount, OffHeapRingBuffer.MAX_CAPACITY).result();
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
        var maxReplicas = Math.max(config.replicationFactor(), maxDeclaredReplicas());
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
                                      logs,
                                      lastSealedOffset,
                                      this::trimFailedAtOpen)
                          .onFailure(_ -> release(floorBytes))
                          .onSuccess(entry -> restoreVisible(config, entry))
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
        return publishStreamConfig(config.withIncarnation(entry.config().incarnation()),
                                   entry,
                                   commitMode).flatMap(_ -> commitMode.republished());
    }

    /// #1278 (ruling C): the destroyed life stops being this node's applied life at once, not when the asynchronous
    /// removal comes back, so a create that follows proposes a NEW life instead of adopting the one being removed.
    public Result<Unit> destroyStream(String streamName) {
        appliedConfigs.remove(streamName);

        return option(streams.remove(streamName)).toResult(new StreamError.StreamNotFound(streamName))
                     .flatMap(this::closeAndRelease)
                     .onSuccess(_ -> forgetQuarantine(streamName))
                     .onSuccess(_ -> publishStreamConfigRemoval(streamName));
    }

    /// A destroyed stream takes its partitions' quarantine with it (#1505 F2): a stream later created under the
    /// same name starts with fresh rings and must not inherit a divergence it never had.
    @Contract
    private void forgetQuarantine(String streamName) {
        divergedAt.keySet().removeIf(ref -> ref.streamName()
                                               .equals(streamName));
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
    ///
    /// #1547: the replication minimum is re-checked HERE, on every commit path. The fresh-create check alone
    /// was bypassable: a re-create of a materialized-but-uncommitted stream republishes the INCOMING config
    /// (`ensureConfigCommitted` → `republishExistingConfig`), so an RF=1 re-create after a failed RF=3 commit
    /// committed `replicas = 1`.
    private Result<Unit> publishStreamConfig(StreamConfig config, StreamEntry entry, CommitMode commitMode) {
        return checkReplicationMinimum(config).flatMap(_ -> clusterNode.fold(() -> latchCommitted(entry),
                                                                             node -> commitMode.publish(this,
                                                                                                        node,
                                                                                                        config,
                                                                                                        entry)));
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
                   .onFailure(cause -> log.debug("Failed to publish stream config for {}: {}",
                                                 config.name(),
                                                 cause.message()))
                   .mapError(_ -> StreamError.General.STREAM_CONFIG_COMMIT_FAILED)
                   .flatMap(_ -> proposalOutcome(config, entry));
    }

    /// #1278 (ruling B): the consensus round resolving does not mean THIS proposal's life committed — the KV applier
    /// refuses a second life over a committed one ([org.pragmatica.cluster.state.kvstore.IncarnationFenced]) without
    /// a notification. The entry is latched committed only when its committed config applies here
    /// ([#commitOwnLife]); a different applied life means another create won, which is a duplicate create.
    private Result<Unit> proposalOutcome(StreamConfig config, StreamEntry entry) {
        return option(appliedConfigs.get(config.name())).filter(applied -> applied.incarnation() != entry.config()
                                                                                                         .incarnation())
                     .fold(() -> success(unit()),
                           _ -> StreamError.General.STREAM_ALREADY_EXISTS.result());
    }

    /// Async sibling of {@link #applyPutCommand}: fire the idempotent config `Put` (the publish-path decoupling — Fix
    /// #2) and hand back its round. Nothing is latched here: the entry becomes committed when its committed config
    /// applies ([#commitOwnLife]); a transient failure is logged and the next publish re-proposes.
    private Promise<List<Object>> applyPutCommandAsync(ClusterNode<KVCommand<AetherKey>> node, StreamConfig config) {
        return node.<Object> apply(putCommand(config))
                   .onFailure(cause -> log.debug("Async stream config publish for {} not yet committed: {}",
                                                 config.name(),
                                                 cause.message()));
    }

    private static List<KVCommand<AetherKey>> putCommand(StreamConfig config) {
        var key = StreamConfigKey.streamConfigKey(config.name());
        var value = StreamConfigValue.streamConfigValue(config);

        return List.of(new KVCommand.Put<AetherKey, AetherValue>(key, value));
    }

    /// Publish-path commit (#1278 ruling F): fire the config `Put` and wait at most [#PUBLISH_COMMIT_WAIT] for it to
    /// commit and apply here, so the first publish to a new stream normally lands in its committed life. The outcome
    /// is not read: the accept gate ([#committedLife]) decides from the applied life, and a commit slower than the
    /// wait — a still-catching-up leader — leaves the publish refused retriably instead of stalling it.
    private Result<Unit> fireAsyncCommit(ClusterNode<KVCommand<AetherKey>> node,
                                         StreamConfig config,
                                         StreamEntry entry) {
        return applyPutCommandAsync(node, config).await(PUBLISH_COMMIT_WAIT)
                                   .fold(_ -> success(unit()),
                                         _ -> success(unit()));
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

        appliedConfigs.put(streamName, config);
        streams.compute(streamName, (_, existing) -> reconcileCommittedConfig(config, existing));
    }

    /// Reconcile a committed `StreamConfigKey` Put against the local map. Absent locally — hydrate the
    /// follower entry (unchanged materialization path; a native-OOM hydrate returns `null` so the
    /// [java.util.Map#compute] contract leaves no entry, exactly as the former `computeIfAbsent` did).
    /// Present — let a genuinely committed app/blueprint config become authoritative over a prior REST
    /// management default (see {@link #adoptIfMoreDurable}).
    @NullReturn
    private StreamEntry reconcileCommittedConfig(StreamConfig config, StreamEntry existing) {
        return option(existing).map(entry -> reconcileExisting(config, entry))
                     .or(() -> hydrateEntry(config));
    }

    /// #1278 (rulings A/B): the committed life is the only life. The local entry of THAT life becomes committed here
    /// (and only here); a local entry of another life was a losing proposal — under ruling A it never accepted a
    /// write — and is discarded for the committed one.
    @NullReturn
    private StreamEntry reconcileExisting(StreamConfig config, StreamEntry existing) {
        if (config.incarnation() != existing.config().incarnation()) {
            return replaceLosingLife(config, existing);
        }

        commitOwnLife(config, existing);

        return adoptIfMoreDurable(config, existing);
    }

    @Contract
    private void commitOwnLife(StreamConfig config, StreamEntry entry) {
        if (entry.isCommitted()) {
            return;
        }

        adoptIncarnation(config).onFailure(cause -> log.warn("Stream '{}' committed; reclaiming other lives of its name did"
                                                            + " not complete: {}",
                                                             config.name(),
                                                             cause.message()));
        entry.markCommitted();
    }

    @NullReturn
    private StreamEntry replaceLosingLife(StreamConfig committed, StreamEntry local) {
        if (local.isCommitted()) {
            // Not reachable through the life fence: a committed life is replaced only after its removal applied,
            // which removes the local entry first. Kept loud, and the WALs are kept for inspection.
            log.error("Stream '{}' replaces COMMITTED local incarnation {} with committed incarnation {} without a removal",
                      committed.name(),
                      local.config().incarnation(),
                      committed.incarnation());
            releaseEntry(local);
        } else {
            log.info("Stream '{}' adopts the committed incarnation {}; this node's proposal {} lost and held no writes",
                     committed.name(),
                     committed.incarnation(),
                     local.config().incarnation());
            releaseEntry(local);
            local.deleteWals();
        }

        return hydrateEntry(committed);
    }

    /// A committed config for an ALREADY-materialized stream. The publish auto-create path
    /// (`StreamRoutes.ensureStreamExists`) can win a race and materialize a management DEFAULT (the cluster's
    /// replication defaults, #1564) before this committed app/blueprint config's notification is applied here;
    /// when the incoming config carries STRICTLY STRONGER durability (a higher replication factor or a higher
    /// confirmation factor) AND the same partition count, its replication factors are adopted onto the SAME
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
        log.info("Adopting committed config for stream '{}' over prior local default: replication_factor {}->{}, confirmation_factor {}->{}",
                 config.name(),
                 existing.config().replicationFactor(),
                 config.replicationFactor(),
                 existing.config().confirmationFactor(),
                 config.confirmationFactor());

        return existing.withConfig(config);
    }

    private static boolean strongerDurability(StreamConfig incoming, StreamConfig existing) {
        return incoming.replicationFactor() > existing.replicationFactor() || incoming.confirmationFactor() > existing.confirmationFactor();
    }

    @Contract
    @MessageReceiver
    public void onStreamConfigRemove(ValueRemove<StreamConfigKey, StreamConfigValue> remove) {
        var streamName = remove.cause().key().streamName();

        appliedConfigs.remove(streamName);
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
        adoptIncarnation(config).onFailure(cause -> log.warn("Stream '{}' hydrates; reclaiming other lives of its name did"
                                                            + " not complete: {}",
                                                             config.name(),
                                                             cause.message()));
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
        // allocate the memory, so there is no committed partition to diverge from. The same path takes
        // a WAL open or recovery refusal, and a failed trim at open (#1638 F6), which also leave the whole
        // stream unmaterialized on this node; the trim failure is flagged durably first. markCommitted on
        // success keeps the follower's later createStream short-circuiting (no re-publish).
        return StreamEntry.fromConfig(config,
                                      evictionListener,
                                      bytes -> reserveForGrowth(config, bytes),
                                      this::release,
                                      partition -> shouldMaterialize(config.name(),
                                                                     partition),
                                      logs,
                                      lastSealedOffset,
                                      this::trimFailedAtOpen)
                          .onSuccess(entry -> restoreVisible(config, entry))
                          .onSuccess(StreamEntry::markCommitted)
                          .onFailure(cause -> hydrationFailed(config, floorBytes, cause))
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
    /// The cause is the ring build's own — an off-heap allocation, a WAL open, a refused WAL recovery
    /// (#1345: `WalReplayMismatch`) or a failed trim at open (#1638 F6, flagged at [#trimFailedAtOpen]) — so it is
    /// carried, not narrated as an allocation failure.
    private void hydrationFailed(StreamConfig config, long floorBytes, Cause cause) {
        release(floorBytes);
        log.warn("Follower could not materialize committed stream '{}' — entry not created: {}",
                 config.name(),
                 cause.message());
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
    /// epoch) propagates UNCHANGED — no lazy create, no retry. `minAcks` is the #1236 replica floor, checked
    /// through {@link #publishLocalAtFloor} — AFTER the owner admission, so a forward that lands on a
    /// non-owner is answered with the retryable [StreamError.NotOwnerAppend], never with a
    /// `NOT_ENOUGH_REPLICAS` about replication this node does not own. The caller reads `minAcks` from the
    /// stream it knows; on the lazy-materialization path that stream was unknown (a confirmation factor of 0), so the
    /// retry takes its floor from the committed config it materializes from (#1290 review M1).
    ///
    /// The owner re-checks the stream's consistency ({@link #ensureWritableConsistency}, #1262) on BOTH
    /// attempts — the first append and the post-materialize retry — rather than trusting the forwarder to
    /// have refused: a STRONG or UNKNOWN stream is never appended here as EVENTUAL.
    public Result<Long> publishForwarded(String streamName,
                                         int partition,
                                         byte[] payload,
                                         long timestamp,
                                         int minAcks) {
        return writableAppend(streamName, partition, payload, timestamp, minAcks).fold(cause -> recoverForwardedPublish(cause,
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

    /// The retry's replica floor is the committed config's own `CF - 1`: the caller's floor was read
    /// before the stream existed here and is therefore always 0 on this path.
    private Result<Long> materializeThenPublish(StreamConfig config,
                                                String streamName,
                                                int partition,
                                                byte[] payload,
                                                long timestamp) {
        return ensureStreamMaterialized(config).flatMap(_ -> writableAppend(streamName,
                                                                            partition,
                                                                            payload,
                                                                            timestamp,
                                                                            config.confirmationFactor() - 1));
    }

    private Result<Long> writableAppend(String streamName, int partition, byte[] payload, long timestamp, int minAcks) {
        return ensureWritableConsistency(streamName).flatMap(_ -> publishLocalAtFloor(streamName,
                                                                                      partition,
                                                                                      payload,
                                                                                      timestamp,
                                                                                      minAcks));
    }

    public Result<Long> publishLocal(String streamName, int partition, byte[] payload, long timestamp) {
        return publishLocal(streamName,
                            partition,
                            payload,
                            timestamp,
                            ownerEpochSource.currentOwnerEpoch(streamName, partition));
    }

    /// Owner-local publish that also checks the replica floor (#1236) — `minAcks` in-sync peers must exist
    /// BEFORE the append, so a `NOT_ENOUGH_REPLICAS` refusal leaves nothing in the ring or the WAL. The
    /// floor runs AFTER the epoch fence and the owner admission (#1230/#1236 composition): the in-sync
    /// floor is the OWNER's replication state, so a non-owner is redirected ([StreamError.NotOwnerAppend])
    /// rather than answering `NOT_ENOUGH_REPLICAS` for a partition whose replication it does not own.
    public Result<Long> publishLocalAtFloor(String streamName,
                                            int partition,
                                            byte[] payload,
                                            long timestamp,
                                            int minAcks) {
        return publishLocal(streamName,
                            partition,
                            payload,
                            timestamp,
                            ownerEpochSource.currentOwnerEpoch(streamName, partition),
                            minAcks);
    }

    /// Owner-local publish stamped with an explicit `ownerEpoch` fencing token (#345 item 1d-ii). The
    /// append is fenced against the partition high-water before `buffer.append`; on accept the event is
    /// replicated to the registered replica set carrying the SAME `ownerEpoch` so every replica fences
    /// the deposed owner identically. The no-epoch overload above stamps the node's current owner epoch
    /// from the injected [StreamOwnerEpochSource] (floor [Epoch#ZERO] when unowned/non-fenced).
    ///
    /// Crash durability (streaming-persistence W3): when a per-partition WAL is configured the event's
    /// frame is written to that partition's [AppendLog] and the publish does NOT resolve as success
    /// until the frame is fsync-durable. A crash before the fsync loses the event AND fails the publish
    /// (the caller was never acked, so it retries) — only WAL-durable events ack. With no WAL configured
    /// this is a no-op gate and behavior is exactly as before.
    ///
    /// Ordering (#1231/#1232): offset assignment, the WAL frame write and the replication send run in the
    /// partition's ordered append section ([OffHeapRingBuffer#appendOrdered]), so concurrent publishers
    /// get distinct contiguous offsets, the WAL file is in offset order (recovery places records by it) and
    /// replicas receive events in offset order (their `fromOffset` check rejects anything else). Only the
    /// group-commit fsync is awaited after the section is released, so concurrent publishers still share
    /// fsyncs. A publish whose fsync then fails has already been replicated.
    ///
    /// Visibility (#1235): the appended event is NOT readable by consumers and wakes no push listener
    /// until it is durable here AND acknowledged by `confirmationFactor - 1` distinct peers ([#refreshVisible]).
    /// A failed frame write or fsync therefore never exposes the event, even when a peer acks it later.
    ///
    /// Owner admission (#1230): refused with [StreamError.NotOwnerAppend] when the committed owner of
    /// `(streamName, partition)` is another node — see [OwnerWriteAdmission]. It runs AFTER the epoch fence,
    /// so a deposed writer presenting a stale epoch is told it is deposed ([StreamError.StaleEpochAppend],
    /// permanent) rather than being redirected (`NotOwnerAppend`, transient); a current-epoch append from a
    /// live non-owner passes the fence and is refused here.
    public Result<Long> publishLocal(String streamName,
                                     int partition,
                                     byte[] payload,
                                     long timestamp,
                                     Epoch ownerEpoch) {
        return publishLocal(streamName, partition, payload, timestamp, ownerEpoch, NO_REPLICA_FLOOR);
    }

    private Result<Long> publishLocal(String streamName,
                                      int partition,
                                      byte[] payload,
                                      long timestamp,
                                      Epoch ownerEpoch,
                                      int minAcks) {
        return resolveWritableEntry(streamName).flatMap(entry -> publishInSection(entry,
                                                                                  streamName,
                                                                                  partition,
                                                                                  payload,
                                                                                  timestamp,
                                                                                  ownerEpoch,
                                                                                  admitOwnerWrite(streamName,
                                                                                                  partition,
                                                                                                  minAcks).flatMap(_ -> provenanceAdmits(streamName,
                                                                                                                                         partition,
                                                                                                                                         some(ownerEpoch)))))
                                   .flatMap(this::awaitDurable)
                                   .onSuccess(offset -> ownerDurable(streamName, partition, offset))
                                   .fold(cause -> handleDrop(cause, streamName, partition),
                                         Result::success);
    }

    /// The owner's append at `offset` is durable — its group commit resolved, and group commit resolves in
    /// offset order, so the whole prefix is. Visibility is recomputed BEFORE the publish returns, so an
    /// owner-only (`confirmationFactor <= 1`) publisher can read its own write. A ring released in the
    /// meantime has no reader left to expose the event to, so its absence is ignored.
    @Contract
    private void ownerDurable(String streamName, int partition, long offset) {
        resolvePartitionBuffer(streamName, partition).onSuccess(ring -> ownerDurable(ring, streamName, partition, offset));
    }

    @Contract
    private void ownerDurable(OffHeapRingBuffer ring, String streamName, int partition, long offset) {
        ring.markDurable(offset);
        refreshVisible(ring, streamName, partition);
    }

    /// Owner-side ack observer (#1235). The replication manager runs it BEFORE it records the ack in the
    /// registry, so no waiter can be resolved — by an await or a registry read — before the event is
    /// visible. It runs a second time after the registry update; see [#refreshVisible] for why.
    @Contract
    private void onReplicaAck(ReplicationMessage.ReplicateAck ack) {
        resolvePartitionBuffer(ack.streamName(), ack.partition()).onSuccess(ring -> ackedVisible(ring, ack));
    }

    /// Reads the ack through the overlay, because this observer runs BEFORE the registry records it.
    @Contract
    private void ackedVisible(OffHeapRingBuffer ring, ReplicationMessage.ReplicateAck ack) {
        ring.advanceVisible(Math.min(ring.durableOffset(),
                                     replicationManager.replicatedThrough(ack,
                                                                          confirmationFactorFor(ack.streamName()) - 1)));
    }

    /// A rebuilt ring's replayed WAL tail is durable and not visible ([StreamEntry#placeRecord], #1387); this
    /// restores `visible` to what the ack state supports, before the ring can be read. Nothing about acks
    /// is persisted — the production [ReplicaRegistry] writes through `WatermarkStore.NOOP` — so on an
    /// OWNER every peer is blind after a restart and the tail stays at the seed (the sealed bound) until a
    /// live ack covers it, exactly its state before the restart; a stream with no peer barrier
    /// (`confirmationFactor <= 1`) sees the whole tail at once, as before. A REPLICA's visible position is its
    /// OWN durability (#1235 replica side), so its tail is visible at once. `NONE` — the role unresolved —
    /// takes the owner rule: an unresolved role must not expose more than the owner would. The confirmation
    /// count is read from `config`, not [#confirmationFactorFor]: on a fresh create the entry is not in
    /// `streams` yet, and the lookup would report `0` — no barrier — and expose the tail.
    @Contract
    private void restoreVisible(StreamConfig config, int partition, OffHeapRingBuffer ring) {
        switch (placementRoleSupplier.roleFor(config.name(), partition)) {
            case REPLICA -> holdRecoveredTailUntilVerified(config.name(), partition, ring);
            case OWNER, NONE -> ring.advanceVisible(Math.min(ring.durableOffset(),
                                                             replicationManager.replicatedThrough(config.name(),
                                                                                                  partition,
                                                                                                  config.confirmationFactor() - 1)));
        }
    }

    /// A REPLICA that recovered a tail keeps it invisible until [#markVerified] (#1730 phase 2): before, "its OWN
    /// durability" made the whole recovered tail visible at once, including the unacknowledged records of an ex-owner.
    /// A copy that recovered nothing has nothing to verify.
    @Contract
    private void holdRecoveredTailUntilVerified(String streamName, int partition, OffHeapRingBuffer ring) {
        if (ring.durableOffset() >= 0) {
            unverifiedReplicas.add(partitionKeyOf(streamName, partition));
        }
    }

    /// This copy has been compared with its committed owner through `offset` (#1730 phase 2): that prefix, as far as it
    /// is durable here, becomes visible. When `offset` reaches the head everything held is verified and later appends
    /// extend visibility as they always did; below the head the copy stays unverified and its own appends do not
    /// extend it.
    @Contract
    public void markVerified(String streamName, int partition, long offset) {
        resolvePartitionBuffer(streamName, partition).onSuccess(ring -> exposeVerified(streamName,
                                                                                       partition,
                                                                                       ring,
                                                                                       offset));
    }

    private void exposeVerified(String streamName, int partition, OffHeapRingBuffer ring, long offset) {
        ring.advanceVisible(Math.min(offset, ring.durableOffset()));
        if (offset >= ring.headOffset()) {
            unverifiedReplicas.remove(partitionKeyOf(streamName, partition));
        }
    }

    @Contract
    private void restoreVisible(StreamConfig config, StreamEntry entry) {
        entry.materialized()
             .forEach((partition, materialized) -> {
                          restoreVisible(config,
                                         partition,
                                         materialized.ring());
                          reportInterruptedRepair(config.name(),
                                                  partition,
                                                  materialized.wal());
                      });
    }

    /// visible = min(durable, the highest offset `confirmationFactor - 1` distinct peers have acknowledged).
    /// Both inputs cover a contiguous prefix — group commit resolves in offset order, and a replica acks
    /// only its verified contiguous run (#260) — so the minimum is a prefix too. No advance is lost to a
    /// race between the fsync path and the ack path. The fsync path writes `durable` and then reads the
    /// registry. The ack's second observer call runs after the registry write and then reads `durable`.
    /// Both are volatile accesses, so at least one of the two sees both inputs.
    @Contract
    private void refreshVisible(OffHeapRingBuffer ring, String streamName, int partition) {
        ring.advanceVisible(Math.min(ring.durableOffset(), peerAcknowledgedThrough(streamName, partition)));
    }

    private long peerAcknowledgedThrough(String streamName, int partition) {
        return replicationManager.replicatedThrough(streamName, partition, confirmationFactorFor(streamName) - 1);
    }

    /// Frozen-ring drop handling (#1233). The ring reports [StreamError.General#EVENT_DROPPED] BEFORE the
    /// in-section [#logAndReplicate] callback runs, so a dropped event is never WAL-written or replicated. The drop
    /// FAILS the publish for any stream with durability semantics: `confirmationFactor >= 2`, a partition
    /// WAL, an entity keyspace log (`entity:`), or a durable-topic / DLQ stream (`topic:`) — the last two by
    /// name, so an RF=1 entity keyspace without a WAL can never ack a lost write. The refusal is counted in
    /// [#refusedPublishDropsSinceBoot] and logged. `StreamConfig` carries no explicit best-effort flag, so
    /// only the remaining app streams are best-effort: the drop is absorbed as FER (degrade forward — the
    /// event is lost, the stream keeps accepting), made observable by [#droppedEventsSinceBoot] and a WARN,
    /// and acked at the unchanged ring head, exactly the offset such a stream reported before this change.
    /// Every other failure propagates unchanged.
    private Result<Long> handleDrop(Cause cause, String streamName, int partition) {
        if (cause != StreamError.General.EVENT_DROPPED) {
            return cause.result();
        }

        return isBestEffort(streamName, partition)
               ? recordBestEffortDrop(streamName, partition)
               : refuseDurableDrop(cause, streamName, partition);
    }

    private boolean isBestEffort(String streamName, int partition) {
        return ! isDurableByName(streamName)
               && confirmationFactorFor(streamName) < 2
               && walFor(streamName, partition).isEmpty();
    }

    private static boolean isDurableByName(String streamName) {
        return streamName.startsWith(ENTITY_STREAM_PREFIX) || DurableTopicNames.isTopicStream(streamName);
    }

    private Result<Long> recordBestEffortDrop(String streamName, int partition) {
        var dropped = droppedEventsSinceBoot.incrementAndGet();

        log.warn("Dropped event on best-effort stream '{}' partition {}: larger than the frozen ring's allocation ({} dropped since boot)",
                 streamName,
                 partition,
                 dropped);

        return resolvePartitionBuffer(streamName, partition).map(OffHeapRingBuffer::headOffset);
    }

    private Result<Long> refuseDurableDrop(Cause cause, String streamName, int partition) {
        var refused = refusedPublishDropsSinceBoot.incrementAndGet();

        log.warn("Refused publish on durable stream '{}' partition {}: event larger than the frozen ring's allocation; the ring cannot grow until it is rebuilt with pool budget ({} refused since boot)",
                 streamName,
                 partition,
                 refused);

        return cause.result();
    }

    /// Events dropped since boot on best-effort streams because a frozen ring could not fit them (#1233).
    /// Drops on streams with durability semantics are not counted here — they fail the publish instead.
    public long droppedEventsSinceBoot() {
        return droppedEventsSinceBoot.get();
    }

    /// Owner publishes on durable streams refused since boot because a frozen ring could not fit the event
    /// (#1233) — the failing-class counterpart of [#droppedEventsSinceBoot].
    public long refusedPublishDropsSinceBoot() {
        return refusedPublishDropsSinceBoot.get();
    }

    /// Replicated appends refused since boot because this replica's frozen ring could not fit the event
    /// (#1233). Each one stalls the partition on this replica: the receive handler stops the batch there,
    /// backfill hits the same refusal and keeps the replica SYNCING, and an owner whose confirmation barrier
    /// needs this replica times out its publishes — until the ring is rebuilt with pool budget.
    public long refusedReplicaDropsSinceBoot() {
        return refusedReplicaDropsSinceBoot.get();
    }

    /// Replica partitions quarantined since boot (#1505 F2): each one held an entry that differs from the event its
    /// sender offered for that offset. A quarantined partition acks nothing at or past the divergent offset and is
    /// never promoted CAUGHT_UP on this node (see [#quarantinedAt]).
    public long quarantinedPartitionsSinceBoot() {
        return quarantinedPartitionsSinceBoot.get();
    }

    /// The lowest offset at which `(streamName, partition)` holds a divergent entry on this replica (#1505 F2), or
    /// [Option#none] when the partition is not quarantined.
    public Option<Long> quarantinedAt(String streamName, int partition) {
        return option(divergedAt.get(new PartitionRef(streamName, partition)));
    }

    /// This manager's quarantine record as the backfill orchestrator consumes it (#1505 F2/R3). Its promotion guard
    /// runs under [#quarantineLock], the same lock that records a divergence.
    public QuarantineView quarantineView() {
        return new ManagerQuarantineView();
    }

    /// What a repair of a quarantined replica removed: everything above `keptThrough`, which is `removed` events
    /// spanning `[firstRemoved, lastRemoved]`.
    public record TailCut(long keptThrough, long removed, long firstRemoved, long lastRemoved, Option<Epoch> epoch) {
        /// This cut and a LATER one of the same repair (a window step further back): one range `[firstRemoved, lastRemoved]`.
        TailCut then(TailCut next) {
            return new TailCut(Math.min(keptThrough, next.keptThrough),
                               removed + next.removed,
                               Math.min(firstRemoved, next.firstRemoved),
                               Math.max(lastRemoved, next.lastRemoved),
                               epoch.orElse(() -> next.epoch));
        }
    }

    /// Repairs a quarantined REPLICA copy by cutting its tail back to the last offset it shares with its sender
    /// (#1730 phase 2, KIP-101): the divergent entry sits at `quarantinedAt`, so every offset below it is kept and
    /// everything from it up is removed from the WAL, then the epoch history, then the ring, inside the ring's ordered
    /// section so no replicated append lands between. The quarantine is lifted in the same section (only if it is still
    /// the one this repair read, so a divergence recorded meanwhile at a lower offset is kept; a quarantine is always at
    /// an offset the ring holds, so a repair always removes at least that one). Nothing is quarantined: `none`. The CALLER decides that the sender is the committed owner of a later epoch and that this node is not
    /// the owner; this method never does.
    ///
    /// Refused, leaving the partition quarantined, when the cut lies below the ring's retained range
    /// ([StreamError.TruncateBelowRetained]): those offsets were evicted and possibly sealed, so the ring cannot
    /// vouch that they are gone.
    @Contract
    public Result<Option<TailCut>> repairDivergence(String streamName,
                                                    int partition,
                                                    QuarantineView.RepairAuthority authority) {
        var ref = new PartitionRef(streamName, partition);

        return quarantinedAt(streamName, partition).fold(() -> success(none()),
                                                         divergedAtOffset -> cutDivergentTail(streamName,
                                                                                              partition,
                                                                                              ref,
                                                                                              divergedAtOffset,
                                                                                              authority).map(Option::some));
    }

    private Result<TailCut> cutDivergentTail(String streamName,
                                             int partition,
                                             PartitionRef ref,
                                             long divergedAtOffset,
                                             QuarantineView.RepairAuthority authority) {
        var keep = divergedAtOffset - 1;

        return requireVouchingHistory(streamName, partition, divergedAtOffset).flatMap(_ -> requireAboveSealedFloor(streamName,
                                                                                                                    partition,
                                                                                                                    keep))
                                     .flatMap(_ -> resolvePartitionBuffer(streamName, partition))
                                     .flatMap(ring -> cutRing(streamName,
                                                              partition,
                                                              ref,
                                                              ring,
                                                              divergedAtOffset,
                                                              keep,
                                                              authority))
                                     .onSuccess(cut -> reportCut(streamName, partition, cut))
                                     .onFailure(cause -> log.warn("Replica {}[{}] could not cut its divergent tail back to offset {}: {}",
                                                                  streamName,
                                                                  partition,
                                                                  keep,
                                                                  cause.message()));
    }

    /// A divergence found by comparing owner-epoch provenance is established only when THIS copy's history can vouch for
    /// its records: a copy that holds records and no history (or a history that does not start at its base) compares
    /// unequal with every owner at offset 0, which is the absence of evidence, not a lineage split. Such a copy is not
    /// cut (it keeps the quarantine and the durable flag it always had); a copy without a WAL has no provenance and is
    /// judged by the records alone.
    private Result<Unit> requireVouchingHistory(String streamName, int partition, long divergedAtOffset) {
        return walFor(streamName, partition).map(_ -> provenanceOf(streamName, partition).flatMap(local -> vouches(local,
                                                                                                                   streamName,
                                                                                                                   partition,
                                                                                                                   divergedAtOffset)))
                     .or(Result.unitResult());
    }

    private static Result<Unit> vouches(LogProvenance local, String streamName, int partition, long divergedAtOffset) {
        return ProvenanceComparison.incompleteness(local).isPresent()
               ? new StreamError.DivergenceNotEstablished(streamName, partition, divergedAtOffset).<Unit> result()
               : Result.unitResult();
    }

    /// Offsets at or below the last sealed offset were durably sealed into segments: what this copy holds there is not a tail
    /// it may discard, so a divergence at or below the sealed floor is refused (the quarantine and the flag stand).
    private Result<Unit> requireAboveSealedFloor(String streamName, int partition, long keep) {
        var floor = lastSealedOffset.lastSealedOffset(streamName, partition);

        return keep < floor
               ? new StreamError.TruncateBelowRetained(streamName, partition, keep, floor + 1L).<Unit> result()
               : Result.unitResult();
    }

    private Result<TailCut> cutRing(String streamName,
                                    int partition,
                                    PartitionRef ref,
                                    OffHeapRingBuffer ring,
                                    long divergedAtOffset,
                                    long keep,
                                    QuarantineView.RepairAuthority authority) {
        var head = ring.headOffset();
        var wal = walFor(streamName, partition);
        var epoch = divergentEpoch(streamName, partition, divergedAtOffset);

        return ring.truncateSuffix(keep,
                                   () -> authorised(streamName, partition, divergedAtOffset, authority).flatMap(_ -> wal.map(appendLog -> appendLog.truncateSuffix(keep))
                                                                                                                        .or(Result.unitResult())),
                                   _ -> forgetCutState(streamName, partition, ref, divergedAtOffset, keep))
                   .map(removed -> new TailCut(keep, removed, keep + 1, head, epoch));
    }

    /// Evaluated inside the cut's ordered section, immediately before the first thing it removes: the committed owner that
    /// authorises the cut must still be the committed owner of a later epoch than the records about to go.
    private Result<Unit> authorised(String streamName,
                                    int partition,
                                    long divergedAtOffset,
                                    QuarantineView.RepairAuthority authority) {
        return authority.holds(divergentEpoch(streamName, partition, divergedAtOffset))
               ? Result.unitResult()
               : new StreamError.RepairNotAuthorized(streamName, partition, divergedAtOffset).<Unit> result();
    }

    /// Runs inside the ring's ordered section, after the ring shrank: the quarantine this repair read is lifted, and
    /// the replicated write the durability barrier remembers is forgotten when it was above the cut -- a barrier that
    /// still pointed at it would expose, as durable and visible, offsets the cut removed.
    @Contract
    private void forgetCutState(String streamName, int partition, PartitionRef ref, long divergedAtOffset, long keep) {
        synchronized (quarantineLock) {
            divergedAt.remove(ref, divergedAtOffset);
            provenanceMismatchAt.remove(ref);
        }
        // What remains is the prefix this copy shares with its sender: verified by construction.
        unverifiedReplicas.remove(partitionKeyOf(streamName, partition));
        lastReplicatedWalWrite.computeIfPresent(partitionKeyOf(streamName, partition),
                                                (_, write) -> write.offset() > keep
                                                              ? null
                                                              : write);
    }

    /// A cut is routine for a stream that confirms with replicas (what it removed was never acknowledged: an
    /// acknowledgement needs every in-sync member, and the committed owner is one). With `confirmation_factor` 1 the
    /// acknowledgement was the old owner's alone, so the removed records may have been acknowledged and are lost: that
    /// is the stated acks=1 window, and the operator is told exactly which offsets.
    @Contract
    private void reportCut(String streamName, int partition, TailCut cut) {
        if (cut.removed() > 0) {
            var ref = new PartitionRef(streamName, partition);
            var first = !pendingCuts.containsKey(ref);
            var combined = pendingCuts.merge(ref, cut, TailCut::then);

            if (first) {
                scheduleUnsettledReport(streamName, partition);
            }

            walFor(streamName, partition).onPresent(wal -> persistPendingCut(wal, combined));
        }
    }

    /// The cut is durable at once, so what it discarded is recorded durably too until the repair settles: a copy that restarts in
    /// between (the cut is in its WAL, the data is gone, and nothing is left to cut again) would otherwise never tell the
    /// operator what it lost. The record is one small file beside the WAL, removed when the report is made.
    @Contract
    private void persistPendingCut(AppendLog wal, TailCut cut) {
        var epoch = cut.epoch().map(e -> e.incarnation() + " " + e.rabiaTerm() + " " + e.localCounter()).or("-");

        try {
            Files.writeString(pendingCutFile(wal),
                              cut.keptThrough()
                             + " " + cut.removed()
                             + " " + cut.firstRemoved()
                             + " " + cut.lastRemoved()
                             + " " + epoch,
                              StandardOpenOption.CREATE,
                              StandardOpenOption.TRUNCATE_EXISTING,
                              StandardOpenOption.WRITE,
                              StandardOpenOption.SYNC);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not record the pending truncation report beside {}: {}", wal.path(), e.getMessage());
        }
    }

    private static Path pendingCutFile(AppendLog wal) {
        return wal.path()
                  .resolveSibling(wal.path().getFileName() + ".pending-cut");
    }

    /// A partition reopened with a pending truncation record: the repair that made the cut never settled (the process died first).
    /// What it discarded is reported now, once, and the record removed.
    @Contract
    private void reportInterruptedRepair(String streamName, int partition, Option<AppendLog> wal) {
        wal.onPresent(appendLog -> readPendingCut(pendingCutFile(appendLog)).onPresent(cut -> {
            Runnable report = () -> {
                reportCut(streamName, partition, cut, confirmationFactorFor(streamName), false);
                deletePendingCut(pendingCutFile(appendLog));
            };

            if (operatorWarningsBound) {
                report.run();
            } else {
                deferredReports.add(report);
            }
        }));
    }

    private static Option<TailCut> readPendingCut(Path file) {
        try {
            var parts = Files.readString(file).trim().split(" ");
            var epoch = parts.length >= 7
                        ? Option.some(Epoch.epoch(Long.parseLong(parts[4]),
                                                  Long.parseLong(parts[5]),
                                                  Long.parseLong(parts[6])))
                        : Option.<Epoch> none();

            return Option.some(new TailCut(Long.parseLong(parts[0]),
                                           Long.parseLong(parts[1]),
                                           Long.parseLong(parts[2]),
                                           Long.parseLong(parts[3]),
                                           epoch));
        } catch (IOException | RuntimeException e) {
            return Option.none();
        }
    }

    @Contract
    private static void deletePendingCut(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not remove the pending truncation record {}: {}", file, e.getMessage());
        }
    }

    /// The repair of `(streamName, partition)` is over: ONE report for everything it discarded, however many window steps it
    /// took (a divergence older than the compared window is cut back one window per run), carrying the final range
    /// `[cut + 1, localHead]`, the epoch of the discarded records when the copy's history names it, and that the writer's
    /// acknowledgement was the old owner's alone (`confirmation_factor` 1). With a factor of 2 or more nothing was acknowledged
    /// by the old owner alone and it is only logged.
    @Contract
    private void settleRepair(String streamName, int partition) {
        var ref = new PartitionRef(streamName, partition);

        option(pendingCuts.remove(ref)).onPresent(cut -> {
            var earlier = reportedUnsettled.remove(ref);

            if (earlier == null || cut.removed() > earlier.removed()) {
                reportCut(streamName, partition, cut, confirmationFactorFor(streamName), true);
            }

            walFor(streamName, partition).onPresent(wal -> deletePendingCut(pendingCutFile(wal)));
        });
    }

    /// The bound after which a durable cut whose repair has not settled is reported anyway, with the range known so far and
    /// `repairSettled=false`: the loss is real from the moment the cut is durable, and a repair that stalls (the owner unreachable,
    /// a window step refused) must not hide it. Late-bound; `AetherNode` derives it from the backfill redrive interval
    /// (`STREAM_BACKFILL_REDRIVE_INTERVAL`, 5 s) times 12, i.e. twelve redrive ticks, which is room for a stepped-back repair of
    /// several 1,024-record windows. None (the default for unit managers) reports only on settle or reopen.
    @Contract
    public void repairReportBound(TimeSpan bound) {
        this.repairReportBound = Option.some(bound);
    }

    @Contract
    private void scheduleUnsettledReport(String streamName, int partition) {
        repairReportBound.onPresent(bound -> SharedScheduler.schedule(() -> reportUnsettled(streamName, partition), bound));
    }

    @Contract
    private void reportUnsettled(String streamName, int partition) {
        var ref = new PartitionRef(streamName, partition);

        option(pendingCuts.get(ref)).filter(_ -> !reportedUnsettled.containsKey(ref)).onPresent(cut -> {
            reportedUnsettled.put(ref, cut);
            reportCut(streamName, partition, cut, confirmationFactorFor(streamName), false);
        });
    }

    private void reportCut(String streamName, int partition, TailCut cut, int confirmationFactor, boolean settled) {
        if (confirmationFactor <= 1) {
            var epoch = cut.epoch().map(Epoch::toString).or("unknown");

            // The subject is the event identity: (partition, epoch, first cut offset, settled flag), so an unsettled report and the
            // settled one that follows it are two distinct events, at most two per truncation.
            OperatorWarnings.raise(log,
                                   operatorWarnings,
                                   OperatorWarningCode.STREAM_DIVERGENT_TAIL_TRUNCATED,
                                   streamName + "[" + partition + "]@" + epoch + "@" + cut.firstRemoved() + "#settled=" + settled,
                                   "Replica {}[{}] discarded offsets [{}, {}] ({} events, epoch {}) that diverged from its owner; "
                                  + "ackedAtOwner=true: confirmation_factor is 1, so they may have been acknowledged by their writer "
                                  + "and are lost; repairSettled={}",
                                   streamName,
                                   partition,
                                   cut.firstRemoved(),
                                   cut.lastRemoved(),
                                   cut.removed(),
                                   epoch,
                                   settled);

            return;
        }

        log.info("Replica {}[{}] cut its tail back to offset {}: {} unacknowledged events [{}, {}] diverged from its owner",
                 streamName,
                 partition,
                 cut.keptThrough(),
                 cut.removed(),
                 cut.firstRemoved(),
                 cut.lastRemoved());
    }

    /// The divergence found by provenance could not be repaired: it stays quarantined, so the durable flag says so. A divergence
    /// the repair resolves never gets here, so an ordinary failover raises no flag and blocks nothing.
    @Contract
    private void flagUnrepaired(String streamName, int partition) {
        option(provenanceMismatchAt.get(new PartitionRef(streamName, partition))).onPresent(offset -> raiseOnce(streamName,
                                                                                                                partition,
                                                                                                                PartitionRecoveryReasonKind.MARKED_DIVERGED,
                                                                                                                "N13: this copy's owner-epoch provenance differs from its catch-up source's at offset " + offset
                                                                                                               + " and the divergence could not be repaired"));
    }

    /// The epoch the records at and above `offset` on this copy were written under, from its owner-epoch history; none for a
    /// copy that keeps no log (and so no history) or whose history does not reach the offset.
    private Option<Epoch> divergentEpoch(String streamName, int partition, long offset) {
        return walFor(streamName, partition).flatMap(_ -> provenanceOf(streamName, partition).option())
                     .flatMap(local -> Option.from(local.history()
                                                        .stream()
                                                        .filter(entry -> entry.startOffset() <= offset)
                                                        .reduce((_, later) -> later)))
                     .map(entry -> entry.epoch()
                                        .rank());
    }

    private final class ManagerQuarantineView implements QuarantineView {
        @Override
        public Option<Long> quarantinedAt(String streamName, int partition) {
            return StreamPartitionManager.this.quarantinedAt(streamName, partition);
        }

        @Override
        public <T> Option<T> unlessQuarantined(String streamName, int partition, Supplier<T> promotion) {
            synchronized (quarantineLock) {
                return quarantinedAt(streamName, partition).fold(() -> some(promotion.get()), _ -> none());
            }
        }

        @Contract
        @Override
        public void verified(String streamName, int partition, long offset) {
            markVerified(streamName, partition, offset);
        }

        @Override
        public Result<Option<Long>> repair(String streamName, int partition, RepairAuthority authority) {
            return repairDivergence(streamName, partition, authority).map(cut -> cut.map(TailCut::keptThrough));
        }

        @Contract
        @Override
        public void verifiedForEpoch(String streamName, int partition, Epoch epoch) {
            markVerifiedForEpoch(streamName, partition, epoch);
        }

        @Override
        public Epoch committedEpoch(String streamName, int partition) {
            return ownerEpochSource.currentOwnerEpoch(streamName, partition);
        }

        @Override
        public boolean verifiedForCurrentEpoch(String streamName, int partition) {
            return replicaVerified(streamName, partition);
        }

        @Contract
        @Override
        public void repairSettled(String streamName, int partition) {
            settleRepair(streamName, partition);
        }

        @Contract
        @Override
        public void flagUnrepaired(String streamName, int partition) {
            StreamPartitionManager.this.flagUnrepaired(streamName, partition);
        }
    }

    /// Owner admission first, then the replica floor: the floor is evaluated only for an admitted write.
    private Result<Unit> admitOwnerWrite(String streamName, int partition, int minAcks) {
        return ownerWriteAdmission.remoteCommittedOwner(streamName, partition)
                                  .map(owner -> new StreamError.NotOwnerAppend(streamName, partition, owner).<Unit> result())
                                  .or(Result::unitResult)
                                  .flatMap(_ -> ownerServeGate.admit(streamName, partition))
                                  .flatMap(_ -> ensureReplicaFloor(streamName, partition, minAcks))
                                  .flatMap(_ -> ensureSegmentTierRoom());
    }

    /// #1604: refuse an owner write while the durable segment tier is at or above
    /// [SegmentTierPressure#REFUSE_AT] -- see [SegmentTierPressure] for why here and not on replicas.
    private Result<Unit> ensureSegmentTierRoom() {
        return segmentTierPressure.utilization() >= SegmentTierPressure.REFUSE_AT
               ? StreamError.General.SEGMENT_TIER_FULL.result()
               : Result.unitResult();
    }

    /// Bind the durable segment tier's pressure (#1604); the default reads none, so no write is refused.
    @Contract
    public void segmentTierPressure(SegmentTierPressure pressure) {
        this.segmentTierPressure = pressure;
    }

    /// The owner-side pre-checks — epoch fence, then `admission` (owner admission and replica floor, #1230/#1236)
    /// — run BEFORE the partition's ordered section: they read committed state the section cannot make
    /// atomic with the append, and a refused write must neither take an offset nor hold the lock.
    private Result<LoggedAppend> publishInSection(StreamEntry entry,
                                                  String streamName,
                                                  int partition,
                                                  byte[] payload,
                                                  long timestamp,
                                                  Epoch ownerEpoch,
                                                  Result<Unit> admission) {
        return appendToPartition(entry,
                                 streamName,
                                 partition,
                                 payload,
                                 timestamp,
                                 ownerEpoch,
                                 admission,
                                 offset -> logAndReplicate(streamName, partition, offset, payload, timestamp, ownerEpoch));
    }

    /// The in-section half of an owner publish — runs under the partition's append lock, right after the
    /// ring assigned `offset`: write the WAL frame (no fsync), start its group commit, then send the event
    /// to the replicas. A failed frame write fails the publish and sends nothing.
    private Result<LoggedAppend> logAndReplicate(String streamName,
                                                 int partition,
                                                 long offset,
                                                 byte[] payload,
                                                 long timestamp,
                                                 Epoch ownerEpoch) {
        return writeWalFrame(walFor(streamName, partition),
                             offset,
                             payload,
                             timestamp,
                             ownerEpoch).onSuccess(_ -> replicationManager.replicateEvent(streamName,
                                                                                          partition,
                                                                                          offset,
                                                                                          payload,
                                                                                          timestamp,
                                                                                          ownerEpoch))
                            .onSuccess(_ -> flagMissingHistory(streamName, partition, offset));
    }

    private static Result<LoggedAppend> writeWalFrame(Option<AppendLog> wal,
                                                      long offset,
                                                      byte[] payload,
                                                      long timestamp,
                                                      Epoch ownerEpoch) {
        return wal.map(w -> writeWalFrame(w, offset, payload, timestamp, ownerEpoch))
                  .or(() -> success(new LoggedAppend(offset,
                                                     Promise.unitPromise())));
    }

    private static Result<LoggedAppend> writeWalFrame(AppendLog wal,
                                                      long offset,
                                                      byte[] payload,
                                                      long timestamp,
                                                      Epoch ownerEpoch) {
        return attributedWrite(wal, offset, payload, timestamp, some(ownerEpoch)).map(writeSeq -> new LoggedAppend(offset,
                                                                                                                   wal.commit(writeSeq)));
    }

    /// #1596, spec #1569 §7.5.1: the WAL frame of the record at `offset`, attributed to owner epoch `provenance` --
    /// the epoch under which the record was FIRST appended: the owner's stamp on a publish, the batch epoch on a
    /// live replica receive (one epoch per batch, #1577). The log records the epoch start durably before the frame
    /// ([AppendLog#write(long, byte[], long, AppendLog.EpochKey, AppendLog.EpochOrder)]) the first time an epoch
    /// writes, so every record on disk is attributed. [Option#none] writes an unattributed frame: a catch-up apply,
    /// whose provenance is the source's installed slice ([#installProvenance]), never the fence epoch it presents.
    ///
    /// Empty-history rule: a history starts only at the base. A log that already holds records but has no history
    /// (written before provenance existed) records nothing; the divergence rule reads it as `HISTORY_MISSING` and
    /// flags it rather than attributing its old records to the epoch that happens to write next.
    private static Result<Long> attributedWrite(AppendLog wal,
                                                long offset,
                                                byte[] payload,
                                                long timestamp,
                                                Option<Epoch> provenance) {
        return provenance.filter(_ -> historyMayRecordAt(wal, offset))
                         .map(epoch -> keyedWrite(wal, offset, payload, timestamp, epoch))
                         .or(() -> wal.write(offset, payload, timestamp));
    }

    private static boolean historyMayRecordAt(AppendLog wal, long offset) {
        return offset == PROVENANCE_BASE || !wal.epochHistory()
                                                .isEmpty();
    }

    /// #1596 empty-history rule, the flag half: an attributed append the log could not attribute (records, no
    /// history) raises `HISTORY_MISSING` for this copy -- once per process, not once per append.
    @Contract
    private void flagMissingHistory(String streamName, int partition, long offset) {
        walFor(streamName, partition).filter(wal -> !historyMayRecordAt(wal, offset))
              .onPresent(_ -> raiseOnce(streamName,
                                        partition,
                                        PartitionRecoveryReasonKind.HISTORY_MISSING,
                                        "this copy holds records written before owner-epoch provenance existed"));
    }

    /// #1638 F6: the trim at open failed ([StreamEntry#trimAtOpen]), so the owner-epoch history keeps entries above the
    /// head that no record reaches and the partition is not opened on this node. The trim's own cause is logged at
    /// ERROR here, and `LOCAL_MISMATCH` is raised for this copy on the durable flag -- the log's history disagrees with
    /// its own records, the local-verification failure of spec #1569 §7.5.3 -- so the state is visible cluster-wide
    /// and survives the restart, like an N13 divergence. The evidence carries the head only, so a retry raises the same
    /// reason. Returns `cause`, which fails the open.
    private Cause trimFailedAtOpen(String streamName, int partition, long head, Cause cause) {
        log.error("Partition {}[{}] not opened: its owner-epoch history holds entries above head {} that could not be trimmed: {}",
                  streamName,
                  partition,
                  head,
                  cause.message());
        raiseOnce(streamName,
                  partition,
                  PartitionRecoveryReasonKind.LOCAL_MISMATCH,
                  "the owner-epoch history holds entries above head " + head + " that the trim at open could not drop");

        return cause;
    }

    /// Where the static recovery path reports a failed trim at open (#1638 F6); returns the cause the open fails with.
    @FunctionalInterface
    interface TrimFailure {
        Cause trimFailed(String streamName, int partition, long head, Cause cause);
    }

    /// Raise a reason about this node's copy on the durable flag, at most once per process per reason. Without a
    /// flag wired the reason is logged at ERROR and left to the local fence.
    @Contract
    private void raiseOnce(String streamName, int partition, PartitionRecoveryReasonKind kind, String evidence) {
        if (raisedLocally.add(streamName + "#" + partition + "#" + kind + "#" + evidence)) {
            partitionFlags.onEmpty(() -> log.error("Partition {}[{}] needs the durable flag {} ({}), but no partition flag is wired here",
                                                   streamName,
                                                   partition,
                                                   kind,
                                                   evidence))
                          .onPresent(flags -> raise(flags, streamName, partition, kind, evidence));
        }
    }

    /// Recovery: FER -- a raise that did not commit (no leader, contention) is forgotten, so the next occurrence of
    /// the condition raises again; the local fence (quarantine, refused appends) holds meanwhile.
    @Contract
    private void raise(PartitionFlags flags,
                       String streamName,
                       int partition,
                       PartitionRecoveryReasonKind kind,
                       String evidence) {
        flags.raise(streamName,
                    partition,
                    flags.local(kind, evidence))
             .onFailure(cause -> forgetRaise(streamName, partition, kind, evidence, cause));
    }

    @Contract
    private void forgetRaise(String streamName,
                             int partition,
                             PartitionRecoveryReasonKind kind,
                             String evidence,
                             Cause cause) {
        raisedLocally.remove(streamName + "#" + partition + "#" + kind + "#" + evidence);
        log.warn("Partition {}[{}]: raising {} on the durable flag failed, retried at its next occurrence: {}",
                 streamName,
                 partition,
                 kind,
                 cause.message());
    }

    private static Result<Long> keyedWrite(AppendLog wal, long offset, byte[] payload, long timestamp, Epoch epoch) {
        return ProvenanceEntry.provenanceEntry(epoch,
                                               none(),
                                               offset)
                              .key()
                              .flatMap(key -> wal.write(offset, payload, timestamp, key, ProvenanceEntry.ORDER));
    }

    /// Gate the publish ack on WAL fsync (streaming-persistence W3), OUTSIDE the ordered section. With no
    /// WAL configured the barrier is already resolved. With a WAL present the GROUP-COMMIT fsync is
    /// awaited here — the event is acked only once it survives `kill -9`. [TerminalOperation]: the
    /// blocking await IS the durability contract (publish does not resolve until fsync), and the WAL's
    /// group commit batches concurrent publishers into a single fsync so the barrier does not serialize
    /// throughput.
    @TerminalOperation
    private Result<Long> awaitDurable(LoggedAppend logged) {
        return logged.durable()
                     .await()
                     .map(_ -> logged.offset());
    }

    /// An owner append that has left the ordered section: its offset, and the group-commit fsync its WAL
    /// frame waits on (already resolved when the partition has no WAL).
    private record LoggedAppend(long offset, Promise<Unit> durable) {}

    /// Admitted storage run without single-event fallback: a caller retains per-event outcomes when
    /// an oversized run must be retried one event at a time. Refusals before append preserve the run.
    public Result<Long> publishLocalBatchAtFloor(String streamName,
                                                 int partition,
                                                 List<byte[]> payloads,
                                                 long timestamp,
                                                 int minAcks) {
        var ownerEpoch = ownerEpochSource.currentOwnerEpoch(streamName, partition);

        return resolveWritableEntry(streamName).flatMap(entry -> publishBatchInSection(entry,
                                                                                       streamName,
                                                                                       partition,
                                                                                       payloads,
                                                                                       timestamp,
                                                                                       ownerEpoch,
                                                                                       minAcks))
                                   .flatMap(this::awaitDurable)
                                   .onSuccess(offset -> ownerDurable(streamName, partition, offset));
    }

    private Result<LoggedAppend> publishBatchInSection(StreamEntry entry,
                                                       String streamName,
                                                       int partition,
                                                       List<byte[]> payloads,
                                                       long timestamp,
                                                       Epoch ownerEpoch,
                                                       int minAcks) {
        return ensureNotStale(streamName, partition, ownerEpoch).flatMap(_ -> admitOwnerWrite(streamName,
                                                                                              partition,
                                                                                              minAcks))
                             .flatMap(_ -> provenanceAdmits(streamName,
                                                            partition,
                                                            some(ownerEpoch)))
                             .flatMap(_ -> checkEventSizes(entry, payloads))
                             .flatMap(_ -> resolveAppendTarget(streamName, partition, entry))
                             .flatMap(buffer -> appendRunInSection(buffer,
                                                                   streamName,
                                                                   partition,
                                                                   payloads,
                                                                   timestamp,
                                                                   ownerEpoch))
                             .onSuccess(_ -> entry.updateActivity());
    }

    private Result<LoggedAppend> appendRunInSection(OffHeapRingBuffer buffer,
                                                    String streamName,
                                                    int partition,
                                                    List<byte[]> payloads,
                                                    long timestamp,
                                                    Epoch ownerEpoch) {
        return buffer.appendBatchOrdered(payloads,
                                         LongStream.generate(() -> timestamp).limit(payloads.size()).toArray(),
                                         lastOffset -> logRunAndReplicate(streamName,
                                                                          partition,
                                                                          lastOffset,
                                                                          payloads,
                                                                          timestamp,
                                                                          ownerEpoch));
    }

    private static Result<Unit> checkEventSizes(StreamEntry entry, List<byte[]> payloads) {
        for (var payload : payloads) {
            if (payload.length > entry.config().maxEventSizeBytes()) {
                return checkEventSize(entry, payload);
            }
        }

        return Result.unitResult();
    }

    /// The in-section half of a batch publish: the run's WAL frames in offset order (no fsync), its group
    /// commit started, then ONE replication message for the run. A failed frame write fails the publish
    /// and sends nothing.
    private Result<LoggedAppend> logRunAndReplicate(String streamName,
                                                    int partition,
                                                    long lastOffset,
                                                    List<byte[]> payloads,
                                                    long timestamp,
                                                    Epoch ownerEpoch) {
        var firstOffset = lastOffset - payloads.size() + 1;

        return writeWalFrames(walFor(streamName, partition),
                              firstOffset,
                              payloads,
                              timestamp,
                              ownerEpoch).onSuccess(_ -> replicationManager.replicateEvents(streamName,
                                                                                            partition,
                                                                                            firstOffset,
                                                                                            payloads,
                                                                                            Collections.nCopies(payloads.size(),
                                                                                                                timestamp),
                                                                                            ownerEpoch))
                             .onSuccess(_ -> flagMissingHistory(streamName, partition, firstOffset));
    }

    private static Result<LoggedAppend> writeWalFrames(Option<AppendLog> wal,
                                                       long firstOffset,
                                                       List<byte[]> payloads,
                                                       long timestamp,
                                                       Epoch ownerEpoch) {
        return wal.map(w -> writeWalFrames(w, firstOffset, payloads, timestamp, ownerEpoch))
                  .or(() -> success(new LoggedAppend(firstOffset + payloads.size() - 1,
                                                     Promise.unitPromise())));
    }

    private static Result<LoggedAppend> writeWalFrames(AppendLog wal,
                                                       long firstOffset,
                                                       List<byte[]> payloads,
                                                       long timestamp,
                                                       Epoch ownerEpoch) {
        var writes = IntStream.range(0,
                                     payloads.size())
                              .mapToObj(i -> attributedWrite(wal,
                                                             firstOffset + i,
                                                             payloads.get(i),
                                                             timestamp,
                                                             some(ownerEpoch)))
                              .toList();

        return Result.allOf(writes).map(writeSeqs -> new LoggedAppend(firstOffset + payloads.size() - 1,
                                                                      commitLast(wal, writeSeqs)));
    }

    /// One group commit covering the whole run (its last write covers every earlier one); an empty run
    /// wrote nothing, so there is nothing to wait for.
    private static Promise<Unit> commitLast(AppendLog wal, List<Long> writeSeqs) {
        return writeSeqs.isEmpty()
               ? Promise.unitPromise()
               : wal.commit(writeSeqs.getLast());
    }

    /// The configured [AppendLog] for `(streamName, partition)`, or [Option#none] when no WAL base
    /// dir is wired (the steady-state legacy/Forge path) or the partition is out of range.
    private Option<AppendLog> walFor(String streamName, int partition) {
        return option(streams.get(streamName)).flatMap(entry -> entry.walFor(partition));
    }

    /// The publish path's replication barrier. Visibility is refreshed inline when it resolves (rev1309 F1):
    /// an await can resolve from the registry SNAPSHOT — two concurrent acks each overlay only their own
    /// ack in the pre-update observer call, both registry rows then land, and the await resolves before
    /// either post-update observer call — so without this the continuation could miss its own acked write.
    /// A ring released in the meantime has no reader left to expose the event to.
    public Promise<Unit> awaitReplication(String streamName, int partition, long offset, int minAcks) {
        return replicationManager.awaitReplication(streamName, partition, offset, minAcks)
                                 .onSuccess(_ -> resolvePartitionBuffer(streamName, partition).onSuccess(ring -> refreshVisible(ring,
                                                                                                                                streamName,
                                                                                                                                partition)));
    }

    /// #1262 fail-closed guard, applied ONCE by `StreamWriteRouter.publish` — the single write operation behind
    /// `DefaultStreamPublisher`, `PartitionedStreamAccess` and the management publish (#1263) — and by the
    /// owner-side {@link #publishForwarded}: a stream whose declared consistency no write path can honour is
    /// refused rather than appended as EVENTUAL. The entity-log substrate's direct {@link #publishLocal} is
    /// outside it (EVENTUAL by construction).
    ///   - `STRONG` promises consensus-ordered acknowledgement, and `ConsensusPublishPath` has no production
    ///     caller → [StreamError.General#CONSENSUS_PATH_UNAVAILABLE], the cause `DefaultStreamPublisher`
    ///     already used.
    ///   - `UNKNOWN` (#964) was written by a node running a newer `ConsistencyMode` and may be STRONG there →
    ///     [StreamError.General#UNREADABLE_CONSISTENCY_MODE].
    /// An unknown stream passes; the append path reports it. `StreamResourceValidator` rejects STRONG at
    /// deploy time first, so this is defence in depth for streams created by other routes.
    public Result<Unit> ensureWritableConsistency(String streamName) {
        return option(streams.get(streamName)).map(entry -> writableConsistency(entry.config().consistencyMode()))
                     .or(Result::unitResult);
    }

    private static Result<Unit> writableConsistency(ConsistencyMode mode) {
        return switch (mode) {
            case EVENTUAL -> Result.unitResult();
            case STRONG -> StreamError.General.CONSENSUS_PATH_UNAVAILABLE.result();
            case UNKNOWN -> StreamError.General.UNREADABLE_CONSISTENCY_MODE.result();
        };
    }

    /// Pre-append replica-floor check (#1236). Publish paths call this BEFORE {@link #publishLocal}, so a
    /// `NOT_ENOUGH_REPLICAS` refusal leaves nothing in the ring or the WAL; see
    /// [ReplicationManager#ensureReplicaFloor].
    public Result<Unit> ensureReplicaFloor(String streamName, int partition, int minAcks) {
        return replicationManager.ensureReplicaFloor(streamName, partition, minAcks);
    }

    /// The configured `confirmation_factor` write-ack requirement for `streamName` (in-sync count incl.
    /// owner), or `0` when the stream is unknown. `<= 1` means no peer-ack barrier; `>= 2` means a
    /// publish must await `confirmationFactor - 1` distinct non-self replica acks. Read straight from the
    /// stream's committed config so the REST publish path can gate on the stream's durability setting.
    public int confirmationFactorFor(String streamName) {
        return option(streams.get(streamName)).map(entry -> entry.config()
                                                                 .confirmationFactor())
                     .or(0);
    }

    /// Append a recovered event at the local ring TAIL WITHOUT re-triggering replication. The ring assigns
    /// the next offset, so offsets are preserved only when nothing else appends to the partition between
    /// the caller's choice of position and this append. NO production replica or recovery path calls it
    /// (#1505 F1): the live receive, the catch-up apply and both failover recoveries land at their owner
    /// offsets through the offset-addressed overloads below. It remains for tests that seed a ring. A new
    /// replica-side caller would reopen #1505.
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
        return resolveWritableEntry(streamName).flatMap(entry -> appendReplicatedInSection(entry,
                                                                                           streamName,
                                                                                           partition,
                                                                                           payload,
                                                                                           timestamp,
                                                                                           ownerEpoch))
                                   .onSuccess(offset -> visibleAtOnceWithoutWal(streamName, partition, offset))
                                   .onFailure(cause -> countRefusedReplicaDrop(cause, streamName, partition));
    }

    /// Offset-addressed replica append (#1505) stamped with the no-epoch floor ([Epoch#ZERO]), for callers that
    /// carry no owner epoch. It records NO provenance (#1596): it is the catch-up apply's shape, whose records are
    /// attributed by the source's installed slice ([#installProvenance]). See the fenced overload below.
    public Result<Long> appendRecovered(String streamName, int partition, long offset, byte[] payload, long timestamp) {
        return appendCaughtUp(streamName, partition, offset, payload, timestamp, Epoch.ZERO);
    }

    /// A catch-up apply's append (#1505, #1596): fenced with `fenceEpoch` like the live receive, but attributed to
    /// no epoch, because `fenceEpoch` is the partition's CURRENT owner epoch and the record may have been written
    /// under an earlier one. Its provenance is the source's slice, installed first by [#installProvenance].
    public Result<Long> appendCaughtUp(String streamName,
                                       int partition,
                                       long offset,
                                       byte[] payload,
                                       long timestamp,
                                       Epoch fenceEpoch) {
        return resolveWritableEntry(streamName).flatMap(entry -> appendReplicatedAt(entry,
                                                                                    streamName,
                                                                                    partition,
                                                                                    offset,
                                                                                    payload,
                                                                                    timestamp,
                                                                                    fenceEpoch,
                                                                                    none()))
                                   .onSuccess(held -> visibleAtOnceWithoutWal(streamName, partition, held))
                                   .onFailure(cause -> countRefusedReplicaDrop(cause, streamName, partition));
    }

    /// Offset-addressed replica append (#1505): the single offset authority shared by the replica's catch-up
    /// apply and its live receive. The event lands at owner offset `offset` and nowhere else, decided against
    /// the ring head inside the partition's ordered append section ([OffHeapRingBuffer#appendOrderedAt]), so a
    /// live batch and a catch-up response racing for the same offsets cannot shift one another. Succeeds with
    /// `offset` exactly when this replica now holds the offered event there — appended now, or already held
    /// with identical payload and timestamp (nothing written, no WAL frame). Refusals append nothing:
    /// [StreamError.ReplicaOffsetGap] (offsets below `offset` are missing), [StreamError.ReplicaEntryConflict]
    /// (a DIFFERENT event is held at `offset`; the partition is then quarantined, see [#quarantinedAt]),
    /// [StreamError.ReplicaQuarantined] (`offset` is at or past a known divergence), [StreamError.CursorExpired]
    /// (held once, evicted, unverifiable).
    /// The epoch fence and size check run first, exactly as for [#appendRecovered(String, int, byte[], long, Epoch)].
    /// This is the LIVE receive's shape: the record is attributed to `ownerEpoch`, the batch epoch (#1596, #1577).
    public Result<Long> appendRecovered(String streamName,
                                        int partition,
                                        long offset,
                                        byte[] payload,
                                        long timestamp,
                                        Epoch ownerEpoch) {
        return resolveWritableEntry(streamName).flatMap(entry -> appendReplicatedAt(entry,
                                                                                    streamName,
                                                                                    partition,
                                                                                    offset,
                                                                                    payload,
                                                                                    timestamp,
                                                                                    ownerEpoch,
                                                                                    some(ownerEpoch)))
                                   .onSuccess(held -> visibleAtOnceWithoutWal(streamName, partition, held))
                                   .onFailure(cause -> countRefusedReplicaDrop(cause, streamName, partition));
    }

    /// #1235, replica side: a replicated record becomes visible to reads served BY THIS NODE once its own
    /// WAL write is durable — at once when the partition has no WAL, otherwise at the batch barrier
    /// ([#syncReplicated]), which advances visibility to the offset it committed. A replica does not learn
    /// the owner's visible position, so this bounds a replica-local read by the replica's durability, not
    /// by the owner's confirmation acks.
    ///
    /// #1235 × #1244: requesting the record's own group commit here — one commit request per record — made
    /// the barrier's "one fsync per batch" hold only when the async commits happened to coalesce, so the
    /// advance is the barrier's step, not the append's. A failed WAL write poisons the chain: nothing at or
    /// after it becomes visible, and the failure surfaces where the receive handler awaits the barrier
    /// before acking.
    @Contract
    private void visibleAtOnceWithoutWal(String streamName, int partition, long offset) {
        if (walFor(streamName, partition).isEmpty()) {
            replicaDurable(streamName, partition, offset);
        }
    }

    @Contract
    private void replicaDurable(String streamName, int partition, long offset) {
        var verified = !unverifiedReplicas.contains(partitionKeyOf(streamName, partition));

        resolvePartitionBuffer(streamName, partition).onSuccess(ring -> replicaDurable(ring, offset, verified));
    }

    @Contract
    private static void replicaDurable(OffHeapRingBuffer ring, long offset, boolean verified) {
        ring.markDurable(offset);
        if (verified) {
            ring.advanceVisible(offset);
        }
    }

    /// The replica append shares the owner's ordered section (#1231): it and any concurrent local append
    /// on this partition are serialized, and its WAL frame is written in offset order.
    private Result<Long> appendReplicatedInSection(StreamEntry entry,
                                                   String streamName,
                                                   int partition,
                                                   byte[] payload,
                                                   long timestamp,
                                                   Epoch ownerEpoch) {
        return appendToPartition(entry,
                                 streamName,
                                 partition,
                                 payload,
                                 timestamp,
                                 ownerEpoch,
                                 provenanceAdmits(streamName, partition, some(ownerEpoch)),
                                 offset -> success(logReplicated(streamName,
                                                                 partition,
                                                                 offset,
                                                                 payload,
                                                                 timestamp,
                                                                 some(ownerEpoch))));
    }

    /// Offset-addressed sibling of [#appendReplicatedInSection] (#1505): the same fence, admission and size
    /// checks, then [OffHeapRingBuffer#appendOrderedAt], which writes the WAL frame only when it appends.
    private Result<Long> appendReplicatedAt(StreamEntry entry,
                                            String streamName,
                                            int partition,
                                            long offset,
                                            byte[] payload,
                                            long timestamp,
                                            Epoch ownerEpoch,
                                            Option<Epoch> provenance) {
        return appendTarget(entry,
                            streamName,
                            partition,
                            payload,
                            ownerEpoch,
                            provenanceAdmits(streamName, partition, provenance),
                            offset == 0L).flatMap(buffer -> buffer.appendOrderedAt(offset,
                                                                                   payload,
                                                                                   timestamp,
                                                                                   new PartitionQuarantine(streamName,
                                                                                                           partition),
                                                                                   assigned -> success(logReplicated(streamName,
                                                                                                                     partition,
                                                                                                                     assigned,
                                                                                                                     payload,
                                                                                                                     timestamp,
                                                                                                                     provenance))))
                           .onSuccess(_ -> entry.updateActivity());
    }

    /// The [OffHeapRingBuffer.DivergenceFence] of one partition, backed by [#divergedAt]. The ring calls it inside
    /// the ordered section, so recording a divergence and refusing the next offer cannot race.
    private final class PartitionQuarantine implements OffHeapRingBuffer.DivergenceFence {
        private final PartitionRef ref;

        private PartitionQuarantine(String streamName, int partition) {
            this.ref = new PartitionRef(streamName, partition);
        }

        @Override
        public long divergedAt() {
            return divergedAt.getOrDefault(ref, -1L);
        }

        /// Logged at ERROR once per partition: the first divergence quarantines it, a lower one only lowers the
        /// ceiling.
        @Contract
        @Override
        public void recordDivergence(long offset) {
            synchronized (quarantineLock) {
                recordLocked(offset);
            }
        }

        private void recordLocked(long offset) {
            if (divergedAt.putIfAbsent(ref, offset) == null) {
                quarantinedPartitionsSinceBoot.incrementAndGet();
                log.error("Replica partition {}[{}] QUARANTINED: offset {} holds an event that differs from the one its sender "
                         + "offered. Nothing at or past it is acked, and this node never promotes the partition CAUGHT_UP. "
                         + "A replica whose owner is known repairs itself by cutting its tail back to offset {} and refetching "
                         + "(#1730 phase 2); the owner's own copy stays quarantined",
                          ref.streamName(),
                          ref.partition(),
                          offset,
                          offset - 1);

                return;
            }

            divergedAt.merge(ref, offset, Math::min);
        }
    }

    /// #1233: a replicated event this replica's frozen ring cannot store fails the append (never applied,
    /// never WAL-written, never acked) — counted and logged here so the resulting stall is observable.
    @Contract
    private void countRefusedReplicaDrop(Cause cause, String streamName, int partition) {
        if (cause != StreamError.General.EVENT_DROPPED) {
            return;
        }

        var refused = refusedReplicaDropsSinceBoot.incrementAndGet();

        log.warn("Refused replicated append on '{}' partition {}: event larger than this replica's frozen ring; the partition stays behind on this replica until the ring is rebuilt with pool budget ({} refused since boot)",
                 streamName,
                 partition,
                 refused);
    }

    /// #634 item 1: a replicated/backfilled record enters the SAME per-partition WAL the owner's publish
    /// path uses, so the "replicated" half of `confirmationFactor` is crash-durable rather than RAM-until-seal
    /// — before this, correlated power loss inside the unsealed window lost acked entity writes at ANY RF.
    ///
    /// Runs inside the partition's ordered append section, so frames land in offset order, and writes the
    /// frame with NO fsync (#1244): durability is claimed at the ACK, not the append. [#syncReplicated] is
    /// the barrier — the receive handler awaits it once per batch and a backfill run awaits it before
    /// promoting — and it commits every frame written so far in one group commit. The record's offset is
    /// handed through unchanged. A failed write is recorded rather than raised: the record is applied and
    /// serveable, but [#syncReplicated] fails, so acks stop and the owner's barrier degrades honestly.
    private long logReplicated(String streamName,
                               int partition,
                               long offset,
                               byte[] payload,
                               long timestamp,
                               Option<Epoch> provenance) {
        walFor(streamName, partition).onPresent(wal -> recordReplicatedWrite(streamName,
                                                                             partition,
                                                                             new ReplicatedWrite(wal,
                                                                                                 offset,
                                                                                                 attributedWrite(wal,
                                                                                                                 offset,
                                                                                                                 payload,
                                                                                                                 timestamp,
                                                                                                                 provenance))));
        provenance.onPresent(_ -> flagMissingHistory(streamName, partition, offset));

        return offset;
    }

    /// #1277 review N2: forget the latest replicated write recorded for `(streamName, partition)` when its
    /// WAL is released — but only while the entry still points at THAT instance, so releasing a duplicate
    /// that lost the install race can never erase the live winner's entry (which would let its next
    /// barrier resolve without an fsync).
    ///
    /// Called AFTER the WAL is closed (#1277 review N3): a barrier racing the release then finds the closed
    /// channel through the still-recorded entry and fails, instead of finding no entry and resolving
    /// before the close-time fsync has run.
    @Contract
    private void forgetReplicatedWrites(String streamName, int partition, Option<AppendLog> wal) {
        wal.onPresent(released -> lastReplicatedWalWrite.computeIfPresent(partitionKeyOf(streamName, partition),
                                                                          (_, write) -> unlessWrittenTo(write, released)));
    }

    /// [java.util.Map#computeIfPresent] contract: `null` removes the entry.
    @NullReturn
    private static ReplicatedWrite unlessWrittenTo(ReplicatedWrite write, AppendLog released) {
        return write.wal() == released
               ? null
               : write;
    }

    private void recordReplicatedWrite(String streamName, int partition, ReplicatedWrite write) {
        lastReplicatedWalWrite.put(partitionKeyOf(streamName, partition), write);
    }

    /// The durability barrier for replicated records: ONE group commit covering every WAL frame
    /// [#appendRecovered] has written for `(streamName, partition)` so far (#1244) — a batch of N records
    /// costs one commit request and one fsync, not N. Once that commit succeeds, and before the returned
    /// promise resolves, the records up to the committed write's offset become visible to reads served by
    /// this node (#1235) — so a caller that acks after this barrier acks records this replica already
    /// serves. Fails while the latest replicated write on this WAL failed, and then advances nothing.
    /// WAL-less deployments (legacy, Forge, the explicit non-durable opt-in) resolve immediately — the ack
    /// then means exactly what it meant before the WAL existed.
    public Promise<Unit> syncReplicated(String streamName, int partition) {
        return option(lastReplicatedWalWrite.get(partitionKeyOf(streamName, partition))).map(write -> commitAndExpose(streamName,
                                                                                                                      partition,
                                                                                                                      write))
                     .or(Promise::unitPromise);
    }

    /// [Promise#withSuccess] runs the advance as a step of the barrier promise, not as a detached completion
    /// handler: the caller's success implies the advance already happened.
    private Promise<Unit> commitAndExpose(String streamName, int partition, ReplicatedWrite write) {
        return write.commit()
                    .withSuccess(_ -> replicaDurable(streamName,
                                                     partition,
                                                     write.offset()));
    }

    /// A replicated WAL frame write: the WAL it went to, the record's offset — the position the barrier
    /// exposes once this write is durable (#1235) — and its write sequence (or the write failure).
    ///
    /// The latest write replaces the previous one; no separate poison flag is kept. A later success after
    /// a failed write would leave a hole the ring does not have, but a failed frame write or fsync
    /// FAIL-STOPS the WAL, so every later write on that instance fails too and acks stay withheld. The one
    /// refusal that does not fail-stop, [AppendLog.WalError.OffsetRegression], means the replica's ring
    /// assigned an offset its WAL already holds — ring and WAL disagree, and that offset's payload in the
    /// file may differ from the ring's; a later successful write lets acks resume past it.
    /// `[design intent — unverified: reachable only through a ring/WAL head mismatch such as a frozen-ring
    /// drop during recovery (#1233); no test induces it]`. The entry is forgotten when its WAL is released
    /// ([#forgetReplicatedWrites]), so a rebuilt partition's first barrier never targets a closed WAL.
    private record ReplicatedWrite(AppendLog wal, long offset, Result<Long> writeSeq) {
        Promise<Unit> commit() {
            return writeSeq.async()
                           .flatMap(wal::commit);
        }
    }

    private static String partitionKeyOf(String streamName, int partition) {
        return streamName + "#" + partition;
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

    /// #1333: the consumer-visible span of a partition this node holds — ring tail through the VISIBLE
    /// position — or [Option#none] when the ring is not materialised here. What a projection rebuild
    /// captures, and what a forwarded read answers alongside its events.
    public Option<VisibleBounds> visibleBounds(String streamName, int partition) {
        return resolvePartitionBuffer(streamName, partition).map(buffer -> VisibleBounds.visibleBounds(buffer.tailOffset(),
                                                                                                       buffer.visibleOffset()))
                                     .option();
    }

    /// Append into the partition's ordered section: `inOrder` runs with the assigned offset before any
    /// other append on this partition is assigned one (see [OffHeapRingBuffer#appendOrdered]). The epoch
    /// fence and `admission` are checked first, in that order (#1230: a deposed writer learns it is deposed,
    /// not merely redirected), and before the section is entered.
    private <T> Result<T> appendToPartition(StreamEntry entry,
                                            String streamName,
                                            int partition,
                                            byte[] payload,
                                            long timestamp,
                                            Epoch ownerEpoch,
                                            Result<Unit> admission,
                                            Fn1<Result<T>, Long> inOrder) {
        return appendTarget(entry, streamName, partition, payload, ownerEpoch, admission).flatMap(buffer -> buffer.appendOrdered(payload,
                                                                                                                                 timestamp,
                                                                                                                                 inOrder))
                           .onSuccess(_ -> entry.updateActivity());
    }

    /// The ring an append may enter, after the epoch fence, `admission` and the size check, in that order.
    private Result<OffHeapRingBuffer> appendTarget(StreamEntry entry,
                                                   String streamName,
                                                   int partition,
                                                   byte[] payload,
                                                   Epoch ownerEpoch,
                                                   Result<Unit> admission) {
        return appendTarget(entry, streamName, partition, payload, ownerEpoch, admission, false);
    }

    /// As above; `startsEmptyPartition` marks a replicate append at offset 0, which may materialize an EMPTY
    /// partition outside reshuffle pacing ([#materializeIfHeld]).
    private Result<OffHeapRingBuffer> appendTarget(StreamEntry entry,
                                                   String streamName,
                                                   int partition,
                                                   byte[] payload,
                                                   Epoch ownerEpoch,
                                                   Result<Unit> admission,
                                                   boolean startsEmptyPartition) {
        return ensureNotStale(streamName, partition, ownerEpoch).flatMap(_ -> admission)
                             .flatMap(_ -> checkEventSize(entry, payload))
                             .flatMap(_ -> resolveAppendTarget(streamName, partition, entry, startsEmptyPartition));
    }

    /// Resolve the ring to append into, materializing it lazily on the OWNER/REPLICA path (#265 increment
    /// 2 safety valve, spec §5.4). An already-materialized partition returns its ring directly. A
    /// metadata-only partition is materialized ONLY when this node is its OWNER/REPLICA (a publish/replica-
    /// receive that lands here because reconcile has not fired yet must not drop the write); a genuine
    /// non-replica (`NONE`) is rejected with `PARTITION_NOT_LOCAL` so the caller forwards to a holder (the
    /// read router forwards on an absent local buffer, the write routers route by owner (#1230) — spec §8). The
    /// READ path never materializes — it forwards.
    private Result<OffHeapRingBuffer> resolveAppendTarget(String streamName, int partition, StreamEntry entry) {
        return resolveAppendTarget(streamName, partition, entry, false);
    }

    private Result<OffHeapRingBuffer> resolveAppendTarget(String streamName,
                                                          int partition,
                                                          StreamEntry entry,
                                                          boolean startsEmptyPartition) {
        if (partition < 0 || partition >= entry.declaredPartitions()) {
            return new StreamError.PartitionOutOfRange(streamName, partition, entry.declaredPartitions()).result();
        }

        return entry.ringFor(partition)
                    .fold(() -> materializeIfHeld(streamName, partition, entry, startsEmptyPartition),
                          buffer -> success(buffer));
    }

    /// F1f: a REPLICA's first replicate append at offset 0 for a partition with NOTHING durable (no WAL record, no
    /// sealed segment) materializes outside `reshuffle_concurrency` pacing. Pacing bounds the cost of a history
    /// backfill, and an empty partition has none; pacing it only made a `confirmation_factor` 2 publish to a new
    /// stream time out while the slots were legitimately busy. A partition with data, or whose log cannot be
    /// inspected, stays paced. The budget check still applies.
    private Result<OffHeapRingBuffer> materializeIfHeld(String streamName,
                                                        int partition,
                                                        StreamEntry entry,
                                                        boolean startsEmptyPartition) {
        return switch (placementRoleSupplier.roleFor(streamName, partition)) {
            case OWNER -> buildAndInstall(entry, partition);
            case REPLICA -> startsEmptyPartition && hasNothingDurable(streamName, partition)
                            ? materializeNow(entry, partition, false)
                            : buildAndInstall(entry, partition);
            case NONE -> StreamError.General.PARTITION_NOT_LOCAL.result();
        };
    }

    private boolean hasNothingDurable(String streamName, int partition) {
        return durableWatermark(streamName, partition).filter(watermark -> watermark < 0)
                               .isPresent();
    }

    /// #1596: an append attributed to `provenance` is refused BEFORE the ordered section when this partition's log
    /// already records a strictly later epoch ([StreamError.ProvenanceRegression]) -- a deposed owner's batch that
    /// reaches a replica before the replica's fence high-water has observed the new ownership. Refusing here keeps
    /// the ring and the WAL in step: the in-section refusal ([AppendLog.WalError.EpochRegression]) remains for the
    /// race with a concurrent newer-epoch append, and runs after the ring has assigned the offset. An unattributed
    /// append, a partition without a log, or an unreadable last entry passes (the log's own order check decides).
    private Result<Unit> provenanceAdmits(String streamName, int partition, Option<Epoch> provenance) {
        return provenance.flatMap(epoch -> lastRecordedEpoch(streamName, partition).filter(recorded -> recorded.isStrictlyAfter(epoch))
                                                            .map(recorded -> new StreamError.ProvenanceRegression(streamName,
                                                                                                                  partition,
                                                                                                                  epoch,
                                                                                                                  recorded)))
                         .map(StreamError::<Unit> result)
                         .or(Result::unitResult);
    }

    private Option<Epoch> lastRecordedEpoch(String streamName, int partition) {
        return walFor(streamName, partition).flatMap(wal -> Option.from(wal.epochHistory()
                                                                           .stream()
                                                                           .reduce((earlier, later) -> later)))
                     .flatMap(last -> ProvenanceEntry.provenanceEntry(last).option())
                     .map(entry -> entry.epoch()
                                        .rank());
    }

    /// The owner-epoch history of this node's copy of `(streamName, partition)`, oldest first (#1596): empty when
    /// the partition keeps no log here or its log records none. A failure only for a history this codec did not
    /// write.
    public Result<List<ProvenanceEntry>> epochHistory(String streamName, int partition) {
        return walFor(streamName, partition).map(wal -> Result.allOf(wal.epochHistory()
                                                                        .stream()
                                                                        .map(ProvenanceEntry::provenanceEntry)
                                                                        .toList()))
                     .or(() -> success(List.of()));
    }

    /// What this node's copy of `(streamName, partition)` says about its records' provenance (#1596): the input of
    /// [org.pragmatica.aether.stream.provenance.ProvenanceComparison] for the promotion gate and cold-restart
    /// detection. `low` is the lowest offset held locally, `head` the highest (`-1` for none).
    ///
    /// [Option#none] for a partition that keeps no log here (Forge, the explicit non-durable opt-in): provenance
    /// applies ONLY to WAL-backed, durable partitions (CTO ruling for rc4). A log-less copy records no history, so
    /// comparing it would read `HISTORY_MISSING` and flag every such partition; it makes no durability claim and
    /// keeps its behaviour from before provenance, so a caller (the promotion gate, AD7) excludes it from the
    /// comparison instead of flagging it.
    public Result<Option<LogProvenance>> localProvenance(String streamName, int partition) {
        return walFor(streamName, partition).map(_ -> provenanceOf(streamName, partition).map(Option::some))
                     .or(() -> success(none()));
    }

    private Result<LogProvenance> provenanceOf(String streamName, int partition) {
        return epochHistory(streamName, partition).map(history -> LogProvenance.logProvenance(LogProvenance.baseOf(history),
                                                                                              earliestRetainedOffset(streamName,
                                                                                                                     partition),
                                                                                              nextExpectedOffset(streamName,
                                                                                                                 partition) - 1,
                                                                                              history));
    }

    /// Install a catch-up source's owner-epoch slice, apply the page, and settle the install (#1596, spec #1569
    /// §7.5.1; #1638 F1). The one entry point of a catch-up apply ([AlignedRecovery#applyAttributed]), so no caller
    /// can apply without the install or forget to settle it.
    ///
    /// The slice is the source's entries covering `[base, toOffset]`, read by the source AFTER the records it served,
    /// so they cover every one of them; it is installed first ([#installProvenance]), and nothing of the page is
    /// applied when that fails. When `apply` then fails, the entries THIS install recorded that no record reaches are
    /// dropped ([#trimProvenance]), so the retry installs cleanly and the copy is never ranked by an epoch it did not
    /// receive; a trim that fails is logged, and the next open trims whatever it left.
    public Result<Long> applyAttributed(String streamName,
                                        int partition,
                                        long fromOffset,
                                        long toOffset,
                                        List<ProvenanceEntry> slice,
                                        AlignedRecovery.PageApply apply) {
        return installProvenance(streamName, partition, fromOffset, toOffset, slice).flatMap(installed -> settle(installed,
                                                                                                                 apply.apply()));
    }

    /// [#applyAttributed] for records from a place that carries no provenance ([#installUnattributed]).
    public Result<Long> applyUnattributed(String streamName,
                                          int partition,
                                          long toOffset,
                                          AlignedRecovery.PageApply apply) {
        return installUnattributed(streamName, partition, toOffset).flatMap(installed -> settle(installed, apply.apply()));
    }

    private Result<Long> settle(InstalledSlice installed, Result<Long> applied) {
        return applied.onSuccess(_ -> releaseProvenance(installed))
                      .onFailure(_ -> trimAfterFailedApply(installed));
    }

    /// Recovery: FER -- a trim that fails is logged, and the next open trims (trim at open) whatever it left.
    @Contract
    private void trimAfterFailedApply(InstalledSlice installed) {
        trimProvenance(installed).onFailure(cause -> log.warn("Stream {}[{}]: trimming provenance after a failed apply failed: {}",
                                                              installed.streamName(),
                                                              installed.partition(),
                                                              cause.message()));
    }

    /// Install a catch-up source's owner-epoch slice before its records are applied (#1596, spec #1569 §7.5.1).
    /// Production reaches it through [#applyAttributed] only.
    ///
    /// N13 first: this copy's provenance must equal the slice's over every offset it already holds up to
    /// `toOffset` -- the spec's `[base, fromOffset - 1]`, widened to the held overlap a re-verifying pull covers.
    /// On a mismatch nothing is installed or applied, [StreamError.ProvenanceMismatch] is returned, and the
    /// mismatch is recorded twice: at once, by quarantining the partition locally at the first differing offset
    /// (#1505 F2: nothing at or past it is acked or promoted here), and durably, by raising `MARKED_DIVERGED` for
    /// this copy on the partition flag ([PartitionFlags]), which survives a restart and blocks the partition
    /// cluster-wide until operator resolution (AD14).
    ///
    /// Then every slice entry starting in `[fromOffset, toOffset]` that this log does not already hold is recorded, in
    /// order; one it already holds (same key, same start) is skipped, so a re-install is idempotent. The returned
    /// [InstalledSlice] names what this install recorded and every entry of its range it relies on (#1638 F1); it
    /// stays pending until settled by [#releaseProvenance] or [#trimProvenance]. A partition that keeps no log records
    /// no provenance, and this is a no-op for it.
    public Result<InstalledSlice> installProvenance(String streamName,
                                                    int partition,
                                                    long fromOffset,
                                                    long toOffset,
                                                    List<ProvenanceEntry> slice) {
        return walFor(streamName, partition).map(wal -> installProvenance(wal,
                                                                          streamName,
                                                                          partition,
                                                                          fromOffset,
                                                                          toOffset,
                                                                          slice))
                     .or(() -> Result.success(InstalledSlice.installedSlice(streamName,
                                                                            partition,
                                                                            List.of(),
                                                                            List.of())));
    }

    private Result<InstalledSlice> installProvenance(AppendLog wal,
                                                     String streamName,
                                                     int partition,
                                                     long fromOffset,
                                                     long toOffset,
                                                     List<ProvenanceEntry> slice) {
        var sourceBase = LogProvenance.baseOf(slice);
        var source = LogProvenance.logProvenance(sourceBase, sourceBase, toOffset, slice);

        return provenanceOf(streamName, partition).flatMap(local -> refuseMismatch(streamName,
                                                                                   partition,
                                                                                   ProvenanceComparison.firstDivergence(local,
                                                                                                                        source,
                                                                                                                        0,
                                                                                                                        Math.min(local.head(),
                                                                                                                                 toOffset))))
                           .flatMap(_ -> startsIn(slice, fromOffset, toOffset))
                           .flatMap(claimed -> recordClaimed(wal, streamName, partition, claimed));
    }

    private static Result<List<AppendLog.EpochStart>> startsIn(List<ProvenanceEntry> slice,
                                                               long fromOffset,
                                                               long toOffset) {
        return Result.allOf(slice.stream()
                                 .filter(entry -> entry.startOffset() >= fromOffset && entry.startOffset() <= toOffset)
                                 .map(entry -> entry.key()
                                                    .map(key -> new AppendLog.EpochStart(key,
                                                                                         entry.startOffset())))
                                 .toList());
    }

    private Result<Unit> refuseMismatch(String streamName, int partition, Option<Long> divergence) {
        return divergence.map(offset -> quarantineMismatch(streamName, partition, offset))
                         .or(Result::unitResult);
    }

    private Result<Unit> quarantineMismatch(String streamName, int partition, long offset) {
        new PartitionQuarantine(streamName, partition).recordDivergence(offset);
        provenanceMismatchAt.merge(new PartitionRef(streamName, partition), offset, Math::min);

        return new StreamError.ProvenanceMismatch(streamName, partition, offset).result();
    }

    /// Records, in order, the `claimed` entries this log does not already hold (#1638 B1): an entry already present --
    /// same key, same start -- is skipped, so re-installing a page (a retry after a failed apply, an overlapping pull)
    /// is idempotent. The first refusal stops the install and drops what it had recorded, as a failed apply would.
    ///
    /// #1638 F1: recording and registering the install happen under the partition's [#pendingInstalls] list, as does
    /// every trim, so a trim always sees every install that has recorded or skipped an entry it might drop.
    private Result<InstalledSlice> recordClaimed(AppendLog wal,
                                                 String streamName,
                                                 int partition,
                                                 List<AppendLog.EpochStart> claimed) {
        var pending = pending(new PartitionRef(streamName, partition));

        synchronized (pending) {
            var held = wal.epochHistory();
            var recorded = new ArrayList<AppendLog.EpochStart>();
            var outcome = Result.unitResult();

            for (var entry : claimed) {
                if (outcome.isSuccess() && !held.contains(entry)) {
                    outcome = wal.recordEpochStart(entry.key(),
                                                   entry.startOffset(),
                                                   ProvenanceEntry.ORDER)
                                 .onSuccess(_ -> recorded.add(entry));
                }
            }

            var installed = InstalledSlice.installedSlice(streamName, partition, recorded, claimed);

            pending.add(installed);

            return outcome.fold(cause -> trimProvenance(installed).flatMap(_ -> cause.result()),
                                _ -> Result.success(installed));
        }
    }

    /// Settles a page that applied (#1638 F1): its install is no longer pending, so its entries no longer shield
    /// themselves from another install's trim -- a record now reaches each one it relied on.
    public Unit releaseProvenance(InstalledSlice installed) {
        var pending = pending(installed.ref());

        synchronized (pending) {
            pending.remove(installed);

            return unit();
        }
    }

    /// Settles a page whose apply failed (#1638 B1, F1): drops the entries THIS install recorded that no held record
    /// reaches, except one another pending install also relies on -- recorded or found already held. Never an entry it
    /// did not record: another catch-up's entries above the head are that catch-up's to settle. A partition that keeps
    /// no log has nothing to trim.
    public Result<Unit> trimProvenance(InstalledSlice installed) {
        var pending = pending(installed.ref());

        synchronized (pending) {
            pending.remove(installed);
            var shielded = pending.stream().flatMap(other -> other.claimed()
                                                                  .stream()).collect(Collectors.toSet());
            var droppable = installed.recorded().stream().filter(entry -> !shielded.contains(entry)).toList();

            return walFor(installed.streamName(),
                          installed.partition()).map(wal -> wal.dropEpochStartsAboveHead(nextExpectedOffset(installed.streamName(),
                                                                                                            installed.partition()) - 1,
                                                                                         droppable))
                         .or(Result::unitResult);
        }
    }

    /// The partition's pending installs, and its lock; read and changed only while holding it.
    private List<InstalledSlice> pending(PartitionRef ref) {
        return pendingInstalls.computeIfAbsent(ref, _ -> new ArrayList<>());
    }

    /// One catch-up's install of a source slice (#1638 F1): the entries it `recorded`, and every entry of its range it
    /// `claimed` -- recorded, or skipped because the log already held it. Only the recorded ones are its to trim; the
    /// claimed ones are shielded from any other install's trim while it is pending.
    public record InstalledSlice(String streamName,
                                 int partition,
                                 List<AppendLog.EpochStart> recorded,
                                 List<AppendLog.EpochStart> claimed) {
        public InstalledSlice {
            recorded = List.copyOf(recorded);
            claimed = List.copyOf(claimed);
        }

        static InstalledSlice installedSlice(String streamName,
                                             int partition,
                                             List<AppendLog.EpochStart> recorded,
                                             List<AppendLog.EpochStart> claimed) {
            return new InstalledSlice(streamName, partition, recorded, claimed);
        }

        private PartitionRef ref() {
            return new PartitionRef(streamName, partition);
        }
    }

    /// The seam every catch-up apply lands through (#1505, #1596): records are appended unattributed and fenced with
    /// the partition's current owner epoch, inside [#applyAttributed] / [#applyUnattributed], which install the
    /// source's slice first and settle it after.
    public AlignedRecovery alignedRecovery() {
        return AlignedRecovery.alignedRecovery((streamName, partition, offset, payload, timestamp) -> appendCaughtUp(streamName,
                                                                                                                     partition,
                                                                                                                     offset,
                                                                                                                     payload,
                                                                                                                     timestamp,
                                                                                                                     ownerEpochSource.currentOwnerEpoch(streamName,
                                                                                                                                                        partition)),
                                               this::applyAttributed,
                                               this::applyUnattributed);
    }

    /// Records about to be applied up to `toOffset` carry no provenance (#1596: a sealed segment without a slice).
    /// When they reach past this copy's head, `UNKNOWN(d)` -- a fresh `d`, floored at the last epoch this log records
    /// -- is recorded at the head + 1 before they are appended, and `HISTORY_INCOMPLETE` is raised for this copy:
    /// the range equals no other copy's, so any comparison over it fails closed rather than attributing the records
    /// to whichever epoch came before them. A partition without a log records nothing; records that land at or
    /// below the head are verified against what is held and need nothing. Production reaches it through
    /// [#applyUnattributed] only; the install is pending until settled, like [#installProvenance]'s.
    ///
    /// The head is read outside the partition's ordered section: a live append landing between that read and the
    /// replay's first append makes the replay's appends at those offsets a verification instead, and the unknown
    /// entry then starts above records the live batch attributed -- conservative, never permissive.
    public Result<InstalledSlice> installUnattributed(String streamName, int partition, long toOffset) {
        var start = nextExpectedOffset(streamName, partition);

        return walFor(streamName, partition).filter(_ -> toOffset >= start)
                     .map(wal -> recordUnattributed(wal, streamName, partition, start))
                     .or(() -> Result.success(InstalledSlice.installedSlice(streamName,
                                                                            partition,
                                                                            List.of(),
                                                                            List.of())));
    }

    private Result<InstalledSlice> recordUnattributed(AppendLog wal, String streamName, int partition, long start) {
        var unknown = ProvenanceEpoch.unknown(lastRecordedEpoch(streamName, partition).or(Epoch.ZERO));

        return ProvenanceEntry.provenanceEntry(unknown, start)
                              .key()
                              .flatMap(key -> recordClaimed(wal,
                                                            streamName,
                                                            partition,
                                                            List.of(new AppendLog.EpochStart(key, start))))
                              .onSuccess(_ -> raiseOnce(streamName,
                                                        partition,
                                                        PartitionRecoveryReasonKind.HISTORY_INCOMPLETE,
                                                        "records from offset " + start
                                                       + " were taken from a sealed segment that carries no owner-epoch provenance"));
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

    /// Consumer read of the local ring, bounded by the partition's VISIBLE position (#1235).
    public Result<List<OffHeapRingBuffer.RawEvent>> readLocal(String streamName,
                                                              int partition,
                                                              long fromOffset,
                                                              int maxEvents) {
        return resolvePartitionBuffer(streamName, partition).flatMap(buffer -> buffer.read(fromOffset, maxEvents));
    }

    /// Whether `nodeId` is a registered replica of `(streamName, partition)` in the SAME registry the
    /// replication manager sends to and counts acks from (#1235). Gates the appended-head catch-up read:
    /// a node outside the replica set gets a consumer read instead.
    public boolean isRegisteredReplica(String streamName, int partition, NodeId nodeId) {
        return replicationManager.registry()
                                 .replicasFor(streamName, partition)
                                 .stream()
                                 .map(ReplicaDescriptor::nodeId)
                                 .anyMatch(nodeId::equals);
    }

    /// The durable watermark of a partition this node HOLDS (placement makes it owner or replica) but has not
    /// materialized — paced or budget-deferred — so a replication-class read has no ring to answer from. The
    /// higher of the WAL head and the sealed bound, `-1` when it holds nothing durable, read without opening the
    /// log ([AppendLog.Opener#inspect]). None when the partition is materialized, is not held here, is out of
    /// range, or the log cannot be inspected: an unknown watermark is not reported as an empty one, and the
    /// caller answers `PARTITION_NOT_LOCAL` as before.
    public Option<Long> heldNotMaterializedWatermark(String streamName, int partition) {
        return option(streams.get(streamName)).filter(entry -> isHeldWithoutRing(entry, streamName, partition))
                     .flatMap(_ -> durableWatermark(streamName, partition));
    }

    /// Whether a held-but-unmaterialized partition of `streamName` is deferred for lack of off-heap budget (the same
    /// test [#materializePaced] makes first) rather than merely paced; no reshuffle slot frees such a partition.
    public boolean heldBudgetExhausted(String streamName) {
        return option(streams.get(streamName)).filter(entry -> !isSystemStream(streamName))
                     .map(entry -> availableBytes() < perPartitionFloorBytes(entry.config()))
                     .or(false);
    }

    private boolean isHeldWithoutRing(StreamEntry entry, String streamName, int partition) {
        return partition >= 0
               && partition < entry.declaredPartitions()
               && entry.ringFor(partition)
                       .isEmpty()
               && placementRoleSupplier.roleFor(streamName, partition) != Role.NONE;
    }

    /// The WAL head is read by scanning the whole log (~0.1-0.25 s and a file-sized buffer at 260 MiB, measured
    /// 2026-09-30), so an unmaterialized partition's answer is kept for [#DURABLE_WATERMARK_TTL_NANOS]: probes arrive
    /// every few seconds, and nothing writes an unmaterialized partition's log. Dropped when the ring is installed.
    private Option<Long> durableWatermark(String streamName, int partition) {
        var ref = new PartitionRef(streamName, partition);
        var now = System.nanoTime();
        var cached = durableWatermarks.get(ref);

        if (cached != null && now - cached.atNanos() < DURABLE_WATERMARK_TTL_NANOS) {
            return Option.some(cached.watermark());
        }

        var generation = watermarkGeneration.get();

        return readDurableWatermark(streamName, partition).onPresent(watermark -> cacheUnlessSuperseded(ref,
                                                                                                        new CachedWatermark(watermark,
                                                                                                                            now),
                                                                                                        generation));
    }

    /// Atomic per key against [#dropDurableWatermark]: the drop bumps the generation BEFORE removing, so a put that
    /// runs after the removal sees the bump and does nothing, and one that ran before it is removed.
    @Contract
    private void cacheUnlessSuperseded(PartitionRef ref, CachedWatermark value, long generation) {
        durableWatermarks.compute(ref,
                                  (_, current) -> watermarkGeneration.get() == generation
                                                  ? value
                                                  : current);
    }

    @Contract
    private void dropDurableWatermark(PartitionRef ref) {
        watermarkGeneration.incrementAndGet();
        durableWatermarks.remove(ref);
    }

    private record CachedWatermark(long watermark, long atNanos) {}

    private Option<Long> readDurableWatermark(String streamName, int partition) {
        return logs.fold(() -> Option.some(lastSealedOffset.lastSealedOffset(streamName, partition)),
                         opener -> walHead(opener, streamName, partition).map(head -> Math.max(head,
                                                                                               lastSealedOffset.lastSealedOffset(streamName,
                                                                                                                                 partition))));
    }

    /// The head of the WAL of the stream's CURRENT life (#1278 review): no stream entry, no WAL of its to read.
    private Option<Long> walHead(AppendLog.Opener opener, String streamName, int partition) {
        return option(streams.get(streamName)).flatMap(entry -> opener.inspect(StreamEntry.logName(entry.config(),
                                                                                                   partition))
                                                                      .map(AppendLog.LogExtent::headOffset)
                                                                      .option());
    }

    /// Replication read of the local ring, bounded by the APPENDED head (#1235): serves replica catch-up
    /// and survivor pulls, which must see events that are not yet visible, and the entity log fold, whose
    /// head is the appended head. Never a consumer path.
    public Result<List<OffHeapRingBuffer.RawEvent>> readAppended(String streamName,
                                                                 int partition,
                                                                 long fromOffset,
                                                                 int maxEvents) {
        return resolvePartitionBuffer(streamName, partition).flatMap(buffer -> buffer.readAppended(fromOffset, maxEvents));
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

    /// Cheap point-in-time per-partition WAL + retention-floor view (#634-3) — the [#hydrationSnapshot]
    /// pattern: assembled ON REQUEST from the live `streams` map, adds NO hot-path accounting. Per
    /// MATERIALIZED partition it reports the WAL's [AppendLog.WalStats] (absent on the no-WAL path),
    /// the ring tail (earliest offset still retained in memory) and the durable sealed bound — two of
    /// the three floors the #634-4 invariant spans; the third (the entity checkpoint floor) lives in
    /// consensus KV and is joined by the node-side assembler, which is also where the invariant itself
    /// is evaluated (a checker that cannot see all three floors is a lying sensor, per the ticket).
    public WalSnapshot walSnapshot() {
        return new WalSnapshot(streams.entrySet()
                                      .stream()
                                      .map(entry -> streamWalView(entry.getKey(),
                                                                  entry.getValue()))
                                      .filter(view -> !view.partitions()
                                                           .isEmpty())
                                      .toList());
    }

    private StreamWalView streamWalView(String name, StreamEntry entry) {
        var partitions = IntStream.range(0,
                                         entry.declaredPartitions())
                                  .mapToObj(partition -> partitionWalView(name, partition, entry))
                                  .flatMap(Option::stream)
                                  .toList();

        return new StreamWalView(name, partitions);
    }

    /// #1730: every partition materialized on this node with its appended head — what the owner's ISR maintenance
    /// ([org.pragmatica.aether.stream.replication.IsrMonitor]) measures replica lag against.
    public List<PartitionHead> materializedHeads() {
        return streams.entrySet()
                      .stream()
                      .flatMap(entry -> entry.getValue()
                                             .materialized()
                                             .entrySet()
                                             .stream()
                                             .map(partition -> new PartitionHead(entry.getKey(),
                                                                                 partition.getKey(),
                                                                                 partition.getValue()
                                                                                          .ring()
                                                                                          .headOffset())))
                      .toList();
    }

    public record PartitionHead(String streamName, int partition, long head) {}

    /// [Option#none] for a partition this node has not materialized — nothing local exists to report,
    /// and reporting zeros would be indistinguishable from a real empty WAL.
    private Option<PartitionWalView> partitionWalView(String streamName, int partition, StreamEntry entry) {
        return Option.option(entry.materialized().get(partition)).map(materialized -> toWalView(streamName,
                                                                                                partition,
                                                                                                materialized));
    }

    /// One materialized partition's view (extracted per the lambda-format rule). The ring tail is
    /// reported as `-1` for an EMPTY ring — review catch: `tailOffset()` is raw slot state, `0` on a
    /// ring that never held a record, which would satisfy any covered-from check and silently declare
    /// a restarted-empty partition healthy under a committed checkpoint, the exact blind spot the
    /// retention surface exists to expose. Emptiness is judged by the head: allocation seeds
    /// `headOffset = -1`, and a negative head means no record was ever appended.
    private PartitionWalView toWalView(String streamName,
                                       int partition,
                                       StreamEntry.MaterializedPartition materialized) {
        var ring = materialized.ring();
        var ringTail = ring.headOffset() < 0
                       ? -1L
                       : ring.tailOffset();

        return new PartitionWalView(partition,
                                    materialized.wal().map(AppendLog::stats),
                                    ringTail,
                                    lastSealedOffset.lastSealedOffset(streamName, partition));
    }

    /// Per-node WAL/floors view: one entry per stream with at least one materialized partition.
    public record WalSnapshot(List<StreamWalView> streams) {}

    public record StreamWalView(String stream, List<PartitionWalView> partitions) {}

    /// @param wal                  the partition WAL's counters, absent on the no-WAL (non-durable) path
    /// @param ringTailOffset       earliest offset still retained in the in-memory ring, `-1` when empty
    /// @param sealedThroughOffset  durable sealed bound (`-1` when nothing sealed) — the same value that
    ///                             drives WAL truncation, reported so the operator sees the floor the
    ///                             truncation watermark chases
    public record PartitionWalView(int partition,
                                   Option<AppendLog.WalStats> wal,
                                   long ringTailOffset,
                                   long sealedThroughOffset) {}

    /// Adapt this manager to the narrow {@link org.pragmatica.aether.stream.replication.StreamCatalog}
    /// consumed by `ReplicaSetController`. Exposes `(name, partitions, replicationFactor, confirmationFactor)` per
    /// stream — placement uses `replicationFactor`, while `confirmationFactor` (the write-
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
                                                                           config.replicationFactor(),
                                                                           config.confirmationFactor()))
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

    /// Whether `offset` of `(streamName, partition)` has been evicted and handed to the eviction listener but
    /// is not yet durably sealed (#1234) — a read of it is IN FLIGHT and succeeds once the seal lands.
    public boolean sealInFlight(String streamName, int partition, long offset) {
        return evictionListener.holdsUnsealed(streamName, partition, offset);
    }

    /// Periodically reclaim WAL disk by truncating each partition's write-ahead log up to its DURABLE
    /// last-sealed offset (streaming-persistence W5). For every live stream and each partition that has a
    /// [AppendLog], `base = durable.lastSealedOffset(stream, partition)` is computed and, when `base >= 0`,
    /// `wal.truncate(base)` discards records with `offset <= base`. Those records are already durable in cold
    /// segments (served post-restart by the tiered reader), so dropping them from the WAL loses nothing —
    /// recovery serves them from segments and the un-sealed tail (`offset > base`) stays in the WAL.
    ///
    /// `durable` is the [DurableSealedOffsetSource] view taken ONCE per tick — the watermark a restart would
    /// REBUILD, never the live index (#1345): segment refs reach disk only through the metadata snapshot, so
    /// between a seal and that snapshot the live index is ahead of what recovery will see, and a WAL compacted
    /// off the live index in that window left the survivors' refs nowhere; recovery then seeded the ring below
    /// the compaction point and appended the survivors at fresh offsets. The contiguous bound (#1234) still
    /// applies: it never passes a segment that failed to seal. Best-effort: a `truncate` failure on one
    /// partition is logged and never aborts the others; a `-1` bound (nothing durably sealed) is a no-op for
    /// that partition; the no-WAL path ([Option#none] `logs`) holds no [AppendLog] and is untouched.
    ///
    /// Block durability is not this tick's to judge (#1567): [AppendLog#truncate] never discards past the
    /// log's own seal bound, which only [org.pragmatica.storage.StorageInstance#seal] advances, after the
    /// segment's block is durable and its ref recorded. So `base` bounds truncation by the refs on disk
    /// (#1345), the log bounds it by the blocks on disk, and the effective bound is the lower of the two;
    /// after a restart the log's bound starts at `-1`, so nothing is truncated until the first seal.
    ///
    /// Reclamation is thereby coupled to the metadata snapshot: while the snapshot cannot be written or read
    /// (disk full, permissions, a torn newest file) the durable bound stops advancing and nothing is reclaimed —
    /// the WAL grows, bounded by the disk. That is the safe direction, and it is made visible here rather than
    /// only by the snapshot manager's own WARN: a partition is HELD BACK when its durable bound sits below the
    /// live watermark AND did not move since the previous tick. One such tick is a legitimate race with the
    /// snapshot interval; from the second consecutive tick on ([#HELD_BACK_GRACE_TICKS]) the tick WARNs, then
    /// again every [#HELD_BACK_WARN_EVERY] ticks, naming the partitions and their WAL bytes on disk, and
    /// [#walReclamationHeldBackTicks] counts the consecutive held-back ticks for the operator surface.
    @Contract
    public void truncateWalsToSealed() {
        var durable = durableSealedOffset.current();
        var heldBack = new ArrayList<HeldBackPartition>();

        streams.forEach((streamName, entry) -> truncateStreamWals(streamName, entry, durable, heldBack));
        reportHeldBack(heldBack);
    }

    /// Consecutive truncation ticks in which at least one partition's durable sealed bound sat below its live
    /// watermark without advancing since the previous tick (#1345): `0` while the metadata snapshot keeps up;
    /// climbing means WAL reclamation is halted because the snapshot cannot be written or read.
    public long walReclamationHeldBackTicks() {
        return walReclamationHeldBackTicks;
    }

    @Contract
    private void truncateStreamWals(String streamName,
                                    StreamEntry entry,
                                    LastSealedOffsetSource durable,
                                    List<HeldBackPartition> heldBack) {
        for (int partition = 0; partition < entry.declaredPartitions(); partition++) {
            truncatePartitionToSealed(streamName, partition, entry.walFor(partition), durable, heldBack);
        }
    }

    @Contract
    private void truncatePartitionToSealed(String streamName,
                                           int partition,
                                           Option<AppendLog> wal,
                                           LastSealedOffsetSource durable,
                                           List<HeldBackPartition> heldBack) {
        wal.onPresent(w -> truncateWalToSealed(streamName, partition, w, durable, heldBack));
    }

    @Contract
    private void truncateWalToSealed(String streamName,
                                     int partition,
                                     AppendLog wal,
                                     LastSealedOffsetSource durable,
                                     List<HeldBackPartition> heldBack) {
        var base = durable.lastSealedOffset(streamName, partition);

        noteHeldBack(streamName, partition, wal, base, heldBack);
        if (base >= 0) {
            wal.truncate(base)
               .onFailure(cause -> log.warn("WAL truncate to sealed offset {} failed for {}/{}: {}",
                                            base,
                                            streamName,
                                            partition,
                                            cause.message()));
        }
    }

    /// Held back: the live watermark is above the durable bound, and the durable bound is exactly what this
    /// partition saw at the previous tick — the snapshot has not advanced across a whole tick.
    @Contract
    private void noteHeldBack(String streamName,
                              int partition,
                              AppendLog wal,
                              long durableBase,
                              List<HeldBackPartition> heldBack) {
        var live = lastSealedOffset.lastSealedOffset(streamName, partition);
        var previous = durableAtPreviousTick.put(partitionKeyOf(streamName, partition), durableBase);

        if (live > durableBase && previous != null && previous == durableBase) {
            heldBack.add(HeldBackPartition.heldBackPartition(streamName,
                                                             partition,
                                                             durableBase,
                                                             live,
                                                             wal.stats().sizeBytes()));
        }
    }

    @Contract
    private void reportHeldBack(List<HeldBackPartition> heldBack) {
        if (heldBack.isEmpty()) {
            walReclamationHeldBackTicks = 0;

            return;
        }

        var ticks = walReclamationHeldBackTicks + 1;

        walReclamationHeldBackTicks = ticks;
        if (ticks >= HELD_BACK_GRACE_TICKS && (ticks - HELD_BACK_GRACE_TICKS) % HELD_BACK_WARN_EVERY == 0) {
            log.warn("WAL reclamation held back for {} consecutive tick(s) on {} partition(s), {} WAL bytes on disk: "
                    + "the sealed watermark on disk (metadata snapshot) is not advancing while sealing continues — "
                    + "check that the streams snapshot directory is writable and its LATEST readable. {}",
                     ticks,
                     heldBack.size(),
                     heldBack.stream().mapToLong(HeldBackPartition::walBytes).sum(),
                     heldBack.stream().limit(HELD_BACK_WARN_SAMPLE).map(HeldBackPartition::describe).toList());
        }
    }

    /// @param durable  the on-disk sealed bound the tick used
    /// @param live     the in-memory sealed watermark, above `durable`
    /// @param walBytes live bytes of the partition WAL on disk (compaction would reclaim the sealed prefix)
    private record HeldBackPartition(String streamName, int partition, long durable, long live, long walBytes) {
        static HeldBackPartition heldBackPartition(String streamName,
                                                   int partition,
                                                   long durable,
                                                   long live,
                                                   long walBytes) {
            return new HeldBackPartition(streamName, partition, durable, live, walBytes);
        }

        String describe() {
            return streamName + "/" + partition + " durable=" + durable + " live=" + live + " walBytes=" + walBytes;
        }
    }

    @Contract
    @Override
    public void close() {
        streams.values().forEach(StreamEntry::close);
        streams.clear();
        durableAtPreviousTick.clear();
        totalAllocatedBytes.set(0);
        inFlightMaterializations.clear();
        systemMaterializeQueue.clear();
        appMaterializeQueue.clear();
        queuedMaterializations.clear();
        heldUnmaterializedSince.clear();
        releaseCandidacy.clear();
        // #642: this manager owns the replication manager, and the batcher underneath it arms a
        // one-shot flush per batch (#1246) on the process-wide SharedScheduler. Nothing else called its
        // close(), so a stopped node kept flushing replication batches at its peers.
        replicationManager.close();
    }

    private Result<StreamEntry> resolveStreamEntry(String streamName) {
        return option(streams.get(streamName)).toResult(new StreamError.StreamNotFound(streamName));
    }

    /// #1278 ruling A — the ONE resolution every append uses: the owner paths ([#publishLocal] — which
    /// `publishLocalAtFloor` and `publishForwarded` reach — and [#publishLocalBatchAtFloor]) and the replica paths
    /// (every `appendRecovered` overload and `appendCaughtUp`). A replica holding a proposal that has not committed
    /// must not ack a replicated record into it, any more than an owner may accept one. The only ring writes that
    /// bypass it replay a life's own fsynced WAL while its ring is built, which accepts nothing new.
    private Result<StreamEntry> resolveWritableEntry(String streamName) {
        return resolveStreamEntry(streamName).flatMap(this::committedLife);
    }

    /// #1278 ruling A — THE accept gate: a write is accepted only into the life whose committed config this node has
    /// applied. A proposed life (materialized locally, config not yet committed and applied here) refuses with the
    /// retriable [StreamError.StreamConfigNotYetVisible] before anything reaches the ring, the WAL or a replica, so a
    /// proposal that loses holds nothing to lose. Without a cluster the create is the commit ([#latchCommitted]).
    private Result<StreamEntry> committedLife(StreamEntry entry) {
        return entry.isCommitted()
               ? success(entry)
               : new StreamError.StreamConfigNotYetVisible(entry.config().name()).result();
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
        evictionListener.onStreamDeleted(entry.config().name());
        forgetHeldBack(entry);
        forgetFootprint(entry.config().name());

        return success(unit());
    }

    /// A destroyed stream stops being served and its life's durable refs are reclaimed (#1278 review). Best-effort:
    /// the refs are keyed by the stream's incarnation, so whatever survives is garbage a recreated stream never reads.
    @Contract
    private void forgetFootprint(String streamName) {
        streamFootprint.forget(streamName)
                       .await(FOOTPRINT_TIMEOUT)
                       .onFailure(cause -> log.warn("Stream '{}' was destroyed; reclaiming its durable refs did not complete: {}",
                                                    streamName,
                                                    cause.message()));
    }

    /// Before a stream materializes: serve the life `config` names (#1278 review), so recovery anchors only at that
    /// life's sealed watermark and floor, and reclaim any other life of the name.
    private Result<Unit> adoptIncarnation(StreamConfig config) {
        return streamFootprint.adopt(config.name(),
                                     config.incarnation())
                              .await(FOOTPRINT_TIMEOUT);
    }

    /// The incarnation a fresh proposal carries (#1278, ruling C): always a newly minted one when the config is
    /// committed through a cluster — a name with an applied life never reaches here ([#createAbsentStream]). A manager
    /// without a cluster has no cluster-wide life to distinguish, so the config keeps the incarnation it was built with.
    private StreamConfig withIncarnation(StreamConfig config) {
        if (config.incarnation() != StreamConfig.NO_INCARNATION || clusterNode.isEmpty()) {
            return config;
        }

        return config.withIncarnation(mintIncarnation());
    }

    private static long mintIncarnation() {
        return java.util.concurrent.ThreadLocalRandom.current()
                                                     .nextLong(1L, Long.MAX_VALUE);
    }

    /// The held-back bookkeeping ([#noteHeldBack]) is keyed per partition; a removed stream's keys would
    /// otherwise outlive it, and a re-created stream of the same name would compare its first tick against
    /// the dead stream's bound.
    @Contract
    private void forgetHeldBack(StreamEntry entry) {
        for (int partition = 0; partition < entry.declaredPartitions(); partition++) {
            durableAtPreviousTick.remove(partitionKeyOf(entry.config().name(),
                                                        partition));
        }
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
        IntStream.range(0,
                        entry.declaredPartitions())
                 .forEach(partition -> forgetReplicatedWrites(entry.config().name(),
                                                              partition,
                                                              entry.walFor(partition)));
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
        return (long) config.partitions() * config.replicationFactor();
    }

    /// Largest declared `replication_factor` across known streams (min 1) — the `maxDeclaredReplicas` factor of the
    /// aggregate guard `100 × nodes × maxDeclaredReplicas` (spec §10).
    private int maxDeclaredReplicas() {
        return Math.max(1,
                        streams.values().stream().mapToInt(entry -> entry.config()
                                                                         .replicationFactor()).max().orElse(1));
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
                                          logs,
                                          lastSealedOffset,
                                          this::trimFailedAtOpen)
                          .onFailure(_ -> releaseFailedMaterialize(ref, floorBytes, slotHeld))
                          .onSuccess(candidate -> restoreVisible(config,
                                                                 partition,
                                                                 candidate.ring()))
                          .onSuccess(candidate -> reportInterruptedRepair(config.name(),
                                                                          partition,
                                                                          candidate.wal()))
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
        queueMaterialization(ref, system);

        return new StreamError.ReshufflePaced(ref.streamName(), ref.partition(), reshuffleConcurrency).result();
    }

    @Contract
    private void queueMaterialization(PartitionRef ref, boolean system) {
        if (queuedMaterializations.add(ref)) {
            (system
             ? systemMaterializeQueue
             : appMaterializeQueue).add(ref);
        }
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
        redriveHeldUnmaterialized();
        drainMaterializeQueue();
    }

    /// Level-triggered re-drive (#1805): every tick, EVERY partition this node currently holds (role OWNER/REPLICA)
    /// whose ring is not materialized is (re-)queued — whatever refused it before, and whether or not any edge
    /// fires again — so a lost queue entry or a role that flapped through NONE for any length of time cannot
    /// strand it. Idempotent ([#queueMaterialization] dedups) and the drain still goes through
    /// [#materializePartition], so the `reshuffle_concurrency` bound and the budget-AND apply unchanged. Cost: one
    /// role lookup per unmaterialized partition per tick (the same scan [#heldCount] does). Escalates to a WARN
    /// every [#HELD_UNMATERIALIZED_WARN_TICKS] ticks while a partition stays held but unmaterialized.
    @Contract
    private void redriveHeldUnmaterialized() {
        var tick = reconcileTick.get();

        streams.forEach((name, entry) -> redriveStream(name, entry, tick));
        heldUnmaterializedSince.keySet().removeIf(ref -> !awaitsMaterialize(ref));
    }

    @Contract
    private void redriveStream(String name, StreamEntry entry, long tick) {
        IntStream.range(0,
                        entry.declaredPartitions())
                 .filter(partition -> entry.ringFor(partition)
                                           .isEmpty())
                 .forEach(partition -> redrivePartition(new PartitionRef(name, partition),
                                                        tick));
    }

    @Contract
    private void redrivePartition(PartitionRef ref, long tick) {
        if (shouldMaterialize(ref.streamName(), ref.partition())) {
            var since = heldUnmaterializedSince.computeIfAbsent(ref, _ -> tick);

            queueIfBudgetFits(ref);
            warnIfStalled(ref, tick - since);
        } else {
            heldUnmaterializedSince.remove(ref);
        }
    }

    /// The stream exists and the partition has no ring yet.
    private boolean awaitsMaterialize(PartitionRef ref) {
        return option(streams.get(ref.streamName())).map(entry -> entry.ringFor(ref.partition())
                                                                       .isEmpty())
                     .or(false);
    }

    /// A non-system partition whose floor does not fit the off-heap budget NOW is not queued: it would sit at the
    /// app-class FIFO head, where the budget-AND ([#headCanProceed]) stops the whole class and starves every
    /// fitting partition behind it, owners included. It is re-checked next tick, so it is queued the tick budget
    /// frees. The drain keeps its head-of-line ordering unchanged (minimal change).
    @Contract
    private void queueIfBudgetFits(PartitionRef ref) {
        var system = isSystemStream(ref.streamName());

        if (headCanProceed(ref, system)) {
            queueMaterialization(ref, system);
        }
    }

    @Contract
    private void warnIfStalled(PartitionRef ref, long ticksRefused) {
        if (ticksRefused > 0 && ticksRefused % HELD_UNMATERIALIZED_WARN_TICKS == 0) {
            heldUnmaterializedWarnings.incrementAndGet();
            log.warn("Stream partition {}[{}] has been held but not materialized for {} reconcile ticks — re-queued every tick (reshuffle_concurrency={}, {} queued; waiting on a slot or off-heap budget); writes to it are refused until it materializes",
                     ref.streamName(),
                     ref.partition(),
                     ticksRefused,
                     reshuffleConcurrency,
                     queuedMaterializations.size());
        }
    }

    /// WARNs raised for held-but-unmaterialized paced partitions since boot (#1805); a test seam for the
    /// stall escalation.
    long heldUnmaterializedWarnings() {
        return heldUnmaterializedWarnings.get();
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

        return ReplicaPlacement.replicationFactor(streamClass,
                                                  config.replicationFactor(),
                                                  clusterSizeSupplier.getAsInt());
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
        forgetReplicatedWrites(ref.streamName(), ref.partition(), mp.wal());
        releaseCandidacy.remove(ref);
        freeReshuffleSlot(ref);
        dropDurableWatermark(ref);
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
        var attempts = queue.size();
        // Bounded by the entry size: a ref that is paced again is re-queued at the tail and must not be re-tried
        // within the same tick.
        while (attempts--> 0 && drainOne(queue, system)) {
            continue;
        }
    }

    /// Materialize the next drainable queued ref, if its budget-AND allows. False when nothing proceeded.
    private boolean drainOne(Deque<PartitionRef> queue, boolean system) {
        return nextDrainable(queue).filter(ref -> headCanProceed(ref, system))
                            .filter(queue::remove)
                            .onPresent(this::materializeQueued)
                            .isPresent();
    }

    /// First queued ref that needs no slot or has one free. With a free permit that is the FIFO head; with none
    /// it is the first partition that has since become OWNER — an owner materialize is never paced
    /// ([#buildAndInstall]), so it must not wait behind replica backfills for a permit it will not take (#1805).
    private Option<PartitionRef> nextDrainable(Deque<PartitionRef> queue) {
        return queue.stream()
                    .filter(this::slotAvailableFor)
                    .findFirst()
                    .map(Option::some)
                    .orElseGet(Option::none);
    }

    /// Note (#1805 R2): the OWNER clause lets an owner-role ring materialize with no permit, so a partition that
    /// materialized as OWNER and later turns REPLICA backfills uncounted against `reshuffle_concurrency` — a
    /// transient pacing excess (the same class exists at base whenever a permit is free). The off-heap budget is
    /// still enforced by `materializeNow`.
    private boolean slotAvailableFor(PartitionRef ref) {
        return reshuffleSlots.availablePermits() > 0 || placementRoleSupplier.roleFor(ref.streamName(), ref.partition()) == Role.OWNER;
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

        dropDurableWatermark(new PartitionRef(entry.config().name(),
                                              partition));
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
        /// per-partition [AppendLog]. Only OWNER/REPLICA partitions (under the current placement) are
        /// materialized; a metadata-only (non-replica) partition has NO entry in `materialized` — no ring,
        /// no reserved off-heap bytes. Rings are only ever ADDED this increment (at hydrate for held
        /// partitions, lazily via the reconcile hook / owner-append safety valve); release-on-role-loss is
        /// increment 5.
        record MaterializedPartition(OffHeapRingBuffer ring, Option<AppendLog> wal) {
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
        /// ring is paired with its per-partition WAL (`logs`, W6) and its un-sealed tail replayed at
        /// ORIGINAL offsets (W4, bounded by `lastSealedOffset`); a WAL-open/replay failure closes the built
        /// rings and propagates with its own cause. When NO partition is held (non-replica node) the entry
        /// is built with an EMPTY `materialized` map — metadata present, zero off-heap bytes reserved.
        static Result<StreamEntry> fromConfig(StreamConfig config,
                                              EvictionListener listener,
                                              LongPredicate reserve,
                                              LongConsumer release,
                                              IntPredicate shouldMaterialize,
                                              Option<AppendLog.Opener> logs,
                                              LastSealedOffsetSource lastSealedOffset,
                                              TrimFailure trimFailed) {
            var selected = selectedPartitions(config, shouldMaterialize);
            var ringResults = buildRings(config, selected, listener, reserve, release);

            return Result.allOf(ringResults)
                         .mapError(_ -> StreamError.General.STREAM_MEMORY_EXCEEDED)
                         .flatMap(rings -> openEntryWals(config, selected, rings, logs, lastSealedOffset, trimFailed))
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
                                                            Option<AppendLog.Opener> logs,
                                                            LastSealedOffsetSource lastSealedOffset,
                                                            TrimFailure trimFailed) {
            return buildRing(config, partition, listener, reserve, release).mapError(_ -> StreamError.General.STREAM_MEMORY_EXCEEDED)
                            .flatMap(ring -> openAndRecoverOne(config,
                                                               partition,
                                                               ring,
                                                               logs,
                                                               lastSealedOffset,
                                                               trimFailed));
        }

        private static Result<MaterializedPartition> openAndRecoverOne(StreamConfig config,
                                                                       int partition,
                                                                       OffHeapRingBuffer ring,
                                                                       Option<AppendLog.Opener> logs,
                                                                       LastSealedOffsetSource lastSealedOffset,
                                                                       TrimFailure trimFailed) {
            return openWal(config, partition, logs).onFailure(_ -> ring.closeWithoutRelease())
                          .flatMap(wal -> recoverOne(config, partition, ring, wal, lastSealedOffset, trimFailed));
        }

        private static Result<MaterializedPartition> recoverOne(StreamConfig config,
                                                                int partition,
                                                                OffHeapRingBuffer ring,
                                                                Option<AppendLog> wal,
                                                                LastSealedOffsetSource lastSealedOffset,
                                                                TrimFailure trimFailed) {
            return recoverPartition(config.name(),
                                    partition,
                                    ring,
                                    wal,
                                    lastSealedOffset,
                                    trimFailed).map(_ -> new MaterializedPartition(ring, wal))
                                   .onFailure(_ -> closeRingAndWal(ring, wal));
        }

        @Contract
        private static void closeRingAndWal(OffHeapRingBuffer ring, Option<AppendLog> wal) {
            ring.closeWithoutRelease();
            closeWal(wal);
        }

        private static StreamEntry entryOf(StreamConfig config,
                                           List<Integer> selected,
                                           List<OffHeapRingBuffer> rings,
                                           List<Option<AppendLog>> wals) {
            var map = new ConcurrentHashMap<Integer, MaterializedPartition>();

            for (int i = 0; i < selected.size(); i++) {
                map.put(selected.get(i),
                        new MaterializedPartition(rings.get(i), wals.get(i)));
            }

            var now = System.currentTimeMillis();

            return new StreamEntry(config, config.partitions(), map, now, new AtomicLong(now), new AtomicBoolean(false));
        }

        /// Pair the freshly-built held rings with their per-partition [AppendLog] (W6) and replay each
        /// WAL's un-sealed tail back into its ring (W4). A [Option#none] `logs` yields a
        /// selected-aligned list of [Option#none] (no WAL ⇒ unchanged behavior); a present opener opens
        /// `<stream>/<partition>` for each SELECTED partition index and recovers its tail. A
        /// WAL-open or replay failure closes the WALs already opened and propagates, leaving the caller to
        /// free the rings.
        private static Result<StreamEntry> openEntryWals(StreamConfig config,
                                                         List<Integer> selected,
                                                         List<OffHeapRingBuffer> rings,
                                                         Option<AppendLog.Opener> logs,
                                                         LastSealedOffsetSource lastSealedOffset,
                                                         TrimFailure trimFailed) {
            return openWals(config, selected, logs).flatMap(wals -> recoverWals(config,
                                                                                selected,
                                                                                rings,
                                                                                wals,
                                                                                lastSealedOffset,
                                                                                trimFailed))
                           .map(wals -> entryOf(config, selected, rings, wals));
        }

        /// Replay every partition's un-sealed WAL tail into its fresh ring (streaming-persistence W4),
        /// returning the same partition-aligned WAL list on success so the entry can be assembled. Runs
        /// ONCE at build time, before the partition is published/serving, so the ring is empty and the
        /// replayed events land at their original offsets. On any partition's replay failure the WALs are
        /// closed and the failure propagates (the rings are freed by the caller).
        private static Result<List<Option<AppendLog>>> recoverWals(StreamConfig config,
                                                                   List<Integer> selected,
                                                                   List<OffHeapRingBuffer> rings,
                                                                   List<Option<AppendLog>> wals,
                                                                   LastSealedOffsetSource lastSealedOffset,
                                                                   TrimFailure trimFailed) {
            var results = new ArrayList<Result<Unit>>(rings.size());

            for (int i = 0; i < rings.size(); i++) {
                results.add(recoverPartition(config.name(),
                                             selected.get(i),
                                             rings.get(i),
                                             wals.get(i),
                                             lastSealedOffset,
                                             trimFailed));
            }

            return Result.allOf(results)
                         .map(_ -> wals)
                         .onFailure(_ -> wals.forEach(StreamEntry::closeWal));
        }

        /// Recover one partition's ring. One rule on every path (#1441): the next offset is the highest DURABLE
        /// offset + 1, the maximum over every durable source. There are two, and the floor is read ONCE for both:
        ///   - the sealed floor `lastSealedOffset` rebuilt at boot from the refs in the streams metadata snapshot on
        ///     disk. The ring is seeded at it, so the next append is `floor + 1`;
        ///   - with a WAL, the WAL tail above that floor, placed at its stored offsets (contiguous from `floor + 1`
        ///     or refused), so the next append is `max(floor, tail end) + 1`.
        ///
        /// The snapshot runs behind the live refs (≤100 mutations / 30 s), so the floor alone is not the highest
        /// sealed offset after a crash. Each path covers that lag: with a WAL, truncation is bounded by the same
        /// on-disk floor (#1345), so the WAL still holds every record above it; without one, a seal resolves only
        /// once a snapshot holding its ref is on disk (`RefDurability`), so the floor covers
        /// every completed seal. What a no-WAL crash loses is the range above the last COMPLETED seal -- records
        /// never in the durable tier, which that mode does not promise to keep.
        ///
        /// The WAL is attached to the ring's eviction listener FIRST (#1234): replay can evict, and those
        /// hand-overs must already see the partition as WAL-backed.
        private static Result<Unit> recoverPartition(String streamName,
                                                     int partition,
                                                     OffHeapRingBuffer ring,
                                                     Option<AppendLog> wal,
                                                     LastSealedOffsetSource lastSealedOffset,
                                                     TrimFailure trimFailed) {
            var floor = lastSealedOffset.lastSealedOffset(streamName, partition);

            return wal.onPresent(ring::attachWal)
                      .map(w -> replayTail(streamName, partition, ring, w, floor).flatMap(_ -> trimAtOpen(streamName,
                                                                                                          partition,
                                                                                                          w,
                                                                                                          ring,
                                                                                                          trimFailed)))
                      .or(() -> seedRing(ring, floor));
        }

        /// #1638 S1, trim at open: once the ring holds the recovered head, drop every provenance entry above it -- one a
        /// crash left between its durable record and the first frame of its epoch, or a catch-up whose apply did not
        /// happen. Such a ghost would rank this copy by an epoch it holds no record of (`LogProvenance.SOURCE_ORDER`),
        /// and a promotion that picked it would lose the acked records above its head.
        ///
        /// #1638 F6: a trim that fails leaves the ghost in place, so the partition stays closed on this node -- detect and
        /// flag, never guess -- and the failure is reported with its own cause ([TrimFailure]).
        private static Result<Unit> trimAtOpen(String streamName,
                                               int partition,
                                               AppendLog wal,
                                               OffHeapRingBuffer ring,
                                               TrimFailure trimFailed) {
            var head = ring.headOffset();

            return wal.truncateEpochsAboveHead(head)
                      .mapError(cause -> trimFailed.trimFailed(streamName, partition, head, cause));
        }

        /// Place the WAL's un-sealed tail (records above the durable last-sealed offset `base`) at its
        /// STORED offsets (#1232), seeding the fresh ring first so reads below the tail cleanly miss and
        /// fall through to the tiered reader. A refusal is logged at ERROR here, where stream and
        /// partition are known: it leaves the stream unmaterialized on this node when the stream is being
        /// created, and this partition unbuilt on a lazy per-partition materialize.
        private static Result<Unit> replayTail(String streamName,
                                               int partition,
                                               OffHeapRingBuffer ring,
                                               AppendLog wal,
                                               long base) {
            var records = new ArrayList<WalRecord>();

            return wal.replay(-1L, records::add)
                      .flatMap(_ -> placeTail(streamName,
                                              partition,
                                              wal.path(),
                                              ring,
                                              base,
                                              sortedByOffset(streamName, partition, records)))
                      .onFailure(cause -> log.error("Stream {} is not materialized on this node: partition {} refused its WAL: {}",
                                                    streamName,
                                                    partition,
                                                    cause.message()));
        }

        /// `fileRecords` is the whole file in offset order — the records at or below `base` too (lazy
        /// truncation keeps them until compaction) — because only the file's LOWEST stored offset can tell a
        /// lost head from a hole (see [#requireHead]). The tail placed is the records above `base`.
        ///
        /// Recovery therefore holds the whole file in memory at boot, one partition at a time: the
        /// un-sealed tail plus any lazily truncated records below the floor, which compaction bounds at
        /// about its 8 MB threshold. The un-sealed tail itself is bounded only by sealing. Replay already
        /// read the whole file's bytes before #1258; this keeps the parsed records too.
        ///
        /// The tail is checked for contiguity against the seed BEFORE anything is appended (#1345): a
        /// mid-log hole or duplicate refuses the partition with nothing in the ring, so no append can evict,
        /// seal, and hand the sink segments from a recovery that is about to be refused. [#placeRecord]
        /// re-checks per record as it appends; this pass is what makes the refusal total.
        private static Result<Unit> placeTail(String streamName,
                                              int partition,
                                              Path walFile,
                                              OffHeapRingBuffer ring,
                                              long base,
                                              List<WalRecord> fileRecords) {
            var tail = recordsAbove(fileRecords, base);

            return requireHead(streamName, partition, walFile, base, fileRecords).flatMap(_ -> requireContiguous(streamName,
                                                                                                                 partition,
                                                                                                                 walFile,
                                                                                                                 base,
                                                                                                                 tail))
                              .flatMap(_ -> seedRing(ring, base))
                              .flatMap(_ -> appendTail(streamName, partition, walFile, ring, tail));
        }

        /// Every tail record must carry the offset the ring will assign it — `seed + 1, seed + 2, …`. The
        /// first mismatch is the refusal, with the same cause [#placeRecord] would raise at that record.
        private static Result<Unit> requireContiguous(String streamName,
                                                      int partition,
                                                      Path walFile,
                                                      long seed,
                                                      List<WalRecord> tail) {
            var expected = seed + 1;

            for (var record : tail) {
                if (record.offset() != expected) {
                    return new StreamError.WalReplayMismatch(streamName, partition, walFile, expected, record.offset()).result();
                }

                expected++;
            }

            return success(unit());
        }

        private static List<WalRecord> recordsAbove(List<WalRecord> fileRecords, long base) {
            return fileRecords.stream()
                              .filter(record -> record.offset() > base)
                              .toList();
        }

        /// Position the fresh ring so the next append is `seed + 1`; a no-op when `seed < 0` (nothing
        /// sealed, and the WAL starts at offset 0 or is empty).
        private static Result<Unit> seedRing(OffHeapRingBuffer ring, long seed) {
            return seed >= 0
                   ? ring.seedHead(seed)
                   : success(unit());
        }

        /// The file's LOWEST stored offset must be at most `base + 1` (#1278). `base` already counts every offset
        /// retention reclaimed: the sealed watermark is rebuilt from the persisted reclaimed-through floor
        /// ([org.pragmatica.aether.stream.segment.SegmentIndex#lastSealedOffset]), and retention makes that floor
        /// durable before it drops the refs it licenses. So a file starting above `base + 1` is not reclaimed
        /// history: the offsets between were neither sealed under a surviving ref nor reclaimed, and the WAL
        /// records that held them are gone -- a LOST head, refused with [StreamError.WalHeadLost] naming the range.
        /// It used to be accepted with a WARN (#1258), when nothing could tell it from retention.
        ///
        /// The lowest offset is the file's first record in offset order; for a file written by the fixed writer,
        /// which refuses a non-increasing offset, it is also the first physical record. The two differ only for an
        /// out-of-order file (pre-#1232, or a writer defect), and there the lowest offset is the right evidence:
        /// frames `[5, 2]` at floor 1 still hold offset 2 = floor + 1, so the missing 3 and 4 are a HOLE, refused
        /// by [#requireContiguous] as [StreamError.WalReplayMismatch].
        private static Result<Unit> requireHead(String streamName,
                                                int partition,
                                                Path walFile,
                                                long base,
                                                List<WalRecord> fileRecords) {
            return fileRecords.isEmpty() || fileRecords.getFirst()
                                                       .offset() <= base + 1
                   ? success(unit())
                   : headLost(new StreamError.WalHeadLost(streamName,
                                                          partition,
                                                          walFile,
                                                          base,
                                                          fileRecords.getFirst().offset()));
        }

        private static Result<Unit> headLost(StreamError.WalHeadLost lost) {
            if (COUNTED_HEADS_LOST.add(lost)) {
                WAL_RECOVERY_HEADS_LOST.incrementAndGet();
            }

            return lost.result();
        }

        /// Place the recovered records by their STORED offsets (#1232): sorted by offset (stable, so a
        /// duplicate stays adjacent to its twin), each must be exactly the offset the ring assigns next.
        /// A file whose frames are merely out of order — as the pre-#1232 owner path could write — is
        /// thereby recovered correctly; a gap between records or a duplicate stops the recovery with
        /// [StreamError.WalReplayMismatch] at the first mismatch. Records are NEVER renumbered: that would
        /// shift every later record against replicas, sealed segments and consumer cursors. The events
        /// are the un-sealed tail, which fits the fresh ring; the ordered append is used, so a recovered
        /// event may re-trigger the eviction→seal listener — idempotent for the tail being recovered.
        private static Result<Unit> appendTail(String streamName,
                                               int partition,
                                               Path walFile,
                                               OffHeapRingBuffer ring,
                                               List<WalRecord> records) {
            var placed = Result.unitResult();

            for (var record : records) {
                placed = placed.flatMap(_ -> placeRecord(streamName, partition, walFile, ring, record));
            }

            return placed;
        }

        /// The fixed writer refuses out-of-order offsets, so a file that needed reordering was written
        /// by pre-#1232 code or by a writer defect — WARNed, then placed by stored offset.
        private static List<WalRecord> sortedByOffset(String streamName, int partition, List<WalRecord> records) {
            var sorted = records.stream().sorted(Comparator.comparingLong(WalRecord::offset)).toList();

            if (!sorted.equals(records)) {
                log.warn("Stream {} partition {}: WAL frames were out of offset order (a pre-#1232 file or a writer"
                        + " defect); placing {} records by their stored offsets",
                         streamName,
                         partition,
                         records.size());
            }

            return sorted;
        }

        /// The record is placed DURABLE and NOT VISIBLE (#1387): it was read back from the fsynced WAL, so
        /// it is durable by construction, but nothing about its acks survived the restart. The plain
        /// `append` exposed every replayed offset at once — an owner then served records its confirmation
        /// peers never confirmed. Visibility is restored per role once the ring is installed
        /// ([StreamPartitionManager#restoreVisible]).
        private static Result<Unit> placeRecord(String streamName,
                                                int partition,
                                                Path walFile,
                                                OffHeapRingBuffer ring,
                                                WalRecord record) {
            var expected = ring.headOffset() + 1;

            return record.offset() == expected
                   ? ring.appendOrdered(record.payload(),
                                        record.timestampMillis(),
                                        Result::success)
                         .onSuccess(ring::markDurable)
                         .mapToUnit()
                   : new StreamError.WalReplayMismatch(streamName, partition, walFile, expected, record.offset()).result();
        }

        private static Result<List<Option<AppendLog>>> openWals(StreamConfig config,
                                                                List<Integer> selected,
                                                                Option<AppendLog.Opener> logs) {
            return logs.map(opener -> openSelectedWals(config, selected, opener))
                       .or(() -> success(noWals(selected.size())));
        }

        private static Result<List<Option<AppendLog>>> openSelectedWals(StreamConfig config,
                                                                        List<Integer> selected,
                                                                        AppendLog.Opener opener) {
            var results = selected.stream()
                                  .map(partition -> openPartitionWal(opener, config, partition).map(Option::some))
                                  .toList();

            return Result.allOf(results).onFailure(_ -> closeOpenedWals(results));
        }

        /// Open (or no-WAL) the [AppendLog] for a single lazily-materialized partition index. Mirrors
        /// {@link #openSelectedWals} for the one-partition materialize path ({@link #materializeOne}).
        private static Result<Option<AppendLog>> openWal(StreamConfig config,
                                                         int partition,
                                                         Option<AppendLog.Opener> logs) {
            return logs.map(opener -> openPartitionWal(opener, config, partition).map(Option::some))
                       .or(() -> success(Option.none()));
        }

        /// The log `<stream>[@<incarnation>]/<partition>`: under the storage instance's log root in production, which
        /// keeps the pre-#1567 layout `<walBaseDir>/<stream>/<partition>.wal` for a config with no incarnation.
        private static Result<AppendLog> openPartitionWal(AppendLog.Opener opener, StreamConfig config, int partition) {
            return opener.open(logName(config, partition));
        }

        /// The log of one partition of one LIFE of a stream (#1278 review), shared by the open and the read-only
        /// inspect of it: keyed by the config's incarnation, so a recreated stream never reopens the old life's WAL.
        static String logName(StreamConfig config, int partition) {
            return SegmentIndex.durableName(config.name(), config.incarnation()) + "/" + partition;
        }

        private static List<Option<AppendLog>> noWals(int count) {
            var wals = new ArrayList<Option<AppendLog>>(count);

            for (int i = 0; i < count; i++) {
                wals.add(Option.none());
            }

            return wals;
        }

        @Contract
        private static void closeOpenedWals(List<Result<Option<AppendLog>>> results) {
            results.forEach(r -> r.onSuccess(StreamEntry::closeWal));
        }

        @Contract
        private static void closeWal(Option<AppendLog> wal) {
            wal.onPresent(AppendLog::close);
        }

        /// The [AppendLog] for `partition`, or [Option#none] when the partition is metadata-only (not
        /// materialized on this node), out of range, or no WAL is configured.
        Option<AppendLog> walFor(int partition) {
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
        private static void deleteWalFile(Option<AppendLog> wal) {
            wal.onPresent(w -> w.deleteFiles()
                                .onFailure(cause -> log.warn("Failed to delete WAL files of {}: {}",
                                                             w.path(),
                                                             cause.message())));
        }
    }
}
