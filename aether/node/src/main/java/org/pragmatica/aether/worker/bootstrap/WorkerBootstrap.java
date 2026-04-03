package org.pragmatica.aether.worker.bootstrap;

import org.pragmatica.aether.worker.WorkerError;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter.DelegateRouter;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Handles initial KV state bootstrap for a newly joining worker.
///
/// Bootstrap steps:
/// 1. Request a snapshot from the governor (or any core node)
/// 2. Apply the snapshot to the local KVStore
/// 3. Start the Decision stream from the snapshot's sequence number
///
/// The snapshot is requested via the cluster network (QuicClusterNetwork) connection.
@SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01"}) public interface WorkerBootstrap {
    Logger LOG = LoggerFactory.getLogger(WorkerBootstrap.class);

    long snapshotSequence();
    void requestSnapshot(Option<NodeId> source);
    Promise<Unit> onSnapshotReceived(SnapshotResponse response);
    void onSnapshotRequest(SnapshotRequest request, byte[] kvState, long sequenceNumber);
    boolean isBootstrapped();
    void markBootstrapped();
    int incrementRetry();

    static WorkerBootstrap workerBootstrap(NodeId selfId, DelegateRouter delegateRouter, KVStore<?, ?> kvStore) {
        record workerBootstrap(NodeId selfId,
                               DelegateRouter delegateRouter,
                               KVStore<?, ?> kvStore,
                               AtomicBoolean bootstrapped,
                               AtomicLong snapshotSequenceHolder,
                               AtomicInteger retryCounter) implements WorkerBootstrap {
            @Override public long snapshotSequence() {
                return snapshotSequenceHolder.get();
            }

            @Override public void requestSnapshot(Option<NodeId> source) {
                source.onPresent(this::sendSnapshotRequest)
                                .onEmpty(() -> LOG.warn("No snapshot source available for bootstrap"));
            }

            @Override public Promise<Unit> onSnapshotReceived(SnapshotResponse response) {
                return Promise.lift(WorkerError.NetworkFailure::new, () -> applySnapshot(response));
            }

            @Override public void onSnapshotRequest(SnapshotRequest request, byte[] kvState, long sequenceNumber) {
                var response = SnapshotResponse.snapshotResponse(kvState, sequenceNumber);
                delegateRouter.route(new NetworkServiceMessage.Send(request.requester(), response));
                LOG.info("Sent snapshot to {} at sequence {}",
                         request.requester().id(),
                         sequenceNumber);
            }

            @Override public boolean isBootstrapped() {
                return bootstrapped.get();
            }

            @Override public void markBootstrapped() {
                bootstrapped.set(true);
            }

            @Override public int incrementRetry() {
                return retryCounter.incrementAndGet();
            }

            private void sendSnapshotRequest(NodeId source) {
                var request = SnapshotRequest.snapshotRequest(selfId);
                delegateRouter.route(new NetworkServiceMessage.Send(source, request));
                LOG.info("Requested snapshot from {}", source.id());
            }

            private void applySnapshot(SnapshotResponse response) {
                LOG.info("Applying snapshot at sequence {}, size={} bytes",
                         response.sequenceNumber(),
                         response.kvState().length);
                kvStore.restoreSnapshot(response.kvState()).onSuccess(_ -> markSnapshotApplied(response.sequenceNumber()))
                                       .onFailure(cause -> LOG.error("Failed to apply snapshot: {}", cause));
            }

            private void markSnapshotApplied(long sequenceNumber) {
                snapshotSequenceHolder.set(sequenceNumber);
                bootstrapped.set(true);
                LOG.info("Snapshot applied successfully at sequence {}", sequenceNumber);
            }
        }
        return new workerBootstrap(selfId,
                                   delegateRouter,
                                   kvStore,
                                   new AtomicBoolean(false),
                                   new AtomicLong(- 1),
                                   new AtomicInteger(0));
    }
}
