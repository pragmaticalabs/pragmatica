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

import static org.pragmatica.lang.Unit.unit;


/// Distributed DHT client with quorum-based reads and writes.
/// Routes operations to responsible nodes via consistent hashing and DHTNetwork.
public final class DistributedDHTClient implements DHTClient {
    /// Upper bound on the resolve-time fallback ring probe (issue #428, C2): after an R-set quorum
    /// MISS, at most this many ring members OUTSIDE the R-set are probed for a stranded copy. Keeps
    /// the mitigation a bounded, best-effort cache-warmth pass rather than an unbounded ring scan.
    private static final int DEFAULT_FALLBACK_PROBE_LIMIT = 8;
    private static final HexFormat HEX = HexFormat.of();

    private final DHTNode node;
    private final DHTNetwork network;
    private final DHTConfig config;
    private final OwnerEpochSource ownerEpochSource;
    private final ResolveFallbackObserver fallbackObserver;
    /// Pending operations indexed by correlation ID.
    private final ConcurrentHashMap<String, PendingOperation<?>> pendingOps = new ConcurrentHashMap<>();

    private record PendingOperation<T>(QuorumCollector<T> collector) {}

    private DistributedDHTClient(DHTNode node,
                                 DHTNetwork network,
                                 DHTConfig config,
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
        return new DistributedDHTClient(node, network, config, OwnerEpochSource.zero(), ResolveFallbackObserver.noop());
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
        return new DistributedDHTClient(node, network, config, ownerEpochSource, ResolveFallbackObserver.noop());
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
        return new DistributedDHTClient(node, network, scopedConfig, ownerEpochSource, fallbackObserver);
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

        return promise.timeout(config.operationTimeout())
                      .flatMap(quorumResult -> resolveOrFallback(key, quorumResult));
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

    /// Route the R-set quorum outcome (issue #428, C2): a present value passes straight through; a
    /// MISS enters the bounded fallback probe. Pure routing — the resolved value is not transformed.
    private Promise<Option<byte[]>> resolveOrFallback(byte[] key, Option<byte[]> quorumResult) {
        return quorumResult.isPresent()
               ? Promise.success(quorumResult)
               : fallbackResolve(key);
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
    private Promise<Option<byte[]>> fallbackResolve(byte[] key) {
        var fallbackTargets = fallbackTargets(key);

        return fallbackTargets.isEmpty()
               ? Promise.success(Option.none())
               : probeAndRepair(key, fallbackTargets);
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

    private Promise<Option<byte[]>> probeAndRepair(byte[] key, List<NodeId> fallbackTargets) {
        return Promise.allOf(probeAll(key, fallbackTargets))
                      .map(DistributedDHTClient::firstPresent)
                      .flatMap(found -> resolveFallbackOutcome(key, found, fallbackTargets.size()));
    }

    private List<Promise<Option<byte[]>>> probeAll(byte[] key, List<NodeId> fallbackTargets) {
        return fallbackTargets.stream()
                              .map(target -> probeTarget(target, key))
                              .toList();
    }

    /// Single-target best-effort read for the fallback probe: reuses the same local/remote get
    /// primitives as the quorum path but against a lone target (quorum 1 of 1). A transport refusal
    /// or timeout degrades to an empty result rather than failing, so one dead fallback candidate
    /// never aborts the probe.
    private Promise<Option<byte[]>> probeTarget(NodeId target, byte[] key) {
        Promise<Option<byte[]>> probe = Promise.promise();
        var collector = QuorumCollector.<Option<byte[]>> quorumCollector(1, 1, probe);

        if (target.equals(node.nodeId())) {
            handleLocalGet(key, collector);
        } else {
            sendRemoteGet(target, key, collector);
        }

        return probe.timeout(config.operationTimeout()).recover(DistributedDHTClient::degradeToNone);
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

    private Promise<Option<byte[]>> resolveFallbackOutcome(byte[] key, Option<byte[]> found, int probed) {
        return found.fold(() -> reportUnresolved(key, probed),
                          value -> repairAndReport(key, value, probed));
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
        return put(key, value).map(_ -> Option.some(value)).recover(_ -> Option.some(value));
    }

    /// All-miss after the bounded probe: report loudly (P3/P4 — never silent) and resolve empty.
    private Promise<Option<byte[]>> reportUnresolved(byte[] key, int probed) {
        fallbackObserver.onUnresolvedAfterFallback(hex(key), probed);

        return Promise.success(Option.none());
    }

    private static Option<byte[]> degradeToNone(Cause ignored) {
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
            case WriteOutcome.EncodeFailed failed -> DHTError.peerUnreachable(failed.peerId(), "encode failed: " + failed.messageType());
        };
    }
}
