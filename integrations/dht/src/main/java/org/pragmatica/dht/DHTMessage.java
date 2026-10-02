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

    /// Response to a get request, carrying the answering replica's [Readiness] for the key's partition.
    record GetResponse(String requestId, NodeId sender, Option<byte[]> value, Readiness readiness) implements DHTMessage {}

    /// Request to put a value.
    ///
    /// Carries the writer's current owner epoch as three primitive `long`s (`epochIncarnation`, `epochTerm`,
    /// `epochCounter`) — the fencing token each replica enforces against its per-partition
    /// high-water (#345 piece 1c). The `Epoch` type that mints these lives in the BSL-1.1
    /// `aether/slice` module, so only the primitives cross this Apache-2.0 wire.
    record PutRequest(String requestId,
                      NodeId sender,
                      byte[] key,
                      byte[] value,
                      long version,
                      long epochIncarnation,
                      long epochTerm,
                      long epochCounter) implements DHTMessage {
        public PutRequest {
            key = key.clone();
            value = value.clone();
        }
    }

    /// Response to a put request.
    record PutResponse(String requestId, NodeId sender, boolean success, boolean superseded) implements DHTMessage {}

    /// Request to remove a value.
    record RemoveRequest(String requestId, NodeId sender, byte[] key) implements DHTMessage {
        public RemoveRequest {
            key = key.clone();
        }
    }

    /// Response to a remove request.
    record RemoveResponse(String requestId, NodeId sender, boolean found) implements DHTMessage {}

    /// Request to check if key exists.
    record ExistsRequest(String requestId, NodeId sender, byte[] key) implements DHTMessage {
        public ExistsRequest {
            key = key.clone();
        }
    }

    /// Response to exists request, carrying the answering replica's [Readiness] for the key's partition.
    record ExistsResponse(String requestId, NodeId sender, boolean exists, Readiness readiness) implements DHTMessage {}

    /// A key-value pair with version used in migration data transfers. Carries the owner epoch as
    /// three primitive `long`s (`epochIncarnation`, `epochTerm`, `epochCounter`) so migrated entries preserve their
    /// fencing token across transfer (#345 piece 1c).
    record KeyValue(byte[] key, byte[] value, long version, long epochIncarnation, long epochTerm, long epochCounter) {
        public KeyValue {
            key = key.clone();
            value = value.clone();
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
    record MigrationDataResponse(String requestId,
                                 NodeId sender,
                                 List<KeyValue> entries,
                                 boolean ackRequested,
                                 boolean refused) implements DHTMessage {}

    /// Acknowledgement that a [MigrationDataResponse] carrying `ackRequested=true` was applied by the
    /// receiver (issue #427, D2). `requestId` echoes the response's correlation id so the departing
    /// sender resolves the matching pending push. Additive to the internal cluster protocol
    /// (rebuilt-together within the rc), mirroring the `PublishForwardResponse.retryable` precedent.
    record MigrationDataAck(String requestId, NodeId sender) implements DHTMessage {}

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
