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

import static org.pragmatica.lang.Option.option;

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

        return option(cooldowns.get(hint.blockIdHex()))
            .map(lastAttempt -> (now - lastAttempt) >= config.cooldownMs())
            .or(true);
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
