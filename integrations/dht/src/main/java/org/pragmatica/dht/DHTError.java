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

    /// One replica's fence refused one write slot: the writer's owner epoch is older than that replica's
    /// high-water (#1818, the owner's fence ruling).
    static DHTError replicaFenced(NodeId replica) {
        return new ReplicaFenced(replica);
    }

    record ReplicaFenced(NodeId replica) implements DHTError, Cause.Transient {
        @Override
        public String message() {
            return "Replica " + replica.id() + " refused the write: the writer's owner epoch is stale";
        }
    }

    /// A write that did not reach its quorum because owner-epoch fences refused it (#1818, the owner's fence
    /// ruling — the Dynamo stance). It is NOT a definite failure: replicas whose high-water lagged may have
    /// applied it, and a copy of it can still take effect on keys the new owner never rewrites (until #1777
    /// track 3). The coordinator rolls back its own accept; callers must treat the outcome as unknown and
    /// retry — a retry is stamped with the owner epoch as it stands by then.
    static DHTError writeIndeterminate(int required, int achieved, int fenced) {
        return new WriteIndeterminate(required, achieved, fenced);
    }

    record WriteIndeterminate(int required, int achieved, int fenced) implements DHTError, Cause.Transient {
        @Override
        public String message() {
            return "Write outcome indeterminate: required " + required
                 + " acks, got " + achieved
                 + ", " + fenced
                 + " refused by owner-epoch fences; it may have been applied";
        }
    }

    /// One replica's refusal of a put stamped with an older replication change than it has applied (#1777, CTO ruling
    /// R1c). The write as a whole fails [ReplicationChangeStale] if quorum becomes unreachable because of it.
    static DHTError replicaOnNewerReplication(NodeId replica) {
        return new ReplicaOnNewerReplication(replica);
    }

    record ReplicaOnNewerReplication(NodeId replica) implements DHTError, Cause.Transient {
        @Override
        public String message() {
            return "Replica " + replica.id()
                 + " refused the write: it has applied a newer replication change, or has not yet learned the committed one";
        }
    }

    /// A put that did not reach its quorum because replicas that applied a newer replication change refused it (#1777,
    /// CTO ruling R1c). Its quorum was sized under factors the cluster has left; replicas that had not applied the change
    /// yet may hold it, and the catch-up that follows the change carries those copies, so the outcome is unknown, not a
    /// failure. Retriable: the retry is stamped, and sized, under the change as this node has applied it by then.
    static DHTError replicationChangeStale(int required, int achieved, int refused) {
        return new ReplicationChangeStale(required, achieved, refused);
    }

    record ReplicationChangeStale(int required, int achieved, int refused) implements DHTError, Cause.Transient {
        @Override
        public String message() {
            return "Write refused by the replication-change fence: required " + required
                 + " acks, got " + achieved
                 + ", " + refused
                 + " replicas have applied a newer replication change; retry once this node has applied it";
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

    /// This node has not yet read the cluster's committed replication factors (#1777 track 1), so it cannot
    /// place a key or size a quorum: a guess could read fewer replicas than the cluster writes to and answer a
    /// false "absent". Transient — the node resolves them once its consensus state is restored.
    DHTError REPLICATION_UNRESOLVED = new ReplicationUnresolved();

    record ReplicationUnresolved() implements DHTError, Cause.Transient {
        @Override
        public String message() {
            return "DHT replication factors not yet resolved from the committed cluster configuration";
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
