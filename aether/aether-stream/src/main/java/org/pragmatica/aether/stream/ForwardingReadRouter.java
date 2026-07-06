// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.ReadPreference;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource.CommittedOwner;
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
///   1. `GOVERNOR` reads local. `NEAREST` is LOCAL-FIRST: read local when self covers the partition
///      (caught-up replica, or no registry), else forward to the HRW owner — never to a lagging remote.
///      `ANY_REPLICA` enters remote-preferring replica selection (no registry → local).
///   2. `LINEARIZABLE` routes strictly to the COMMITTED owner (#345 item 1e): the read goes to
///      `StreamPartitionOwnershipValue.owner` (the fenced owner), NOT the on-the-fly HRW owner, so it
///      observes the authoritative log even when a reshuffle has made the two diverge. When self IS the
///      committed owner the read is served locally only after the owner-side guards pass (still committed
///      owner, epoch not stale, caught up to the handover); when a remote node is the committed owner the
///      read is forwarded there via the existing read-forward transport. With no committed record
///      (legacy / unowned partitions) it falls back to the replica-routed read so nothing breaks.
///   3. A REMOTE replica the LOCAL registry marks CAUGHT_UP exists → forward via {@link #attemptPrimary}
///      (random pick + single retry on a distinct replica).
///   4. Self is CAUGHT_UP for this partition (local has full coverage) → read local.
///   5. Self lagging, no locally-known caught-up peer → forward to the deterministic HRW owner (every
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
    private final Option<CommittedStreamOwnerSource> committedOwnerSource;
    private final LinearizableOwnerServe<E> ownerServe;

    private ForwardingReadRouter(Option<ReplicaRegistry> registry,
                                 NodeId selfNodeId,
                                 Option<StreamForwardClient> forwardClient,
                                 ReadPreference readPreference,
                                 OwnerResolver ownerResolver,
                                 LocalReader<E> localReader,
                                 RemoteDecoder<E> remoteDecoder,
                                 StreamReadForwardMetrics metrics,
                                 Option<CommittedStreamOwnerSource> committedOwnerSource,
                                 Option<OwnershipEpochHighWater> epochHighWater,
                                 Option<LinearizableBarrier> barrier) {
        this.registry = registry;
        this.selfNodeId = selfNodeId;
        this.forwardClient = forwardClient;
        this.readPreference = readPreference;
        this.ownerResolver = ownerResolver;
        this.localReader = localReader;
        this.remoteDecoder = remoteDecoder;
        this.metrics = metrics;
        this.committedOwnerSource = committedOwnerSource;
        this.ownerServe = LinearizableOwnerServe.linearizableOwnerServe(selfNodeId,
                                                                        registry,
                                                                        committedOwnerSource,
                                                                        epochHighWater,
                                                                        barrier,
                                                                        localReader);
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
                                          metrics,
                                          Option.none(),
                                          Option.none(),
                                          Option.none());
    }

    /// #345 item 1e overload: wires the COMMITTED-owner source + the ownership epoch high-water + the
    /// linearizable no-op-round barrier (1e-a) so the `LINEARIZABLE` arm can route to the fenced owner,
    /// run the owner-side guards, and order the no-op round before serving. The non-linearizable arms
    /// ignore all three and stay byte-for-byte identical to the base overload.
    public static <E> ForwardingReadRouter<E> forwardingReadRouter(Option<ReplicaRegistry> registry,
                                                                   NodeId selfNodeId,
                                                                   Option<StreamForwardClient> forwardClient,
                                                                   ReadPreference readPreference,
                                                                   OwnerResolver ownerResolver,
                                                                   LocalReader<E> localReader,
                                                                   RemoteDecoder<E> remoteDecoder,
                                                                   StreamReadForwardMetrics metrics,
                                                                   Option<CommittedStreamOwnerSource> committedOwnerSource,
                                                                   Option<OwnershipEpochHighWater> epochHighWater,
                                                                   Option<LinearizableBarrier> barrier) {
        return new ForwardingReadRouter<>(registry,
                                          selfNodeId,
                                          forwardClient,
                                          readPreference,
                                          ownerResolver,
                                          localReader,
                                          remoteDecoder,
                                          metrics,
                                          committedOwnerSource,
                                          epochHighWater,
                                          barrier);
    }

    public Promise<List<E>> route(String streamName, int partition, long fromOffset, int maxEvents) {
        return switch (readPreference) {
            case GOVERNOR -> localReader.read(streamName, partition, fromOffset, maxEvents);
            case NEAREST -> readLocalFirstThenOwner(streamName, partition, fromOffset, maxEvents);
            case ANY_REPLICA -> readFromReplicaOrLocal(streamName, partition, fromOffset, maxEvents);
            case LINEARIZABLE -> readLinearizable(streamName, partition, fromOffset, maxEvents);
        };
    }

    /// `NEAREST` routing: LOCAL-FIRST, forward only on a local MISS. If self is a registered caught-up
    /// replica it is authoritative — read LOCAL (no forward even at the tail, so steady-state polling stays
    /// cheap). Otherwise read local and return it WHEN NON-EMPTY (the node holds the partition's data even
    /// though placement/registry hasn't marked it a replica — e.g. an in-memory stream whose owner-routed
    /// publish landed locally; the fan-out case), forwarding to the deterministic HRW owner ONLY when the
    /// local read is empty (the genuine non-replica / post-restart-recovery consumer — the A6 case),
    /// failing soft to local when the owner is unknown/self or no forward client is wired. Never forwards
    /// to a remote replica that may lag — that is the {@link #readFromReplicaOrLocal} (`ANY_REPLICA`) path.
    private Promise<List<E>> readLocalFirstThenOwner(String streamName, int partition, long fromOffset, int maxEvents) {
        return registry.fold(() -> localReader.read(streamName, partition, fromOffset, maxEvents),
                             reg -> selfCoversPartition(reg, streamName, partition)
                                    ? localReader.read(streamName, partition, fromOffset, maxEvents)
                                    : localThenForwardIfEmpty(streamName, partition, fromOffset, maxEvents));
    }

    /// Read local; forward to the HRW owner ONLY when the local read came back empty. Keeps a node that
    /// actually holds the data serving it (no needless forward), while a genuinely data-less node forwards
    /// to recover the log from the owner.
    private Promise<List<E>> localThenForwardIfEmpty(String streamName, int partition, long fromOffset, int maxEvents) {
        return localReader.read(streamName, partition, fromOffset, maxEvents)
                          .flatMap(local -> local.isEmpty()
                                            ? forwardToOwner(streamName, partition, fromOffset, maxEvents)
                                            : Promise.success(local));
    }

    /// #345 item 1e: route a `LINEARIZABLE` read to the COMMITTED owner of `(streamName, partition)`. The
    /// committed owner is the fenced `StreamPartitionOwnershipValue.owner`, which during a reshuffle
    /// diverges from the on-the-fly HRW owner the other arms forward to — routing here is what makes the
    /// read linearizable. With no committed-owner source wired or no committed record yet (legacy /
    /// unowned partition) the read degrades to the replica-routed behaviour so nothing breaks.
    private Promise<List<E>> readLinearizable(String streamName, int partition, long fromOffset, int maxEvents) {
        return committedOwnerSource.flatMap(source -> source.committedOwner(streamName, partition))
                                   .map(committed -> routeToCommittedOwner(committed,
                                                                           streamName,
                                                                           partition,
                                                                           fromOffset,
                                                                           maxEvents))
                                   .or(() -> readFromReplicaOrLocal(streamName, partition, fromOffset, maxEvents));
    }

    private Promise<List<E>> routeToCommittedOwner(CommittedOwner committed,
                                                   String streamName,
                                                   int partition,
                                                   long fromOffset,
                                                   int maxEvents) {
        return committed.owner()
                        .equals(selfNodeId)
               ? ownerServe.serveAsCommittedOwner(committed, streamName, partition, fromOffset, maxEvents)
               : forwardReadToCommittedOwner(committed.owner(), streamName, partition, fromOffset, maxEvents);
    }

    private Promise<List<E>> forwardReadToCommittedOwner(NodeId owner,
                                                         String streamName,
                                                         int partition,
                                                         long fromOffset,
                                                         int maxEvents) {
        return forwardClient.map(client -> forwardReadStrict(client, owner, streamName, partition, fromOffset, maxEvents))
                            .or(() -> new StreamError.NotCurrentOwner(streamName, partition, owner, selfNodeId).promise());
    }

    /// LINEARIZABLE forward to the committed owner — UNLIKE {@link #forwardReadToNode}, a remote failure
    /// is PROPAGATED, never softened to a local read on this non-owner node: a linearizable read may only
    /// be served by the committed owner, so on a forward failure the client must retry rather than read a
    /// stale local copy.
    private Promise<List<E>> forwardReadStrict(StreamForwardClient client,
                                               NodeId owner,
                                               String streamName,
                                               int partition,
                                               long fromOffset,
                                               int maxEvents) {
        LOG.log(System.Logger.Level.DEBUG,
                "ReadPreference {0}: linearizable owner-forward to {1} for {2}[{3}]",
                readPreference,
                owner,
                streamName,
                partition);

        return client.readRemote(owner, streamName, partition, fromOffset, maxEvents, ReadPreference.LINEARIZABLE)
                     .map(result -> remoteDecoder.decode(result.events(),
                                                         partition));
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
        var caughtUpRemotes = reg.replicasFor(streamName, partition).stream().filter(ForwardingReadRouter::isCaughtUp).filter(r -> !r.nodeId()
                                                                                                                                     .equals(selfNodeId)).toList();

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
                  .filter(r -> r.nodeId()
                                .equals(selfNodeId))
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
                     .map(result -> remoteDecoder.decode(result.events(),
                                                         partition))
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

        return client.readRemote(primary.nodeId(),
                                 streamName,
                                 partition,
                                 fromOffset,
                                 maxEvents).map(result -> remoteDecoder.decode(result.events(),
                                                                               partition))
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
        var alternatives = pool.stream().filter(r -> !r.nodeId()
                                                       .equals(primary.nodeId())).toList();

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

        return client.readRemote(retry.nodeId(),
                                 streamName,
                                 partition,
                                 fromOffset,
                                 maxEvents)
                     .map(result -> remoteDecoder.decode(result.events(),
                                                         partition));
    }

    private static ReplicaDescriptor pickReplica(List<ReplicaDescriptor> pool) {
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    private static boolean isCaughtUp(ReplicaDescriptor descriptor) {
        return descriptor.state() == ReplicationState.CAUGHT_UP;
    }
}
