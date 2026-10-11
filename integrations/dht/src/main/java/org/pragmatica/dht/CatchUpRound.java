/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.dht;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.DHTMessage.Readiness;


/// One catch-up attempt for one pending partition (#1777 track 2), run by [DHTAntiEntropy].
///
/// The SOURCES are the partition's current co-replicas plus every member of its previous replica set
/// that is still in the ring. Each source is asked for its digest and readiness. Once every source has
/// answered, the round decides once:
///   - **anchored** — at least one source is serving: pull from every serving source whose digest differs;
///   - **anchorless** — no source is serving (genesis, a whole-cluster cold restart, or every holder of the
///     data gone): pull from every source whose digest differs, the union of what survives.
/// The partition becomes serving when every pull it waits on was stored. A source that never answers
/// keeps the round undecided; it is abandoned after [DHTAntiEntropy#CATCH_UP_ROUND_TIMEOUT] and a fresh
/// one starts, so a silent serving source can delay a catch-up but never turn it anchorless.
final class CatchUpRound {
    private record Answer(Readiness readiness, boolean digestMatches) {}

    private final long id;
    private final long generation;
    private final Partition partition;
    private final Set<NodeId> sources;
    /// The sources a serving answer from may authorize deciding without the silent ones: current co-replicas
    /// and exactly recorded previous holders — never a boot-walk node, which answers SERVING for a
    /// partition it may never have held.
    private final Set<NodeId> anchors;
    private final long startedNanos;
    private final ConcurrentHashMap<NodeId, Answer> answers = new ConcurrentHashMap<>();
    private final Set<NodeId> outstandingPulls = ConcurrentHashMap.newKeySet();
    /// The monotonic clock the round's ages are read from: `System.nanoTime` in production, a manual clock in a test.
    private final LongSupplier clock;

    /// The stamp of a round that has not decided. `System.nanoTime` may legally return any value, negative ones included, so
    /// no ordinary reading can be the sentinel: it is the one value a clock reading of a decision is never taken to be.
    private static final long UNDECIDED = Long.MIN_VALUE;

    /// When the round decided, so a round whose pulls are still landing is kept for a bounded while. [#UNDECIDED] means
    /// undecided, and the decision and its stamp are ONE atomic step: a reader can never observe "decided" without the time
    /// it decided at (two separate fields let a tick that landed between them read a stamp of 0 and replace the round).
    private final AtomicLong decidedAtNanos = new AtomicLong(UNDECIDED);
    private final AtomicBoolean anchorless = new AtomicBoolean();

    private CatchUpRound(long id,
                         long generation,
                         Partition partition,
                         Set<NodeId> sources,
                         Set<NodeId> anchors,
                         LongSupplier clock) {
        this.id = id;
        this.generation = generation;
        this.partition = partition;
        this.sources = Set.copyOf(sources);
        this.anchors = Set.copyOf(anchors);
        this.clock = clock;
        this.startedNanos = clock.getAsLong();
    }

    static CatchUpRound catchUpRound(long id,
                                     long generation,
                                     Partition partition,
                                     Set<NodeId> sources,
                                     Set<NodeId> anchors,
                                     LongSupplier clock) {
        return new CatchUpRound(id, generation, partition, sources, anchors, clock);
    }

    /// The pending spell this round was started for.
    long generation() {
        return generation;
    }

    long id() {
        return id;
    }

    Partition partition() {
        return partition;
    }

    Set<NodeId> sources() {
        return sources;
    }

    boolean olderThan(long ageNanos) {
        return clock.getAsLong() - startedNanos > ageNanos;
    }

    boolean anchorless() {
        return anchorless.get();
    }

    /// Record one source's answer. Returns `true` exactly once: for the answer that completes the set.
    boolean answer(NodeId source, Readiness readiness, boolean digestMatches) {
        if (!sources.contains(source)) {
            return false;
        }

        answers.put(source, new Answer(readiness, digestMatches));

        return answers.size() == sources.size() && claimDecision();
    }

    /// Whether this round decided less than `ageNanos` ago — its pulls may still be landing (#1777, K5).
    boolean decidedWithin(long ageNanos) {
        var decidedAt = decidedAtNanos.get();

        return decidedAt != UNDECIDED && clock.getAsLong() - decidedAt <= ageNanos;
    }

    private boolean claimDecision() {
        return decidedAtNanos.compareAndSet(UNDECIDED, clock.getAsLong());
    }

    /// Decide on the answers in hand, for a round some source never answered (#1777, H2): allowed only when at
    /// least one answer came from a serving ANCHOR (co-replica or recorded previous holder) and none was
    /// UNKNOWN. A serving boot-walk node may hold nothing for the partition, so its answer alone would
    /// complete it empty while the silent holder keeps the data. A round that heard only from
    /// catching-up sources never decides on silence — it would be anchorless on a guess. Returns `true`
    /// exactly once, when this call claimed the decision.
    boolean decideOnAnswersInHand() {
        var anyServing = answers.entrySet()
                                .stream()
                                .anyMatch(entry -> anchors.contains(entry.getKey()) && entry.getValue()
                                                                                            .readiness()
                                                                                            .authoritative());

        return anyServing
               && !anyUnknown()
               && claimDecision();
    }

    int silentCount() {
        return sources.size() - answers.size();
    }

    /// Whether any source could not report a trustworthy state — the round must not decide on it.
    boolean anyUnknown() {
        return answers.values()
                      .stream()
                      .anyMatch(answer -> answer.readiness() == Readiness.UNKNOWN);
    }

    /// The sources this round must pull from, fixing whether the round is anchored or anchorless.
    List<NodeId> pullTargets() {
        var serving = answers.entrySet()
                             .stream()
                             .filter(entry -> entry.getValue()
                                                   .readiness()
                                                   .authoritative())
                             .map(Map.Entry::getKey)
                             .toList();

        anchorless.set(serving.isEmpty());
        // Pull from EVERY source whose digest differs, serving or not: a serving source (a non-owner in the
        // boot walk, a previous holder) may hold nothing while a catching-up co-replica holds the only
        // pushed copy; per-key ordering makes the extra pull harmless.
        var candidates = List.copyOf(answers.keySet());
        var targets = candidates.stream().filter(source -> !answers.get(source)
                                                                   .digestMatches()).toList();

        outstandingPulls.addAll(targets);

        return targets;
    }

    /// Record that the pull from `source` was stored. Returns `true` when it was the last outstanding one.
    boolean pullStored(NodeId source) {
        return outstandingPulls.remove(source) && outstandingPulls.isEmpty();
    }
}
