// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.ReadPreference;
import org.pragmatica.aether.stream.forward.RawEventDto;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamReadForwardMetrics;
import org.pragmatica.aether.stream.replication.ReplicaDescriptor;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;


/// The single forward-read routing core shared by every replicated-stream read path. Holds the
/// caught-up-replica selection + owner-forward fallback ALGORITHM exactly once; each caller supplies
/// only its type-specific local-read and decode closures, so the typed-`StreamEvent` reader
/// ({@link PartitionedStreamAccess}) and the raw-event reader ({@link StreamReadRouter}) cannot drift.
///
/// Routing precedence ({@link #route}):
///   1. `GOVERNOR` reads local; `ANY_REPLICA`/`NEAREST` enter replica selection (no registry → local).
///   2. A REMOTE replica the LOCAL registry marks CAUGHT_UP exists → forward via {@link #attemptPrimary}
///      (random pick + single retry on a distinct replica).
///   3. Self is CAUGHT_UP for this partition (local has full coverage) → read local.
///   4. Self lagging, no locally-known caught-up peer → forward to the deterministic HRW owner (every
///      node computes the same owner; the owner self-promotes to CAUGHT_UP first at cold-start), failing
///      soft to a local read when the owner is unknown/self or no forward client is wired.
public final class ForwardingReadRouter<E> {
    private static final System.Logger LOG = System.getLogger(ForwardingReadRouter.class.getName());

    /// Type-specific local partition read (e.g. `readPartition` / `readLocal`).
    @FunctionalInterface
    public interface LocalReader<E> {
        Promise<List<E>> read(String streamName, int partition, long fromOffset, int maxEvents);
    }

    /// Decode forwarded {@link RawEventDto}s into the caller's event type (e.g. `decodeAll` /
    /// `toRawEvents`). Decoding cannot fail — a malformed remote payload is a transport-layer concern
    /// surfaced earlier — so this returns a plain list, matching the existing decoders.
    @FunctionalInterface
    public interface RemoteDecoder<E> {
        List<E> decode(List<RawEventDto> events, int partition);
    }

    /// Resolve the deterministic owner of `(streamName, partition)` — the HRW owner the publish path
    /// routes to. {@link Option#none()} during the bootstrap window (placement not yet known).
    @FunctionalInterface
    public interface OwnerResolver {
        Option<NodeId> resolve(String streamName, int partition);
    }

    private final Option<ReplicaRegistry> registry;
    private final NodeId selfNodeId;
    private final Option<StreamForwardClient> forwardClient;
    private final ReadPreference readPreference;
    private final OwnerResolver ownerResolver;
    private final LocalReader<E> localReader;
    private final RemoteDecoder<E> remoteDecoder;
    private final StreamReadForwardMetrics metrics;

    private ForwardingReadRouter(Option<ReplicaRegistry> registry,
                                 NodeId selfNodeId,
                                 Option<StreamForwardClient> forwardClient,
                                 ReadPreference readPreference,
                                 OwnerResolver ownerResolver,
                                 LocalReader<E> localReader,
                                 RemoteDecoder<E> remoteDecoder,
                                 StreamReadForwardMetrics metrics) {
        this.registry = registry;
        this.selfNodeId = selfNodeId;
        this.forwardClient = forwardClient;
        this.readPreference = readPreference;
        this.ownerResolver = ownerResolver;
        this.localReader = localReader;
        this.remoteDecoder = remoteDecoder;
        this.metrics = metrics;
    }

    public static <E> ForwardingReadRouter<E> forwardingReadRouter(Option<ReplicaRegistry> registry,
                                                                   NodeId selfNodeId,
                                                                   Option<StreamForwardClient> forwardClient,
                                                                   ReadPreference readPreference,
                                                                   OwnerResolver ownerResolver,
                                                                   LocalReader<E> localReader,
                                                                   RemoteDecoder<E> remoteDecoder,
                                                                   StreamReadForwardMetrics metrics) {
        return new ForwardingReadRouter<>(registry,
                                          selfNodeId,
                                          forwardClient,
                                          readPreference,
                                          ownerResolver,
                                          localReader,
                                          remoteDecoder,
                                          metrics);
    }

    public Promise<List<E>> route(String streamName, int partition, long fromOffset, int maxEvents) {
        return switch (readPreference) {
            case GOVERNOR -> localReader.read(streamName, partition, fromOffset, maxEvents);
            case ANY_REPLICA, NEAREST -> readFromReplicaOrLocal(streamName, partition, fromOffset, maxEvents);
        };
    }

    private Promise<List<E>> readFromReplicaOrLocal(String streamName, int partition, long fromOffset, int maxEvents) {
        return registry.map(reg -> selectAndRead(reg, streamName, partition, fromOffset, maxEvents))
                       .or(() -> localReader.read(streamName, partition, fromOffset, maxEvents));
    }

    private Promise<List<E>> selectAndRead(ReplicaRegistry reg,
                                           String streamName,
                                           int partition,
                                           long fromOffset,
                                           int maxEvents) {
        var caughtUpRemotes = reg.replicasFor(streamName, partition)
                                 .stream()
                                 .filter(ForwardingReadRouter::isCaughtUp)
                                 .filter(r -> !r.nodeId().equals(selfNodeId))
                                 .toList();

        if (!caughtUpRemotes.isEmpty()) {
            return forwardClient.map(client -> attemptPrimary(client,
                                                              caughtUpRemotes,
                                                              streamName,
                                                              partition,
                                                              fromOffset,
                                                              maxEvents))
                                .or(() -> localReader.read(streamName, partition, fromOffset, maxEvents));
        }

        if (selfCoversPartition(reg, streamName, partition)) {
            return localReader.read(streamName, partition, fromOffset, maxEvents);
        }

        return forwardToOwner(streamName, partition, fromOffset, maxEvents);
    }

    private boolean selfCoversPartition(ReplicaRegistry reg, String streamName, int partition) {
        return reg.replicasFor(streamName, partition)
                  .stream()
                  .filter(r -> r.nodeId().equals(selfNodeId))
                  .anyMatch(ForwardingReadRouter::isCaughtUp);
    }

    private Promise<List<E>> forwardToOwner(String streamName, int partition, long fromOffset, int maxEvents) {
        return ownerResolver.resolve(streamName, partition)
                            .filter(owner -> !owner.equals(selfNodeId))
                            .flatMap(owner -> forwardClient.map(client -> forwardReadToNode(client,
                                                                                            owner,
                                                                                            streamName,
                                                                                            partition,
                                                                                            fromOffset,
                                                                                            maxEvents)))
                            .or(() -> localReader.read(streamName, partition, fromOffset, maxEvents));
    }

    private Promise<List<E>> forwardReadToNode(StreamForwardClient client,
                                               NodeId owner,
                                               String streamName,
                                               int partition,
                                               long fromOffset,
                                               int maxEvents) {
        LOG.log(System.Logger.Level.DEBUG,
                "ReadPreference {0}: owner-forward to {1} for {2}[{3}]",
                readPreference,
                owner,
                streamName,
                partition);

        return client.readRemote(owner, streamName, partition, fromOffset, maxEvents)
                     .map(result -> remoteDecoder.decode(result.events(), partition))
                     .fold(result -> result.fold(_ -> localReader.read(streamName, partition, fromOffset, maxEvents),
                                                 Promise::success));
    }

    private Promise<List<E>> attemptPrimary(StreamForwardClient client,
                                            List<ReplicaDescriptor> pool,
                                            String streamName,
                                            int partition,
                                            long fromOffset,
                                            int maxEvents) {
        var primary = pickReplica(pool);

        LOG.log(System.Logger.Level.DEBUG,
                "ReadPreference {0}: primary replica {1} for {2}[{3}]",
                readPreference,
                primary.nodeId(),
                streamName,
                partition);

        return client.readRemote(primary.nodeId(), streamName, partition, fromOffset, maxEvents)
                     .map(result -> remoteDecoder.decode(result.events(), partition))
                     .fold(result -> result.fold(cause -> retryOrFail(client,
                                                                      primary,
                                                                      pool,
                                                                      streamName,
                                                                      partition,
                                                                      fromOffset,
                                                                      maxEvents,
                                                                      cause),
                                                 Promise::success));
    }

    private Promise<List<E>> retryOrFail(StreamForwardClient client,
                                         ReplicaDescriptor primary,
                                         List<ReplicaDescriptor> pool,
                                         String streamName,
                                         int partition,
                                         long fromOffset,
                                         int maxEvents,
                                         Cause firstCause) {
        var alternatives = pool.stream().filter(r -> !r.nodeId().equals(primary.nodeId())).toList();

        if (alternatives.isEmpty()) {
            LOG.log(System.Logger.Level.DEBUG,
                    "ReadPreference {0}: single-replica failure for {1}[{2}]: {3}",
                    readPreference,
                    streamName,
                    partition,
                    firstCause.message());

            return firstCause.promise();
        }

        metrics.recordRetry();
        var retry = pickReplica(alternatives);

        LOG.log(System.Logger.Level.DEBUG,
                "ReadPreference {0}: retry replica {1} for {2}[{3}] after primary {4} failed: {5}",
                readPreference,
                retry.nodeId(),
                streamName,
                partition,
                primary.nodeId(),
                firstCause.message());

        return client.readRemote(retry.nodeId(), streamName, partition, fromOffset, maxEvents)
                     .map(result -> remoteDecoder.decode(result.events(), partition));
    }

    private static ReplicaDescriptor pickReplica(List<ReplicaDescriptor> pool) {
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    private static boolean isCaughtUp(ReplicaDescriptor descriptor) {
        return descriptor.state() == ReplicationState.CAUGHT_UP;
    }
}
