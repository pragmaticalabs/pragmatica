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

        Promise<Option<DHTMessage.KeyValue>> promise = Promise.promise();
        var deadlineNanos = readDeadlineNanos();
        var collector = QuorumCollector.newestEntryCollector(quorum, targets.size(), promise);
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

        return putStamped(key, value, freshStamp(), targets, quorum);
    }

    /// A put stamped with this node's HLC time and current owner epoch — what every client write carries.
    private WriteStamp freshStamp() {
        return new WriteStamp(node.hlcClock().now().packed(),
                              ownerEpochSource.currentEpochIncarnation(),
                              ownerEpochSource.currentEpochTerm(),
                              ownerEpochSource.currentEpochCounter(),
                              node.replicationFence());
    }

    /// A put with an explicit stamp. The fallback read's re-homing uses it with the copy's ORIGINAL stamp (#1777
    /// track 3): re-stamping a stranded copy as new would let it beat a later remove's tombstone.
    private Promise<Unit> putStamped(byte[] key, byte[] value, WriteStamp stamp) {
        var targets = targetNodes(key);
        var quorum = config.get().effectiveWriteQuorum(node.ring().nodeCount());

        return targets.isEmpty() || quorumUnreachable(targets, quorum)
               ? DHTError.quorumNotReached(quorum,
                                           targets.size())
                         .promise()
               : putStamped(key, value, stamp, targets, quorum);
    }

    private Promise<Unit> putStamped(byte[] key, byte[] value, WriteStamp stamp, List<NodeId> targets, int quorum) {
        Promise<Unit> promise = Promise.promise();
        var collector = QuorumCollector.<Unit> quorumCollector(quorum, targets.size(), promise);
        var localPut = targets.contains(node.nodeId())
                       ? Option.some(handleLocalPut(key, value, stamp, collector))
                       : Option.<Promise<Boolean>> none();

        targets.stream()
               .filter(target -> !target.equals(node.nodeId()))
               .forEach(target -> sendRemotePut(target, key, value, stamp, collector));

        return promise.timeout(config.get().operationTimeout())
                      .fold(result -> result.fold(cause -> afterFailedWrite(key,
                                                                            stamp,
                                                                            localPut,
                                                                            indeterminateIfFenced(cause,
                                                                                                  quorum,
                                                                                                  collector)),
                                                  Promise::success));
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
    /// best effort; the residual — another lagging replica that also accepted — stays open: #1777 track 3's
    /// tombstones do not close it, owner ruling 2026-10-03 Q8, gauge `belowHighWaterCopyCount`).
    /// A put that times out after a fence refused it is just as indeterminate as one the collector failed on
    /// fences: a slow or lost reply must not skip the rollback and leave the refused accept to spread.
    private static <T> Cause indeterminateIfFenced(Cause cause, int quorum, QuorumCollector<T> collector) {
        return collector.fencedCount() > 0 && !(cause instanceof DHTError.WriteIndeterminate)
               ? DHTError.writeIndeterminate(quorum, collector.successCount(), collector.fencedCount())
               : cause;
    }

    /// The rollback after an indeterminate put OR remove (#1777 track 3) is the same exact-stamp HARD delete of
    /// this node's own accept — never a tombstone: a tombstone at the failed write's stamp would beat the PREVIOUS
    /// value on every replica it reaches and delete the key cluster-wide, an outcome nobody asked for.
    private <T> Promise<T> afterFailedWrite(byte[] key,
                                            WriteStamp stamp,
                                            Option<Promise<Boolean>> localWrite,
                                            Cause cause) {
        return cause instanceof DHTError.WriteIndeterminate
               ? localWrite.map(local -> rollBackLocalAccept(key, stamp, local))
                           .or(Promise.success(false))
                           .fold(_ -> cause.<T> promise())
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
        log.info("Write of {} lost its quorum to owner-epoch fences; local accept {}",
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

        var stamp = freshStamp();
        Promise<Boolean> promise = Promise.promise();
        var collector = QuorumCollector.<Boolean> quorumCollector(quorum, targets.size(), promise);
        var localRemove = targets.contains(node.nodeId())
                          ? Option.some(handleLocalRemove(key, stamp, collector))
                          : Option.<Promise<Boolean>> none();

        targets.stream()
               .filter(target -> !target.equals(node.nodeId()))
               .forEach(target -> sendRemoteRemove(target, key, stamp, collector));

        return promise.timeout(config.get().operationTimeout())
                      .fold(result -> result.fold(cause -> afterFailedWrite(key,
                                                                            stamp,
                                                                            localRemove,
                                                                            indeterminateIfFenced(cause,
                                                                                                  quorum,
                                                                                                  collector)),
                                                  Promise::success));
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

        Promise<Option<DHTMessage.KeyValue>> promise = Promise.promise();
        var collector = QuorumCollector.newestEntryCollector(quorum, targets.size(), promise);

        for (var target : targets) {
            if (target.equals(node.nodeId())) {
                handleLocalExists(key, collector);
            } else {
                sendRemoteExists(target, key, collector);
            }
        }

        return promise.timeout(config.get().operationTimeout())
                      .map(DistributedDHTClient::isLive);
    }

    /// The newest answer holds a live value — not a tombstone, not "no entry" (#1777 track 3).
    private static boolean isLive(Option<DHTMessage.KeyValue> newest) {
        return newest.filter(entry -> !entry.tombstone())
                     .isPresent();
    }

    /// The value of the newest answer, if live: a newest TOMBSTONE resolves "absent" (#1777 track 3).
    private static Option<byte[]> liveValue(DHTMessage.KeyValue newest) {
        return newest.tombstone()
               ? Option.none()
               : Option.some(newest.value());
    }

    private static final byte[] NO_BYTES = new byte[0];

    /// A replica's answer as the entry the reader orders: its stamp, a tombstone or a value. The key is not needed to
    /// order answers to one read and is left empty.
    private static Option<DHTMessage.KeyValue> entryOf(Option<byte[]> value,
                                                       boolean tombstone,
                                                       long version,
                                                       long epochIncarnation,
                                                       long epochTerm,
                                                       long epochCounter) {
        return tombstone
               ? Option.some(new DHTMessage.KeyValue(NO_BYTES,
                                                     NO_BYTES,
                                                     version,
                                                     epochIncarnation,
                                                     epochTerm,
                                                     epochCounter,
                                                     true))
               : value.map(present -> new DHTMessage.KeyValue(NO_BYTES,
                                                              present,
                                                              version,
                                                              epochIncarnation,
                                                              epochTerm,
                                                              epochCounter,
                                                              false));
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
                                                                      entryOf(response.value(),
                                                                              response.tombstone(),
                                                                              response.version(),
                                                                              response.epochIncarnation(),
                                                                              response.epochTerm(),
                                                                              response.epochCounter()),
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

        return response.replicationStale()
               ? DHTError.replicaOnNewerReplication(response.sender())
               : DHTError.OPERATION_TIMEOUT;
    }

    /// Handle a remove response from a remote node.
    /// A refusal by the replica's owner-epoch fence is reported as such, so a remove whose quorum is lost to fences
    /// is indeterminate, as a put is (#1777 track 3).
    @Contract
    public void onRemoveResponse(DHTMessage.RemoveResponse response) {
        removePending(response.requestId()).onPresent(op -> recordRemove(castCollector(op, Boolean.class), response));
    }

    /// Same `@Contract` void-mutator suppression as [#failCollector].
    @SuppressWarnings("JBCT-RET-07")
    private static void recordRemove(QuorumCollector<Boolean> collector, DHTMessage.RemoveResponse response) {
        if (response.fenced()) {
            failCollector(collector,
                          DHTError.replicaFenced(response.sender()));
        } else if (response.replicationStale()) {
            failCollector(collector,
                          DHTError.replicaOnNewerReplication(response.sender()));
        } else {
            collector.onSuccess(response.found());
        }
    }

    /// Handle an exists response from a remote node. A `false` from a replica still catching up is a
    /// refusal of the slot, like an absent [#onGetResponse] (#1777 track 2).
    @Contract
    public void onExistsResponse(DHTMessage.ExistsResponse response) {
        removePending(response.requestId()).onPresent(op -> recordGet(castCollector(op, Option.class),
                                                                      entryOf(response.exists()
                                                                              ? Option.some(NO_BYTES)
                                                                              : Option.none(),
                                                                              response.tombstone(),
                                                                              response.version(),
                                                                              response.epochIncarnation(),
                                                                              response.epochTerm(),
                                                                              response.epochCounter()),
                                                                      response.readiness(),
                                                                      response.sender()));
    }

    /// Count a get or exists answer: an entry — a value or a tombstone — always votes; "no entry" only from an
    /// authoritative replica (#1777 tracks 2 and 3). A tombstone is positive evidence of a remove, whatever the
    /// replica's readiness.
    private static void recordGet(QuorumCollector<Option<DHTMessage.KeyValue>> collector,
                                  Option<DHTMessage.KeyValue> entry,
                                  DHTMessage.Readiness readiness,
                                  NodeId sender) {
        if (entry.isEmpty() && !readiness.authoritative()) {
            failCollector(collector, DHTError.replicaCatchingUp(sender));
        } else {
            collector.onSuccess(entry, sender.id());
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
        Promise<Option<DHTMessage.KeyValue>> answer = Promise.promise();
        var single = QuorumCollector.newestEntryCollector(1, 1, answer);
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
    private static void countReplacementAnswer(InFlightRead read,
                                               NodeId replacement,
                                               Option<DHTMessage.KeyValue> value) {
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

    /// Route the R-set quorum outcome (issue #428, C2): the newest entry decides — a live value passes straight
    /// through, a TOMBSTONE resolves "absent" with no probe (#1777 track 3: a probe could only find a copy the
    /// remove superseded) — and only a read where no replica held any entry enters the bounded fallback probe.
    private Promise<Option<byte[]>> resolveOrFallback(byte[] key,
                                                      Option<DHTMessage.KeyValue> quorumResult,
                                                      QuorumCollector<Option<DHTMessage.KeyValue>> rSetCollector,
                                                      int rSetLive) {
        return quorumResult.fold(() -> fallbackResolve(key, rSetCollector, rSetLive),
                                 newest -> Promise.success(liveValue(newest)));
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
                                                    QuorumCollector<Option<DHTMessage.KeyValue>> rSetCollector,
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
                                                           QuorumCollector<Option<DHTMessage.KeyValue>> rSetCollector,
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
                                                   QuorumCollector<Option<DHTMessage.KeyValue>> rSetCollector,
                                                   int rSetLive) {
        var probesFailed = new AtomicInteger();

        return Promise.allOf(probeAll(key, fallbackTargets, probesFailed))
                      .map(DistributedDHTClient::newestFound)
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
                                   QuorumCollector<Option<DHTMessage.KeyValue>> rSetCollector,
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

    private List<Promise<Option<DHTMessage.KeyValue>>> probeAll(byte[] key,
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
    private Promise<Option<DHTMessage.KeyValue>> probeTarget(NodeId target, byte[] key, AtomicInteger probesFailed) {
        Promise<Option<DHTMessage.KeyValue>> probe = Promise.promise();
        var collector = QuorumCollector.newestEntryCollector(1, 1, probe);

        if (target.equals(node.nodeId())) {
            handleLocalGet(key, collector);
        } else {
            sendRemoteGet(target, key, collector);
        }

        return probe.timeout(config.get().operationTimeout())
                    .recover(cause -> degradeAndCount(cause, probesFailed));
    }

    /// The newest stranded entry the bounded probes found — by owner epoch then version, so a tombstone beats an
    /// older value (#1777 track 3) — or empty when every probe missed. Each probe already degraded failures to a
    /// successful empty, so the results are unwrapped defensively.
    private static Option<DHTMessage.KeyValue> newestFound(List<Result<Option<DHTMessage.KeyValue>>> probeResults) {
        return probeResults.stream()
                           .map(result -> result.or(Option.<DHTMessage.KeyValue> none()))
                           .flatMap(Option::stream)
                           .reduce((left, right) -> right.compareOrder(left) > 0
                                                    ? right
                                                    : left)
                           .map(Option::some)
                           .orElseGet(Option::none);
    }

    /// A stranded TOMBSTONE resolves "absent" and is not re-homed: the key was removed (#1777 track 3).
    private Promise<Option<byte[]>> resolveFallbackOutcome(byte[] key,
                                                           Option<DHTMessage.KeyValue> found,
                                                           ResolveMiss miss) {
        return found.fold(() -> reportUnresolved(miss),
                          entry -> entry.tombstone()
                                   ? Promise.success(Option.none())
                                   : repairAndReport(key, entry, miss.probed()));
    }

    /// Stranded copy found beyond the R-set: fire the observer, then read-repair it back onto the
    /// R-set.
    private Promise<Option<byte[]>> repairAndReport(byte[] key, DHTMessage.KeyValue entry, int probed) {
        fallbackObserver.onResolvedViaFallback(hex(key), probed);

        return readRepair(key, entry);
    }

    /// Re-home a fallback-resolved value onto the current R-set with its ORIGINAL stamp, through the same fenced
    /// path as any put (#1777 track 3, owner ruling Q9). Re-stamping it as new — what this did before — let a copy
    /// stranded before a remove beat the remove's tombstone and resurrect the key. With the original stamp it can
    /// never beat a newer entry; a copy of an old owner epoch is refused by the fence and re-homed by catch-up and
    /// anti-entropy instead. Best-effort: the resolved value is returned whether or not the re-homing reaches quorum.
    private Promise<Option<byte[]>> readRepair(byte[] key, DHTMessage.KeyValue entry) {
        var value = entry.value();

        return putStamped(key,
                          value,
                          new WriteStamp(entry.version(),
                                         entry.epochIncarnation(),
                                         entry.epochTerm(),
                                         entry.epochCounter(),

        // the re-homing is this node's write now, sized under the change it applied
        node.replicationFence())).map(_ -> Option.some(value))
                         .recover(_ -> Option.some(value));
    }

    /// All-miss after the bounded probe: report loudly (P3/P4 — never silent) and resolve empty.
    private Promise<Option<byte[]>> reportUnresolved(ResolveMiss miss) {
        fallbackObserver.onUnresolvedAfterFallback(miss);

        return Promise.success(Option.none());
    }

    /// The degrade-to-empty of a failed probe, counted where the failure is consumed so the count is settled
    /// before the probe's own promise is — a separate `onFailure` callback could run after the total is read.
    private static Option<DHTMessage.KeyValue> degradeAndCount(Cause ignored, AtomicInteger probesFailed) {
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
    private void handleLocalGet(byte[] key, QuorumCollector<Option<DHTMessage.KeyValue>> collector) {
        var readiness = node.readinessFor(key);
        var _ = node.storage()
                    .getEntry(key)
                    .onSuccess(entry -> recordGet(collector,
                                                  entry,
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
                   .onSuccess(_ -> collector.onSuccess(unit()))
                   .onFailure(collector::onFailure);
    }

    /// The local slot of a remove: a stamped, fenced tombstone (#1777 track 3). Returned so a rollback can wait
    /// for it to settle.
    private Promise<Boolean> handleLocalRemove(byte[] key, WriteStamp stamp, QuorumCollector<Boolean> collector) {
        return node.storage()
                   .removeVersioned(key,
                                    stamp.version(),
                                    stamp.epochIncarnation(),
                                    stamp.epochTerm(),
                                    stamp.epochCounter())
                   .onSuccess(collector::onSuccess)
                   .onFailure(collector::onFailure);
    }

    private void handleLocalExists(byte[] key, QuorumCollector<Option<DHTMessage.KeyValue>> collector) {
        handleLocalGet(key, collector);
    }

    private void sendRemoteGet(NodeId target, byte[] key, QuorumCollector<Option<DHTMessage.KeyValue>> collector) {
        sendRemoteGet(target, key, collector, IdGenerator.generate());
    }

    private void sendRemoteGet(NodeId target,
                               byte[] key,
                               QuorumCollector<Option<DHTMessage.KeyValue>> collector,
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

    private void sendRemoteRemove(NodeId target, byte[] key, WriteStamp stamp, QuorumCollector<Boolean> collector) {
        var correlationId = IdGenerator.generate();

        pendingOps.put(correlationId, new PendingOperation<>(collector));
        dispatchTracked(target,
                        new DHTMessage.RemoveRequest(correlationId,
                                                     node.nodeId(),
                                                     key,
                                                     stamp.version(),
                                                     stamp.epochIncarnation(),
                                                     stamp.epochTerm(),
                                                     stamp.epochCounter(),
                                                     stamp.replicationVersion()),
                        correlationId,
                        collector);
    }

    private void sendRemoteExists(NodeId target, byte[] key, QuorumCollector<Option<DHTMessage.KeyValue>> collector) {
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
