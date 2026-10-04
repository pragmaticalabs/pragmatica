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

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.serialization.Codec;


/// Messages for DHT operations between nodes.
@Codec
public sealed interface DHTMessage extends ProtocolMessage {
    @Override
    default StreamType streamType() {
        return StreamType.DHT;
    }

    /// Request to get a value.
    record GetRequest(String requestId, NodeId sender, byte[] key) implements DHTMessage {
        public GetRequest {
            key = key.clone();
        }
    }

    /// Whether a replica's answers for a partition are authoritative (#1777 track 2). A node that became a
    /// replica through a ring change is [#CATCHING_UP] until handoff or anti-entropy has filled it: its
    /// "absent" is no evidence of absence and must not vote. [#UNKNOWN] is the codec sentinel (an ordinal
    /// this build does not have) and is consumed as a refusal, never as serving.
    @Codec
    enum Readiness {
        SERVING,
        CATCHING_UP,
        UNKNOWN;
        /// Whether an absent answer from a replica in this state counts as evidence of absence.
        public boolean authoritative() {
            return this == SERVING;
        }
    }

    /// Response to a get request, carrying the answering replica's [Readiness] for the key's partition and the
    /// STAMP of the entry it holds (#1777 track 3): a reader keeps the newest answer by owner epoch then version,
    /// so a stale replica's value loses to a newer tombstone. `value` is present for a live entry; `tombstone`
    /// marks a removed one; neither means this replica holds no entry for the key.
    record GetResponse(String requestId,
                       NodeId sender,
                       Option<byte[]> value,
                       Readiness readiness,
                       boolean tombstone,
                       long version,
                       long epochIncarnation,
                       long epochTerm,
                       long epochCounter) implements DHTMessage {
        /// An unstamped answer (version and epoch 0): it loses to any stamped entry.
        public GetResponse(String requestId, NodeId sender, Option<byte[]> value, Readiness readiness) {
            this(requestId, sender, value, readiness, false, 0L, 0L, 0L, 0L);
        }
    }

    /// Request to put a value.
    ///
    /// Carries the writer's current owner epoch as three primitive `long`s (`epochIncarnation`, `epochTerm`,
    /// `epochCounter`) — the fencing token each replica enforces against its per-partition
    /// high-water (#345 piece 1c). The `Epoch` type that mints these lives in the BSL-1.1
    /// `aether/slice` module, so only the primitives cross this Apache-2.0 wire.
    ///
    /// `replicationVersion` (#1777, CTO ruling R1c) is the writer's applied replication change ([DHTNode#replicationFence])
    /// when it started the put: the quorum it waits for was sized under that change's factors. A replica that has applied a
    /// newer change refuses the write ([PutResponse#replicationStale]).
    record PutRequest(String requestId,
                      NodeId sender,
                      byte[] key,
                      byte[] value,
                      long version,
                      long epochIncarnation,
                      long epochTerm,
                      long epochCounter,
                      long replicationVersion) implements DHTMessage {
        public PutRequest {
            key = key.clone();
            value = value.clone();
        }
    }

    /// Response to a put request. `fenced` (#1818, the owner's fence ruling) marks a refusal by the owner-epoch
    /// fence: the writer's epoch is older than this replica's high-water. The writer's put may still have been
    /// applied elsewhere, so a quorum lost to fenced refusals is indeterminate, not a definite failure.
    ///
    /// `replicationStale` (#1777, CTO ruling R1c) marks a refusal by the replication-change fence: the put was stamped with
    /// an older replication change than this replica has applied, so its quorum was sized under factors the cluster has
    /// left. The writer retries under the newer change once it has applied it. `fenceUnknown` marks the other refusal of
    /// that fence: this replica does not know the committed change yet (restarted, before its state restore and catch-up),
    /// so it can judge no stamp — a plain retriable refusal that says nothing about the WRITER (v1882 round 5).
    ///
    /// `writePending` (#1777 v1882 r12) marks a refusal because THIS replica holds its own write to the same key that is applied
    /// locally and not yet resolved: answering "superseded" to another writer would let that writer's quorum count a copy this
    /// replica may roll back. A retriable refusal, and NOT evidence about the writer.
    record PutResponse(String requestId,
                       NodeId sender,
                       boolean success,
                       boolean superseded,
                       boolean fenced,
                       boolean replicationStale,
                       boolean fenceUnknown,
                       boolean writePending) implements DHTMessage {
        /// A response that is not a pending-write refusal.
        public PutResponse(String requestId,
                           NodeId sender,
                           boolean success,
                           boolean superseded,
                           boolean fenced,
                           boolean replicationStale,
                           boolean fenceUnknown) {
            this(requestId, sender, success, superseded, fenced, replicationStale, fenceUnknown, false);
        }
    }

    /// Request to remove a value: the replica stores a TOMBSTONE stamped like a put (#1777 track 3), so the remove
    /// supersedes every older copy of the value wherever anti-entropy, migration or a hand-off carries it, and is
    /// fenced by the same owner-epoch high-water a put is.
    record RemoveRequest(String requestId,
                         NodeId sender,
                         byte[] key,
                         long version,
                         long epochIncarnation,
                         long epochTerm,
                         long epochCounter,
                         long replicationVersion) implements DHTMessage {
        public RemoveRequest {
            key = key.clone();
        }
    }

    /// Response to a remove request. `found` means a live value was superseded here. `fenced` marks a refusal by
    /// the owner-epoch fence, exactly as for a [PutResponse]: a remove whose quorum is lost to fences is
    /// indeterminate. `replicationStale` marks a refusal by the replication-change fence, exactly as for a [PutResponse]
    /// (#1777, CTO ruling R1c): a tombstone is a write, and one sized under factors the cluster has left is refused too.
    /// `fenceUnknown` is the replica not knowing the committed change yet, as for a [PutResponse].
    record RemoveResponse(String requestId,
                          NodeId sender,
                          boolean found,
                          boolean fenced,
                          boolean replicationStale,
                          boolean fenceUnknown) implements DHTMessage {
        /// An answer that was not fenced.
        public RemoveResponse(String requestId, NodeId sender, boolean found) {
            this(requestId, sender, found, false, false, false);
        }
    }

    /// Request to check if key exists.
    record ExistsRequest(String requestId, NodeId sender, byte[] key) implements DHTMessage {
        public ExistsRequest {
            key = key.clone();
        }
    }

    /// Response to exists request, carrying the answering replica's [Readiness] for the key's partition and the
    /// stamp of the entry it holds, like [GetResponse] (#1777 track 3).
    record ExistsResponse(String requestId,
                          NodeId sender,
                          boolean exists,
                          Readiness readiness,
                          boolean tombstone,
                          long version,
                          long epochIncarnation,
                          long epochTerm,
                          long epochCounter) implements DHTMessage {
        /// An unstamped answer (version and epoch 0): it loses to any stamped entry.
        public ExistsResponse(String requestId, NodeId sender, boolean exists, Readiness readiness) {
            this(requestId, sender, exists, readiness, false, 0L, 0L, 0L, 0L);
        }
    }

    /// A key-value pair with version used in migration data transfers. Carries the owner epoch as
    /// three primitive `long`s (`epochIncarnation`, `epochTerm`, `epochCounter`) so migrated entries preserve their
    /// fencing token across transfer (#345 piece 1c). `tombstone` (#1777 track 3) marks a removed key: its value is
    /// empty, and it travels through repair, migration and the departure hand-off like any entry, so a stale copy
    /// of the value can never outlive the remove.
    record KeyValue(byte[] key,
                    byte[] value,
                    long version,
                    long epochIncarnation,
                    long epochTerm,
                    long epochCounter,
                    boolean tombstone) {
        public KeyValue {
            key = key.clone();
            value = value.clone();
        }

        /// A live entry.
        public KeyValue(byte[] key,
                        byte[] value,
                        long version,
                        long epochIncarnation,
                        long epochTerm,
                        long epochCounter) {
            this(key, value, version, epochIncarnation, epochTerm, epochCounter, false);
        }

        /// The store's per-key order: owner epoch first, then HLC version (#345 piece 1c, #1818). A positive result
        /// means this entry wins over `other` wherever both meet.
        public int compareOrder(KeyValue other) {
            var byIncarnation = Long.compare(epochIncarnation, other.epochIncarnation);
            var byTerm = Long.compare(epochTerm, other.epochTerm);
            var byCounter = Long.compare(epochCounter, other.epochCounter);

            return byIncarnation != 0
                   ? byIncarnation
                   : byTerm != 0
                     ? byTerm
                     : byCounter != 0
                       ? byCounter
                       : Long.compare(version, other.version);
        }
    }

    /// Request to transfer migration data for a partition range.
    record MigrationDataRequest(String requestId, NodeId sender, int partitionStart, int partitionEnd) implements DHTMessage {}

    /// Response containing migration data. `ackRequested` (issue #427, D2) asks the receiver to
    /// reply with a [MigrationDataAck] once the entries are applied, so a departing node can confirm
    /// its held chunks reached a surviving replica before it halts. The two fire-and-forget senders
    /// (survivor-side rebalance and anti-entropy pull) leave it `false` — the receiver stays silent
    /// then, exactly as before; only the graceful-departure push sets it `true`. `refused` (#1777) marks a
    /// pull the holder declined because the requester is not a replica in the holder's ring — distinct
    /// from a holder that simply has no entries, so the requester never mistakes a refusal for completion.
    ///
    /// `leaving` is the set the departure push excluded when it chose this receiver: the pusher and every
    /// co-departing node it knew of (issue #1818 L1). The receiver checks placement against the ring without
    /// that set, so a co-drainer it has not yet heard of cannot make a legitimate newcomer look like a stray.
    /// Empty on every other sender. `view` is the pusher's ring membership when it chose the receiver
    /// (v1820 r5): a joiner the receiver knows but the pusher does not would otherwise push the receiver past
    /// RF in its own view and make it refuse the copy. Empty means "the receiver's own ring".
    record MigrationDataResponse(String requestId,
                                 NodeId sender,
                                 List<KeyValue> entries,
                                 boolean ackRequested,
                                 boolean refused,
                                 List<NodeId> leaving,
                                 List<NodeId> view) implements DHTMessage {
        /// A pull answer or a survivor-rebalance push: no departure view.
        public MigrationDataResponse(String requestId,
                                     NodeId sender,
                                     List<KeyValue> entries,
                                     boolean ackRequested,
                                     boolean refused) {
            this(requestId, sender, entries, ackRequested, refused, List.of(), List.of());
        }

        /// A response that is not a refusal and carries no departure view beyond `leaving`.
        public MigrationDataResponse(String requestId,
                                     NodeId sender,
                                     List<KeyValue> entries,
                                     boolean ackRequested,
                                     List<NodeId> leaving) {
            this(requestId, sender, entries, ackRequested, false, leaving, List.of());
        }

        /// A departure push: never a refusal.
        public MigrationDataResponse(String requestId,
                                     NodeId sender,
                                     List<KeyValue> entries,
                                     boolean ackRequested,
                                     List<NodeId> leaving,
                                     List<NodeId> view) {
            this(requestId, sender, entries, ackRequested, false, leaving, view);
        }
    }

    /// Acknowledgement of a [MigrationDataResponse] carrying `ackRequested=true` (issue #427, D2).
    /// `requestId` echoes the response's correlation id so the departing sender resolves the matching
    /// pending push. `applied` is `true` only when every entry was stored or was already superseded by
    /// a newer stored entry; `false` is a nack (issue #1818) — the sender counts the batch as not
    /// delivered. Additive to the internal cluster protocol (rebuilt-together within the rc), mirroring
    /// the `PublishForwardResponse.retryable` precedent.
    record MigrationDataAck(String requestId, NodeId sender, boolean applied) implements DHTMessage {}

    /// Request to compute digest of keys in a partition range.
    record DigestRequest(String requestId, NodeId sender, int partitionStart, int partitionEnd) implements DHTMessage {}

    /// Response containing partition digest and the sender's [Readiness] for that partition (#1777), so a
    /// catching-up requester can tell an authoritative source from another catching-up replica.
    record DigestResponse(String requestId, NodeId sender, byte[] digest, Readiness readiness) implements DHTMessage {
        public DigestResponse {
            digest = digest.clone();
        }
    }
}
