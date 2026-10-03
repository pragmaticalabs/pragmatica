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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;


/// Bookkeeping for one in-flight quorum read: which remote targets still owe a reply, which nodes the
/// read has already addressed, and how many replacement requests it may still issue.
///
/// A replacement is a re-issue of an outstanding request to a replica from the CURRENT ring when a
/// target departed mid-read. It never changes what counts as a quorum answer: the collector keeps its
/// original quorum and slot count, and a departed target's slot is either filled by the replacement's
/// reply or failed.
///
/// Thread-safe: departure events (ring-removal thread), replies and the reading thread touch it
/// concurrently. Ownership of a target's slot is decided by the caller through the atomic
/// `pendingOps.remove` on its correlation id; [#claim] only hands the id back, and
/// [#nextReplacement] is atomic per candidate (`addressed.add`) and per budget unit.
final class InFlightRead {
    private final byte[] key;
    private final QuorumCollector<Option<DHTMessage.KeyValue>> collector;
    private final long deadlineNanos;
    private final int maxReissues;
    private final AtomicInteger reissues = new AtomicInteger();
    private final Set<NodeId> addressed = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<NodeId, String> outstanding = new ConcurrentHashMap<>();

    private InFlightRead(byte[] key, QuorumCollector<Option<DHTMessage.KeyValue>> collector, long deadlineNanos, int maxReissues) {
        this.key = key;
        this.collector = collector;
        this.deadlineNanos = deadlineNanos;
        this.maxReissues = maxReissues;
    }

    /// @param deadlineNanos absolute `System.nanoTime()` past which no re-issue may be made (the
    ///                      read's original operation deadline)
    /// @param maxReissues   upper bound on replacement requests for this read
    static InFlightRead inFlightRead(byte[] key,
                                     QuorumCollector<Option<DHTMessage.KeyValue>> collector,
                                     long deadlineNanos,
                                     int maxReissues) {
        return new InFlightRead(key, collector, deadlineNanos, maxReissues);
    }

    byte[] key() {
        return key;
    }

    QuorumCollector<Option<DHTMessage.KeyValue>> collector() {
        return collector;
    }

    /// Record that `target` was addressed (local or remote); it can never be picked as a replacement.
    Unit markAddressed(NodeId target) {
        addressed.add(target);

        return unit();
    }

    /// Record that `target` owes a reply under `correlationId`.
    Unit expect(NodeId target, String correlationId) {
        addressed.add(target);
        outstanding.put(target, correlationId);

        return unit();
    }

    /// Take the outstanding correlation id of a departed target, if it still owes a reply.
    Option<String> claim(NodeId departed) {
        return option(outstanding.remove(departed));
    }

    /// Pick the first current-ring replica this read has not addressed yet, spending one unit of the
    /// re-issue budget. Empty when the budget is spent, the read's deadline has passed, or the
    /// current replica set holds nobody new.
    Option<NodeId> nextReplacement(List<NodeId> currentReplicas) {
        return withinBounds()
               ? firstUnaddressed(currentReplicas)
               : none();
    }

    private Option<NodeId> firstUnaddressed(List<NodeId> currentReplicas) {
        return Option.from(currentReplicas.stream().filter(addressed::add).findFirst());
    }

    private boolean withinBounds() {
        return System.nanoTime() - deadlineNanos < 0 && reissues.incrementAndGet() <= maxReissues;
    }
}
