package org.pragmatica.storage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Manages cross-node block prefetching via SWIM piggyback hints.
///
/// Nodes record local block access frequencies. When gossip rounds occur,
/// the manager exports top-N hints (blocks accessed above threshold).
/// When receiving hints from other nodes, the manager triggers background
/// prefetch for blocks not present locally.
///
/// Supports dormant/active lifecycle: prefetching is a background optimization
/// that should only run when the node is ready.
public interface PrefetchManager {

    /// Record a local block access (called on every get hit).
    void recordAccess(BlockId blockId);

    /// Collect hints to piggyback on SWIM gossip. Resets access counters for collected hints.
    List<PrefetchHint> collectHints();

    /// Process hints received from another node. Triggers background prefetch for missing blocks.
    void processHints(List<PrefetchHint> hints);

    /// Activate the prefetch manager, allowing hint collection and prefetch.
    void activate();

    /// Deactivate the prefetch manager. All operations become no-ops.
    void deactivate();

    /// Whether the prefetch manager is currently active.
    boolean isActive();

    /// Create a prefetch manager for the given storage instance and configuration.
    static PrefetchManager prefetchManager(StorageInstance storage, PrefetchConfig config) {
        return new DefaultPrefetchManager(storage, config);
    }

    /// No-op implementation for when prefetching is disabled.
    PrefetchManager NOOP = new NoOpPrefetchManager();
}

final class NoOpPrefetchManager implements PrefetchManager {

    @Override
    public void recordAccess(BlockId blockId) {
        // no-op
    }

    @Override
    public List<PrefetchHint> collectHints() {
        return List.of();
    }

    @Override
    public void processHints(List<PrefetchHint> hints) {
        // no-op
    }

    @Override
    public void activate() {
        // no-op
    }

    @Override
    public void deactivate() {
        // no-op
    }

    @Override
    public boolean isActive() {
        return false;
    }
}

final class DefaultPrefetchManager implements PrefetchManager {
    private static final Logger log = LoggerFactory.getLogger(DefaultPrefetchManager.class);

    private final StorageInstance storage;
    private final PrefetchConfig config;
    private final ConcurrentHashMap<String, AtomicInteger> accessCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> cooldowns = new ConcurrentHashMap<>();
    private volatile boolean active = false;

    DefaultPrefetchManager(StorageInstance storage, PrefetchConfig config) {
        this.storage = storage;
        this.config = config;
    }

    @Override
    public void recordAccess(BlockId blockId) {
        if (!active) {
            return;
        }

        accessCounts.computeIfAbsent(blockId.hexString(), _ -> new AtomicInteger())
                    .incrementAndGet();
    }

    @Override
    public List<PrefetchHint> collectHints() {
        if (!active) {
            return List.of();
        }

        var snapshot = new ArrayList<>(accessCounts.entrySet());
        var hints = selectTopHints(snapshot);

        hints.forEach(h -> accessCounts.remove(h.blockIdHex()));

        return Collections.unmodifiableList(hints);
    }

    @Override
    public void processHints(List<PrefetchHint> hints) {
        if (!active) {
            return;
        }

        hints.stream()
             .filter(this::aboveThreshold)
             .filter(this::notOnCooldown)
             .forEach(this::attemptPrefetch);
    }

    @Override
    public void activate() {
        active = true;
        log.info("PrefetchManager activated for storage '{}'", storage.name());
    }

    @Override
    public void deactivate() {
        active = false;
        accessCounts.clear();
        cooldowns.clear();
        log.info("PrefetchManager deactivated for storage '{}'", storage.name());
    }

    @Override
    public boolean isActive() {
        return active;
    }

    // --- Hint selection ---

    private List<PrefetchHint> selectTopHints(List<Map.Entry<String, AtomicInteger>> snapshot) {
        return snapshot.stream()
                       .filter(e -> e.getValue().get() >= config.accessThreshold())
                       .sorted(descendingByCount())
                       .limit(config.maxHintsPerGossip())
                       .map(DefaultPrefetchManager::toHint)
                       .toList();
    }

    private static Comparator<Map.Entry<String, AtomicInteger>> descendingByCount() {
        return Comparator.<Map.Entry<String, AtomicInteger>>comparingInt(e -> e.getValue().get())
                         .reversed();
    }

    private static PrefetchHint toHint(Map.Entry<String, AtomicInteger> entry) {
        return PrefetchHint.prefetchHint(entry.getKey(), entry.getValue().get(), TierLevel.MEMORY.name());
    }

    // --- Hint processing ---

    private boolean aboveThreshold(PrefetchHint hint) {
        return hint.accessCount() >= config.accessThreshold();
    }

    private boolean notOnCooldown(PrefetchHint hint) {
        var now = System.currentTimeMillis();
        var lastAttempt = cooldowns.get(hint.blockIdHex());

        return lastAttempt == null || (now - lastAttempt) >= config.cooldownMs();
    }

    private void attemptPrefetch(PrefetchHint hint) {
        BlockId.fromHex(hint.blockIdHex())
               .onSuccess(id -> checkAndFetch(id, hint.blockIdHex()));
    }

    private void checkAndFetch(BlockId id, String blockIdHex) {
        storage.exists(id)
               .onSuccess(exists -> initiateIfMissing(exists, id, blockIdHex));
    }

    private void initiateIfMissing(boolean exists, BlockId id, String blockIdHex) {
        if (exists) {
            return;
        }

        cooldowns.put(blockIdHex, System.currentTimeMillis());
        log.debug("Prefetching block {} from remote tier", id);

        storage.get(id)
               .onSuccess(opt -> opt.onPresent(_ -> log.debug("Prefetch complete for block {}", id)))
               .onFailure(cause -> log.debug("Prefetch failed for block {}: {}", id, cause.message()));
    }
}
