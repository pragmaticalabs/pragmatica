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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.DHTMessage.Readiness;


/// One periodic anti-entropy round for one partition, seen as a vote on tombstone collection (#1777 track 3).
///
/// The round AGREES when every co-replica in this node's ring answers SERVING with a digest equal to the local
/// one. The digest leaves out tombstones expired by their own stamp, so agreement proves that no co-replica holds
/// a LIVE entry for any key whose tombstone has expired here: a replica that missed the remove would carry the
/// stale value in its digest, and this one, holding the tombstone, would not. A co-replica that is silent,
/// differs or is still catching up keeps the round from agreeing — that costs memory (tombstones wait), never data.
final class AgreementRound {
    private final Partition partition;
    private final long startedAtMillis;
    private final Set<NodeId> awaited;
    private final AtomicBoolean refused = new AtomicBoolean();
    private final AtomicBoolean decided = new AtomicBoolean();

    private AgreementRound(Partition partition, long startedAtMillis, Set<NodeId> coReplicas) {
        this.partition = partition;
        this.startedAtMillis = startedAtMillis;
        this.awaited = ConcurrentHashMap.newKeySet();
        this.awaited.addAll(coReplicas);
    }

    static AgreementRound agreementRound(Partition partition, long startedAtMillis, Set<NodeId> coReplicas) {
        return new AgreementRound(partition, startedAtMillis, coReplicas);
    }

    Partition partition() {
        return partition;
    }

    /// The wall-clock time the local digest was taken: tombstones expired then are the ones agreement covers.
    long startedAtMillis() {
        return startedAtMillis;
    }

    /// Record one co-replica's answer. Returns `true` exactly once: on the answer that completes an agreeing round,
    /// or at once for a partition with no co-replica to ask.
    boolean answer(NodeId peer, Readiness readiness, boolean digestMatches) {
        if (readiness != Readiness.SERVING || !digestMatches) {
            refused.set(true);
        }

        awaited.remove(peer);

        return agreed();
    }

    /// Whether the round has agreed, claimed once.
    boolean agreed() {
        return awaited.isEmpty()
               && !refused.get()
               && decided.compareAndSet(false, true);
    }
}
