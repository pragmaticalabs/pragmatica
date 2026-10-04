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

import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.utility.IdGenerator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Distributed DHT client with quorum-based reads and writes.
/// Routes operations to responsible nodes via consistent hashing and DHTNetwork.
public final class DistributedDHTClient implements DHTClient {
    private static final Logger log = LoggerFactory.getLogger(DistributedDHTClient.class);
    /// Upper bound on the resolve-time fallback ring probe (issue #428, C2): after an R-set quorum
    /// MISS, at most this many ring members OUTSIDE the R-set are probed for a stranded copy. Keeps
    /// the mitigation a bounded, best-effort cache-warmth pass rather than an unbounded ring scan.
    private static final int DEFAULT_FALLBACK_PROBE_LIMIT = 8;
    /// Upper bound on replacement requests a single quorum read may issue after its targets depart
    /// the ring mid-read (each departed target costs at most one). Bounds the work a churning ring can
    /// inflict on one read; past it the departed slot fails instead of being replaced.
    private static final int DEFAULT_READ_REISSUE_LIMIT = 3;
    private static final HexFormat HEX = HexFormat.of();

    private final DHTNode node;
    private final DHTNetwork network;
    /// The replication this client places keys and sizes quorums with: the node's live, committed replication for
    /// the base client (#1777 track 1), or a namespace's own declaration for a scoped one.
    private final Supplier<DHTConfig> config;
    private final OwnerEpochSource ownerEpochSource;
    private final ResolveFallbackObserver fallbackObserver;
    /// Pending operations indexed by correlation ID.
    private final ConcurrentHashMap<String, PendingOperation<?>> pendingOps = new ConcurrentHashMap<>();

    private record PendingOperation<T>(QuorumCollector<T> collector) {}

    private DistributedDHTClient(DHTNode node,
                                 DHTNetwork network,
                                 Supplier<DHTConfig> config,
                                 OwnerEpochSource ownerEpochSource,
                                 ResolveFallbackObserver fallbackObserver) {
        this.node = node;
        this.network = network;
        this.config = config;
        this.ownerEpochSource = ownerEpochSource;
        this.fallbackObserver = fallbackObserver;
    }

    /// Create a distributed DHT client at the unfenced epoch floor ([OwnerEpochSource#zero]).
    ///
    /// @param node    local DHT node for handling local storage operations
    /// @param network DHT network for inter-node messaging
    /// @param config  DHT configuration (replication factor, quorum sizes)
    public static DistributedDHTClient distributedDHTClient(DHTNode node, DHTNetwork network, DHTConfig config) {
        return new DistributedDHTClient(node,
                                        network,
                                        () -> config,
                                        OwnerEpochSource.zero(),
                                        ResolveFallbackObserver.noop());
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
        return new DistributedDHTClient(node, network, () -> config, ownerEpochSource, ResolveFallbackObserver.noop());
    }

    /// Create a distributed DHT client that follows the node's LIVE replication ([DHTNode#config]): the cluster's
    /// committed `[replication]` factors, re-read on every operation so a committed change takes effect without a
    /// restart (#1777 track 1). Stamps every put with the node's current owner epoch like
    /// [#distributedDHTClient(DHTNode, DHTNetwork, DHTConfig, OwnerEpochSource)].
    public static DistributedDHTClient distributedDHTClient(DHTNode node,
                                                            DHTNetwork network,
                                                            OwnerEpochSource ownerEpochSource) {
        return new DistributedDHTClient(node, network, node::config, ownerEpochSource, ResolveFallbackObserver.noop());
    }

    /// Return a client that reports resolve-time alternate-target fallback outcomes (issue #428, C2)
    /// to `observer`. Additive and non-mutating: the base factories default to
    /// [ResolveFallbackObserver#noop], so existing callers and their R-set quorum contract are
    /// unaffected; the aether layer wires a real observer to surface fallback hits/misses.
    ///
    /// @param observer sink for fallback-resolved and unresolved-after-fallback notifications
    public DistributedDHTClient withResolveFallbackObserver(ResolveFallbackObserver observer) {
        return new DistributedDHTClient(node, network, config, ownerEpochSource, observer);
    }

    @Override
    public DHTClient scoped(DHTConfig scopedConfig) {
        return scoped(() -> scopedConfig);
    }

    /// A client for a namespace with its own declared replication, read on every operation so a committed change
    /// of the declaration takes effect without a restart (#1777 track 1, e.g. the cache's `[cache]` factors).
    public DistributedDHTClient scoped(Supplier<DHTConfig> scopedConfig) {
        return new DistributedDHTClient(node, network, scopedConfig, ownerEpochSource, fallbackObserver);
    }

    @Override
    public DHTConfig config() {
        return config.get();
    }

    @Override
    public Promise<Option<byte[]>> get(byte[] key) {
        if (!node.replicationResolved()) {
            return DHTError.REPLICATION_UNRESOLVED.promise();
        }

        var targets = targetNodes(key);

        if (targets.isEmpty()) {
            return DHTError.NO_AVAILABLE_NODES.promise();
        }

        var quorum = config.get().effectiveReadQuorum(node.ring().nodeCount());

        if (quorumUnreachable(targets, quorum)) {
            return DHTError.quorumNotReached(quorum,
                                             targets.size())
                           .promise();
        }

        Promise<Option<byte[]>> promise = Promise.promise();
        var deadlineNanos = readDeadlineNanos();
        var collector = QuorumCollector.<Option<byte[]>> quorumCollector(quorum, targets.size(), promise);
        var read = InFlightRead.inFlightRead(key, collector, deadlineNanos, DEFAULT_READ_REISSUE_LIMIT);
        var unsubscribe = node.ring().onNodeRemoved(departed -> reissueAfterDeparture(read, departed));
        // every original target is addressed before the first dispatch, so a departure landing mid-loop can
        // never pick a not-yet-dispatched target as its replacement (one replica must never fill two slots)
        targets.forEach(read::markAddressed);
        targets.forEach(target -> dispatchRead(read, target));

        return promise.timeout(config.get().operationTimeout())
                      .withResult(_ -> unsubscribe.run())
                      .flatMap(quorumResult -> resolveOrFallback(key,
                                                                 quorumResult,
                                                                 collector,
                                                                 targets.size()));
    }

    @Override
    public Promise<Unit> put(byte[] key, byte[] value) {
        if (!node.replicationResolved()) {
            return DHTError.REPLICATION_UNRESOLVED.promise();
        }

        var targets = targetNodes(key);

        if (targets.isEmpty()) {
            return DHTError.NO_AVAILABLE_NODES.promise();
        }

        var quorum = config.get().effectiveWriteQuorum(node.ring().nodeCount());

        if (quorumUnreachable(targets, quorum)) {
            return DHTError.quorumNotReached(quorum,
                                             targets.size())
                           .promise();
        }

        var stamp = new WriteStamp(node.hlcClock().now().packed(),
                                   ownerEpochSource.currentEpochIncarnation(),
                                   ownerEpochSource.currentEpochTerm(),
                                   ownerEpochSource.currentEpochCounter(),
                                   node.replicationFence());
        Promise<Unit> promise = Promise.promise();
        var collector = QuorumCollector.<Unit> quorumCollector(quorum, targets.size(), promise);
        var localPut = targets.contains(node.nodeId())
                       ? Option.some(handleLocalPut(key, value, stamp, collector))
                       : Option.<Promise<Boolean>> none();

        targets.stream()
               .filter(target -> !target.equals(node.nodeId()))
               .forEach(target -> sendRemotePut(target, key, value, stamp, collector));

        var hasRemote = targets.stream()
                               .anyMatch(target -> !target.equals(node.nodeId()));

        collector.allReplied()
                 .onSuccess(_ -> noteLateStale(collector, stamp));

        return promise.timeout(config.get().operationTimeout())
                      .flatMap(_ -> confirmedByReplicas(collector, quorum, hasRemote))
                      .fold(result -> result.fold(cause -> afterFailedPut(key,
                                                                          stamp,
                                                                          localPut,
                                                                          indeterminateIfFenced(cause, quorum, collector)),
                                                  Promise::success))
                      .onFailure(cause -> noteIfStale(cause, stamp))
                      .onSuccess(_ -> clearIfNoReplicaRefusedAsStale(collector));
    }

    /// A put whose quorum is met is acknowledged only if no replica has refused it as stale (#1777 v1882 r6 F10): a
    /// [DHTError.ReplicationChangeStale] refusal from ANY target is authoritative evidence that this writer is behind, so
    /// the put fails and the caller retries under the newer change. When the quorum was met by this node's own slot alone
    /// (W_old = 1 and the writer is a replica), the local slot — which no fence guards — says nothing about the others, so
    /// the acknowledgement waits for the first reply of any kind from a remote target.
    /// [limit: a replica that applied the newer change but stays silent for the rest of the operation timeout cannot refute
    /// the acknowledgement; #1683-class] A refusal that arrives AFTER the acknowledgement cannot revoke it; it is recorded
    /// ([#noteLateStale]) and the copies the other replicas accepted are pulled by the writers-switched catch-up.
    private Promise<Unit> confirmedByReplicas(QuorumCollector<Unit> collector, int quorum, boolean hasRemote) {
        if (collector.replicationStaleCount() > 0) {
            return staleFailure(collector, quorum);
        }

        if (!hasRemote || collector.remoteSuccessCount() > 0) {
            return Promise.success(unit());
        }

        var remaining = Math.max(config.get().operationTimeout().millis() - collector.elapsedMillis(), 1L);

        return collector.remoteReplied()
                        .timeout(timeSpan(remaining).millis())
                        .fold(_ -> collector.replicationStaleCount() > 0
                                   ? staleFailure(collector, quorum)
                                   : Promise.success(unit()));
    }

    private static Promise<Unit> staleFailure(QuorumCollector<Unit> collector, int quorum) {
        return DHTError.replicationChangeStale(quorum, collector.successCount(), collector.replicationStaleCount())
                       .promise();
    }

    /// A refusal that arrived after the put was acknowledged still says this writer is behind: record it, so the
    /// stale-writer clock starts (the F9 guard in [DHTNode#noteStaleRefusal] drops it if the writer has adopted since).
    @Contract
    private void noteLateStale(QuorumCollector<Unit> collector, WriteStamp stamp) {
        if (collector.replicationStaleCount() > 0) {
            node.noteStaleRefusal(stamp.replicationVersion(), System.currentTimeMillis());
        }
    }

    /// Any accepted write ends a stale episode — but not one that a replica refused as stale (v1882 r6 F10).
    @Contract
    private void clearIfNoReplicaRefusedAsStale(QuorumCollector<Unit> collector) {
        if (collector.replicationStaleCount() == 0) {
            node.clearStaleRefusal();
        }
    }

    /// #1777 (owner rule): a put refused because replicas applied a NEWER change starts this node's stale-writer clock. A
    /// refusal by replicas that do not know the change yet ([DHTError.ReplicationFenceUnknown]) says nothing about this
    /// writer and starts nothing; any accepted write ends the clock.
    private void noteIfStale(Cause cause, WriteStamp stamp) {
        if (cause instanceof DHTError.ReplicationChangeStale) {
            node.noteStaleRefusal(stamp.replicationVersion(), System.currentTimeMillis());
        }
    }

    /// The version and owner epoch one put is stamped with — what a rollback must match exactly — and the replication
    /// change its quorum was sized under (#1777 R1c), read when the put starts.
    private record WriteStamp(long version,
                              long epochIncarnation,
                              long epochTerm,
                              long epochCounter,
                              long replicationVersion) {}

    /// A put that lost its quorum to owner-epoch fences is INDETERMINATE (#1818, the owner's fence ruling):
    /// this node's own store may have accepted it because its high-water lags, and anti-entropy copies bypass
    /// the high-water, so that accept would spread to the replicas that refused it. The coordinator
    /// therefore compare-and-deletes its own accept — only while the stored entry is still exactly the one
    /// it wrote — once its local put has settled. The caller always gets the original cause; a rollback
    /// that fails changes nothing about what the caller must assume (BER: the inverse of the local accept,
    /// best effort, and the residual — another lagging replica that also accepted — is #1777 track 3).
    /// A put that times out after a fence refused it is just as indeterminate as one the collector failed on
    /// fences: a slow or lost reply must not skip the rollback and leave the refused accept to spread.
    private static Cause indeterminateIfFenced(Cause cause, int quorum, QuorumCollector<Unit> collector) {
        return collector.fencedCount() > 0 && !(cause instanceof DHTError.WriteIndeterminate)
               ? DHTError.writeIndeterminate(quorum, collector.successCount(), collector.fencedCount())
               : cause;
    }

    private Promise<Unit> afterFailedPut(byte[] key, WriteStamp stamp, Option<Promise<Boolean>> localPut, Cause cause) {
        return cause instanceof DHTError.WriteIndeterminate || cause instanceof DHTError.ReplicationChangeStale
               ? localPut.map(local -> rollBackLocalAccept(key, stamp, local))
                         .or(Promise.success(false))
                         .fold(_ -> cause.promise())
               : cause.promise();
    }

    private Promise<Boolean> rollBackLocalAccept(byte[] key, WriteStamp stamp, Promise<Boolean> localPut) {
        return localPut.fold(_ -> node.storage()
                                      .removeIfExactly(key,
                                                       stamp.version(),
                                                       stamp.epochIncarnation(),
                                                       stamp.epochTerm(),
                                                       stamp.epochCounter()))
                       .onSuccess(removed -> logRollback(key, removed));
    }

    @Contract
    private void logRollback(byte[] key, boolean removed) {
        log.info("Put of {} was refused by replica fences; local accept {}",
                 hex(key),
                 removed
                 ? "rolled back"
                 : "not present or already superseded");
    }

    @Override
    public Promise<Boolean> remove(byte[] key) {
        if (!node.replicationResolved()) {
            return DHTError.REPLICATION_UNRESOLVED.promise();
        }

        var targets = targetNodes(key);

        if (targets.isEmpty()) {
            return DHTError.NO_AVAILABLE_NODES.promise();
        }

        var quorum = config.get().effectiveWriteQuorum(node.ring().nodeCount());

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

        return promise.timeout(config.get().operationTimeout());
    }

    @Override
    public Promise<Boolean> exists(byte[] key) {
        if (!node.replicationResolved()) {
            return DHTError.REPLICATION_UNRESOLVED.promise();
        }

        var targets = targetNodes(key);

        if (targets.isEmpty()) {
            return DHTError.NO_AVAILABLE_NODES.promise();
        }

        var quorum = config.get().effectiveReadQuorum(node.ring().nodeCount());

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

        return promise.timeout(config.get().operationTimeout());
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
    /// Handle a get response from a remote node. An absent answer from a replica that is still
    /// catching up is a refusal of the slot, not an "absent" vote (#1777 track 2).
    @Contract
    public void onGetResponse(DHTMessage.GetResponse response) {
        removePending(response.requestId()).onPresent(op -> recordGet(castCollector(op, Option.class),
                                                                      response.value(),
                                                                      response.readiness(),
                                                                      response.sender()));
    }

    /// Handle a put response from a remote node. A refusal by the replica's owner-epoch fence is reported as
    /// such, so a quorum lost to fences is indeterminate rather than a definite failure.
    @Contract
    public void onPutResponse(DHTMessage.PutResponse response) {
        removePending(response.requestId()).onPresent(op -> recordPut(castCollector(op, Unit.class), response));
    }

    /// Same `@Contract` void-mutator suppression as [#failCollector].
    @SuppressWarnings("JBCT-RET-07")
    private static void recordPut(QuorumCollector<Unit> collector, DHTMessage.PutResponse response) {
        if (response.success()) {
            collector.onSuccess(unit());
        } else {
            failCollector(collector, putRefusal(response));
        }
    }

    private static Cause putRefusal(DHTMessage.PutResponse response) {
        if (response.fenced()) {
            return DHTError.replicaFenced(response.sender());
        }

        if (response.fenceUnknown()) {
            return DHTError.replicaFenceUnknown(response.sender());
        }

        return response.replicationStale()
               ? DHTError.replicaOnNewerReplication(response.sender())
               : DHTError.OPERATION_TIMEOUT;
    }

    /// Handle a remove response from a remote node.
    @Contract
    public void onRemoveResponse(DHTMessage.RemoveResponse response) {
        removePending(response.requestId()).onPresent(op -> castCollector(op, Boolean.class).onSuccess(response.found()));
    }

    /// Handle an exists response from a remote node. A `false` from a replica still catching up is a
    /// refusal of the slot, like an absent [#onGetResponse] (#1777 track 2).
    @Contract
    public void onExistsResponse(DHTMessage.ExistsResponse response) {
        removePending(response.requestId()).onPresent(op -> recordExists(castCollector(op, Boolean.class),
                                                                         response.exists(),
                                                                         response.readiness(),
                                                                         response.sender()));
    }

    /// Count a get answer: a present value always votes; an absent one only from an authoritative replica.
    private static void recordGet(QuorumCollector<Option<byte[]>> collector,
                                  Option<byte[]> value,
                                  DHTMessage.Readiness readiness,
                                  NodeId sender) {
        if (value.isEmpty() && !readiness.authoritative()) {
            failCollector(collector, DHTError.replicaCatchingUp(sender));
        } else {
            collector.onSuccess(value, sender.id());
        }
    }

    /// Same `@Contract` void-mutator suppression as [#failCollector].
    @SuppressWarnings("JBCT-RET-07")
    private static void recordExists(QuorumCollector<Boolean> collector,
                                     boolean exists,
                                     DHTMessage.Readiness readiness,
                                     NodeId sender) {
        if (!exists && !readiness.authoritative()) {
            failCollector(collector, DHTError.replicaCatchingUp(sender));
        } else {
            collector.onSuccess(exists);
        }
    }

    // --- Private helpers ---
    private long readDeadlineNanos() {
        return System.nanoTime() + config.get()
                                         .operationTimeout()
                                         .nanos();
    }

    private void dispatchRead(InFlightRead read, NodeId target) {
        if (target.equals(node.nodeId())) {
            read.markAddressed(target);
            handleLocalGet(read.key(), read.collector());
        } else {
            sendTrackedGet(read, target);
        }
    }

    private void sendTrackedGet(InFlightRead read, NodeId target) {
        var correlationId = IdGenerator.generate();

        read.expect(target, correlationId);
        sendRemoteGet(target, read.key(), read.collector(), correlationId);
    }

    /// A target of an in-flight read left the ring: its reply may never come, and waiting for it
    /// would burn the whole operation timeout. If it still owed a reply, take over its slot (the
    /// atomic `pendingOps.remove` arbitrates against a concurrent reply, so exactly one side owns the
    /// slot) and re-issue to a replacement replica from the current ring. Quorum semantics are
    /// untouched: same quorum, same slot count, and a departure is never counted as an empty answer.
    private void reissueAfterDeparture(InFlightRead read, NodeId departed) {
        read.claim(departed).flatMap(this::removePending).onPresent(_ -> takeOverDepartedSlot(read, departed));
    }

    private void takeOverDepartedSlot(InFlightRead read, NodeId departed) {
        read.collector().noteDeparted();
        replaceOrFail(read, departed);
    }

    /// Fill a departed target's slot with a replacement, or fail the slot (fast-fail accrual, the same
    /// `QuorumCollector#onFailure` path a transport refusal takes) when no replacement is allowed.
    private void replaceOrFail(InFlightRead read, NodeId departed) {
        read.nextReplacement(targetNodes(read.key()))
            .onPresent(replacement -> dispatchReplacement(read, replacement))
            .onEmpty(() -> failCollector(read.collector(),
                                         DHTError.peerUnreachable(departed, "left the ring mid-read")));
    }

    /// A replacement was not a replica when the value was written, and the survivor rebalance that would
    /// give it a copy starts only after the ring listeners ran. Its "absent" is therefore no evidence of
    /// absence and must not vote: counting it breaks the R+W intersection (an absent original plus an
    /// absent replacement would out-vote a holder still owing its reply). A present answer fills the slot;
    /// an absent one fails it, so the read either finds the value or fails fast as retryable.
    private void dispatchReplacement(InFlightRead read, NodeId replacement) {
        Promise<Option<byte[]>> answer = Promise.promise();
        var single = QuorumCollector.<Option<byte[]>> quorumCollector(1, 1, answer);
        var _ = answer.onSuccess(value -> countReplacementAnswer(read, replacement, value))
                      .onFailure(cause -> failCollector(read.collector(),
                                                        cause));

        if (replacement.equals(node.nodeId())) {
            handleLocalGet(read.key(), single);
        } else {
            var correlationId = IdGenerator.generate();

            read.expect(replacement, correlationId);
            sendRemoteGet(replacement, read.key(), single, correlationId);
        }
    }

    /// Same `@Contract` void-mutator suppression as [#failCollector].
    @SuppressWarnings("JBCT-RET-07")
    private static void countReplacementAnswer(InFlightRead read, NodeId replacement, Option<byte[]> value) {
        if (value.isPresent()) {
            read.collector().onSuccess(value, replacement.id());
        } else {
            failCollector(read.collector(), DHTError.peerUnreachable(replacement, "replacement holds no copy yet"));
        }
    }

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
                                               config.get().effectiveReplicationFactor(node.ring().nodeCount()));

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

    /// Route the R-set quorum outcome (issue #428, C2): a present value passes straight through; a
    /// MISS enters the bounded fallback probe. Pure routing — the resolved value is not transformed.
    private Promise<Option<byte[]>> resolveOrFallback(byte[] key,
                                                      Option<byte[]> quorumResult,
                                                      QuorumCollector<Option<byte[]>> rSetCollector,
                                                      int rSetLive) {
        return quorumResult.isPresent()
               ? Promise.success(quorumResult)
               : fallbackResolve(key, rSetCollector, rSetLive);
    }

    /// Resolve-time alternate-target fallback (issue #428, C2) — staged arm B: a CACHE-WARMTH +
    /// interim-correctness mitigation invoked only on an R-set quorum MISS, never on the hit path.
    /// Probes a BOUNDED set of ring members OUTSIDE the R-set for a stranded copy (e.g. one left
    /// behind by an in-flight rebalance and not yet re-homed). On a hit it read-repairs the value
    /// back onto the current R-set and returns it; on all-miss it reports loudly (never silent —
    /// P3/P4) and returns empty. The durable tier is stage 2, out of scope here.
    ///
    /// FULL replication naturally no-ops: the R-set already spans every node, so the candidate set
    /// (`nodes()` minus the R-set) is empty and this returns empty without probing — stranded-copy
    /// resolution in FULL mode is stage-2 durable-tier territory.
    private Promise<Option<byte[]>> fallbackResolve(byte[] key,
                                                    QuorumCollector<Option<byte[]>> rSetCollector,
                                                    int rSetLive) {
        var fallbackTargets = fallbackTargets(key);

        return fallbackTargets.isEmpty()
               ? reportAfterRSetSettles(key, rSetCollector, rSetLive)
               : probeAndRepair(key, fallbackTargets, rSetCollector, rSetLive);
    }

    /// The ring is no bigger than the R-set (a 3-node cluster, or FULL replication): there is nothing to probe, so
    /// the read's empty rests on the R-set alone. Report it once every R-set reply has arrived (or the operation
    /// timeout passes), WITHOUT delaying the caller: the read returns empty now and the line follows.
    private Promise<Option<byte[]>> reportAfterRSetSettles(byte[] key,
                                                           QuorumCollector<Option<byte[]>> rSetCollector,
                                                           int rSetLive) {
        var _ = rSetCollector.allReplied()
                             .timeout(config.get().operationTimeout())
                             .onResultRun(() -> fallbackObserver.onUnresolvedAfterFallback(missReport(key,
                                                                                                      rSetCollector,
                                                                                                      rSetLive,
                                                                                                      0,
                                                                                                      0)));

        return Promise.success(Option.none());
    }

    /// Bounded ring-probe candidates: every ring member MINUS the R-set already read by the quorum
    /// pass, capped at [#DEFAULT_FALLBACK_PROBE_LIMIT]. Self is excluded when it was an R-set member
    /// (already read via `targetNodes`); when self is NOT in the R-set it stays a candidate and is
    /// probed locally.
    private List<NodeId> fallbackTargets(byte[] key) {
        var rSet = Set.copyOf(targetNodes(key));

        return node.ring()
                   .nodes()
                   .stream()
                   .filter(candidate -> !rSet.contains(candidate))
                   .limit(DEFAULT_FALLBACK_PROBE_LIMIT)
                   .toList();
    }

    private Promise<Option<byte[]>> probeAndRepair(byte[] key,
                                                   List<NodeId> fallbackTargets,
                                                   QuorumCollector<Option<byte[]>> rSetCollector,
                                                   int rSetLive) {
        var probesFailed = new AtomicInteger();

        return Promise.allOf(probeAll(key, fallbackTargets, probesFailed))
                      .map(DistributedDHTClient::firstPresent)
                      .flatMap(found -> resolveFallbackOutcome(key,
                                                               found,
                                                               missReport(key,
                                                                          rSetCollector,
                                                                          rSetLive,
                                                                          fallbackTargets.size(),
                                                                          probesFailed.get())));
    }

    /// The counts an all-miss report carries. Read AFTER the probes settle, so `rSetAnswered` includes R-set
    /// replies that arrived once the quorum had already resolved.
    private ResolveMiss missReport(byte[] key,
                                   QuorumCollector<Option<byte[]>> rSetCollector,
                                   int rSetLive,
                                   int probed,
                                   int probesFailed) {
        var rSetSize = node.ring()
                           .nodesFor(key,
                                     config.get().effectiveReplicationFactor(node.ring().nodeCount()))
                           .size();
        var candidates = node.ring().nodeCount() - rSetLive;

        return ResolveMiss.resolveMiss(hex(key),
                                       rSetSize,
                                       rSetLive,
                                       rSetCollector.successCount(),
                                       probed,
                                       probesFailed,
                                       Math.max(0, candidates - probed),
                                       rSetCollector.elapsedMillis(),
                                       rSetCollector.valueSource().or(""),
                                       rSetCollector.departedCount());
    }

    private List<Promise<Option<byte[]>>> probeAll(byte[] key,
                                                   List<NodeId> fallbackTargets,
                                                   AtomicInteger probesFailed) {
        return fallbackTargets.stream()
                              .map(target -> probeTarget(target, key, probesFailed))
                              .toList();
    }

    /// Single-target best-effort read for the fallback probe: reuses the same local/remote get
    /// primitives as the quorum path but against a lone target (quorum 1 of 1). A transport refusal
    /// or timeout degrades to an empty result rather than failing, so one dead fallback candidate
    /// never aborts the probe.
    private Promise<Option<byte[]>> probeTarget(NodeId target, byte[] key, AtomicInteger probesFailed) {
        Promise<Option<byte[]>> probe = Promise.promise();
        var collector = QuorumCollector.<Option<byte[]>> quorumCollector(1, 1, probe);

        if (target.equals(node.nodeId())) {
            handleLocalGet(key, collector);
        } else {
            sendRemoteGet(target, key, collector);
        }

        return probe.timeout(config.get().operationTimeout())
                    .recover(cause -> degradeAndCount(cause, probesFailed));
    }

    /// First stranded copy in probe order, or empty when every bounded probe missed. Each probe
    /// already degraded failures to a successful empty, so the results are unwrapped defensively.
    private static Option<byte[]> firstPresent(List<Result<Option<byte[]>>> probeResults) {
        return probeResults.stream()
                           .map(result -> result.or(Option.<byte[]> none()))
                           .filter(Option::isPresent)
                           .findFirst()
                           .orElseGet(Option::none);
    }

    private Promise<Option<byte[]>> resolveFallbackOutcome(byte[] key, Option<byte[]> found, ResolveMiss miss) {
        return found.fold(() -> reportUnresolved(miss), value -> repairAndReport(key, value, miss.probed()));
    }

    /// Stranded copy found beyond the R-set: fire the observer, then read-repair it back onto the
    /// R-set.
    private Promise<Option<byte[]>> repairAndReport(byte[] key, byte[] value, int probed) {
        fallbackObserver.onResolvedViaFallback(hex(key), probed);

        return readRepair(key, value);
    }

    /// Re-home a fallback-resolved value onto the current R-set via the standard quorum [#put].
    /// Best-effort: the resolved value is returned to the caller whether or not the re-homing write
    /// reaches quorum, so a repair failure degrades to a plain successful read rather than failing
    /// the get.
    private Promise<Option<byte[]>> readRepair(byte[] key, byte[] value) {
        return put(key, value).map(_ -> Option.some(value))
                  .recover(_ -> Option.some(value));
    }

    /// All-miss after the bounded probe: report loudly (P3/P4 — never silent) and resolve empty.
    private Promise<Option<byte[]>> reportUnresolved(ResolveMiss miss) {
        fallbackObserver.onUnresolvedAfterFallback(miss);

        return Promise.success(Option.none());
    }

    /// The degrade-to-empty of a failed probe, counted where the failure is consumed so the count is settled
    /// before the probe's own promise is — a separate `onFailure` callback could run after the total is read.
    private static Option<byte[]> degradeAndCount(Cause ignored, AtomicInteger probesFailed) {
        probesFailed.incrementAndGet();

        return Option.none();
    }

    private static String hex(byte[] key) {
        return HEX.formatHex(key);
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
        var readiness = node.readinessFor(key);
        var _ = node.getLocal(key)
                    .onSuccess(value -> recordGet(collector,
                                                  value,
                                                  readiness,
                                                  node.nodeId()))
                    .onFailure(collector::onFailure);
    }

    /// The local slot of a put. Returned so a rollback can wait for it to settle (#1818).
    private Promise<Boolean> handleLocalPut(byte[] key,
                                            byte[] value,
                                            WriteStamp stamp,
                                            QuorumCollector<Unit> collector) {
        return node.storage()
                   .putVersioned(key,
                                 value,
                                 stamp.version(),
                                 stamp.epochIncarnation(),
                                 stamp.epochTerm(),
                                 stamp.epochCounter())
                   .onSuccess(_ -> collector.onLocalSuccess(unit()))
                   .onFailure(collector::onLocalFailure);
    }

    private void handleLocalRemove(byte[] key, QuorumCollector<Boolean> collector) {
        var _ = node.removeLocal(key).onSuccess(collector::onSuccess).onFailure(collector::onFailure);
    }

    private void handleLocalExists(byte[] key, QuorumCollector<Boolean> collector) {
        var readiness = node.readinessFor(key);
        var _ = node.existsLocal(key)
                    .onSuccess(exists -> recordExists(collector,
                                                      exists,
                                                      readiness,
                                                      node.nodeId()))
                    .onFailure(collector::onFailure);
    }

    private void sendRemoteGet(NodeId target, byte[] key, QuorumCollector<Option<byte[]>> collector) {
        sendRemoteGet(target, key, collector, IdGenerator.generate());
    }

    private void sendRemoteGet(NodeId target,
                               byte[] key,
                               QuorumCollector<Option<byte[]>> collector,
                               String correlationId) {
        pendingOps.put(correlationId, new PendingOperation<>(collector));
        dispatchTracked(target,
                        new DHTMessage.GetRequest(correlationId, node.nodeId(), key),
                        correlationId,
                        collector);
    }

    private void sendRemotePut(NodeId target,
                               byte[] key,
                               byte[] value,
                               WriteStamp stamp,
                               QuorumCollector<Unit> collector) {
        var correlationId = IdGenerator.generate();

        pendingOps.put(correlationId, new PendingOperation<>(collector));
        dispatchTracked(target,
                        new DHTMessage.PutRequest(correlationId,
                                                  node.nodeId(),
                                                  key,
                                                  value,
                                                  stamp.version(),
                                                  stamp.epochIncarnation(),
                                                  stamp.epochTerm(),
                                                  stamp.epochCounter(),
                                                  stamp.replicationVersion()),
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
                                      failOwnedSlot(correlationId,
                                                    collector,
                                                    toCause(outcome));
                                  }
                                  })
                       .onFailure(cause -> failOwnedSlot(correlationId, collector, cause));
    }

    /// Fail a refused request's slot only if this path still owns it: a concurrent departure may already
    /// have claimed the pending op (and re-issued the slot), and one slot must never be counted twice.
    private <T> void failOwnedSlot(String correlationId, QuorumCollector<T> collector, Cause cause) {
        removePending(correlationId).onPresent(_ -> failCollector(collector, cause));
    }

    private static Cause toCause(WriteOutcome outcome) {
        return switch (outcome) {
            case WriteOutcome.Sent ignored -> DHTError.OPERATION_TIMEOUT;  // unreachable; defensive default
            case WriteOutcome.BackpressureRefused refused -> DHTError.peerUnreachable(refused.peerId(), "backpressure");
            case WriteOutcome.ConnectionDead dead -> DHTError.peerUnreachable(dead.peerId(), "connection dead");
            case WriteOutcome.NoPeerState nope -> DHTError.peerUnreachable(nope.peerId(), "no peer state");
            case WriteOutcome.EncodeFailed failed -> DHTError.peerUnreachable(failed.peerId(),
                                                                              "encode failed: " + failed.messageType());
        };
    }
}
