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

    /// `knownIncarnation` is the cluster incarnation (#1529) of the worker's installed projection: its
    /// `minimumRevision` is a revision of THAT incarnation and bounds nothing in a newer one.
    record ManifestRequest(NodeId sender, long requestId, long minimumRevision, long knownIncarnation) implements WorkerMetadataMessage {
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
                    long clusterIncarnation,
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

    /// The DHT replication a worker resolves its DHT client from (#1777 track 1, CTO ruling B): the narrow subset of
    /// the core's committed cluster configuration a worker needs. The cluster TOML itself is never served to workers
    /// (#1390) — it carries infrastructure credential references — so a core derives this from it when it builds a
    /// worker's projection, and the worker applies it on every projection install.
    @Codec
    record DhtReplication(int replicationFactor,
                          int confirmationFactor,
                          int cacheReplicationFactor,
                          int cacheConfirmationFactor) {}

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
