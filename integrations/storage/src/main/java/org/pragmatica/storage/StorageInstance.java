package org.pragmatica.storage;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Promise.resolved;
import static org.pragmatica.lang.Unit.unit;


/// Hierarchical storage instance with write-through and tier-waterfall reads.
/// Each instance has its own name, tier configuration, and metadata tracking.
public interface StorageInstance {
    /// Store content -- computes SHA-256, deduplicates, writes through tiers.
    Promise<BlockId> put(byte[] content);
    /// Store content with explicit metadata.
    Promise<BlockId> put(byte[] content, BlockMetadata metadata);
    /// Read content by block ID -- waterfall through tiers by latency.
    Promise<Option<byte[]>> get(BlockId id);
    /// Check if a block exists in any tier.
    ///
    /// A shared tier answers for the whole cluster, so `true` here does NOT mean this node holds the block:
    /// it never makes a block local. [#warm] does.
    Promise<Boolean> exists(BlockId id);

    /// Makes this node hold a copy of `id` in its own cache tiers, fetched from wherever the read
    /// waterfall finds it (in practice the cluster-shared tier). Resolves `true` when a copy was made,
    /// and `false` when nothing was to be done or nothing could be: a node-private tier already holds the
    /// block, the block is absent everywhere, or every cache tier refused it (full, or failing).
    ///
    /// Not a read: it counts no access, so a prefetch cannot make a block look popular. The copy is recorded
    /// as a cache-only marker when this node holds no record of the block ([BlockLifecycle#cacheOnly]), so
    /// demotion can see and evict it. Only cache tiers are written -- never a durable one: a warmed copy is
    /// not this node's data.
    ///
    /// The default warms nothing: an implementation without cache tiers has nowhere to put the copy.
    default Promise<Boolean> warm(BlockId id) {
        return Promise.success(false);
    }

    /// Adds a named reference to a block this instance ALREADY holds, crediting one reference for the
    /// new name. This is the aliasing primitive -- a second (third, ...) name for a block that some
    /// other reference is already keeping alive.
    ///
    /// NEVER pair it with [#put] for the same block. [#put] already credits the block it writes -- a
    /// fresh write starts at refCount 1, a deduplicating write increments an existing one -- so
    /// `put`-then-`createRef` leaves refCount 2 for ONE logical reference, and an explicit
    /// [#deleteRef] afterwards only brings it back to 1. The block never reaches zero, never reports
    /// [BlockLifecycle#isOrphaned], and [StorageGarbageCollector] can never collect it (#812). To
    /// write content and name it, use [#putRef], which credits exactly once.
    Promise<Unit> createRef(String name, BlockId id);
    /// Resolve a named reference to its block ID.
    Option<BlockId> resolveRef(String name);
    /// Delete a named reference.
    Promise<Unit> deleteRef(String name);

    /// Releases one reference credited by [#put] -- the debit for a block that carries no name of its
    /// own. [DefaultContentStore] stores chunk blocks with plain `put` and names only the manifest, so
    /// `put`'s credit is the only thing holding a chunk; this is how that credit is given back when the
    /// manifest is superseded or deleted (#981). Decrements only: at zero the block reports
    /// [BlockLifecycle#isOrphaned] and [StorageGarbageCollector] collects it through the same lifecycle
    /// record it already reads -- there is no second delete path. Never removes anything from a tier,
    /// so a block another reference still holds stays readable through it.
    ///
    /// The default releases nothing: an implementation without a lifecycle record has no credit to give
    /// back. A double that delegates to a real instance must override this too, or its chunks leak.
    default Promise<Unit> release(BlockId id) {
        return Promise.success(unit());
    }

    /// [#putRef] that also reports which block the swap DISPLACED -- the id `name` pointed at until this
    /// call, taken from the same atomic pointer swap that installs the new one, so each displaced id is
    /// handed out exactly once however many writers race on `name`. [DefaultContentStore] releases the
    /// displaced manifest's chunks from this, never from a pre-read of the name: two overwrites of one
    /// name that both pre-read the same manifest would both release its chunks, and a third name
    /// deduplicating to them would lose them (#981 round 2). Counting is [#putRef]'s: the displaced
    /// block is already decremented when this resolves.
    ///
    /// The default composes a pre-read with [#putRef] -- correct for one writer per name, NOT under
    /// contention; an implementation with an atomic swap (`DefaultStorageInstance`) overrides it, and a
    /// double that delegates to one must delegate this too or it re-opens the race.
    default Promise<RefSwap> swapRef(String name, byte[] content) {
        var displaced = resolveRef(name);

        return putRef(name, content).map(current -> RefSwap.refSwap(current, displaced));
    }

    /// [#deleteRef] that reports which block `name` pointed at, from the same atomic removal --
    /// exactly once per removal, so two deletes of one name release its manifest's chunks once. Same
    /// default caveat as [#swapRef].
    default Promise<Option<BlockId>> dropRef(String name) {
        var displaced = resolveRef(name);

        return deleteRef(name).map(_ -> displaced);
    }

    /// What a ref swap did: the block `name` now points at, and the one it displaced, if any.
    record RefSwap(BlockId current, Option<BlockId> displaced) {
        static RefSwap refSwap(BlockId current, Option<BlockId> displaced) {
            return new RefSwap(current, displaced);
        }
    }

    /// Writes (or deduplicates) `content` and points `name` at the resulting block -- the write-and-ref
    /// primitive, and the only correct way to store content under a name.
    ///
    /// Credits the block EXACTLY ONE reference, for the one named reference it creates, and releases
    /// whatever `name` previously pointed to. Both halves are load-bearing: crediting twice ([#put]
    /// followed by [#createRef]) produces a block that can never reach refCount 0 (#812), and failing
    /// to release the displaced target leaks that block's count forever. Creating and replacing are
    /// therefore the same operation -- creating replaces nothing -- so `name` never has to be known to
    /// be absent for this to be correct, and re-storing the SAME content under the same name is a
    /// no-op on the count rather than a leak.
    ///
    /// Never leaves `name` absent: it resolves to the old target or the new one at every instant,
    /// unlike [#deleteRef] followed by [#createRef] (#264).
    Promise<BlockId> putRef(String name, byte[] content);

    /// Replaces what `name` points to. The replace-oriented name for [#putRef] -- the same operation
    /// with the same counting -- kept because #737's caller (cursor commits) expresses replacement
    /// rather than creation.
    ///
    /// Routing the default through [#putRef] is what keeps it honest on every implementor: the
    /// previous default composed [#put] with [#createRef] and so carried two independent counting
    /// defects onto anything that did not override it -- it never decremented the superseded block,
    /// AND it double-counted the new one (#812).
    ///
    /// [DefaultStorageInstance]'s composite: the metadata pointer swap itself is atomic, but the new
    /// block's credit and the displaced block's decrement are only ordered, not atomic together -- a
    /// crash between them over-counts the displaced block (#737).
    default Promise<BlockId> replaceRef(String name, byte[] content) {
        return putRef(name, content);
    }

    /// Delete a block from all tiers and remove its lifecycle metadata. Used by explicit content/manifest
    /// deletion and stream retention -- never by GC, which uses [#deleteFromPrivateTiers] (#250).
    Promise<Unit> delete(BlockId id);

    /// Collect `orphan` -- the lifecycle record exactly as the caller's scan saw it -- from
    /// node-private tiers. A tier reporting [StorageTier#isShared] is skipped -- this node's local
    /// refcount belief is not authoritative for a cluster-shared tier, so orphan-driven garbage
    /// collection must never issue a delete against it. Used by [StorageGarbageCollector];
    /// a caller that legitimately needs "delete everywhere" (stream retention) must keep
    /// using [#delete] -- content/manifest deletion no longer does; it releases (#981).
    ///
    /// Resolves to `true` when the block was collected and `false` when it was NOT, because its
    /// record no longer equals `orphan` -- something (a deduplicating [#put], a read, a
    /// [#createRef]) touched it after the scan, and the scan's orphan verdict is stale (#801).
    /// Only the scanned record is a valid argument: a caller that constructs one gets `false`.
    ///
    /// Default falls back to [#delete] -- correct for any implementation with no
    /// tier-sharing concept (e.g. test doubles). Only [DefaultStorageInstance], where the
    /// real hazard exists, overrides this with tier-filtered, record-conditional deletion.
    default Promise<Boolean> deleteFromPrivateTiers(BlockLifecycle orphan) {
        return delete(orphan.blockId()).map(_ -> true);
    }

    /// The directory under which [#openLog] places this instance's append logs, when it has one. It
    /// belongs to the instance, so whatever adopts the instance's storage adopts its logs (#1569).
    default Option<Path> logRoot() {
        return none();
    }

    /// Open-or-create the append log `name` under [#logRoot], as `<logRoot>/<name>.wal` -- a stream
    /// names its partition logs `<stream>/<partition>`. Fails with [StorageError.InvalidLogName] for a
    /// name that is blank or resolves outside the root, and with [StorageError.LogsUnsupported] on an
    /// instance without a log root. See [AppendLog] for what a log is, and what it is never used for.
    default Result<AppendLog> openLog(String name) {
        return StorageError.LogsUnsupported.logsUnsupported(name()).result();
    }

    /// The names of the append logs under [#logRoot], as [#openLog] takes them. READ-ONLY (#1569 A3): it
    /// lists files and opens none, so no log is recovered and nothing on the volume changes. Empty when the
    /// root does not exist yet.
    default Result<List<String>> listLogs() {
        return StorageError.LogsUnsupported.logsUnsupported(name()).result();
    }

    /// The extent of log `name` -- lowest and highest valid offsets, valid and total bytes -- READ-ONLY
    /// ([AppendLog#inspect]; #1569 A3/A4): a torn tail is reported, never cut.
    default Result<AppendLog.LogExtent> inspectLog(String name) {
        return StorageError.LogsUnsupported.logsUnsupported(name()).result();
    }

    /// Seal offsets `[fromOffset, toOffset]` of `log`: store `block` -- the caller's encoding of that
    /// range -- under `refName`, and only then let `log` be truncated through `toOffset` (#1567).
    ///
    /// The order is the guarantee, and it is enforced here rather than by callers:
    ///   1. the block is written to every durable tier ([StorageTier#isDurable]) -- and to the instance's
    ///      last tier -- and each of those writes must succeed; for [LocalDiskTier] that is a forced file
    ///      and a forced directory. Other tiers are best-effort cache. The write policy is ignored: a seal
    ///      is never written behind. A block already stored is written to those tiers again, because its
    ///      existing record may belong to a write still in flight;
    ///   2. `refName` is pointed at the block, with [#putRef]'s counting;
    ///   3. [AppendLog#sealedThrough] advances to `toOffset`, which is what lets [AppendLog#truncate] pass it.
    /// A failure at any step leaves the log's seal bound where it was, so its records stay. "Durable" here
    /// means the local-disk tier: an in-memory DHT tier never counts (#1544), and an instance with no
    /// durable tier refuses with [StorageError.NoDurableTier]. The ref itself reaches disk only with the
    /// next metadata snapshot (#1345) -- see [AppendLog]'s class doc.
    default Promise<BlockId> seal(AppendLog log, long fromOffset, long toOffset, String refName, byte[] block) {
        return StorageError.NoDurableTier.noDurableTier(name()).promise();
    }

    /// Instance name.
    String name();
    /// Tier utilization info.
    List<TierInfo> tierInfo();

    record TierInfo(TierLevel level, long usedBytes, long maxBytes) {
        static TierInfo tierInfo(TierLevel level, long usedBytes, long maxBytes) {
            return new TierInfo(level, usedBytes, maxBytes);
        }
    }

    /// Graceful shutdown — drains pending writes (write-behind) and releases resources.
    @Contract
    void shutdown();

    /// Create a storage instance with write-through policy and in-memory metadata store.
    static StorageInstance storageInstance(String name, List<StorageTier> tiers) {
        return storageInstance(name, tiers, WritePolicy.WRITE_THROUGH);
    }

    /// Create a storage instance with specified write policy and in-memory metadata store.
    static StorageInstance storageInstance(String name, List<StorageTier> tiers, WritePolicy writePolicy) {
        return storageInstance(name, tiers, InMemoryMetadataStore.inMemoryMetadataStore(name), writePolicy);
    }

    /// Create a storage instance with a custom metadata store and write-through policy.
    static StorageInstance storageInstance(String name, List<StorageTier> tiers, MetadataStore metadataStore) {
        return storageInstance(name, tiers, metadataStore, WritePolicy.WRITE_THROUGH);
    }

    /// Create a storage instance with a custom metadata store and write policy.
    static StorageInstance storageInstance(String name,
                                           List<StorageTier> tiers,
                                           MetadataStore metadataStore,
                                           WritePolicy writePolicy) {
        return storageInstance(name, tiers, metadataStore, writePolicy, none());
    }

    /// Create a storage instance with a custom metadata store, write policy and append-log root
    /// ([#openLog]).
    static StorageInstance storageInstance(String name,
                                           List<StorageTier> tiers,
                                           MetadataStore metadataStore,
                                           WritePolicy writePolicy,
                                           Option<Path> logRoot) {
        return storageInstance(name, tiers, metadataStore, writePolicy, logRoot, AppendLog.TornTailSink.logOnly());
    }

    /// As above, with the sink every log this instance opens reports a torn tail to (#1569 A10).
    static StorageInstance storageInstance(String name,
                                           List<StorageTier> tiers,
                                           MetadataStore metadataStore,
                                           WritePolicy writePolicy,
                                           Option<Path> logRoot,
                                           AppendLog.TornTailSink tornTailSink) {
        return storageInstance(name,
                               tiers,
                               metadataStore,
                               writePolicy,
                               logRoot,
                               tornTailSink,
                               DefaultStorageInstance::ignoreAccess);
    }

    /// As above, plus `accessListener`, told of every [#get] that found its block -- after the access is
    /// recorded, once per caller (callers sharing one in-flight read are each counted). It runs on the
    /// reading thread, so it must be cheap and must not throw. [#warm] never reports to it.
    static StorageInstance storageInstance(String name,
                                           List<StorageTier> tiers,
                                           MetadataStore metadataStore,
                                           WritePolicy writePolicy,
                                           Option<Path> logRoot,
                                           AppendLog.TornTailSink tornTailSink,
                                           Consumer<BlockId> accessListener) {
        return new DefaultStorageInstance(name,
                                          tiers,
                                          metadataStore,
                                          writePolicy,
                                          logRoot,
                                          tornTailSink,
                                          accessListener,
                                          DefaultStorageInstance.CacheMarkerLimits.DEFAULT);
    }
}

final class DefaultStorageInstance implements StorageInstance {
    private static final Logger log = LoggerFactory.getLogger(DefaultStorageInstance.class);
    private static final long PROMOTION_FAILURE_WARN_EVERY = 1_000;
    private static final String LOG_SUFFIX = ".wal";

    private final String name;
    private final List<StorageTier> tiers;
    /// Tiers a write-through put must land on, in write order: every durable tier
    /// ([StorageTier#isDurable]) before the last, then the last. A failure on any of them fails the put
    /// (#1567). Before #1567 only the last tier was required -- on the `streams` instance that is the
    /// in-memory DHT tier, so a local-disk failure was absorbed as a cache miss (#910) and the block lived
    /// in memory. Durable tiers go first so that a failure of the last (shared) tier is compensated on
    /// node-private tiers only ([#undoRequiredWrites]).
    private final List<StorageTier> requiredTiers;
    /// Every other tier: best-effort cache, a failure there is absorbed (#910).
    private final List<StorageTier> cacheTiers;
    private final MetadataStore metadataStore;
    private final WritePolicy writePolicy;
    private final Option<Path> logRoot;
    private final AppendLog.TornTailSink tornTailSink;
    private final Option<WriteBehindQueue> writeBehindQueue;
    /// Non-capacity promotion failures per cache tier, for the WARN-once-then-every-N policy (#910).
    private final Map<TierLevel, AtomicLong> promotionFailures = new ConcurrentHashMap<>();
    private final SingleFlightCache readCache = SingleFlightCache.singleFlightCache();
    /// Blocks whose private-tier bytes GC is deleting right now, keyed by id, each resolving when that
    /// deletion has finished. A [#put] whose claim succeeds while its id is here has claimed the slot
    /// GC just vacated and must not write until GC's tier deletes are done, or GC deletes the bytes
    /// it has just written (#801). One collection per id at a time: the only caller is the single
    /// scheduled maintenance tick, which awaits each block in turn, so `putIfAbsent` never loses --
    /// it is there so that a second collector for the same id would wait on the first's promise
    /// rather than replace it.
    private final Map<BlockId, Promise<Unit>> collecting = new ConcurrentHashMap<>();
    /// Claimed writes in flight, keyed by id, each resolving with its claimant's outcome once the claim is
    /// finalized or released (#1567). A put of the same content registers here BEFORE it claims, so a put
    /// that finds a registration waits for that write instead of deduplicating onto a claim whose bytes may
    /// never land -- a ref, cursor or checkpoint naming it would then name nothing.
    private final Map<BlockId, Promise<Unit>> writing = new ConcurrentHashMap<>();
    private final Consumer<BlockId> accessListener;
    /// Levels of the tiers that are cluster-shared: a copy there is not this node's, so a record that places
    /// the block only on these tiers holds nothing of this node's to track.
    private final Set<TierLevel> sharedLevels;
    private final CacheMarkerLimits markerLimits;
    private final LongSupplier clockMillis;
    /// The cache-only markers in the metadata store: counted exactly on first use (a snapshot restore puts markers
    /// there before any read), then kept by every creation here, and re-counted by every [#sweepIdleMarkers]. A
    /// removal elsewhere is not seen until the next sweep, so it can only over-count, never let the table grow.
    ///
    /// A slot is RESERVED before the marker is claimed and confirmed (or given back) after, all under one lock, so
    /// racing readers cannot all pass a room check and overshoot the cap, and a recount never wipes a reservation
    /// that is still in flight: it can over-count for a moment (a marker already in the store whose claim has not
    /// been confirmed yet), never under-count.
    private final Object markerLock = new Object();
    private int cacheMarkers;
    private int pendingMarkerClaims;
    private boolean markersCounted;
    private final AtomicLong lastMarkerSweepMillis = new AtomicLong();

    /// Bounds on the cache-only markers that READS create for blocks this node does not own. A marker is
    /// created for every distinct block read from a shared tier, so without a bound the metadata store would
    /// grow with the cluster's whole working set. At `maxMarkers` no new marker is created until a sweep
    /// (at most one per `sweepIntervalMs`) has dropped the markers that hold nothing of this node's and have
    /// been idle for `idleMs`. A block read while the table is full is simply not tracked: it counts no
    /// access, exactly like the prefetch observation table at its cap.
    record CacheMarkerLimits(int maxMarkers, long idleMs, long sweepIntervalMs) {
        static final CacheMarkerLimits DEFAULT = cacheMarkerLimits(100_000, 600_000L, 5_000L);

        static CacheMarkerLimits cacheMarkerLimits(int maxMarkers, long idleMs, long sweepIntervalMs) {
            return new CacheMarkerLimits(maxMarkers, idleMs, sweepIntervalMs);
        }
    }

    DefaultStorageInstance(String name,
                           List<StorageTier> tiers,
                           MetadataStore metadataStore,
                           WritePolicy writePolicy,
                           Option<Path> logRoot,
                           AppendLog.TornTailSink tornTailSink) {
        this(name,
             tiers,
             metadataStore,
             writePolicy,
             logRoot,
             tornTailSink,
             DefaultStorageInstance::ignoreAccess,
             CacheMarkerLimits.DEFAULT);
    }

    DefaultStorageInstance(String name,
                           List<StorageTier> tiers,
                           MetadataStore metadataStore,
                           WritePolicy writePolicy,
                           Option<Path> logRoot,
                           AppendLog.TornTailSink tornTailSink,
                           Consumer<BlockId> accessListener,
                           CacheMarkerLimits markerLimits) {
        this(name,
             tiers,
             metadataStore,
             writePolicy,
             logRoot,
             tornTailSink,
             accessListener,
             markerLimits,
             System::currentTimeMillis);
    }

    /// Test seam: the clock marker idleness and sweep spacing are measured on.
    DefaultStorageInstance(String name,
                           List<StorageTier> tiers,
                           MetadataStore metadataStore,
                           WritePolicy writePolicy,
                           Option<Path> logRoot,
                           AppendLog.TornTailSink tornTailSink,
                           Consumer<BlockId> accessListener,
                           CacheMarkerLimits markerLimits,
                           LongSupplier clockMillis) {
        this.accessListener = accessListener;
        this.markerLimits = markerLimits;
        this.clockMillis = clockMillis;
        this.name = name;
        this.tiers = List.copyOf(tiers);
        this.requiredTiers = requiredTiersOf(this.tiers);
        this.cacheTiers = this.tiers.stream().filter(tier -> !requiredTiers.contains(tier)).toList();
        this.sharedLevels = sharedLevelsOf(this.tiers);
        this.metadataStore = metadataStore;
        this.writePolicy = writePolicy;
        this.logRoot = logRoot;
        this.tornTailSink = tornTailSink;
        this.writeBehindQueue = writePolicy == WritePolicy.WRITE_BEHIND
                                ? some(WriteBehindQueue.writeBehindQueue())
                                : none();
        writeBehindQueue.onPresent(WriteBehindQueue::activate);
        log.info("Storage instance '{}' created with {} tier(s), policy={}", name, tiers.size(), writePolicy);
    }

    @Override
    public Promise<BlockId> put(byte[] content) {
        return put(content, BlockMetadata.blockMetadata(content.length));
    }

    @Override
    public Promise<BlockId> put(byte[] content, BlockMetadata metadata) {
        return BlockId.blockId(content)
                      .async()
                      .flatMap(id -> handlePut(id, content));
    }

    @Override
    public Promise<Option<byte[]>> get(BlockId id) {
        return readCache.deduplicate(id,
                                     () -> waterfallRead(id))
                        .withSuccess(opt -> opt.onPresent(_ -> recordAccess(id)));
    }

    @Override
    public Promise<Boolean> exists(BlockId id) {
        return holdsOwnedRecord(id)
               ? Promise.success(true)
               : checkTiersForExistence(id, 0, _ -> true);
    }

    /// "Exists" means readable. A cache-only marker records that the block was read here once; the shared copy it
    /// pointed at may be gone, so a marker does not make the block exist -- the tiers are asked.
    private boolean holdsOwnedRecord(BlockId id) {
        return metadataStore.getLifecycle(id)
                            .map(lc -> !lc.cacheOnly())
                            .or(false);
    }

    /// Fetches the block through the read waterfall only when no node-private tier holds it, and copies it
    /// into the cache tiers. The waterfall read is the one [#get] shares (single flight, integrity verified),
    /// but no access is recorded: this is this node acting on a hint, not a reader asking for the block.
    @Override
    public Promise<Boolean> warm(BlockId id) {
        return checkTiersForExistence(id, 0, tier -> !tier.isShared()).flatMap(held -> fetchUnlessHeld(held, id));
    }

    private Promise<Boolean> fetchUnlessHeld(boolean held, BlockId id) {
        return held || cacheTiers.isEmpty() || !roomToTrack(id)
               ? Promise.success(false)
               : readCache.deduplicate(id,
                                       () -> waterfallRead(id))
                          .flatMap(opt -> copyIfFound(id, opt));
    }

    /// A prefetch is refused, not half done, when the copy could not be tracked: a record already exists, or the
    /// marker table has room for one.
    private boolean roomToTrack(BlockId id) {
        return metadataStore.containsBlock(id) || hasRoomForMarker();
    }

    private Promise<Boolean> copyIfFound(BlockId id, Option<byte[]> found) {
        return found.fold(() -> Promise.success(false), content -> copyToCacheTiers(id, content));
    }

    /// The record comes first: a copy with no record is invisible to demotion (#886). The read that fetched
    /// the block normally created it; if it did not, a counted marker is claimed here and given back unless a
    /// cache tier accepted the copy, so a failed warm leaves no empty marker of its own. When there is neither a
    /// record nor a slot for one (another reader took the last slot since the room check), no copy is made.
    private Promise<Boolean> copyToCacheTiers(BlockId id, byte[] content) {
        var created = claimWarmMarker(id);

        return created.isPresent() || metadataStore.containsBlock(id)
               ? promoteToCacheTiers(id, content).map(_ -> settleWarm(id, created))
               : Promise.success(false);
    }

    private Option<BlockLifecycle> claimWarmMarker(BlockId id) {
        var marker = BlockLifecycle.cacheOnlyLifecycle(id, EnumSet.noneOf(TierLevel.class));

        return metadataStore.containsBlock(id) || !reserveMarkerSlot()
               ? Option.none()
               : claimReservedMarker(id, marker);
    }

    private Option<BlockLifecycle> claimReservedMarker(BlockId id, BlockLifecycle marker) {
        if (metadataStore.claimBlock(id, marker)) {
            confirmMarkerSlot();

            return Option.some(marker);
        }

        giveBackMarkerSlot();

        return Option.none();
    }

    private boolean settleWarm(BlockId id, Option<BlockLifecycle> created) {
        var held = holdsPrivateCopy(id);

        if (!held) {
            created.onPresent(marker -> dropUnusedMarker(id, marker));
        }

        return held;
    }

    private void dropUnusedMarker(BlockId id, BlockLifecycle marker) {
        if (metadataStore.releaseClaim(id, marker)) {
            forgetMarker();
        }
    }

    private boolean holdsPrivateCopy(BlockId id) {
        return metadataStore.getLifecycle(id)
                            .map(lc -> lc.presentIn()
                                         .stream()
                                         .anyMatch(level -> !sharedLevels.contains(level)))
                            .or(false);
    }

    @Override
    public Promise<Unit> createRef(String refName, BlockId id) {
        return metadataStore.computeLifecycle(id, BlockLifecycle::withRefCountIncrementedIfOwned)
                            .filter(BlockLifecycle::cacheOnly)
                            .isPresent()
               ? StorageError.CacheOnlyBlock.cacheOnlyBlock(id).promise()
               : Promise.success(nameBlock(refName, id));
    }

    private Unit nameBlock(String refName, BlockId id) {
        metadataStore.putRef(refName, id);

        return unit();
    }

    @Override
    public Option<BlockId> resolveRef(String refName) {
        return metadataStore.resolveRef(refName);
    }

    @Override
    public Promise<Unit> deleteRef(String refName) {
        return dropRef(refName).mapToUnit();
    }

    @Override
    public Promise<Option<BlockId>> dropRef(String refName) {
        var displaced = metadataStore.removeRef(refName);

        displaced.onPresent(id -> metadataStore.computeLifecycle(id, BlockLifecycle::withRefCountDecremented));

        return Promise.success(displaced);
    }

    @Override
    public Promise<Unit> release(BlockId id) {
        metadataStore.computeLifecycle(id, BlockLifecycle::withRefCountDecremented);

        return Promise.success(unit());
    }

    @Override
    public Promise<BlockId> putRef(String refName, byte[] content) {
        return swapRef(refName, content).map(RefSwap::current);
    }

    @Override
    public Promise<RefSwap> swapRef(String refName, byte[] content) {
        return BlockId.blockId(content)
                      .async()
                      .flatMap(id -> handlePut(id, content))
                      .map(id -> repointRef(refName, id));
    }

    @Override
    public Promise<Unit> delete(BlockId id) {
        return deleteFromAllTiers(id, 0).withSuccess(_ -> removeLifecycleMetadata(id, "deleted from all tiers"));
    }

    /// #801: the scan's verdict is only good until something touches the record, so the record is
    /// taken FIRST, by compare-and-remove against the scanned value -- a deduplicating put, a read
    /// or a ref that landed after the scan has changed it, the remove fails and nothing is deleted.
    /// Once the record is gone no put can deduplicate onto this block (its claim succeeds instead);
    /// `collecting` makes that claimant wait for the tier deletes so they cannot wipe its fresh
    /// write. A tier delete that fails puts the scanned record back (if no claimant has taken the
    /// slot) so the next cycle retries it, as it did when the record was removed last -- and does so
    /// INSIDE the resolution of the failed delete, before the claimant is released and before the
    /// returned promise resolves, never as an `onFailure` side effect (those run on the executor,
    /// after the caller has already seen the result).
    @Override
    public Promise<Boolean> deleteFromPrivateTiers(BlockLifecycle orphan) {
        var id = orphan.blockId();
        var done = Promise.<Unit> promise();

        collecting.putIfAbsent(id, done);
        if (!metadataStore.releaseClaim(id, orphan)) {
            finishCollecting(id, done);
            log.debug("Block {} touched since the GC scan, not collected", id);

            return Promise.success(false);
        }

        return deleteFromPrivateTiers(id, 0).fold(result -> collected(orphan, done, result));
    }

    private Promise<Boolean> collected(BlockLifecycle orphan, Promise<Unit> done, Result<Unit> result) {
        var id = orphan.blockId();

        result.onFailure(_ -> metadataStore.claimBlock(id, orphan));
        finishCollecting(id, done);
        result.onSuccess(_ -> log.debug("Block {} deleted from private tiers; shared copy retained", id));

        return resolved(result.map(_ -> true));
    }

    private void finishCollecting(BlockId id, Promise<Unit> done) {
        collecting.remove(id, done);
        done.succeed(unit());
    }

    @Override
    @Contract
    public void shutdown() {
        writeBehindQueue.onPresent(WriteBehindQueue::deactivate);
        log.info("Storage instance '{}' shut down", name);
    }

    @Override
    public Option<Path> logRoot() {
        return logRoot;
    }

    @Override
    public Result<AppendLog> openLog(String logName) {
        return logRoot.toResult(StorageError.LogsUnsupported.logsUnsupported(name))
                      .flatMap(root -> logFile(root, logName))
                      .flatMap(file -> AppendLog.open(file, tornTailSink));
    }

    @Override
    public Result<List<String>> listLogs() {
        return logRoot.toResult(StorageError.LogsUnsupported.logsUnsupported(name))
                      .flatMap(DefaultStorageInstance::logNamesUnder);
    }

    @Override
    public Result<AppendLog.LogExtent> inspectLog(String logName) {
        return logRoot.toResult(StorageError.LogsUnsupported.logsUnsupported(name))
                      .flatMap(root -> logFile(root, logName))
                      .flatMap(AppendLog::inspect);
    }

    /// Steps 1-3 of the interface doc, as a data dependency: the ref is repointed only in a continuation
    /// of the durable write, and the log's bound moves only in a continuation of the repoint.
    @Override
    public Promise<BlockId> seal(AppendLog log, long fromOffset, long toOffset, String refName, byte[] block) {
        return checkSealable(fromOffset, toOffset).async()
                            .flatMap(_ -> storeDurably(block))
                            .map(id -> repointRef(refName, id).current())
                            .map(id -> markSealed(log, toOffset, id));
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<TierInfo> tierInfo() {
        return tiers.stream()
                    .map(DefaultStorageInstance::toTierInfo)
                    .toList();
    }

    // --- Write flow ---
    private Promise<BlockId> handlePut(BlockId id, byte[] content) {
        return claimOrAwait(id,
                            content,
                            sentinelFor(id),
                            this::writeThroughTiers,
                            this::deduplicateBlock,
                            this::handlePut);
    }

    /// #1567: one writer per id at a time. The caller registers its own promise in [#writing] before it
    /// claims. If another write is registered, this put waits for it and then goes round again (`again`):
    /// after a successful write its claim fails and it deduplicates onto the finished block; after a failed
    /// write the claim was released, so it claims and writes the block itself. Retrying rather than failing
    /// is deliberate: this put holds the content, and a first writer's failure (a full tier, a flaky disk)
    /// says nothing about whether this write can land -- if it cannot, this put fails on its own attempt.
    private Promise<BlockId> claimOrAwait(BlockId id,
                                          byte[] content,
                                          BlockLifecycle sentinel,
                                          Fn2<Promise<BlockId>, BlockId, byte[]> write,
                                          Fn2<Promise<BlockId>, BlockId, byte[]> deduplicate,
                                          Fn2<Promise<BlockId>, BlockId, byte[]> again) {
        var mine = Promise.<Unit> promise();

        return option(writing.putIfAbsent(id, mine)).fold(() -> claimAndWrite(id,
                                                                              content,
                                                                              sentinel,
                                                                              mine,
                                                                              write,
                                                                              deduplicate),
                                                          inFlight -> inFlight.fold(_ -> again.apply(id, content)));
    }

    /// No registered writer: either this put claims the id and writes it, or the block is already complete
    /// (every claimant registers first, so a claim that fails here is never someone's in-flight write).
    private Promise<BlockId> claimAndWrite(BlockId id,
                                           byte[] content,
                                           BlockLifecycle sentinel,
                                           Promise<Unit> mine,
                                           Fn2<Promise<BlockId>, BlockId, byte[]> write,
                                           Fn2<Promise<BlockId>, BlockId, byte[]> deduplicate) {
        if (!metadataStore.claimBlock(id, sentinel)) {
            finishWriting(id, mine, Result.unitResult());

            return deduplicate.apply(id, content);
        }

        return afterCollection(id).flatMap(_ -> write.apply(id, content))
                              .fold(result -> claimantDone(id, sentinel, mine, result));
    }

    /// The claim is released BEFORE the waiters are resumed, as a dependent step rather than an `onFailure`
    /// callback, so a waiter going round again finds the id free and claims it itself.
    private Promise<BlockId> claimantDone(BlockId id,
                                          BlockLifecycle sentinel,
                                          Promise<Unit> mine,
                                          Result<BlockId> result) {
        return result.fold(_ -> releaseOwnClaim(id),
                           _ -> Promise.success(unit()))
                     .flatMap(_ -> finishedWriting(id, mine, result));
    }

    private Promise<BlockId> finishedWriting(BlockId id, Promise<Unit> mine, Result<BlockId> result) {
        finishWriting(id, mine, result.mapToUnit());

        return resolved(result);
    }

    /// Released by id, not by comparison with the sentinel (#1602 B1): a read of the id during the write can
    /// add presence or an access to the claim record, and a compare-and-remove would then miss and leave a
    /// record claiming a block no tier holds -- which a retry deduplicates onto and "succeeds". The record is
    /// this writer's own: every other put of the id waits in [#writing] and adds nothing to it.
    ///
    /// A cache copy the failed put found already there (the claim replaced the cache-only marker that tracked it)
    /// is not the failed write's to lose: the record is put back as a marker over the cache tiers that still hold
    /// the block, or the copy would sit in memory where demotion cannot see it (#886). BER: the inverse of the
    /// claim's replacement.
    private Promise<Unit> releaseOwnClaim(BlockId id) {
        return checkTiersForExistence(id, 0, DefaultStorageInstance::isPrivateCacheTier).map(held -> settleReleasedClaim(id,
                                                                                                                         held));
    }

    private static boolean isPrivateCacheTier(StorageTier tier) {
        return ! tier.isShared() && !tier.isDurable();
    }

    private Unit settleReleasedClaim(BlockId id, boolean cacheCopyHeld) {
        if (cacheCopyHeld) {
            metadataStore.computeLifecycle(id, lc -> BlockLifecycle.cacheOnlyLifecycle(id, cacheLevelsIn(lc)));
        } else {
            metadataStore.removeLifecycle(id);
        }

        return unit();
    }

    private Set<TierLevel> cacheLevelsIn(BlockLifecycle record) {
        var cacheLevels = tiers.stream().filter(tier -> !tier.isDurable()).map(StorageTier::level).toList();

        return record.presentIn()
                     .stream()
                     .filter(cacheLevels::contains)
                     .collect(Collectors.toCollection(() -> EnumSet.noneOf(TierLevel.class)));
    }

    private void finishWriting(BlockId id, Promise<Unit> mine, Result<Unit> outcome) {
        writing.remove(id, mine);
        mine.resolve(outcome);
    }

    /// #801: a claim that succeeded because GC has just compare-and-removed this id's orphan record
    /// must let GC's in-flight tier deletes finish before writing. Registered before the remove, so
    /// a claimant that observed the removal observes the registration too.
    private Promise<Unit> afterCollection(BlockId id) {
        return option(collecting.get(id)).or(Promise.success(unit()));
    }

    /// Points `refName` at `newId`, decrementing whatever it previously pointed to. `newId`'s own
    /// reference count was already accounted for by [#handlePut] -- a fresh block starts at refCount
    /// 1, deduplication already incremented an existing one -- so this only has to release the
    /// superseded target. The metadata pointer swap itself is atomic; the credit (already applied by
    /// [#handlePut], before this call) and the decrement (applied here, after the swap) are only
    /// ordered, not atomic together -- a crash between the swap and the decrement over-counts the
    /// displaced block (never decremented, stays live). The swap-then-decrement order is still load-
    /// bearing for a different hazard: a concurrent GC scan can never observe a floor-clamped
    /// transient zero on a block that is, at that same instant, still genuinely live (#737).
    private RefSwap repointRef(String refName, BlockId newId) {
        var displaced = metadataStore.replaceRef(refName, newId);

        displaced.onPresent(oldId -> metadataStore.computeLifecycle(oldId, BlockLifecycle::withRefCountDecremented));

        return RefSwap.refSwap(newId, displaced);
    }

    /// The claim IS the block's lifecycle record -- there is no second one. It names the tier the
    /// active policy writes first, and [#trackNewBlock] finalizes it in place once the write lands.
    private BlockLifecycle sentinelFor(BlockId id) {
        return BlockLifecycle.blockLifecycle(id, initialTier());
    }

    private TierLevel initialTier() {
        return writePolicy == WritePolicy.WRITE_BEHIND
               ? tiers.getFirst()
                      .level()
               : tiers.getLast()
                      .level();
    }

    /// The increment is the claim on an existing block, and it only counts if it LANDED: an empty
    /// result means the record vanished between the failed claim and this call -- GC compare-and-
    /// removed it (#801) -- so the id must not be handed out and the put goes round again, where its
    /// claim now succeeds and [#afterCollection] orders its write behind GC's tier deletes.
    private Promise<BlockId> deduplicateBlock(BlockId id, byte[] content) {
        return creditOwnedBlock(id).fold(() -> handlePut(id, content),
                                         lc -> Promise.success(deduplicated(lc.blockId())));
    }

    /// Credits a reference to the block's record, but only to a record this node owns. A cache-only marker
    /// is no duplicate to deduplicate onto -- it records that the block was READ here, never that it was
    /// written to this node's required tiers -- so it is left as it is and reported as empty, which sends
    /// the put round again to claim the id (a claim replaces a marker, see [MetadataStore#claimBlock]).
    private Option<BlockLifecycle> creditOwnedBlock(BlockId id) {
        return metadataStore.computeLifecycle(id, BlockLifecycle::withRefCountIncrementedIfOwned)
                            .filter(lc -> !lc.cacheOnly());
    }

    private static BlockId deduplicated(BlockId id) {
        log.debug("Block {} already stored, incremented refCount", id);

        return id;
    }

    private Promise<BlockId> writeThroughTiers(BlockId id, byte[] content) {
        return writePolicy == WritePolicy.WRITE_BEHIND
               ? writeBehindToTiers(id, content)
               : writeToAllTiers(id, content);
    }

    /// The claimant's write: a required tier that fails after earlier ones succeeded undoes those first
    /// ([#undoRequiredWrites]), while the claim is still held.
    private Promise<BlockId> writeToAllTiers(BlockId id, byte[] content) {
        var lastLevel = tiers.getLast().level();

        return writeRequiredTiers(id,
                                  content,
                                  0,
                                  List.of()).fold(result -> undoOnFailure(id, result))
                                 .map(_ -> recordRequiredPresence(id))
                                 .flatMap(_ -> promoteToCacheTiers(id, content))
                                 .map(_ -> trackNewBlock(id, lastLevel));
    }

    /// Sequential and fail-fast: a required tier that fails ends the write with a [RequiredTierFailed]
    /// listing the tiers on which THIS write created the copy. Presence is recorded only once every
    /// required tier holds the block ([#recordRequiredPresence]), so a failed write leaves no record saying
    /// a tier has it (#1602 B1).
    private Promise<Unit> writeRequiredTiers(BlockId id, byte[] content, int index, List<StorageTier> created) {
        if (index >= requiredTiers.size()) {
            return Promise.success(unit());
        }

        var tier = requiredTiers.get(index);

        return createdByThisWrite(tier, id).flatMap(fresh -> putRequired(tier, id, content, created, fresh))
                                 .flatMap(next -> writeRequiredTiers(id, content, index + 1, next));
    }

    private static Promise<List<StorageTier>> putRequired(StorageTier tier,
                                                          BlockId id,
                                                          byte[] content,
                                                          List<StorageTier> created,
                                                          boolean fresh) {
        return tier.put(id, content)
                   .mapError(cause -> RequiredTierFailed.requiredTierFailed(created, cause))
                   .map(_ -> withCreated(created, tier, fresh));
    }

    private static List<StorageTier> withCreated(List<StorageTier> created, StorageTier tier, boolean fresh) {
        return fresh
               ? Stream.concat(created.stream(),
                               Stream.of(tier))
                       .toList()
               : created;
    }

    /// Whether this write creates the tier's copy -- only such a copy may be undone (#1602 B2): a copy that
    /// was there before (written after the last metadata snapshot, say, and named by a KV checkpoint) is
    /// not this write's to delete. A shared tier is never undone, so it is not asked (its `exists` is a
    /// network round trip). An `exists` that fails counts as "was there": FER toward keeping a copy this
    /// write cannot prove it made.
    private static Promise<Boolean> createdByThisWrite(StorageTier tier, BlockId id) {
        return tier.isShared()
               ? Promise.success(false)
               : tier.exists(id)
                     .map(existed -> !existed)
                     .recover(_ -> false);
    }

    /// A required write that failed, and the tiers on which it had already CREATED the block. Internal to
    /// the write path: the caller sees `origin`.
    record RequiredTierFailed(List<StorageTier> created, Cause origin, String message) implements Cause {
        static RequiredTierFailed requiredTierFailed(List<StorageTier> created, Cause origin) {
            return new RequiredTierFailed(created, origin, origin.message());
        }
    }

    private static Cause originOf(Cause cause) {
        return cause instanceof RequiredTierFailed failed
               ? failed.origin()
               : cause;
    }

    /// #910's orphan, not reintroduced: a copy this write created on a required tier before a later required
    /// tier failed would sit there with no record once the claim is released, and GC -- driven by records --
    /// never collects it. So those copies, and only those (#1602 B2), are deleted first, as a dependent step,
    /// before the failure (its original cause) reaches the caller and before the claim is released.
    /// BER, best effort: a failed delete is logged at WARN and absorbed -- the orphan it leaves is the
    /// pre-fix outcome, and failing the put for it would change nothing the caller can act on. A shared
    /// tier is never undone: another node's copy of the same content-addressed block may live there.
    private Promise<Unit> undoOnFailure(BlockId id, Result<Unit> result) {
        return result.fold(cause -> undoRequiredWrites(id, cause), _ -> Promise.success(unit()));
    }

    private Promise<Unit> undoRequiredWrites(BlockId id, Cause cause) {
        var created = cause instanceof RequiredTierFailed failed
                      ? failed.created()
                      : List.<StorageTier> of();

        return deleteCreated(id, created, created.size() - 1).flatMap(_ -> originOf(cause).<Unit> promise());
    }

    private Promise<Unit> deleteCreated(BlockId id, List<StorageTier> created, int index) {
        if (index < 0) {
            return Promise.success(unit());
        }

        var tier = created.get(index);

        return tier.delete(id)
                   .recover(cause -> undoFailed(tier, id, cause))
                   .flatMap(_ -> deleteCreated(id, created, index - 1));
    }

    private static Unit undoFailed(StorageTier tier, BlockId id, Cause cause) {
        log.warn("Could not remove block {} from tier {} after a later required tier failed; it stays there "
                + "unreferenced and garbage collection will not find it: {}",
                 id,
                 tier.level(),
                 cause.message());

        return unit();
    }

    /// Every required tier now holds the block: record that on the block's record. The last tier's presence
    /// is the claim record itself ([#trackNewBlock]); the others are added to it.
    private Unit recordRequiredPresence(BlockId id) {
        requiredTiers.stream()
                     .filter(tier -> tier != tiers.getLast())
                     .forEach(tier -> recordTierPresence(id,
                                                         tier.level()));

        return unit();
    }

    private static List<StorageTier> requiredTiersOf(List<StorageTier> tiers) {
        var last = tiers.getLast();
        var durableBefore = tiers.stream().filter(tier -> tier != last && tier.isDurable());

        return Stream.concat(durableBefore,
                             Stream.of(last))
                     .toList();
    }

    private Promise<BlockId> writeBehindToTiers(BlockId id, byte[] content) {
        var fastTier = tiers.getFirst();

        return fastTier.put(id, content)
                       .flatMap(_ -> enqueueRemainingTiers(id, content, fastTier))
                       .map(_ -> trackNewBlock(id,
                                               fastTier.level()));
    }

    private Promise<Unit> enqueueRemainingTiers(BlockId id, byte[] content, StorageTier fastTier) {
        var remaining = tiers.stream().filter(t -> t != fastTier).toList();

        return writeBehindQueue.fold(() -> Promise.success(unit()),
                                     queue -> enqueueNextTier(queue, id, content, remaining, 0));
    }

    private Promise<Unit> enqueueNextTier(WriteBehindQueue queue,
                                          BlockId id,
                                          byte[] content,
                                          List<StorageTier> remaining,
                                          int index) {
        if (index >= remaining.size()) {
            return Promise.success(unit());
        }

        return queue.enqueue(id,
                             content,
                             remaining.get(index))
                    .flatMap(_ -> enqueueNextTier(queue, id, content, remaining, index + 1));
    }

    private Promise<Unit> promoteToCacheTiers(BlockId id, byte[] content) {
        if (cacheTiers.isEmpty()) {
            return Promise.success(unit());
        }

        return promoteToNextCacheTier(id, content, cacheTiers, 0);
    }

    private Promise<Unit> promoteToNextCacheTier(BlockId id, byte[] content, List<StorageTier> cacheTiers, int index) {
        if (index >= cacheTiers.size()) {
            return Promise.success(unit());
        }

        var tier = cacheTiers.get(index);
        // The durable write has already succeeded by the time a cache tier is asked; a cache-tier
        // failure is recovered, not propagated, or the caller is told its durably stored data
        // failed (#910). The old chain logged "skipped" and then flatMapped the failure through.
        return tier.put(id, content)
                   .withSuccess(_ -> recordTierPresence(id,
                                                        tier.level()))
                   .fold(result -> result.fold(cause -> discardFailedPromotion(tier, id, cause),
                                               Promise::success))
                   .flatMap(_ -> promoteToNextCacheTier(id, content, cacheTiers, index + 1));
    }

    /// A cache tier that failed MID-WRITE can hold a truncated copy, and the read waterfall stops
    /// at the first tier that returns bytes — the corrupt copy would then fail every read with
    /// `IntegrityError` while the durable copy sits unreachable behind it (review of #1095, B-1,
    /// reproduced on a real `LocalDiskTier` under ENOSPC). So the failed promotion is followed by a
    /// best-effort `delete` on that tier, itself absorbed, before the chain moves on. A tier that
    /// refused BEFORE writing (`TierFull`) wrote nothing to discard, and the id may already hold a
    /// valid copy there from an earlier promotion — deleting it would evict a good cache entry on
    /// every re-promotion against a full tier (r3, a).
    private Promise<Unit> discardFailedPromotion(StorageTier tier, BlockId id, Cause cause) {
        logPromotionFailure(tier, id, cause);
        if (cause instanceof StorageError.TierFull) {
            return Promise.success(unit());
        }

        return tier.delete(id)
                   .recover(deleteCause -> discardFailed(tier, id, deleteCause));
    }

    private static Unit discardFailed(StorageTier tier, BlockId id, Cause cause) {
        log.warn("Cache tier {} could not discard the failed write of {}; a partial copy may remain and reads of it will fail their integrity check: {}",
                 tier.level(),
                 id,
                 cause.message());

        return unit();
    }

    /// `TierFull` is steady state on a hot tier and logs at DEBUG (a per-put WARN there is the
    /// #718 flood). Any OTHER cause means the tier is not working: the FIRST such failure per
    /// tier logs at WARN, then every `PROMOTION_FAILURE_WARN_EVERY`th with the running count, the
    /// rest at DEBUG — a dead cache tier is visible at INFO without flooding it (review of #1095,
    /// SF-1).
    private void logPromotionFailure(StorageTier tier, BlockId id, Cause cause) {
        if (cause instanceof StorageError.TierFull) {
            log.debug("Cache promotion to {} skipped for {}: tier full ({})", tier.level(), id, cause.message());

            return;
        }

        var failures = promotionFailures.computeIfAbsent(tier.level(), _ -> new AtomicLong()).incrementAndGet();

        if (failures == 1 || failures % PROMOTION_FAILURE_WARN_EVERY == 0) {
            log.warn("Cache promotion to {} FAILED for {} (failure #{} on this tier; the block is durable and reads fall through to the durable tier; further failures at DEBUG, next WARN at #{}): {}",
                     tier.level(),
                     id,
                     failures,
                     failures + PROMOTION_FAILURE_WARN_EVERY - failures % PROMOTION_FAILURE_WARN_EVERY,
                     cause.message());
        } else {
            log.debug("Cache promotion to {} failed for {} (failure #{}): {}",
                      tier.level(),
                      id,
                      failures,
                      cause.message());
        }
    }

    /// Finalizes the record that [#handlePut]'s claim already created -- an UPDATE, never a re-create.
    /// On the write-through path the cache-tier promotions have by now accumulated their presence
    /// onto that record via [#recordTierPresence]; the previous unconditional `createLifecycle`
    /// (a plain `put`) overwrote it with a durable-only record, so a block that was written and
    /// never read sat physically in the memory tier while `listBlocksByTier(MEMORY)` could not
    /// see it, and demotion was blind to it however far over its watermark the tier was (#886).
    /// The add is idempotent by construction (the claim names the same tier); the fallback keeps
    /// the pre-existing post-condition that a successful put always leaves a record, for the one
    /// case where the claim can vanish mid-write (a metadata restore that clears the map).
    private BlockId trackNewBlock(BlockId id, TierLevel initialTier) {
        metadataStore.computeLifecycle(id,
                                       lc -> lc.withTierAdded(initialTier))
                     .onEmpty(() -> metadataStore.createLifecycle(BlockLifecycle.blockLifecycle(id, initialTier)));
        log.debug("Block {} stored in tier {}", id, initialTier);

        return id;
    }

    // --- Log / seal flow ---
    private static Result<List<String>> logNamesUnder(Path root) {
        return FileOps.exists(root)
               ? FileOps.walk(root, DefaultStorageInstance::isLogFile).map(files -> logNames(root, files))
               : Result.success(List.of());
    }

    private static boolean isLogFile(Path path) {
        return FileOps.isRegularFile(path) && path.getFileName()
                                                  .toString()
                                                  .endsWith(LOG_SUFFIX);
    }

    private static List<String> logNames(Path root, List<Path> files) {
        return files.stream()
                    .map(file -> root.relativize(file)
                                     .toString())
                    .map(relative -> relative.substring(0,
                                                        relative.length() - LOG_SUFFIX.length()))
                    .sorted()
                    .toList();
    }

    /// `name` resolves strictly under `root`: relative, and never climbing out of it with `..`.
    private static Result<Path> logFile(Path root, String logName) {
        var invalid = StorageError.InvalidLogName.invalidLogName(logName);

        return Result.lift(_ -> invalid,
                           () -> root.resolve(logName + LOG_SUFFIX))
                     .filter(invalid,
                             file -> isStrictlyUnder(root, file, logName));
    }

    private static boolean isStrictlyUnder(Path root, Path file, String logName) {
        var normalizedRoot = root.toAbsolutePath().normalize();
        var normalizedFile = file.toAbsolutePath().normalize();

        return ! logName.isBlank()
               && !Path.of(logName).isAbsolute()
               && normalizedFile.startsWith(normalizedRoot)
               && !normalizedFile.equals(normalizedRoot);
    }

    private Result<Unit> checkSealable(long fromOffset, long toOffset) {
        if (fromOffset < 0 || toOffset < fromOffset) {
            return StorageError.InvalidSealRange.invalidSealRange(fromOffset, toOffset).result();
        }

        return tiers.stream()
                    .anyMatch(StorageTier::isDurable)
               ? Result.unitResult()
               : StorageError.NoDurableTier.noDurableTier(name).result();
    }

    /// A write-through put whatever the instance's policy. A block this instance already holds is
    /// credited and then written to the required tiers again: it may have been written behind
    /// ([WritePolicy#WRITE_BEHIND]) or before its durable tier existed, and a seal must not name a block
    /// it has not itself seen become durable. An in-flight write of the same block is waited for first
    /// ([#claimOrAwait]).
    private Promise<BlockId> storeDurably(byte[] content) {
        return BlockId.blockId(content)
                      .async()
                      .flatMap(id -> handleDurablePut(id, content));
    }

    private Promise<BlockId> handleDurablePut(BlockId id, byte[] content) {
        var sentinel = BlockLifecycle.blockLifecycle(id,
                                                     tiers.getLast().level());

        return claimOrAwait(id,
                            content,
                            sentinel,
                            this::writeToAllTiers,
                            this::rewriteDeduplicated,
                            this::handleDurablePut);
    }

    /// The credit is taken first so GC cannot collect the block under the rewrite (#801); if the rewrite
    /// fails the credit is given back as a dependent step, before the caller sees the failure -- BER:
    /// the increment's inverse restores the count, and no ref names the block. An empty increment means
    /// GC removed the record meanwhile; the put goes round again as a fresh claim, as [#deduplicateBlock]
    /// does.
    private Promise<BlockId> rewriteDeduplicated(BlockId id, byte[] content) {
        return creditOwnedBlock(id).fold(() -> handleDurablePut(id, content), _ -> rewriteRequiredTiers(id, content));
    }

    private Promise<BlockId> rewriteRequiredTiers(BlockId id, byte[] content) {
        return writeRequiredTiers(id,
                                  content,
                                  0,
                                  List.of()).mapError(DefaultStorageInstance::originOf)
                                 .withFailure(_ -> giveBackCredit(id))
                                 .map(_ -> recordRequiredPresence(id))
                                 .map(_ -> id);
    }

    private void giveBackCredit(BlockId id) {
        metadataStore.computeLifecycle(id, BlockLifecycle::withRefCountDecremented);
    }

    private static BlockId markSealed(AppendLog log, long toOffset, BlockId id) {
        log.markSealed(toOffset);

        return id;
    }

    // --- Read flow ---
    private Promise<Option<byte[]>> waterfallRead(BlockId id) {
        return waterfallReadFromTier(id, 0);
    }

    private Promise<Option<byte[]>> waterfallReadFromTier(BlockId id, int tierIndex) {
        if (tierIndex >= tiers.size()) {
            return Promise.success(none());
        }

        var tier = tiers.get(tierIndex);

        return tier.get(id)
                   .flatMap(opt -> handleTierReadResult(opt, id, tierIndex, tier));
    }

    private Promise<Option<byte[]>> handleTierReadResult(Option<byte[]> opt,
                                                         BlockId id,
                                                         int tierIndex,
                                                         StorageTier tier) {
        return opt.fold(() -> waterfallReadFromTier(id, tierIndex + 1),
                        content -> verifyAndReturn(id, content, tier));
    }

    private Promise<Option<byte[]>> verifyAndReturn(BlockId id, byte[] content, StorageTier tier) {
        return BlockId.blockId(content)
                      .async()
                      .flatMap(computedId -> completeVerification(computedId, id, content, tier));
    }

    private Promise<Option<byte[]>> completeVerification(BlockId computedId,
                                                         BlockId expectedId,
                                                         byte[] content,
                                                         StorageTier tier) {
        if (!computedId.equals(expectedId)) {
            log.warn("Integrity check failed in tier {} for block {}", tier.level(), expectedId);

            return StorageError.IntegrityError.integrityError(expectedId, computedId).promise();
        }

        recordReadPresence(expectedId, tier);

        return Promise.success(some(content));
    }

    // --- Existence check ---
    private Promise<Boolean> checkTiersForExistence(BlockId id, int tierIndex, Predicate<StorageTier> inScope) {
        if (tierIndex >= tiers.size()) {
            return Promise.success(false);
        }

        return existsInScope(tiers.get(tierIndex), id, inScope).flatMap(found -> foundOrNext(found,
                                                                                             id,
                                                                                             tierIndex,
                                                                                             inScope));
    }

    private static Promise<Boolean> existsInScope(StorageTier tier, BlockId id, Predicate<StorageTier> inScope) {
        return inScope.test(tier)
               ? tier.exists(id)
               : Promise.success(false);
    }

    private Promise<Boolean> foundOrNext(boolean found, BlockId id, int tierIndex, Predicate<StorageTier> inScope) {
        return found
               ? Promise.success(true)
               : checkTiersForExistence(id, tierIndex + 1, inScope);
    }

    // --- Lifecycle helpers ---
    private void recordAccess(BlockId id) {
        metadataStore.computeLifecycle(id, BlockLifecycle::withAccessTimestamp);
        accessListener.accept(id);
    }

    @Contract
    static void ignoreAccess(BlockId id) {}

    private static Set<TierLevel> sharedLevelsOf(List<StorageTier> tiers) {
        var shared = EnumSet.noneOf(TierLevel.class);

        tiers.stream().filter(StorageTier::isShared).map(StorageTier::level).forEach(shared::add);

        return shared;
    }

    /// A read found `id` in `tier`. A node with a record just adds the presence. A node with none -- it did
    /// not write the block -- gets a cache-only marker, so its accesses are counted (promotion reads them)
    /// and any copy it keeps is visible to demotion. Two cases are not a cache and get none: the copy it just
    /// read is on a DURABLE tier (the block is this node's own data whose record was lost, and calling it a
    /// cache would let demotion evict the only durable copy), or the instance has no shared tier (nothing else
    /// holds the block, so this node's copy is the block).
    private void recordReadPresence(BlockId id, StorageTier tier) {
        metadataStore.computeLifecycle(id,
                                       lc -> lc.withTierAdded(tier.level()))
                     .onEmpty(() -> trackReadOnlyBlock(id, tier));
    }

    private void trackReadOnlyBlock(BlockId id, StorageTier tier) {
        if (!sharedLevels.isEmpty() && !tier.isDurable() && reserveMarkerSlot()) {
            createMarker(id, tier.level());
        }
    }

    /// The slot is already reserved ([#reserveMarkerSlot]). The claim loses to a writer or another reader that got
    /// there first; the slot is then given back and the presence is added to whatever record that left.
    private void createMarker(BlockId id, TierLevel level) {
        if (metadataStore.claimBlock(id,
                                     BlockLifecycle.cacheOnlyLifecycle(id, EnumSet.of(level)))) {
            confirmMarkerSlot();
        } else {
            giveBackMarkerSlot();
            recordTierPresence(id, level);
        }
    }

    /// Takes one marker slot atomically, so racing readers cannot all pass a room check and overshoot the cap.
    /// The caller must make the marker or give the slot back.
    private boolean reserveMarkerSlot() {
        countMarkersOnFirstUse();

        return tryReserveSlot() || sweepIdleMarkers() && tryReserveSlot();
    }

    private boolean tryReserveSlot() {
        synchronized (markerLock) {
            var hasRoom = cacheMarkers + pendingMarkerClaims < markerLimits.maxMarkers();

            if (hasRoom) {
                pendingMarkerClaims++;
            }

            return hasRoom;
        }
    }

    private void confirmMarkerSlot() {
        synchronized (markerLock) {
            pendingMarkerClaims--;
            cacheMarkers++;
        }
    }

    private void giveBackMarkerSlot() {
        synchronized (markerLock) {
            pendingMarkerClaims--;
        }
    }

    private void forgetMarker() {
        synchronized (markerLock) {
            cacheMarkers--;
        }
    }

    private boolean hasRoomForMarker() {
        countMarkersOnFirstUse();

        return markersInUse() < markerLimits.maxMarkers() || sweepIdleMarkers();
    }

    private int markersInUse() {
        synchronized (markerLock) {
            return cacheMarkers + pendingMarkerClaims;
        }
    }

    private void countMarkersOnFirstUse() {
        synchronized (markerLock) {
            if (!markersCounted) {
                markersCounted = true;
                cacheMarkers = (int) metadataStore.listAllLifecycles()
                                                  .stream()
                                                  .filter(BlockLifecycle::cacheOnly)
                                                  .count();
            }
        }
    }

    /// At most one sweep per interval; true when it left room for another marker.
    private boolean sweepIdleMarkers() {
        var now = clockMillis.getAsLong();
        var last = lastMarkerSweepMillis.get();

        return now - last >= markerLimits.sweepIntervalMs()
               && lastMarkerSweepMillis.compareAndSet(last, now)
               && reclaimIdleMarkers(now) < markerLimits.maxMarkers();
    }

    /// Drops the markers that track nothing of this node's -- every tier in the record is shared -- and have
    /// not been touched for the idle window, by compare-and-remove against the record as scanned (a read or
    /// put that landed since wins). Re-counts the markers left, which also corrects the drift in
    /// [#cacheMarkers]; returns that count.
    private int reclaimIdleMarkers(long now) {
        synchronized (markerLock) {
            var markers = metadataStore.listAllLifecycles().stream().filter(BlockLifecycle::cacheOnly).toList();
            var reclaimed = markers.stream()
                                   .filter(marker -> isIdleSharedOnly(marker, now))
                                   .filter(marker -> metadataStore.releaseClaim(marker.blockId(),
                                                                                marker))
                                   .count();
            var remaining = markers.size() - (int) reclaimed;

            cacheMarkers = remaining;
            log.debug("Cache marker sweep on '{}': {} reclaimed, {} left", name, reclaimed, remaining);

            return remaining + pendingMarkerClaims;
        }
    }

    private boolean isIdleSharedOnly(BlockLifecycle marker, long now) {
        return now - marker.lastAccessedAt() >= markerLimits.idleMs() && sharedLevels.containsAll(marker.presentIn());
    }

    private void recordTierPresence(BlockId id, TierLevel tier) {
        metadataStore.computeLifecycle(id, lc -> lc.withTierAdded(tier));
    }

    private static TierInfo toTierInfo(StorageTier tier) {
        return TierInfo.tierInfo(tier.level(), tier.usedBytes(), tier.maxBytes());
    }

    // --- Delete flow ---
    private Promise<Unit> deleteFromAllTiers(BlockId id, int tierIndex) {
        if (tierIndex >= tiers.size()) {
            return Promise.success(unit());
        }

        return tiers.get(tierIndex)
                    .delete(id)
                    .flatMap(_ -> deleteFromAllTiers(id, tierIndex + 1));
    }

    private Promise<Unit> deleteFromPrivateTiers(BlockId id, int tierIndex) {
        if (tierIndex >= tiers.size()) {
            return Promise.success(unit());
        }

        var tier = tiers.get(tierIndex);

        return (tier.isShared()
                ? Promise.<Unit> success(unit())
                : tier.delete(id)).flatMap(_ -> deleteFromPrivateTiers(id, tierIndex + 1));
    }

    /// #250 review: `delete` and `deleteFromPrivateTiers` diverge in what actually happened to the
    /// shared (DHT) tier -- one message for both hid that a "private tiers" deletion leaves the
    /// cluster-shared copy alive, which reads as data loss it is not.
    private void removeLifecycleMetadata(BlockId id, String outcome) {
        metadataStore.removeLifecycle(id);
        log.debug("Block {} {}", id, outcome);
    }
}
