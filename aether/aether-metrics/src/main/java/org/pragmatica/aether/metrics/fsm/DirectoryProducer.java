// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.metrics.fsm;

import java.util.List;
import java.util.function.Supplier;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.cluster.metrics.ClusterDirectory;
import org.pragmatica.cluster.metrics.ClusterDirectory.DhtReplication;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// The leader's producer of the [ClusterDirectory]: reads the directory content and stamps it with the leader epoch and a generation. The
/// generation is a per-epoch counter that moves only when the content changes, so the directory a leader already sent is not re-sent, and a
/// new epoch starts again at 1 (the epoch triple orders first).
final class DirectoryProducer {
    private static final Logger log = LoggerFactory.getLogger(DirectoryProducer.class);

    private final Supplier<List<NodeInfo>> nodes;
    private final Supplier<Result<DhtReplication>> dhtReplication;
    private Option<ClusterDirectory> last = Option.none();

    DirectoryProducer(Supplier<List<NodeInfo>> nodes, Supplier<Result<DhtReplication>> dhtReplication) {
        this.nodes = nodes;
        this.dhtReplication = dhtReplication;
    }

    /// The directory for `epoch`: the previous one when nothing changed under the same epoch, the next generation when the content changed,
    /// generation 1 under a new epoch. A DHT replication that cannot be derived keeps the previous directory.
    synchronized Option<ClusterDirectory> produce(Epoch epoch) {
        last = dhtReplication.get().fold(this::keepAfterFailure, replication -> Option.some(next(epoch, replication)));

        return last;
    }

    private Option<ClusterDirectory> keepAfterFailure(Cause cause) {
        log.debug("Cluster directory not refreshed, DHT replication unavailable: {}", cause.message());

        return last;
    }

    private ClusterDirectory next(Epoch epoch, DhtReplication replication) {
        var content = nodes.get();
        var sameEpoch = last.map(previous -> isOf(previous, epoch)).or(false);
        var unchanged = last.map(previous -> sameEpoch
                                             && previous.nodes()
                                                        .equals(content)
                                             && previous.dhtReplication()
                                                        .equals(replication))
                            .or(false);

        return unchanged
               ? last.or(() -> stamp(1L, epoch, content, replication))
               : stamp(generationAfter(sameEpoch), epoch, content, replication);
    }

    private long generationAfter(boolean sameEpoch) {
        return sameEpoch
               ? last.map(previous -> previous.generation() + 1)
                     .or(1L)
               : 1L;
    }

    private static boolean isOf(ClusterDirectory directory, Epoch epoch) {
        return directory.epochIncarnation() == epoch.incarnation()
               && directory.epochTerm() == epoch.rabiaTerm()
               && directory.epochCounter() == epoch.localCounter();
    }

    private static ClusterDirectory stamp(long generation,
                                          Epoch epoch,
                                          List<NodeInfo> content,
                                          DhtReplication replication) {
        return new ClusterDirectory(generation,
                                    epoch.incarnation(),
                                    epoch.rabiaTerm(),
                                    epoch.localCounter(),
                                    content,
                                    replication);
    }
}
