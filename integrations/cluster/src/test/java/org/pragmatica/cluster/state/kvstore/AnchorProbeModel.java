// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.pragmatica.consensus.rabia.infrastructure.OlderCheckpointCluster.Applied;

/// P-0 probe model (no merge): a SIMULATION of the commit ring's resubscribe check and of a replica, built on the batches a real
/// cluster committed. The ring, anchors and replica do not exist before W1/W3, so they are simulated here; the batches, slots and batch
/// ids come from real engines and real stores (`OlderCheckpointCluster`).
final class AnchorProbeModel {
    private AnchorProbeModel() {}

    /// A `(slot, batchId)` anchor pair of an effect-bearing entry.
    record Pair(long slot, String batchId) {}

    /// The effects of a recorded batch: the plain `Put`s it carried (this harness has no guarded commands), as `key -> value`.
    static Map<String, String> effectsOf(Applied entry) {
        var effects = new LinkedHashMap<String, String>();

        entry.commands().forEach(command -> {
            if (command instanceof KVCommand.Put<?, ?> put) {
                effects.put(String.valueOf(put.key()), String.valueOf(put.value()));
            }
        });

        return effects;
    }

    /// A source ring simulated from a core's recorded history. `floor` is the lowest slot it can serve from: 0 for a core that has run
    /// since genesis, the restore frontier for a core restarted from a checkpoint (its ring is empty below it).
    static final class SimRing {
        private final long floor;
        private final long nextSlot;
        private final TreeMap<Long, Applied> bySlot = new TreeMap<>();

        SimRing(List<Applied> history, long floor) {
            this.floor = floor;
            history.forEach(entry -> bySlot.put(entry.slot(), entry));
            this.nextSlot = history.isEmpty() ? floor : history.getLast().slot() + 1;
        }

        long floor() {
            return floor;
        }

        long nextSlot() {
            return nextSlot;
        }

        String idAt(long slot) {
            var entry = bySlot.get(slot);

            return entry == null ? null : entry.batchId();
        }

        List<Applied> entriesFrom(long position) {
            return List.copyOf(bySlot.tailMap(position, true).values());
        }
    }

    /// A replica that follows a core's history: its position is the next slot, its anchor the last `K` effect pairs, its state the effects.
    static final class Replica {
        static final int K = 8;
        private long applied;
        private final ArrayDeque<Pair> pairs = new ArrayDeque<>();
        private final Map<String, String> state = new LinkedHashMap<>();

        static Replica following(List<Applied> history) {
            var replica = new Replica();

            history.forEach(replica::apply);

            return replica;
        }

        void apply(Applied entry) {
            state.putAll(effectsOf(entry));
            pairs.addLast(new Pair(entry.slot(), entry.batchId()));
            while (pairs.size() > K) {
                pairs.removeFirst();
            }
            applied = entry.slot() + 1;
        }

        long applied() {
            return applied;
        }

        Map<String, String> state() {
            return Map.copyOf(state);
        }

        List<Pair> lastPairs(int k) {
            var all = new ArrayList<>(pairs);

            return all.subList(Math.max(0, all.size() - k), all.size());
        }

        /// Applies every entry the source holds at or after this replica's position, in order (what a granted resubscribe delivers).
        void catchUp(SimRing source) {
            source.entriesFrom(applied).forEach(this::apply);
        }
    }

    enum Outcome { ACCEPT, AHEAD, EVICTED, UNANCHORED }

    record Verdict(Outcome outcome, String reason) {
        boolean accepted() {
            return outcome == Outcome.ACCEPT;
        }
    }

    /// The position-only rule (what a replica that trusts the engine phase would do): resume iff its position is not ahead of the source.
    static Verdict positionOnly(Replica replica, SimRing source) {
        return replica.applied() <= source.nextSlot()
               ? new Verdict(Outcome.ACCEPT, "position " + replica.applied() + " <= source frontier " + source.nextSlot())
               : new Verdict(Outcome.AHEAD, "position " + replica.applied() + " > source frontier " + source.nextSlot());
    }

    /// The anchored rule of the design (section 2.4): take the replica's last `k` pairs; those below the source's floor cannot be
    /// verified and are ignored, at least one must be verifiable, and every verifiable pair must be present in the source with an equal
    /// batch id. `checkIds` false is the mutation "drop the batch-id comparison".
    static Verdict anchored(Replica replica, SimRing source, int k, boolean checkIds) {
        if (replica.applied() > source.nextSlot()) {
            return new Verdict(Outcome.AHEAD, "position " + replica.applied() + " > source frontier " + source.nextSlot());
        }

        var pairs = replica.lastPairs(k);

        if (pairs.isEmpty()) {
            return new Verdict(Outcome.UNANCHORED, "no anchor pair");
        }

        var verifiable = pairs.stream().filter(pair -> pair.slot() >= source.floor()).toList();

        if (verifiable.isEmpty()) {
            return new Verdict(Outcome.EVICTED, "every anchor pair is below the source floor " + source.floor());
        }

        for (var pair : verifiable) {
            var id = source.idAt(pair.slot());

            if (id == null) {
                return new Verdict(Outcome.AHEAD, "anchor slot " + pair.slot() + " absent in the source ring");
            }

            if (checkIds && !id.equals(pair.batchId())) {
                return new Verdict(Outcome.AHEAD, "anchor slot " + pair.slot() + " holds a different batch in the source");
            }
        }

        return new Verdict(Outcome.ACCEPT, verifiable.size() + " of " + pairs.size() + " anchor pairs verified");
    }
}
