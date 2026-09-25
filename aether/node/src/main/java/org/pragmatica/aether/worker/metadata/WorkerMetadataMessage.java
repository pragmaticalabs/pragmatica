// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
package org.pragmatica.aether.worker.metadata;

import java.util.List;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.serialization.Codec;


/// Pull/ack scoped state transfer. Every chunk belongs to one manifest and immutable content hash.
@Codec
public sealed interface WorkerMetadataMessage extends ProtocolMessage {
    @Override
    default StreamType streamType() {
        return StreamType.SYNC;
    }

    record ManifestRequest(NodeId sender, long requestId, long minimumRevision) implements WorkerMetadataMessage {
        @Override
        public StreamType streamType() {
            return StreamType.CONTROL;
        }
    }

    record Manifest(NodeId sender,
                    long requestId,
                    String incarnation,
                    long generation,
                    long committedRevision,
                    List<ScopeContent> scopes,
                    String error) implements WorkerMetadataMessage {
        public Manifest {
            scopes = List.copyOf(scopes);
        }

        @Override
        public StreamType streamType() {
            return StreamType.CONTROL;
        }
    }

    @Codec
    record ScopeContent(String scope, String hash, int length) {}

    record ChunkRequest(NodeId sender,
                        long requestId,
                        String incarnation,
                        long generation,
                        String scope,
                        String hash,
                        int offset) implements WorkerMetadataMessage {}

    record Chunk(NodeId sender,
                 long requestId,
                 String incarnation,
                 long generation,
                 String scope,
                 String hash,
                 int offset,
                 byte[] bytes,
                 String error) implements WorkerMetadataMessage {
        public Chunk {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
