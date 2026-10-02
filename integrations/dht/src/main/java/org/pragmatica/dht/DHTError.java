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

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;


/// Error causes for distributed DHT operations.
public sealed interface DHTError extends Cause {
    DHTError NO_AVAILABLE_NODES = new NoAvailableNodes();
    DHTError OPERATION_TIMEOUT = new OperationTimeout();
    DHTError MIGRATION_IN_PROGRESS = new MigrationInProgress();

    static DHTError quorumNotReached(int required, int achieved) {
        return new QuorumNotReached(required, achieved);
    }

    /// A read could not reach an authoritative quorum because replicas it needed are still catching up after
    /// a ring change (#1777 track 2). Never "absent": the value may exist on replicas that have not yet been
    /// filled or on the holders they are filling from. Transient — a retry after catch-up is answered.
    static DHTError notCaughtUp(int required, int authoritative) {
        return new NotCaughtUp(required, authoritative);
    }

    /// Data-plane epoch-fence rejection (#345 piece 1c): a versioned put whose owner epoch is
    /// STRICTLY older than the replica's per-DHT-partition high-water — a deposed owner attempting
    /// to commit an OLD epoch over a newer one. Carries the presented epoch as its two primitive
    /// longs (term, counter) so this Apache-2.0 module stays independent of the BSL-1.1 `Epoch`
    /// type that mints it.
    ///
    /// Unlike a within-epoch HLC-version supersede (which `putVersioned` reports as a plain
    /// `false` written-flag), an epoch reject is RETURNED to the caller as a failure (spec §8:
    /// data-plane rejects are surfaced, not silent). It is the data-plane sibling of the
    /// CP-plane `org.pragmatica.cluster.state.kvstore.StaleEpoch`; the aether-level gate impl can
    /// translate to that shared vocabulary at its module boundary.
    static DHTError staleEpochWrite(long epochIncarnation, long epochTerm, long epochCounter) {
        return new StaleEpochWrite(epochIncarnation, epochTerm, epochCounter);
    }

    record StaleEpochWrite(long epochIncarnation, long epochTerm, long epochCounter) implements DHTError {
        @Override
        public String message() {
            return "Stale-epoch DHT write rejected: presented owner epoch " + epochIncarnation
                 + ":" + epochTerm
                 + ":" + epochCounter
                 + " is older than the partition high-water";
        }
    }

    /// Transport-layer refusal observed synchronously when dispatching a per-replica
    /// request. Carries the target peer id and a short reason ("backpressure",
    /// "connection dead", "no peer state") so the `QuorumCollector` can record this
    /// replica as failed immediately, without waiting the per-op timeout.
    static DHTError peerUnreachable(NodeId peerId, String reason) {
        return new PeerUnreachable(peerId, reason);
    }

    record PeerUnreachable(NodeId peerId, String reason) implements DHTError, Cause.Transient {
        @Override
        public String message() {
            return "Peer " + peerId.id() + " unreachable: " + reason;
        }
    }

    record QuorumNotReached(int required, int achieved) implements DHTError, Cause.Transient {
        @Override
        public String message() {
            return "Quorum not reached: required " + required + ", achieved " + achieved;
        }
    }

    /// One replica's refusal of one read slot: it holds no value and is still catching up, so its "absent"
    /// is not counted (#1777 track 2). The read as a whole fails [NotCaughtUp] if quorum becomes
    /// unreachable because of it.
    static DHTError replicaCatchingUp(NodeId replica) {
        return new ReplicaCatchingUp(replica);
    }

    record ReplicaCatchingUp(NodeId replica) implements DHTError, Cause.Transient {
        @Override
        public String message() {
            return "Replica " + replica.id() + " is still catching up";
        }
    }

    record NotCaughtUp(int required, int authoritative) implements DHTError, Cause.Transient {
        @Override
        public String message() {
            return "Replicas not caught up: required " + required + " authoritative answers, got " + authoritative;
        }
    }

    record NoAvailableNodes() implements DHTError, Cause.Transient {
        @Override
        public String message() {
            return "No available nodes for key";
        }
    }

    record OperationTimeout() implements DHTError, Cause.Transient {
        @Override
        public String message() {
            return "DHT operation timed out";
        }
    }

    record MigrationInProgress() implements DHTError, Cause.Transient {
        @Override
        public String message() {
            return "Operation rejected: migration in progress";
        }
    }
}
