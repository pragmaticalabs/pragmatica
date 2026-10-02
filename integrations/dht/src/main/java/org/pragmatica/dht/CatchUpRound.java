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
    private final Partition partition;
    private final Set<NodeId> sources;
    private final long startedNanos;
    private final ConcurrentHashMap<NodeId, Answer> answers = new ConcurrentHashMap<>();
    private final Set<NodeId> outstandingPulls = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean decided = new AtomicBoolean();
    private final AtomicBoolean anchorless = new AtomicBoolean();

    private CatchUpRound(long id, Partition partition, Set<NodeId> sources, long startedNanos) {
        this.id = id;
        this.partition = partition;
        this.sources = Set.copyOf(sources);
        this.startedNanos = startedNanos;
    }

    static CatchUpRound catchUpRound(long id, Partition partition, Set<NodeId> sources) {
        return new CatchUpRound(id, partition, sources, System.nanoTime());
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
        return System.nanoTime() - startedNanos > ageNanos;
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

        return answers.size() == sources.size() && decided.compareAndSet(false, true);
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
        var candidates = serving.isEmpty()
                         ? List.copyOf(answers.keySet())
                         : serving;
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
