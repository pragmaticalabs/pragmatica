package org.pragmatica.storage;

import java.util.List;

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

