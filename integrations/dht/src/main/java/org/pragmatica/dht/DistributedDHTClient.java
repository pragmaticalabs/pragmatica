/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.dht;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.utility.IdGenerator;

import static org.pragmatica.lang.Unit.unit;


/// Distributed DHT client with quorum-based reads and writes.
/// Routes operations to responsible nodes via consistent hashing and DHTNetwork.
public final class DistributedDHTClient implements DHTClient {
    private final DHTNode node;
    private final DHTNetwork network;
    private final DHTConfig config;
    private final OwnerEpochSource ownerEpochSource;
    /// Pending operations indexed by correlation ID.
    private final ConcurrentHashMap<String, PendingOperation<?>> pendingOps = new ConcurrentHashMap<>();

    private record PendingOperation<T>(QuorumCollector<T> collector) {}

    private DistributedDHTClient(DHTNode node,
                                 DHTNetwork network,
                                 DHTConfig config,
                                 OwnerEpochSource ownerEpochSource) {
        this.node = node;
        this.network = network;
        this.config = config;
        this.ownerEpochSource = ownerEpochSource;
    }

    /// Create a distributed DHT client at the unfenced epoch floor ([OwnerEpochSource#zero]).
    ///
    /// @param node    local DHT node for handling local storage operations
    /// @param network DHT network for inter-node messaging
    /// @param config  DHT configuration (replication factor, quorum sizes)
    public static DistributedDHTClient distributedDHTClient(DHTNode node, DHTNetwork network, DHTConfig config) {
        return new DistributedDHTClient(node, network, config, OwnerEpochSource.zero());
    }

    /// Create a distributed DHT client that stamps every put with the node's current owner epoch
    /// from `ownerEpochSource` (#345 piece 1c), so replicas can fence a deposed owner's write.
    ///
    /// @param node             local DHT node for handling local storage operations
    /// @param network          DHT network for inter-node messaging
    /// @param config           DHT configuration (replication factor, quorum sizes)
    /// @param ownerEpochSource ambient source of this node's current owner epoch for stamping puts
    public static DistributedDHTClient distributedDHTClient(DHTNode node,
                                                            DHTNetwork network,
                                                            DHTConfig config,
                                                            OwnerEpochSource ownerEpochSource) {
        return new DistributedDHTClient(node, network, config, ownerEpochSource);
    }

    @Override
    public DHTClient scoped(DHTConfig scopedConfig) {
        return new DistributedDHTClient(node, network, scopedConfig, ownerEpochSource);
    }

    @Override
    public DHTConfig config() {
        return config;
    }

    @Override
    public Promise<Option<byte[]>> get(byte[] key) {
        var targets = targetNodes(key);

        if (targets.isEmpty()) {
            return DHTError.NO_AVAILABLE_NODES.promise();
        }

        var quorum = config.effectiveReadQuorum(node.ring().nodeCount());

        if (quorumUnreachable(targets, quorum)) {
            return DHTError.quorumNotReached(quorum,
                                             targets.size())
                           .promise();
        }

        Promise<Option<byte[]>> promise = Promise.promise();
        var collector = QuorumCollector.<Option<byte[]>> quorumCollector(quorum, targets.size(), promise);

        for (var target : targets) {
            if (target.equals(node.nodeId())) {
                handleLocalGet(key, collector);
            } else {
                sendRemoteGet(target, key, collector);
            }
        }

        return promise.timeout(config.operationTimeout());
    }

    @Override
    public Promise<Unit> put(byte[] key, byte[] value) {
        var targets = targetNodes(key);

        if (targets.isEmpty()) {
            return DHTError.NO_AVAILABLE_NODES.promise();
        }

        var quorum = config.effectiveWriteQuorum(node.ring().nodeCount());

        if (quorumUnreachable(targets, quorum)) {
            return DHTError.quorumNotReached(quorum,
                                             targets.size())
                           .promise();
        }

        var version = node.hlcClock().now().packed();
        var epochTerm = ownerEpochSource.currentEpochTerm();
        var epochCounter = ownerEpochSource.currentEpochCounter();
        Promise<Unit> promise = Promise.promise();
        var collector = QuorumCollector.<Unit> quorumCollector(quorum, targets.size(), promise);

        for (var target : targets) {
            if (target.equals(node.nodeId())) {
                handleLocalPut(key, value, version, epochTerm, epochCounter, collector);
            } else {
                sendRemotePut(target, key, value, version, epochTerm, epochCounter, collector);
            }
        }

        return promise.timeout(config.operationTimeout());
    }

    @Override
    public Promise<Boolean> remove(byte[] key) {
        var targets = targetNodes(key);

        if (targets.isEmpty()) {
            return DHTError.NO_AVAILABLE_NODES.promise();
        }

        var quorum = config.effectiveWriteQuorum(node.ring().nodeCount());

        if (quorumUnreachable(targets, quorum)) {
            return DHTError.quorumNotReached(quorum,
                                             targets.size())
                           .promise();
        }

        Promise<Boolean> promise = Promise.promise();
        var collector = QuorumCollector.<Boolean> quorumCollector(quorum, targets.size(), promise);

        for (var target : targets) {
            if (target.equals(node.nodeId())) {
                handleLocalRemove(key, collector);
            } else {
                sendRemoteRemove(target, key, collector);
            }
        }

        return promise.timeout(config.operationTimeout());
    }

    @Override
    public Promise<Boolean> exists(byte[] key) {
        var targets = targetNodes(key);

        if (targets.isEmpty()) {
            return DHTError.NO_AVAILABLE_NODES.promise();
        }

        var quorum = config.effectiveReadQuorum(node.ring().nodeCount());

        if (quorumUnreachable(targets, quorum)) {
            return DHTError.quorumNotReached(quorum,
                                             targets.size())
                           .promise();
        }

        Promise<Boolean> promise = Promise.promise();
        var collector = QuorumCollector.<Boolean> quorumCollector(quorum, targets.size(), promise);

        for (var target : targets) {
            if (target.equals(node.nodeId())) {
                handleLocalExists(key, collector);
            } else {
                sendRemoteExists(target, key, collector);
            }
        }

        return promise.timeout(config.operationTimeout());
    }

    @Override
    public Partition partitionFor(byte[] key) {
        return node.partitionFor(key);
    }

    /// Get the underlying node.
    public DHTNode node() {
        return node;
    }

    /// Get the HLC clock (shared with DHTNode).
    public HlcClock hlcClock() {
        return node.hlcClock();
    }

    // --- Response handlers (called by message router) ---
    /// Handle a get response from a remote node.
    @Contract
    public void onGetResponse(DHTMessage.GetResponse response) {
        removePending(response.requestId()).onPresent(op -> castCollector(op, Option.class).onSuccess(response.value()));
    }

    /// Handle a put response from a remote node.
    @Contract
    public void onPutResponse(DHTMessage.PutResponse response) {
        removePending(response.requestId()).onPresent(op -> {
            if (response.success()) {
                castCollector(op, Unit.class).onSuccess(unit());
            } else {
                failCollector(castCollector(op, Unit.class), DHTError.OPERATION_TIMEOUT);
            }
        });
    }

    /// Handle a remove response from a remote node.
    @Contract
    public void onRemoveResponse(DHTMessage.RemoveResponse response) {
        removePending(response.requestId()).onPresent(op -> castCollector(op, Boolean.class).onSuccess(response.found()));
    }

    /// Handle an exists response from a remote node.
    @Contract
    public void onExistsResponse(DHTMessage.ExistsResponse response) {
        removePending(response.requestId()).onPresent(op -> castCollector(op, Boolean.class).onSuccess(response.exists()));
    }

    // --- Private helpers ---
    /// Whether quorum is arithmetically unreachable for this op: after liveness filtering, fewer
    /// live targets remain than the required `quorum`. The `quorum` is derived from the full ring
    /// size ([`DHTConfig#effectiveWriteQuorum`] / [`DHTConfig#effectiveReadQuorum`] capped at the
    /// replication factor), while `targets` is the liveness-filtered subset; when a scale-down /
    /// drain shrinks the live subset below quorum, no combination of responses can satisfy it.
    /// Failing fast here (with [`DHTError.QuorumNotReached`], the SAME cause the failure-accrual
    /// path raises) lets the caller's transient-failure retry kick in immediately rather than
    /// waiting the full per-op timeout — and after Fix 1 has pruned a drained node from the
    /// routing view, the fast retry succeeds against the remaining live replicas.
    private static boolean quorumUnreachable(List<NodeId> targets, int quorum) {
        return targets.size() < quorum;
    }

    private List<NodeId> targetNodes(byte[] key) {
        var ringTargets = node.ring().nodesFor(key,
                                               config.effectiveReplicationFactor(node.ring().nodeCount()));

        return filterByLiveness(ringTargets);
    }

    /// Filter the static consistent-hash target list to peers currently reachable from
    /// this node. The ring describes ownership (which replicas are responsible for the
    /// key); reachability is decided at runtime by the transport. Pre-filtering targets
    /// avoids stalling the `QuorumCollector` on replicas that have no chance of
    /// responding within the per-op timeout.
    ///
    /// When `network.livePeers()` returns the empty set (default impl, no
    /// connectivity-introspection adapter), the ring targets are returned unchanged —
    /// preserving the pre-RC1 behaviour for non-cluster paths (e.g. worker DHT).
    ///
    /// See `aether/docs/specs/dht-resilience-spec.md` Layer 2.
    private List<NodeId> filterByLiveness(List<NodeId> ringTargets) {
        var live = network.livePeers();

        if (live.isEmpty()) {
            return ringTargets;
        }

        return ringTargets.stream()
                          .filter(live::contains)
                          .toList();
    }

    private Option<PendingOperation<?>> removePending(String correlationId) {
        return Option.option(pendingOps.remove(correlationId));
    }

    @SuppressWarnings("unchecked")
    private <T> QuorumCollector<T> castCollector(PendingOperation<?> op, Class<?> ignored) {
        return (QuorumCollector<T>) op.collector();
    }

    /// Record a failed replica response on the collector. [`QuorumCollector#onFailure`] is a
    /// `@Contract` void mutator (it has no monadic return), so nothing is actually discarded here;
    /// the suppression silences the textual RET-07 chain-terminal heuristic, which cannot see the
    /// void return type.
    @SuppressWarnings("JBCT-RET-07")
    private static <T> void failCollector(QuorumCollector<T> collector, Cause cause) {
        collector.onFailure(cause);
    }

    /// Route the local get Promise into the quorum collector. The Promise's outcome is fully
    /// observed by the success/failure callbacks below; the Promise handle itself is intentionally
    /// not retained (the collector owns resolution of the outer per-op promise).
    private void handleLocalGet(byte[] key, QuorumCollector<Option<byte[]>> collector) {
        var _ = node.getLocal(key).onSuccess(collector::onSuccess).onFailure(collector::onFailure);
    }

    private void handleLocalPut(byte[] key,
                                byte[] value,
                                long version,
                                long epochTerm,
                                long epochCounter,
                                QuorumCollector<Unit> collector) {
        var _ = node.storage()
                    .putVersioned(key, value, version, epochTerm, epochCounter)
                    .onSuccess(_ -> collector.onSuccess(unit()))
                    .onFailure(collector::onFailure);
    }

    private void handleLocalRemove(byte[] key, QuorumCollector<Boolean> collector) {
        var _ = node.removeLocal(key).onSuccess(collector::onSuccess).onFailure(collector::onFailure);
    }

    private void handleLocalExists(byte[] key, QuorumCollector<Boolean> collector) {
        var _ = node.existsLocal(key).onSuccess(collector::onSuccess).onFailure(collector::onFailure);
    }

    private void sendRemoteGet(NodeId target, byte[] key, QuorumCollector<Option<byte[]>> collector) {
        var correlationId = IdGenerator.generate();

        pendingOps.put(correlationId, new PendingOperation<>(collector));
        dispatchTracked(target,
                        new DHTMessage.GetRequest(correlationId, node.nodeId(), key),
                        correlationId,
                        collector);
    }

    private void sendRemotePut(NodeId target,
                               byte[] key,
                               byte[] value,
                               long version,
                               long epochTerm,
                               long epochCounter,
                               QuorumCollector<Unit> collector) {
        var correlationId = IdGenerator.generate();

        pendingOps.put(correlationId, new PendingOperation<>(collector));
        dispatchTracked(target,
                        new DHTMessage.PutRequest(correlationId,
                                                  node.nodeId(),
                                                  key,
                                                  value,
                                                  version,
                                                  epochTerm,
                                                  epochCounter),
                        correlationId,
                        collector);
    }

    private void sendRemoteRemove(NodeId target, byte[] key, QuorumCollector<Boolean> collector) {
        var correlationId = IdGenerator.generate();

        pendingOps.put(correlationId, new PendingOperation<>(collector));
        dispatchTracked(target,
                        new DHTMessage.RemoveRequest(correlationId, node.nodeId(), key),
                        correlationId,
                        collector);
    }

    private void sendRemoteExists(NodeId target, byte[] key, QuorumCollector<Boolean> collector) {
        var correlationId = IdGenerator.generate();

        pendingOps.put(correlationId, new PendingOperation<>(collector));
        dispatchTracked(target,
                        new DHTMessage.ExistsRequest(correlationId, node.nodeId(), key),
                        correlationId,
                        collector);
    }

    /// Fan a request to a remote target via `sendOutcome`, and short-circuit the per-op
    /// quorum waiting when the transport refuses synchronously.
    ///
    /// On `Sent` outcome: nothing else to do — the response will arrive via the regular
    /// message-routing path and resolve the collector through `handleRemote*Response`.
    /// On any refusal outcome: remove the pending op and call `collector.onFailure` with
    /// an appropriate `Cause` so the `QuorumCollector` fast-fail logic (`failures > total
    /// - quorum` → `promise.fail`) fires immediately, rather than waiting the full
    /// per-op `operationTimeout`. This is the architectural answer to the 1MB-push hang
    /// and 08-resources/Deploy_SQL_app slowdown — see
    /// `aether/docs/specs/dht-resilience-spec.md` Layer 3.
    private <T> void dispatchTracked(NodeId target,
                                     ProtocolMessage message,
                                     String correlationId,
                                     QuorumCollector<T> collector) {
        var _ = network.sendOutcome(target, message)
                       .onSuccess(outcome -> {
                                      if (!outcome.isSent()) {
                                      pendingOps.remove(correlationId);
                                      failCollector(collector,
                                                    toCause(outcome));
                                  }
                                  })
                       .onFailure(cause -> {
                           pendingOps.remove(correlationId);
                           failCollector(collector, cause);
                       });
    }

    private static Cause toCause(WriteOutcome outcome) {
        return switch (outcome) {
            case WriteOutcome.Sent ignored -> DHTError.OPERATION_TIMEOUT;  // unreachable; defensive default
            case WriteOutcome.BackpressureRefused refused -> DHTError.peerUnreachable(refused.peerId(), "backpressure");
            case WriteOutcome.ConnectionDead dead -> DHTError.peerUnreachable(dead.peerId(), "connection dead");
            case WriteOutcome.NoPeerState nope -> DHTError.peerUnreachable(nope.peerId(), "no peer state");
        };
    }
}
