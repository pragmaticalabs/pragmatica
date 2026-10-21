package org.pragmatica.storage;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;


/// Bookkeeping is bounded (#1325). An observation counter lives while it keeps being touched: one idle
/// for longer than `observationWindowMs` is reclaimed, and at most `maxTrackedBlocks` blocks are
/// observed at once (a soft cap: concurrent first sightings can overshoot it by the number of racing
/// threads). Past the cap new blocks are NOT observed until idle counters are reclaimed -- a stream of
/// once-read blocks cannot grow the table, at the price of not seeing a newly hot block while the table
/// is full of fresh once-read ones. A cooldown entry lives for `cooldownMs` and is reclaimed after that
/// during normal operation, not only on deactivation. Reclaiming scans the table, so it runs from
/// [#collectHints]/[#processHints] and, when the table is full, from [#recordAccess] at most once per
/// [#RECLAIM_INTERVAL_MS].
///
/// A hint is acted on through [StorageInstance#warm], which fetches the block into the local cache tiers
/// unless a node-private tier already holds it. (It cannot be `exists` followed by `get`: `exists` on a
/// cluster-shared tier is true for every block ever written, so that pair never fetched anything.) At most
/// [PrefetchConfig#maxInFlight] prefetches run at once (an operator key, `prefetch_max_in_flight`); a hint that finds none free is dropped and counted, not queued --
/// hints go stale in seconds, and a queue would only delay the ones that are still worth acting on.
final class DefaultPrefetchManager implements PrefetchManager {
    private static final Logger log = LoggerFactory.getLogger(DefaultPrefetchManager.class);
    private static final long RECLAIM_INTERVAL_MS = 1_000L;

    private final StorageInstance storage;
    private final PrefetchConfig config;
    private final LongSupplier clockMillis;
    private final ConcurrentHashMap<String, Observation> observations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> cooldowns = new ConcurrentHashMap<>();
    private final AtomicLong lastReclaimMillis = new AtomicLong();
    private final AtomicInteger reclaimPasses = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong hintsSent = new AtomicLong();
    private final AtomicLong hintsReceived = new AtomicLong();
    private final AtomicLong started = new AtomicLong();
    private final AtomicLong warmed = new AtomicLong();
    private final AtomicLong droppedCooldown = new AtomicLong();
    private final AtomicLong droppedInFlight = new AtomicLong();
    private final AtomicLong droppedUntracked = new AtomicLong();
    private final AtomicLong droppedDormant = new AtomicLong();
    private volatile boolean active = false;

    DefaultPrefetchManager(StorageInstance storage, PrefetchConfig config) {
        this(storage, config, System::currentTimeMillis);
    }

    /// Test seam: the clock the observation window and cooldown are measured on.
    DefaultPrefetchManager(StorageInstance storage, PrefetchConfig config, LongSupplier clockMillis) {
        this.storage = storage;
        this.config = config;
        this.clockMillis = clockMillis;
    }

    private static final class Observation {
        private final AtomicInteger count = new AtomicInteger();
        private volatile long lastSeenMillis;

        int touch(long now) {
            lastSeenMillis = now;

            return count.incrementAndGet();
        }
    }

    /// An immutable reading of one counter: ranking sorts these, never the live counters.
    private record Candidate(String blockIdHex, int count) {}

    @Override
    @Contract
    public void recordAccess(BlockId blockId) {
        if (!active) {
            return;
        }

        var key = blockId.hexString();

        if (hasRoomFor(key)) {
            observations.computeIfAbsent(key, _ -> new Observation()).touch(clockMillis.getAsLong());
        } else {
            droppedUntracked.incrementAndGet();
        }
    }

    private boolean hasRoomFor(String key) {
        return observations.containsKey(key) || observations.size() < config.maxTrackedBlocks() || reclaimWhenDue();
    }

    private boolean reclaimWhenDue() {
        var now = clockMillis.getAsLong();
        var last = lastReclaimMillis.get();

        return now - last >= RECLAIM_INTERVAL_MS
               && lastReclaimMillis.compareAndSet(last, now)
               && reclaimExpired() > 0;
    }

    /// Drops idle observation counters and elapsed cooldowns; returns how many counters were dropped.
    private int reclaimExpired() {
        var now = clockMillis.getAsLong();
        var before = observations.size();

        reclaimPasses.incrementAndGet();
        observations.values().removeIf(observation -> now - observation.lastSeenMillis >= config.observationWindowMs());
        cooldowns.values().removeIf(attemptedAt -> now - attemptedAt >= config.cooldownMs());

        return before - observations.size();
    }

    @Override
    public List<PrefetchHint> collectHints() {
        if (!active) {
            return List.of();
        }

        reclaimExpired();
        var hints = selectTopHints(snapshotCandidates());

        hints.forEach(h -> observations.remove(h.blockIdHex()));
        hintsSent.addAndGet(hints.size());

        return Collections.unmodifiableList(hints);
    }

    private List<Candidate> snapshotCandidates() {
        return observations.entrySet()
                           .stream()
                           .map(entry -> new Candidate(entry.getKey(),
                                                       entry.getValue().count.get()))
                           .toList();
    }

    @Override
    @Contract
    public void processHints(List<PrefetchHint> hints) {
        if (!active) {
            droppedDormant.addAndGet(hints.size());

            return;
        }

        reclaimExpired();
        hintsReceived.addAndGet(hints.size());
        hints.stream().filter(this::aboveThreshold).filter(this::notOnCooldown).forEach(this::attemptPrefetch);
    }

    @Override
    public PrefetchStats stats() {
        return PrefetchStats.prefetchStats(active,
                                           observations.size(),
                                           hintsSent.get(),
                                           hintsReceived.get(),
                                           started.get(),
                                           warmed.get(),
                                           droppedCooldown.get(),
                                           droppedInFlight.get(),
                                           droppedUntracked.get(),
                                           droppedDormant.get());
    }

    @Override
    public Result<Unit> activate() {
        active = true;
        log.info("PrefetchManager activated for storage '{}'", storage.name());

        return Result.success(unit());
    }

    @Override
    public Result<Unit> deactivate() {
        active = false;
        observations.clear();
        cooldowns.clear();
        log.info("PrefetchManager deactivated for storage '{}'", storage.name());

        return Result.success(unit());
    }

    @Override
    public boolean isActive() {
        return active;
    }

    // --- Hint selection ---
    private List<PrefetchHint> selectTopHints(List<Candidate> snapshot) {
        return snapshot.stream()
                       .filter(candidate -> candidate.count() >= config.accessThreshold())
                       .sorted(descendingByCount())
                       .limit(config.maxHintsPerGossip())
                       .map(DefaultPrefetchManager::toHint)
                       .toList();
    }

    private static Comparator<Candidate> descendingByCount() {
        return Comparator.comparingInt(Candidate::count)
                         .reversed()
                         .thenComparing(Candidate::blockIdHex);
    }

    private static PrefetchHint toHint(Candidate candidate) {
        return PrefetchHint.prefetchHint(candidate.blockIdHex(), candidate.count());
    }

    // --- Hint processing ---
    private boolean aboveThreshold(PrefetchHint hint) {
        return hint.accessCount() >= config.accessThreshold();
    }

    private boolean notOnCooldown(PrefetchHint hint) {
        var now = clockMillis.getAsLong();
        var cooledDown = option(cooldowns.get(hint.blockIdHex())).map(lastAttempt -> (now - lastAttempt) >= config.cooldownMs())
                               .or(true);

        if (!cooledDown) {
            droppedCooldown.incrementAndGet();
        }

        return cooledDown;
    }

    private void attemptPrefetch(PrefetchHint hint) {
        BlockId.fromHex(hint.blockIdHex()).onSuccess(id -> startIfSlotFree(id, hint.blockIdHex()));
    }

    /// The slot is taken before the attempt and given back when it ends, whatever way it ends.
    private void startIfSlotFree(BlockId id, String blockIdHex) {
        if (inFlight.incrementAndGet() > config.maxInFlight()) {
            inFlight.decrementAndGet();
            droppedInFlight.incrementAndGet();

            return;
        }

        cooldowns.put(blockIdHex, clockMillis.getAsLong());
        started.incrementAndGet();
        log.debug("Prefetching block {} into the local cache tiers", id);
        storage.warm(id).withResult(result -> finishPrefetch(result, id));
    }

    /// FER: a failed prefetch is absorbed (logged at debug) because it costs only a later read of the block from the
    /// shared tier; the slot is already given back and the cooldown stays, so a dead hint is not retried every round.
    private void finishPrefetch(Result<Boolean> result, BlockId id) {
        inFlight.decrementAndGet();
        result.onSuccess(copied -> countWarmed(copied, id))
              .onFailure(cause -> log.debug("Prefetch failed for block {}: {}",
                                            id,
                                            cause.message()));
    }

    private void countWarmed(boolean copied, BlockId id) {
        if (copied) {
            warmed.incrementAndGet();
            log.debug("Prefetch complete for block {}", id);
        }
    }

    // --- Test observation ---
    int trackedBlocks() {
        return observations.size();
    }

    int reclaimPasses() {
        return reclaimPasses.get();
    }

    int cooldownEntries() {
        return cooldowns.size();
    }
}
