// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.metrics;

import java.util.List;

import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.rabia.VoterConfiguration;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Codec;


/// The four non-KV items a worker needs, as one payload on the existing ClusterSync ping and pong: the peer directory and the endpoint
/// directory (both read from `nodes`, which lists every addressed core and worker), the DHT replication and the leader's installed voter
/// configuration (#2543: a passive client never applies a roster change itself, so this is its only source of the current electorate;
/// empty while the leader's genesis is pending). Ordered by [#version()]; the `generation` is a per-leader-epoch counter the leader
/// increments when the content changes.
@Codec
public record ClusterDirectory(long generation,
                               long epochIncarnation,
                               long epochTerm,
                               long epochCounter,
                               List<NodeInfo> nodes,
                               DhtReplication dhtReplication,
                               Option<VoterConfiguration> voters) {
    public ClusterDirectory {
        nodes = List.copyOf(nodes);
    }

    public static ClusterDirectory clusterDirectory(DirectoryVersion version,
                                                    List<NodeInfo> nodes,
                                                    DhtReplication dhtReplication,
                                                    Option<VoterConfiguration> voters) {
        return new ClusterDirectory(version.generation(),
                                    version.epochIncarnation(),
                                    version.epochTerm(),
                                    version.epochCounter(),
                                    nodes,
                                    dhtReplication,
                                    voters);
    }

    public DirectoryVersion version() {
        return DirectoryVersion.directoryVersion(epochIncarnation, epochTerm, epochCounter, generation);
    }

    /// True when the two directories carry the same content, whatever their versions.
    public boolean sameContentAs(ClusterDirectory other) {
        return nodes.equals(other.nodes)
               && dhtReplication.equals(other.dhtReplication)
               && voters.equals(other.voters);
    }

    /// The DHT replication a worker resolves its DHT client from: the narrow subset of the committed cluster configuration a worker needs
    /// (the cluster TOML itself is never sent to workers), the committed configuration version it comes from, and the latest replication
    /// change with its quorum floor.
    @Codec
    public record DhtReplication(int replicationFactor,
                                 int confirmationFactor,
                                 int cacheReplicationFactor,
                                 int cacheConfirmationFactor,
                                 long configVersion,
                                 long changeVersion,
                                 int floorWriteQuorum,
                                 int floorReadQuorum,
                                 boolean changeSettled) {}
}
