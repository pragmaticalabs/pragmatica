// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    /// The stream's per-partition state, created on first use: what a writer reaches.
    PartitionState partitionState(String streamName) {
        return partitionStates.computeIfAbsent(streamName, _ -> PartitionState.partitionState());
    }

    /// The stream's per-partition state if any was recorded: what a reader reaches, so a lookup of a name never creates one.
    Option<PartitionState> existingPartitionState(String streamName) {
        return option(partitionStates.get(streamName));
    }

    /// The stream's life ended: its per-partition state goes with it (#2201 I-20).
    @Contract
    void purgePartitionState(String streamName) {
        partitionStates.remove(streamName);
    }
}
