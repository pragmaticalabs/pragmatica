// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.stream.ForwardingReadRouter.OwnerResolver;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;


/// Raw-payload WRITE router — the publish-side mirror of {@link StreamReadRouter}. Holds the
/// owner-routing decision for a management/API publish exactly once so it cannot drift from the app
/// publish path: when this node is the partition's owner it appends locally (and awaits the min-sync
/// barrier); otherwise it write-forwards to the HRW owner via {@link StreamForwardClient} — the SAME
/// deterministic owner the read router ({@link StreamReadRouter}) and the app publisher
/// ({@link DefaultStreamPublisher}) route to.
///
/// Since #265 made non-owner nodes metadata-only, a management publish landing on an arbitrary node
/// (the harness hits any node's mgmt API) must reach the owner instead of failing
/// {@link StreamError.General#PARTITION_NOT_LOCAL} on {@code publishLocal}.
public final class StreamWriteRouter {
    private static final NodeId NO_SELF = new NodeId("__no_self__");

    private final StreamPartitionManager partitionManager;
    private final Option<StreamForwardClient> forwardClient;
    private final NodeId selfNodeId;
    private final OwnerResolver ownerResolver;

    private StreamWriteRouter(StreamPartitionManager partitionManager,
                              Option<StreamForwardClient> forwardClient,
                              NodeId selfNodeId,
                              OwnerResolver ownerResolver) {
        this.partitionManager = partitionManager;
        this.forwardClient = forwardClient;
        this.selfNodeId = selfNodeId;
        this.ownerResolver = ownerResolver;
    }

    public static StreamWriteRouter streamWriteRouter(StreamPartitionManager partitionManager,
                                                      Option<StreamForwardClient> forwardClient,
                                                      NodeId selfNodeId,
                                                      OwnerResolver ownerResolver) {
        return new StreamWriteRouter(partitionManager, forwardClient, selfNodeId, ownerResolver);
    }

    /// Minimal-runtime / test writer: no forward client, always appends locally. Mirrors
    /// {@link StreamReadRouter#localOnly}.
    public static StreamWriteRouter localOnly(StreamPartitionManager partitionManager) {
        return new StreamWriteRouter(partitionManager, Option.none(), NO_SELF, (_, _) -> Option.none());
    }

    /// Publish `payload` to `(streamName, partition)`, resolving to the assigned offset. Routes by
    /// AUTHORITY, never by ring presence (#1230) — a replica holds the same materialized ring the owner
    /// does: a remote HRW owner is write-forwarded; a self owner appends locally (mirroring
    /// {@link DefaultStreamPublisher}'s eventual path — local append + min-sync await). Falls back to a
    /// local append only when the owner is unknown or no forward client is wired (bootstrap / minimal
    /// runtime), matching the read router's soft-fail-to-local posture. The local append is admitted only
    /// for the committed owner; a refusal in the ownership-lag window redirects to that owner.
    ///
    /// **STRONG (#1262):** a stream declared `STRONG` is refused with `CONSENSUS_PATH_UNAVAILABLE` before
    /// routing — see {@link StreamPartitionManager#ensureConsensusPathNotRequired}.
    public Promise<Long> publish(String streamName, int partition, byte[] payload, long timestamp) {
        return partitionManager.ensureConsensusPathNotRequired(streamName)
                               .async()
                               .flatMap(_ -> routePublish(streamName, partition, payload, timestamp));
    }

    private Promise<Long> routePublish(String streamName, int partition, byte[] payload, long timestamp) {
        return ownerResolver.resolve(streamName, partition)
                            .filter(owner -> !owner.equals(selfNodeId))
                            .flatMap(owner -> forwardTo(owner, streamName, partition, payload, timestamp))
                            .or(() -> publishLocal(streamName, partition, payload, timestamp));
    }

    private Promise<Long> publishLocal(String streamName, int partition, byte[] payload, long timestamp) {
        var minSyncReplicas = partitionManager.minSyncReplicasFor(streamName);

        return partitionManager.publishLocal(streamName, partition, payload, timestamp)
                               .fold(cause -> StreamForwardRetry.redirectNotOwner(cause,
                                                                                  owner -> forwardTo(owner,
                                                                                                     streamName,
                                                                                                     partition,
                                                                                                     payload,
                                                                                                     timestamp)),
                                     offset -> awaitMinSync(streamName, partition, offset, minSyncReplicas));
    }

    private Promise<Long> awaitMinSync(String streamName, int partition, long offset, int minSyncReplicas) {
        return minSyncReplicas > 1
               ? partitionManager.awaitReplication(streamName, partition, offset, minSyncReplicas - 1)
                                 .map(_ -> offset)
               : Promise.success(offset);
    }

    private Option<Promise<Long>> forwardTo(NodeId owner,
                                            String streamName,
                                            int partition,
                                            byte[] payload,
                                            long timestamp) {
        return forwardClient.map(client -> attemptForward(client, owner, streamName, partition, payload, timestamp));
    }

    /// Owner-forward with the shared bounded retry folded in (write-forward race fix): re-attempts ONLY
    /// when the owner reported the failure as retryable (`RemotePublishRetryable`) and attempts remain —
    /// the owner's committed-config view had not yet caught up to the config this sender just committed
    /// and forwarded — bounded so no unbounded loop; no other failure cause is ever retried. The retry
    /// policy lives once in {@link StreamForwardRetry} (shared with {@link DefaultStreamPublisher} and
    /// {@link PartitionedStreamAccess}).
    private Promise<Long> attemptForward(StreamForwardClient client,
                                         NodeId owner,
                                         String streamName,
                                         int partition,
                                         byte[] payload,
                                         long timestamp) {
        return StreamForwardRetry.withBoundedRetry(() -> client.publishRemote(owner,
                                                                              streamName,
                                                                              partition,
                                                                              payload,
                                                                              timestamp));
    }
}
