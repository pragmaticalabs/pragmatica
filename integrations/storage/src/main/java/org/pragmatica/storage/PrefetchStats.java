package org.pragmatica.storage;

/// What a [PrefetchManager] has done since it was created.
///
/// @param active whether the manager is currently active
/// @param tracked blocks with a live observation counter right now
/// @param hintsSent hints handed out by [PrefetchManager#collectHints] (for gossip to one peer)
/// @param hintsReceived hints given to [PrefetchManager#processHints], before any filtering
/// @param started prefetches started: a hint that passed every filter and got a slot
/// @param warmed prefetches that made a copy in a local cache tier
/// @param droppedCooldown hints ignored because the block was attempted less than `cooldownMs` ago
/// @param droppedInFlight hints ignored because the in-flight cap was reached (never queued: hints go stale quickly)
/// @param droppedUntracked accesses not observed because the observation table was full
/// @param droppedDormant hints that arrived while the manager was dormant (the node not yet ready, or stopping)
public record PrefetchStats(boolean active,
                            int tracked,
                            long hintsSent,
                            long hintsReceived,
                            long started,
                            long warmed,
                            long droppedCooldown,
                            long droppedInFlight,
                            long droppedUntracked,
                            long droppedDormant) {
    public static PrefetchStats prefetchStats(boolean active,
                                              int tracked,
                                              long hintsSent,
                                              long hintsReceived,
                                              long started,
                                              long warmed,
                                              long droppedCooldown,
                                              long droppedInFlight,
                                              long droppedUntracked,
                                              long droppedDormant) {
        return new PrefetchStats(active,
                                 tracked,
                                 hintsSent,
                                 hintsReceived,
                                 started,
                                 warmed,
                                 droppedCooldown,
                                 droppedInFlight,
                                 droppedUntracked,
                                 droppedDormant);
    }

    /// A manager that does nothing: inactive, with nothing counted.
    public static PrefetchStats empty() {
        return new PrefetchStats(false, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
