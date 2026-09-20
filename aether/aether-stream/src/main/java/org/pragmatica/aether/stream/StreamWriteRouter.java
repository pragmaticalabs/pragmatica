// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.function.Function;

import org.pragmatica.aether.slice.PublishOutcomeUnknown;
import org.pragmatica.aether.stream.ForwardingReadRouter.OwnerResolver;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;


/// The ONE owner-routed stream write operation (#1263). All three write entry points delegate here —
/// the management/API publish directly, the slice {@link DefaultStreamPublisher} and
/// `StreamAccess.publish` ({@link PartitionedStreamAccess}) after encoding the event and picking its
/// partition — so owner routing, the STRONG refusal (#1262), write-forwarding with the bounded retry
/// ({@link StreamForwardRetry}), the committed-owner redirect (#1230) and the min-sync barrier are decided
/// once and cannot drift between paths. It is also the publish-side mirror of {@link StreamReadRouter}:
/// when this node is the partition's owner it appends locally and awaits the barrier; otherwise it
/// write-forwards to the HRW owner via {@link StreamForwardClient} — the SAME deterministic owner the read
/// router routes to.
///
/// The min-sync barrier is the stream's committed `min-sync-replicas`, read live on every publish, never a
/// value frozen into an entry point at construction. An unknown self never forwards: with no identity to
/// compare against, "the owner is someone else" cannot be established, so the write lands locally and the
/// committed-owner admission decides.
///
/// Since #265 made non-owner nodes metadata-only, a management publish landing on an arbitrary node
/// (the harness hits any node's mgmt API) must reach the owner instead of failing
/// {@link StreamError.General#PARTITION_NOT_LOCAL} on {@code publishLocal}.
///
/// **The one write operation (#1263).** Three entry points delegate to this router whole: the slice
/// {@link DefaultStreamPublisher}, {@code StreamAccess.publish} ({@link PartitionedStreamAccess}) and the
/// management publish. There is a deliberate FOURTH arm that is NOT on the router: the entity-log substrate
/// ({@code StreamEntityLogSubstrate}) calls {@link StreamPartitionManager#publishLocal} directly — it is
/// owner-local by construction and builds an EVENTUAL config itself, so neither the owner routing nor the
/// #1262 consistency guard applies to it. The owner side of a forwarded publish
/// ({@code StreamForwardHandler}) is the router's counterpart on the receiving node; note that it reads
/// {@code min-sync-replicas} TWICE (once for the pre-append floor, once for the barrier) where this router
/// reads it ONCE and feeds both from that value ({@link #publishLocal}).
public final class StreamWriteRouter {
    private final StreamPartitionManager partitionManager;
    private final Option<StreamForwardClient> forwardClient;
    private final Option<NodeId> selfNodeId;
    private final OwnerResolver ownerResolver;

    private StreamWriteRouter(StreamPartitionManager partitionManager,
                              Option<StreamForwardClient> forwardClient,
                              Option<NodeId> selfNodeId,
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
        return new StreamWriteRouter(partitionManager, forwardClient, Option.some(selfNodeId), ownerResolver);
    }

    /// Entry-point overload for the typed publishers, whose self identity may be unknown (#1263).
    public static StreamWriteRouter streamWriteRouter(StreamPartitionManager partitionManager,
                                                      Option<StreamForwardClient> forwardClient,
                                                      Option<NodeId> selfNodeId,
                                                      OwnerResolver ownerResolver) {
        return new StreamWriteRouter(partitionManager, forwardClient, selfNodeId, ownerResolver);
    }

    /// Minimal-runtime / test writer: no forward client, always appends locally. Mirrors
    /// {@link StreamReadRouter#localOnly}.
    public static StreamWriteRouter localOnly(StreamPartitionManager partitionManager) {
        return new StreamWriteRouter(partitionManager, Option.none(), Option.none(), (_, _) -> Option.none());
    }

    /// The typed publishers' owner rule (#47/#467), once: prefer the partition-aware HRW resolver (the SAME
    /// `ReplicaSetController` placement that owns the replica set), falling back to the arg-less leader
    /// resolver only when no HRW resolver is wired (legacy / minimal runtimes). [Option#none] from both keeps
    /// the fail-soft local write.
    static Option<NodeId> hrwOwner(Option<Function<Integer, Option<NodeId>>> partitionOwnerResolver,
                                   Option<Fn0<Option<NodeId>>> fallbackResolver,
                                   int partition) {
        return partitionOwnerResolver.flatMap(resolver -> resolver.apply(partition))
                                     .orElse(() -> fallbackResolver.flatMap(Fn0::apply));
    }

    /// Publish `payload` to `(streamName, partition)`, resolving to the assigned offset. Routes by
    /// AUTHORITY, never by ring presence (#1230) — a replica holds the same materialized ring the owner
    /// does: a remote HRW owner is write-forwarded; a self owner appends locally (local append + min-sync
    /// barrier — the one path every entry point gets since #1263). Falls back to a
    /// local append only when the owner is unknown or no forward client is wired (bootstrap / minimal
    /// runtime), matching the read router's soft-fail-to-local posture. The local append is admitted only
    /// for the committed owner; a refusal in the ownership-lag window redirects to that owner.
    ///
    /// **STRONG (#1262):** a stream declared `STRONG` is refused with `CONSENSUS_PATH_UNAVAILABLE` before
    /// routing — see {@link StreamPartitionManager#ensureWritableConsistency}.
    public Promise<Long> publish(String streamName, int partition, byte[] payload, long timestamp) {
        return partitionManager.ensureWritableConsistency(streamName)
                               .async()
                               .flatMap(_ -> routePublish(streamName, partition, payload, timestamp));
    }

    private Promise<Long> routePublish(String streamName, int partition, byte[] payload, long timestamp) {
        return ownerResolver.resolve(streamName, partition)
                            .filter(this::isRemote)
                            .flatMap(owner -> forwardTo(owner, streamName, partition, payload, timestamp))
                            .or(() -> publishLocal(streamName, partition, payload, timestamp));
    }

    /// Forwardable only when known to differ from this node; a self owner, or an unknown self, never forwards,
    /// so the send-to-self QUIC drop (which hangs the forward) cannot occur.
    private boolean isRemote(NodeId owner) {
        return selfNodeId.map(self -> !owner.equals(self))
                         .or(false);
    }

    /// `min-sync-replicas` is read ONCE here and feeds both the pre-append floor and the post-append barrier,
    /// so a config raised while a publish is in flight moves the NEXT publish's barrier, never this one's
    /// (#1361 M12; pinned by `StreamWritePathContractTest`).
    private Promise<Long> publishLocal(String streamName, int partition, byte[] payload, long timestamp) {
        var minSyncReplicas = partitionManager.minSyncReplicasFor(streamName);
        // #1230: a NotOwnerAppend refusal is redirected to the committed owner, before #1236's floor check;
        // the floor precedes the append (a refusal is not in the log); after it, an unconfirmed barrier is
        // an unknown outcome.
        return partitionManager.publishLocalAtFloor(streamName, partition, payload, timestamp, minSyncReplicas - 1)
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
                                 .mapError(PublishOutcomeUnknown.FACTORY)
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
    /// policy lives once in {@link StreamForwardRetry}; since #1263 this router is its only production
    /// caller, and {@link DefaultStreamPublisher} and {@link PartitionedStreamAccess} inherit it by delegation.
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
