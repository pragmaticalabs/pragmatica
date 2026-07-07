// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.stream.ForwardingReadRouter.OwnerResolver;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardError;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;


/// Raw-payload WRITE router — the publish-side mirror of {@link StreamReadRouter}. Holds the
/// owner-routing decision for a management/API publish exactly once so it cannot drift from the app
/// publish path: when this node materializes the partition it appends locally (and awaits the min-sync
/// barrier); otherwise it write-forwards to the HRW owner via {@link StreamForwardClient} — the SAME
/// deterministic owner the read router ({@link StreamReadRouter}) and the app publisher
/// ({@link DefaultStreamPublisher}) route to.
///
/// Since #265 made non-owner nodes metadata-only, a management publish landing on an arbitrary node
/// (the harness hits any node's mgmt API) must reach the owner instead of failing
/// {@link StreamError.General#PARTITION_NOT_LOCAL} on {@code publishLocal}.
public final class StreamWriteRouter {
    private static final NodeId NO_SELF = new NodeId("__no_self__");
    /// Bounded forward-publish retry budget (write-forward race fix): 1 initial attempt + up to 2 retries.
    private static final int MAX_FORWARD_ATTEMPTS = 3;
    /// Fixed short backoff between forward-publish retries — long enough for the owner's KV apply of the
    /// just-committed config to land, short enough to stay well within the publish path's forward budget.
    private static final TimeSpan FORWARD_RETRY_BACKOFF = TimeSpan.timeSpan(150).millis();

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

    /// Publish `payload` to `(streamName, partition)`, resolving to the assigned offset. LOCAL-FIRST by
    /// materialization: a node that holds the partition ring appends locally (mirroring
    /// {@link DefaultStreamPublisher}'s eventual path — local append + min-sync await); a metadata-only
    /// node write-forwards to the HRW owner. Falls back to a local append only when the owner is
    /// unknown/self or no forward client is wired (bootstrap / minimal runtime), matching the read
    /// router's soft-fail-to-local posture.
    public Promise<Long> publish(String streamName, int partition, byte[] payload, long timestamp) {
        return partitionManager.partitionBuffer(streamName, partition)
                               .isPresent()
               ? publishLocal(streamName, partition, payload, timestamp)
               : forwardToOwner(streamName, partition, payload, timestamp);
    }

    private Promise<Long> publishLocal(String streamName, int partition, byte[] payload, long timestamp) {
        var minSyncReplicas = partitionManager.minSyncReplicasFor(streamName);

        return partitionManager.publishLocal(streamName, partition, payload, timestamp)
                               .async()
                               .flatMap(offset -> awaitMinSync(streamName, partition, offset, minSyncReplicas));
    }

    private Promise<Long> awaitMinSync(String streamName, int partition, long offset, int minSyncReplicas) {
        return minSyncReplicas > 1
               ? partitionManager.awaitReplication(streamName, partition, offset, minSyncReplicas - 1)
                                 .map(_ -> offset)
               : Promise.success(offset);
    }

    private Promise<Long> forwardToOwner(String streamName, int partition, byte[] payload, long timestamp) {
        return ownerResolver.resolve(streamName, partition)
                            .filter(owner -> !owner.equals(selfNodeId))
                            .flatMap(owner -> forwardClient.map(client -> attemptForward(client,
                                                                                         owner,
                                                                                         streamName,
                                                                                         partition,
                                                                                         payload,
                                                                                         timestamp,
                                                                                         1)))
                            .or(() -> partitionManager.publishLocal(streamName, partition, payload, timestamp)
                                                      .async());
    }

    /// One owner-forward attempt with the bounded-retry decision folded in (write-forward race fix): it
    /// RESOLVES on success and on any PERMANENT failure, and re-attempts ONLY when the owner reported the
    /// failure as retryable (`RemotePublishRetryable`) and attempts remain — the owner's committed-config
    /// view had not yet caught up to the config this sender just committed and forwarded. Bounded at
    /// {@link #MAX_FORWARD_ATTEMPTS}, so no unbounded loop; no other failure cause is ever retried.
    private Promise<Long> attemptForward(StreamForwardClient client,
                                         NodeId owner,
                                         String streamName,
                                         int partition,
                                         byte[] payload,
                                         long timestamp,
                                         int attempt) {
        return client.publishRemote(owner, streamName, partition, payload, timestamp)
                     .fold(result -> result.fold(cause -> retryOrPropagate(client,
                                                                           owner,
                                                                           streamName,
                                                                           partition,
                                                                           payload,
                                                                           timestamp,
                                                                           attempt,
                                                                           cause),
                                                 Promise::success));
    }

    private Promise<Long> retryOrPropagate(StreamForwardClient client,
                                           NodeId owner,
                                           String streamName,
                                           int partition,
                                           byte[] payload,
                                           long timestamp,
                                           int attempt,
                                           Cause cause) {
        return StreamForwardError.isRetryablePublish(cause) && attempt < MAX_FORWARD_ATTEMPTS
               ? scheduleForwardRetry(client, owner, streamName, partition, payload, timestamp, attempt + 1)
               : cause.promise();
    }

    /// Schedule the next attempt after a short fixed backoff through the shared scheduler (no
    /// `Thread.sleep`), bridging its resolution into the pending promise returned to the caller.
    private Promise<Long> scheduleForwardRetry(StreamForwardClient client,
                                               NodeId owner,
                                               String streamName,
                                               int partition,
                                               byte[] payload,
                                               long timestamp,
                                               int nextAttempt) {
        var pending = Promise.<Long> promise();

        SharedScheduler.schedule(() -> sendScheduledForward(pending,
                                                            client,
                                                            owner,
                                                            streamName,
                                                            partition,
                                                            payload,
                                                            timestamp,
                                                            nextAttempt),
                                 FORWARD_RETRY_BACKOFF);

        return pending;
    }

    private void sendScheduledForward(Promise<Long> pending,
                                      StreamForwardClient client,
                                      NodeId owner,
                                      String streamName,
                                      int partition,
                                      byte[] payload,
                                      long timestamp,
                                      int nextAttempt) {
        attemptForward(client, owner, streamName, partition, payload, timestamp, nextAttempt).onResult(pending::resolve);
    }
}
