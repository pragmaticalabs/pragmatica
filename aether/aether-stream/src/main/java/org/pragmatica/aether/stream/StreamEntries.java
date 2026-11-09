// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.StreamPartitionManager.StreamEntry;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.option;


/// #2201 A-05 (spec 5.1, ruling Q4): the stream registry a [StreamPartitionManager] owns, for the manager's lifetime. Rows 1-3
/// of the spec table (the entries, the applied configs, the deferred repair reports), the two sets that track OTHER lives of a
/// name and so must outlive any one of them (ruling Q3), and each stream's [PartitionState], keyed by stream name. Not the
/// public slice-api `StreamRegistry`, which this does not touch.
final class StreamEntries {
    private final ConcurrentHashMap<String, StreamEntry> streams = new ConcurrentHashMap<>();
    /// #1564 (R8): the config of every `StreamConfigKey` Put this node has APPLIED, by stream name -- the committed value
    /// exactly as KV holds it, independent of whether (or with which factors) a local entry exists. A local entry can lag or
    /// differ: a native-OOM hydrate leaves none, and the adoption rule keeps a stronger local config over a weaker committed one.
    private final ConcurrentHashMap<String, StreamConfig> appliedConfigs = new ConcurrentHashMap<>();
    /// Reports of repairs a previous process left unsettled that were found before the operator-warning sink was wired; made
    /// when it is.
    private final CopyOnWriteArrayList<Runnable> deferredReports = new CopyOnWriteArrayList<>();
    /// The WAL directories already reported as orphaned by this process: one event per directory, however many streams adopt
    /// after it.
    private final Set<String> orphanedWalReported = ConcurrentHashMap.newKeySet();
    /// The lives announced as released: the startup snapshot still lists them, and must not announce them again.
    private final Set<String> releasedOrphans = ConcurrentHashMap.newKeySet();
    /// #2952: the lives (`<stream>@<incarnation>`) this process proposed or adopted, so far. Their WAL directories are its own,
    /// never an orphan, though the startup snapshot, taken off the KV dispatcher, can list one created while it ran.
    private final Set<String> ownLives = ConcurrentHashMap.newKeySet();
    /// #2952: the other-lives reports of the adoptions made so far, in adoption order, waiting for the one drainer that runs them
    /// off the adopting (KV dispatcher) thread.
    private final ConcurrentLinkedQueue<Runnable> lifeReports = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, PartitionState> partitionStates = new ConcurrentHashMap<>();

    private StreamEntries() {}

    static StreamEntries streamEntries() {
        return new StreamEntries();
    }

    ConcurrentHashMap<String, StreamEntry> streams() {
        return streams;
    }

    ConcurrentHashMap<String, StreamConfig> appliedConfigs() {
        return appliedConfigs;
    }

    CopyOnWriteArrayList<Runnable> deferredReports() {
        return deferredReports;
    }

    Set<String> orphanedWalReported() {
        return orphanedWalReported;
    }

    Set<String> releasedOrphans() {
        return releasedOrphans;
    }

    Set<String> ownLives() {
        return ownLives;
    }

    ConcurrentLinkedQueue<Runnable> lifeReports() {
        return lifeReports;
    }

    /// The incarnation of the name's live life on this node: the local entry's, else the applied committed config's (know
    /// `f7bc44d170`). None when the name has no life here.
    Option<Long> liveIncarnation(String streamName) {
        return option(streams.get(streamName)).map(entry -> entry.config()
                                                                 .incarnation())
                     .orElse(() -> option(appliedConfigs.get(streamName)).map(StreamConfig::incarnation));
    }

    /// The per-partition state of the name's live life, for a writer that names no life (the entry-less writes, and every
    /// writer that reaches the state by name). A name with no life on this node gets a dead bucket: a throwaway state,
    /// never stored, so the write is dropped.
    PartitionState livePartitionState(String streamName) {
        return liveIncarnation(streamName).map(incarnation -> partitionState(streamName, incarnation))
                              .or(() -> deadBucket(StreamConfig.NO_INCARNATION));
    }

    /// The per-partition state of the life `incarnation`, created on first use: what a writer reaches (know `f7bc44d170`).
    /// A state stamped with `incarnation` is kept. An absent state, or one stamped with another incarnation, is replaced
    /// only when `incarnation` is the live one ([#liveIncarnation]) and the stored state belongs to no life this node holds
    /// (neither the local entry's nor the applied config's). Otherwise the writer gets a dead bucket, never stored. The
    /// replace is a compare-and-set against the state read, and liveness is read between the two, so a writer whose life
    /// ended meanwhile, or whose name was re-created meanwhile, cannot replace the new life's state: the set fails, and the
    /// retry finds the life no longer live. The compare-and-set and its retry are pinned by `StreamEntriesFirstWriteRaceTest`
    /// (a `put` in place of the set, or a lost set that does not retry, loses writes); `StreamEntriesTest` pins each liveness
    /// check on its own. [unverified: no test ends or re-creates a life between the liveness read and the set]
    /// No lambda here calls into `streams` (the lock order is streams-bin, then
    /// partitionStates-bin, never the reverse).
    PartitionState partitionState(String streamName, long incarnation) {
        var stored = option(partitionStates.get(streamName));

        return stored.filter(state -> state.incarnation() == incarnation)
                     .or(() -> replacedOrDead(streamName, stored, incarnation));
    }

    /// The per-partition state a hydrate of the life `incarnation` records into, while the hydrate builds that life's entry:
    /// inside `streams.compute` for the name (where `streams.get` still answers the replaced entry, or none), or before a
    /// fresh create puts its entry. The life being installed is the live one there, so this is [#partitionState] with the
    /// live incarnation taken from the hydrated config (know `f7bc44d170` item 3): with an applied committed config, only
    /// that life's hydrate installs; with none (a fresh create), the hydrate installs unless the stored state is the local
    /// entry's (a concurrent create that won the put). Otherwise a dead bucket.
    PartitionState hydratingPartitionState(String streamName, long incarnation) {
        var stored = option(partitionStates.get(streamName));

        return stored.filter(state -> state.incarnation() == incarnation)
                     .or(() -> hydratedOrDead(streamName, stored, incarnation));
    }

    /// The stored state of the name's live life, if any: what a reader reaches, so a lookup never creates state. A state
    /// stamped with another incarnation (an ended life's, or a life still being hydrated) reads as absent. While the name
    /// has no life here, the stored state is the one a removal still in progress has not purged yet, and its own readers
    /// keep reaching it until the purge (a replica's fsync barrier racing the release, `syncReplicated`): no later life can
    /// read it, because a later life's readers see only states stamped with its incarnation, and no write that names no life
    /// is stored meanwhile ([#livePartitionState]).
    Option<PartitionState> existingPartitionState(String streamName) {
        return liveIncarnation(streamName).fold(() -> option(partitionStates.get(streamName)),
                                                incarnation -> lifePartitionState(streamName, incarnation));
    }

    /// The stored state of the life `incarnation`, if any; never creates one.
    Option<PartitionState> lifePartitionState(String streamName, long incarnation) {
        return option(partitionStates.get(streamName)).filter(state -> state.incarnation() == incarnation);
    }

    /// The life `incarnation` ended: its per-partition state goes with it (#2201 I-20), and only its own. A state of
    /// another life (a stream created again under the name while this removal was still running) is kept.
    @Contract
    void purgePartitionState(String streamName, long incarnation) {
        lifePartitionState(streamName, incarnation).onPresent(state -> partitionStates.remove(streamName, state));
    }

    private PartitionState replacedOrDead(String streamName, Option<PartitionState> stored, long incarnation) {
        return mayReplace(streamName, stored, incarnation)
               ? installed(streamName, stored, incarnation)
               : deadBucket(incarnation);
    }

    private PartitionState hydratedOrDead(String streamName, Option<PartitionState> stored, long incarnation) {
        return hydrates(streamName, incarnation, stored)
               ? hydratedInstalled(streamName, stored, incarnation)
               : deadBucket(incarnation);
    }

    private boolean mayReplace(String streamName, Option<PartitionState> stored, long incarnation) {
        return isLive(streamName, incarnation) && stored.filter(state -> heldByThisNode(streamName,
                                                                                        state.incarnation()))
                                                        .isEmpty();
    }

    private boolean isLive(String streamName, long incarnation) {
        return liveIncarnation(streamName).filter(live -> live == incarnation)
                              .isPresent();
    }

    /// Whether `incarnation` is the life of the local entry or of the applied committed config of the name.
    private boolean heldByThisNode(String streamName, long incarnation) {
        return option(streams.get(streamName)).filter(entry -> entry.config()
                                                                    .incarnation() == incarnation)
                     .isPresent() || option(appliedConfigs.get(streamName)).filter(config -> config.incarnation() == incarnation)
                                           .isPresent();
    }

    private boolean hydrates(String streamName, long incarnation, Option<PartitionState> stored) {
        return option(appliedConfigs.get(streamName)).map(applied -> applied.incarnation() == incarnation)
                     .or(() -> stored.filter(state -> isLocalEntry(streamName,
                                                                   state.incarnation()))
                                     .isEmpty());
    }

    private boolean isLocalEntry(String streamName, long incarnation) {
        return option(streams.get(streamName)).filter(entry -> entry.config()
                                                                    .incarnation() == incarnation)
                     .isPresent();
    }

    private PartitionState installed(String streamName, Option<PartitionState> stored, long incarnation) {
        var fresh = PartitionState.partitionState(incarnation);

        return swapped(streamName, stored, fresh)
               ? fresh
               : partitionState(streamName, incarnation);
    }

    private PartitionState hydratedInstalled(String streamName, Option<PartitionState> stored, long incarnation) {
        var fresh = PartitionState.partitionState(incarnation);

        return swapped(streamName, stored, fresh)
               ? fresh
               : hydratingPartitionState(streamName, incarnation);
    }

    /// Compare-and-set: `fresh` goes in only if the stored state is still `stored`.
    private boolean swapped(String streamName, Option<PartitionState> stored, PartitionState fresh) {
        return stored.map(state -> partitionStates.replace(streamName, state, fresh))
                     .or(() -> partitionStates.putIfAbsent(streamName, fresh) == null);
    }

    /// A state no life reads: what a write that no life on this node owns lands in, then dropped with it.
    private static PartitionState deadBucket(long incarnation) {
        return PartitionState.partitionState(incarnation);
    }
}
