// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.pragmatica.aether.node.StorageFactory.StorageSetup;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.PromotionManager;
import org.pragmatica.storage.PromotionManager.PromotionStats;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


/// Turns the one promotion condition that needs an operator into an operator event, and its recovery: a storage
/// instance whose promotion cycles keep failing without copying a single block. It detects FAILING COPIES (the faster
/// tier rejects a write, or the source cannot be read), and nothing else surfaces them (a failed copy logs at DEBUG). It
/// does not detect a tier that sits above its promotion watermark: promotion skips such a tier without attempting a copy.
///
/// A cycle is STALLED when it failed at least one copy and promoted none. [#STALLED_AFTER_CYCLES] such cycles in a row
/// raise `storage-promotion-stalled` once. Once raised, it ends only with a cycle that copied a block successfully
/// (`storage-promotion-resumed`): an idle cycle (no candidates, nothing attempted) leaves it raised, because a broken tier
/// with no hot candidate looks exactly like a healthy idle one, and "works again" must mean a copy worked. Before the
/// event is raised, an idle or successful cycle restarts the count. A skipped tick, one where the previous cycle was still
/// running, runs no cycle and moves nothing.
///
/// [#observe] runs on the promotion thread after each cycle. The driver starts one cycle at a time, and each start
/// happens-after the previous finish, so the per-instance state needs no further coordination.
final class PromotionStallWatch {
    static final int STALLED_AFTER_CYCLES = 3;
    private static final Logger log = LoggerFactory.getLogger(PromotionStallWatch.class);

    private final Map<String, PromotionManager> managers;
    private final OperatorWarningSink sink;
    private final Map<String, Tracker> trackers = new ConcurrentHashMap<>();

    private PromotionStallWatch(Map<String, PromotionManager> managers, OperatorWarningSink sink) {
        this.managers = managers;
        this.sink = sink;
    }

    /// Watches every instance's promotion manager. The inert one (`streams`, or `promotion = false`) never runs a cycle,
    /// so its stats never move and it never raises anything.
    static PromotionStallWatch promotionStallWatch(Map<String, StorageSetup> setups, OperatorWarningSink sink) {
        var managers = setups.entrySet()
                             .stream()
                             .collect(Collectors.toMap(Map.Entry::getKey,
                                                       entry -> entry.getValue()
                                                                     .promotionManager()));

        return new PromotionStallWatch(Map.copyOf(managers), sink);
    }

    /// Looks at each watched instance's stats once.
    Unit observe() {
        managers.forEach((instance, manager) -> observe(instance, manager.stats()));

        return unit();
    }

    private Unit observe(String instance, PromotionStats now) {
        var before = trackers.getOrDefault(instance, Tracker.tracker());
        var after = before.after(now);

        trackers.put(instance, after);

        return raiseTransition(instance, before, after);
    }

    private Unit raiseTransition(String instance, Tracker before, Tracker after) {
        if (!before.isStalled() && after.isStalled()) {
            return OperatorWarnings.raise(log,
                                          sink,
                                          OperatorWarningCode.STORAGE_PROMOTION_STALLED,
                                          instance,
                                          "Storage '{}' promotion copied nothing in {} consecutive cycles while copy attempts failed: its faster tier "
                                         + "rejects the write or its source tier cannot be read. Check both tiers' health and free space "
                                         + "([storage.{}] memory_max_bytes or disk_max_bytes), or stop promoting this instance with "
                                         + "[storage.{}] promotion = false",
                                          instance,
                                          after.streak(),
                                          instance,
                                          instance);
        }

        if (before.isStalled() && !after.isStalled()) {
            return OperatorWarnings.raise(log,
                                          sink,
                                          OperatorWarningCode.STORAGE_PROMOTION_RESUMED,
                                          instance,
                                          "Storage '{}' promotion works again: a cycle copied a block successfully",
                                          instance);
        }

        return unit();
    }

    /// What the watch has seen of one instance: the stats at the last cycle it looked at, and how many cycles in a row
    /// were stalled.
    record Tracker(PromotionStats seen, int streak) {
        static Tracker tracker() {
            return new Tracker(PromotionStats.promotionStats(), 0);
        }

        boolean isStalled() {
            return streak >= STALLED_AFTER_CYCLES;
        }

        /// Advances over the cycles that ran since `seen`. No cycle since means nothing to judge.
        Tracker after(PromotionStats now) {
            if (now.cycles() == seen.cycles()) {
                return this;
            }

            return new Tracker(now, nextStreak(now));
        }

        /// A stalled cycle adds one. Otherwise the count restarts, except that a raised stall holds through a cycle that
        /// copied nothing: only a successful copy ends it.
        private int nextStreak(PromotionStats now) {
            if (isStalledSince(now)) {
                return streak + 1;
            }

            return isStalled() && !copiedSince(now)
                   ? streak
                   : 0;
        }

        private boolean copiedSince(PromotionStats now) {
            return now.blocksPromoted() > seen.blocksPromoted();
        }

        private boolean isStalledSince(PromotionStats now) {
            return now.failed() > seen.failed() && now.blocksPromoted() == seen.blocksPromoted();
        }
    }
}
