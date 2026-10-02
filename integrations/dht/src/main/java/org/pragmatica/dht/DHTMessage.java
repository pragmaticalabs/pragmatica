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

    /// Response to a get request.
    record GetResponse(String requestId, NodeId sender, Option<byte[]> value) implements DHTMessage {}

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

    /// Response to a put request. `fenced` (#1818, the owner's fence ruling) marks a refusal by the owner-epoch
    /// fence: the writer's epoch is older than this replica's high-water. The writer's put may still have been
    /// applied elsewhere, so a quorum lost to fenced refusals is indeterminate, not a definite failure.
    record PutResponse(String requestId, NodeId sender, boolean success, boolean superseded, boolean fenced) implements DHTMessage {}

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

    /// Response to exists request.
    record ExistsResponse(String requestId, NodeId sender, boolean exists) implements DHTMessage {}

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
    /// then, exactly as before; only the graceful-departure push sets it `true`.
    ///
    /// `leaving` is the set the departure push excluded when it chose this receiver: the pusher and every
    /// co-departing node it knew of (issue #1818 L1). The receiver checks placement against the ring without
    /// that set, so a co-drainer it has not yet heard of cannot make a legitimate newcomer look like a stray.
    /// Empty on every other sender.
    record MigrationDataResponse(String requestId,
                                 NodeId sender,
                                 List<KeyValue> entries,
                                 boolean ackRequested,
                                 List<NodeId> leaving) implements DHTMessage {}

    /// Acknowledgement of a [MigrationDataResponse] carrying `ackRequested=true` (issue #427, D2).
    /// `requestId` echoes the response's correlation id so the departing sender resolves the matching
    /// pending push. `applied` is `true` only when every entry was stored or was already superseded by
    /// a newer stored entry; `false` is a nack (issue #1818) — the sender counts the batch as not
    /// delivered. Additive to the internal cluster protocol (rebuilt-together within the rc), mirroring
    /// the `PublishForwardResponse.retryable` precedent.
    record MigrationDataAck(String requestId, NodeId sender, boolean applied) implements DHTMessage {}

    /// Request to compute digest of keys in a partition range.
    record DigestRequest(String requestId, NodeId sender, int partitionStart, int partitionEnd) implements DHTMessage {}

    /// Response containing partition digest.
    record DigestResponse(String requestId, NodeId sender, byte[] digest) implements DHTMessage {
        public DigestResponse {
            digest = digest.clone();
        }
    }
}
