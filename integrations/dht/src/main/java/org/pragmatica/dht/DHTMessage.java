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
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Codec;

import java.util.List;

/// Messages for DHT operations between nodes.
@Codec
public sealed interface DHTMessage extends ProtocolMessage {
    /// Request to get a value.
    record GetRequest(String requestId, NodeId sender, byte[] key) implements DHTMessage {
        public GetRequest {
            key = key.clone();
        }
    }

    /// Response to a get request.
    record GetResponse(String requestId, NodeId sender, Option<byte[]> value) implements DHTMessage {}

    /// Request to put a value.
    record PutRequest(String requestId, NodeId sender, byte[] key, byte[] value) implements DHTMessage {
        public PutRequest {
            key = key.clone();
            value = value.clone();
        }
    }

    /// Response to a put request.
    record PutResponse(String requestId, NodeId sender, boolean success) implements DHTMessage {}

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

    /// A key-value pair used in migration data transfers.
    record KeyValue(byte[] key, byte[] value) {
        public KeyValue {
            key = key.clone();
            value = value.clone();
        }
    }

    /// Request to transfer migration data for a partition range.
    record MigrationDataRequest(String requestId, NodeId sender, int partitionStart, int partitionEnd) implements DHTMessage {}

    /// Response containing migration data.
    record MigrationDataResponse(String requestId, NodeId sender, List<KeyValue> entries) implements DHTMessage {}

    /// Request to compute digest of keys in a partition range.
    record DigestRequest(String requestId, NodeId sender, int partitionStart, int partitionEnd) implements DHTMessage {}

    /// Response containing partition digest.
    record DigestResponse(String requestId, NodeId sender, byte[] digest) implements DHTMessage {
        public DigestResponse {
            digest = digest.clone();
        }
    }
}
