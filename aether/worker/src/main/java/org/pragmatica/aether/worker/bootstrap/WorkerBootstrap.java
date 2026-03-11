package org.pragmatica.aether.worker.bootstrap;

import org.pragmatica.aether.worker.WorkerError;
import org.pragmatica.aether.worker.network.WorkerNetwork;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Handles initial KV state bootstrap for a newly joining worker.
///
/// Bootstrap steps:
/// 1. Request a snapshot from the governor (or any core node)
/// 2. Apply the snapshot to the local KVStore
/// 3. Start the Decision stream from the snapshot's sequence number
///
/// The snapshot is requested via the WorkerNetwork TCP connection.
@SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01"})
public final class WorkerBootstrap {
    private static final Logger LOG = LoggerFactory.getLogger(WorkerBootstrap.class);

    private final NodeId selfId;
    private final WorkerNetwork network;
    private final KVStore<?, ?> kvStore;
    private volatile boolean bootstrapped;
    private volatile long snapshotSequence = - 1;

    private WorkerBootstrap(NodeId selfId, WorkerNetwork network, KVStore<?, ?> kvStore) {
        this.selfId = selfId;
        this.network = network;
        this.kvStore = kvStore;
        this.bootstrapped = false;
    }

    /// Factory method.
    public static WorkerBootstrap workerBootstrap(NodeId selfId, WorkerNetwork network, KVStore<?, ?> kvStore) {
        return new WorkerBootstrap(selfId, network, kvStore);
    }

    /// The sequence number at which the snapshot was taken, or -1 if no snapshot applied.
    public long snapshotSequence() {
        return snapshotSequence;
    }

    /// Request a snapshot from the given source node.
    public void requestSnapshot(Option<NodeId> source) {
        source.onPresent(this::sendSnapshotRequest)
              .onEmpty(() -> LOG.warn("No snapshot source available for bootstrap"));
    }

    /// Handle a received snapshot response.
    public Promise<Unit> onSnapshotReceived(SnapshotResponse response) {
        return Promise.lift(WorkerError.NetworkFailure::new, () -> applySnapshot(response));
    }

    /// Handle incoming snapshot requests (governor responds with snapshot).
    public void onSnapshotRequest(SnapshotRequest request, byte[] kvState, long sequenceNumber) {
        var response = SnapshotResponse.snapshotResponse(kvState, sequenceNumber);
        network.send(request.requester(), response);
        LOG.info("Sent snapshot to {} at sequence {}",
                 request.requester()
                        .id(),
                 sequenceNumber);
    }

    /// Whether this node has completed bootstrapping.
    public boolean isBootstrapped() {
        return bootstrapped;
    }

    /// Mark bootstrap as complete.
    public void markBootstrapped() {
        bootstrapped = true;
    }

    private void sendSnapshotRequest(NodeId source) {
        var request = SnapshotRequest.snapshotRequest(selfId);
        network.send(source, request);
        LOG.info("Requested snapshot from {}", source.id());
    }

    private void applySnapshot(SnapshotResponse response) {
        LOG.info("Applying snapshot at sequence {}, size={} bytes", response.sequenceNumber(), response.kvState().length);
        kvStore.restoreSnapshot(response.kvState())
               .onSuccess(_ -> markSnapshotApplied(response.sequenceNumber()))
               .onFailure(cause -> LOG.error("Failed to apply snapshot: {}", cause));
    }

    private void markSnapshotApplied(long sequenceNumber) {
        snapshotSequence = sequenceNumber;
        bootstrapped = true;
        LOG.info("Snapshot applied successfully at sequence {}", sequenceNumber);
    }
}
