// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/// A stub-level MODEL of the worker-replica feed's ordering rule (W-OBS), not production code and not the real ring.
/// The real ring (`CommitRing`) and applier (`ReplicaApplier`) do not exist at this commit, so the interleave
/// tripwire TW-O16 is written against this model. It has exactly the moving parts the rule is about: a core that bumps
/// its `nextSlot` and offers entries to one sink, a heartbeat carrying `nextSlot`, and a replica that decides what moves
/// its `applied` position.
final class ReplicaFeedModel {
    private ReplicaFeedModel() {}

    /// The effects of one batch, applied at `slot`; `from` is the previous entry's `slot + 1` (the chain link).
    record Entry(long from, long slot, List<String> effects) {}

    sealed interface Message permits EntryMessage, HeartbeatMessage {}

    record EntryMessage(Entry entry) implements Message {}

    record HeartbeatMessage(long nextSlot) implements Message {}

    /// A core with one subscriber. `nextSlot` is the engine-side frontier the store publishes; the sink queue is what the
    /// replica will receive, in order.
    static final class Core {
        private long nextSlot;
        private final List<Message> sink = new ArrayList<>();
        private Entry pending;

        Core(long nextSlot) {
            this.nextSlot = nextSlot;
        }

        /// The offer-inside-append rule (I-W9): the bump and the offer happen in one critical section, so no heartbeat can
        /// observe the bumped `nextSlot` before the entry is in the queue.
        void appendAtomically(List<String> effects) {
            bump(effects);
            offerPending();
        }

        /// First half of a non-atomic append: the revision moved, the entry is not yet offered. This is the window a
        /// heartbeat could fall into if the offer were not in the same critical section.
        void bump(List<String> effects) {
            pending = new Entry(nextSlot, nextSlot, List.copyOf(effects));
            nextSlot++;
        }

        void offerPending() {
            sink.add(new EntryMessage(pending));
            pending = null;
        }

        void heartbeat() {
            sink.add(new HeartbeatMessage(nextSlot));
        }

        List<Message> delivered() {
            return List.copyOf(sink);
        }
    }

    /// A replica. `heartbeatAdvances` selects the rule: true is the WITHDRAWN draft-2 rule ("advance on a contiguous
    /// heartbeat"), false is the design's rule (only entries move `applied`).
    static final class Replica {
        private final boolean heartbeatAdvances;
        private long applied;
        private final Set<String> effects = new LinkedHashSet<>();
        private boolean gapSeen;

        Replica(long applied, boolean heartbeatAdvances) {
            this.applied = applied;
            this.heartbeatAdvances = heartbeatAdvances;
        }

        void receive(List<Message> messages) {
            messages.forEach(this::receive);
        }

        private void receive(Message message) {
            switch (message) {
                case EntryMessage entry -> accept(entry.entry());
                case HeartbeatMessage heartbeat -> advanceOnHeartbeat(heartbeat);
            }
        }

        private void accept(Entry entry) {
            if (entry.slot() < applied) {
                return;
            }

            if (entry.from() > applied) {
                gapSeen = true;

                return;
            }

            effects.addAll(entry.effects());
            applied = entry.slot() + 1;
        }

        private void advanceOnHeartbeat(HeartbeatMessage heartbeat) {
            if (heartbeatAdvances && heartbeat.nextSlot() > applied) {
                applied = heartbeat.nextSlot();
            }
        }

        long applied() {
            return applied;
        }

        Set<String> effects() {
            return Set.copyOf(effects);
        }

        boolean gapSeen() {
            return gapSeen;
        }
    }
}
