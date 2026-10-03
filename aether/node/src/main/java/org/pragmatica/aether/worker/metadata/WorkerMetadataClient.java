// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
package org.pragmatica.aether.worker.metadata;

import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.function.Function;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.pragmatica.lang.utils.TimeSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.SliceCodec;


/// One in-flight manifest and chunk; verified scopes become visible together through normal KV replay.
public final class WorkerMetadataClient {
    /// Point-in-time resource occupancy, sampled under the same monitor as mutation, plus the cluster
    /// incarnation (#1529) of the installed projection — `0` until one is installed.
    public synchronized Map<String, Long> resourceMetrics() {
        return Map.of("clientVerifiedBytes",
                      verified.values().stream().mapToLong(bytes -> bytes.length).sum(),
                      "clientCacheLimit",
                      limits.cacheBytes(),
                      "clientBufferBytes",
                      (long) buffer.length,
                      "clientScopeLimit",
                      (long) limits.scopeBytes(),
                      "clientInstalledIncarnation",
                      installedIncarnation);
    }

    private final NodeId self;
    private final TimeSource clock = TimeSource.system();
    private final KVStore<AetherKey, AetherValue> store;
    private final SliceCodec codec;
    private final Supplier<Set<NodeId>> cores;
    private final Predicate<NodeId> eligibleCore;
    private final BiConsumer<NodeId, ProtocolMessage> send;
    private final Function<List<NodeInfo>, Result<Unit>> directoryReceived;
    private final Consumer<List<NodeInfo>> endpointDirectoryReceived;
    private final Runnable projectionReady;
    /// Applies the core-derived DHT replication on EVERY projection install (#1777 track 1): a worker never restores
    /// consensus state, so this is the only path by which its DHT learns — and re-learns, on a live change — the
    /// cluster's committed factors.
    private final Consumer<WorkerMetadataMessage.DhtReplication> dhtReplicationReceived;
    private final Consumer<String> report;
    private final WorkerMetadataLimits limits;
    private final Map<String, byte[]> verified = new HashMap<>();
    private Option<NodeId> target = Option.none();
    private Option<WorkerMetadataMessage.Manifest> manifest = Option.none();
    private Option<WorkerMetadataMessage.ScopeContent> receiving = Option.none();
    private byte[] buffer = new byte[0];
    private int offset;
    private long requestId;
    private long installedRevision;
    /// Cluster incarnation (#1529) of the installed projection; `installedRevision` is a revision of it.
    private long installedIncarnation;
    private List<WorkerMetadataMessage.ScopeContent> installedScopes = List.of();
    private long deadline;
    private long nextPoll;
    private boolean waiting;
    private boolean assignedWorker;
    private long confirmedAtNanos;
    private long requestedAtNanos;

    public synchronized boolean hasFreshProjection() {
        return hasFreshProjection(clock.nanoTime());
    }

    synchronized boolean hasFreshProjection(long nowNanos) {
        return assignedWorker
               && confirmedAtNanos != 0
               && nowNanos - confirmedAtNanos < limits.manifestTtl()
                                                      .nanos();
    }

    public WorkerMetadataClient(NodeId self,
                                KVStore<AetherKey, AetherValue> store,
                                SliceCodec codec,
                                Supplier<Set<NodeId>> cores,
                                Predicate<NodeId> eligibleCore,
                                BiConsumer<NodeId, ProtocolMessage> send,
                                Function<List<NodeInfo>, Result<Unit>> directoryReceived,
                                Consumer<List<NodeInfo>> endpointDirectoryReceived,
                                Runnable projectionReady,
                                Consumer<String> report,
                                WorkerMetadataLimits limits,
                                Consumer<WorkerMetadataMessage.DhtReplication> dhtReplicationReceived) {
        this.self = self;
        this.store = store;
        this.codec = codec;
        this.cores = cores;
        this.eligibleCore = eligibleCore;
        this.send = send;
        this.directoryReceived = directoryReceived;
        this.endpointDirectoryReceived = endpointDirectoryReceived;
        this.projectionReady = projectionReady;
        this.report = report;
        this.limits = limits;
        this.dhtReplicationReceived = dhtReplicationReceived;
    }

    public synchronized Unit tick() {
        var now = clock.nanoTime();

        if (waiting && now >= deadline) {
            abandon("metadata request timed out");
        }

        if (waiting || now < nextPoll) {
            return Unit.unit();
        }

        var available = cores.get().stream().filter(eligibleCore).sorted().toList();

        if (available.isEmpty()) {
            nextPoll = now + limits.pollInterval().nanos();

            return Unit.unit();
        }

        var core = available.get((int) Math.floorMod((long) self.id().hashCode() + requestId,
                                                     (long) available.size()));

        target = Option.some(core);
        manifest = Option.none();
        receiving = Option.none();
        waiting = true;
        requestedAtNanos = clock.nanoTime();
        deadline = now + limits.manifestTtl().nanos();
        send.accept(core,
                    new WorkerMetadataMessage.ManifestRequest(self,
                                                              ++requestId,
                                                              installedRevision,
                                                              installedIncarnation));

        return Unit.unit();
    }

    public synchronized Unit onManifest(WorkerMetadataMessage.Manifest response) {
        if (!waiting || response.requestId() != requestId || !fromTarget(response.sender()) || manifest.isPresent()) {
            return Unit.unit();
        }

        if (!response.error().isEmpty() || !validManifest(response)) {
            abandon("metadata manifest rejected or oversized");

            return Unit.unit();
        }

        manifest = Option.some(response);
        if (installedScopes.equals(response.scopes())) {
            complete(response);

            return Unit.unit();
        }

        requestNext(response);

        return Unit.unit();
    }

    private boolean fromTarget(NodeId sender) {
        return eligibleCore.test(sender) && target.filter(sender::equals)
                                                  .isPresent();
    }

    private boolean validManifest(WorkerMetadataMessage.Manifest response) {
        if (response.incarnation().isBlank() || response.generation() <= 0 || behindInstalled(response) || response.scopes()
                                                                                                                   .size() > limits.scopesPerWorker()) {
            return false;
        }

        var names = new HashSet<String>();
        long bytes = 0;

        for (var scope : response.scopes()) {
            if (!names.add(scope.scope()) || scope.length() < 1 || scope.length() > limits.scopeBytes() || scope.hash()
                                                                                                                .length() != 64) {
                return false;
            }

            bytes += scope.length();
            if (bytes > limits.cacheBytes()) {
                return false;
            }
        }

        return names.contains(WorkerMetadataIndex.DIRECTORY)
               && names.contains(WorkerMetadataIndex.ENDPOINT_DIRECTORY)
               && names.contains(WorkerMetadataIndex.DHT_REPLICATION)
               && names.contains(WorkerMetadataIndex.GLOBAL)
               && names.contains("node:" + self.id());
    }

    /// A manifest from an older cluster incarnation, or from an older revision of the installed one. A
    /// newer incarnation (a cold restart restored the cores, #1529) resets the revision latch: its revisions
    /// start over and are not comparable with the installed one.
    private boolean behindInstalled(WorkerMetadataMessage.Manifest response) {
        return response.clusterIncarnation() < installedIncarnation || response.clusterIncarnation() == installedIncarnation && response.committedRevision() < installedRevision;
    }

    private void requestNext(WorkerMetadataMessage.Manifest current) {
        var missing = current.scopes().stream().filter(scope -> !hasVerified(scope)).findFirst();

        if (missing.isEmpty()) {
            install(current);

            return;
        }

        var scope = missing.get();

        receiving = Option.some(scope);
        buffer = new byte[scope.length()];
        offset = 0;
        requestChunk(current, scope);
    }

    private boolean hasVerified(WorkerMetadataMessage.ScopeContent scope) {
        var bytes = verified.get(scope.hash());

        return bytes != null && bytes.length == scope.length();
    }

    private void requestChunk(WorkerMetadataMessage.Manifest current, WorkerMetadataMessage.ScopeContent scope) {
        send.accept(current.sender(),
                    new WorkerMetadataMessage.ChunkRequest(self,
                                                           requestId,
                                                           current.incarnation(),
                                                           current.generation(),
                                                           scope.scope(),
                                                           scope.hash(),
                                                           offset));
    }

    public synchronized Unit onChunk(WorkerMetadataMessage.Chunk response) {
        if (!waiting || response.requestId() != requestId || !fromTarget(response.sender())) {
            return Unit.unit();
        }

        manifest.onPresent(current -> receiving.onPresent(scope -> consume(current, scope, response)));

        return Unit.unit();
    }

    private void consume(WorkerMetadataMessage.Manifest current,
                         WorkerMetadataMessage.ScopeContent scope,
                         WorkerMetadataMessage.Chunk response) {
        if (!current.incarnation().equals(response.incarnation()) || current.generation() != response.generation() || !scope.scope()
                                                                                                                            .equals(response.scope()) || !scope.hash()
                                                                                                                                                               .equals(response.hash()) || response.offset() != offset) {
            return;
        }

        if (!response.error().isEmpty()) {
            abandon("metadata content evicted; repairing baseline");

            return;
        }

        var bytes = response.bytes();

        if (bytes.length == 0 || bytes.length > limits.chunkBytes() || bytes.length > buffer.length - offset) {
            abandon("invalid metadata chunk size");

            return;
        }

        System.arraycopy(bytes, 0, buffer, offset, bytes.length);
        offset += bytes.length;
        if (offset < buffer.length) {
            requestChunk(current, scope);

            return;
        }

        WorkerMetadataServer.hash(buffer)
                            .onSuccess(hash -> finishScope(current, scope, hash))
                            .onFailure(_ -> abandon("metadata checksum unavailable"));
    }

    private void finishScope(WorkerMetadataMessage.Manifest current,
                             WorkerMetadataMessage.ScopeContent scope,
                             String hash) {
        if (!scope.hash().equals(hash)) {
            abandon("metadata checksum mismatch");

            return;
        }

        verified.put(hash, buffer);
        buffer = new byte[0];
        receiving = Option.none();
        requestNext(current);
    }

    private void install(WorkerMetadataMessage.Manifest current) {
        decodeProjection(current).flatMap(projection -> installProjection(current, projection))
                        .onSuccess(_ -> complete(current))
                        .onFailure(_ -> abandon("metadata projection install failed"));
    }

    private Result<Projection> decodeProjection(WorkerMetadataMessage.Manifest current) {
        var entries = new HashMap<StructuredKey, Object>();
        var peers = new ArrayList<NodeInfo>();
        var endpoints = new ArrayList<NodeInfo>();
        var replication = new ArrayList<WorkerMetadataMessage.DhtReplication>();

        return Result.allOf(current.scopes()
                                   .stream()
                                   .map(scope -> decodeScope(scope, entries, peers, endpoints, replication))).map(_ -> new Projection(Map.copyOf(entries),
                                                                                                                                      List.copyOf(peers),
                                                                                                                                      List.copyOf(endpoints),
                                                                                                                                      replication.isEmpty()
                                                                                                                                      ? Option.<WorkerMetadataMessage.DhtReplication> none()
                                                                                                                                      : Option.some(replication.getFirst())));
    }

    private Result<Unit> decodeScope(WorkerMetadataMessage.ScopeContent scope,
                                     Map<StructuredKey, Object> entries,
                                     List<NodeInfo> peers,
                                     List<NodeInfo> endpoints,
                                     List<WorkerMetadataMessage.DhtReplication> replication) {
        return Result.lift(Causes::fromThrowable,
                           () -> codec.decode(verified.get(scope.hash())))
                     .flatMap(decoded -> switch (scope.scope()) {
            case WorkerMetadataIndex.DIRECTORY -> decodeDirectory(decoded, peers);
            case WorkerMetadataIndex.ENDPOINT_DIRECTORY -> decodeDirectory(decoded, endpoints);
            case WorkerMetadataIndex.DHT_REPLICATION -> decodeDhtReplication(decoded, replication);
            default -> decodeEntries(decoded, entries);
        });
    }

    private static Result<Unit> decodeDhtReplication(Object decoded,
                                                     List<WorkerMetadataMessage.DhtReplication> replication) {
        if (! (decoded instanceof WorkerMetadataMessage.DhtReplication record)) {
            return Causes.cause("Invalid metadata DHT replication").result();
        }

        replication.add(record);

        return Result.success(Unit.unit());
    }

    private static Result<Unit> decodeDirectory(Object decoded, List<NodeInfo> peers) {
        if (! (decoded instanceof List<?> list)) {
            return Causes.cause("Invalid metadata directory").result();
        }

        for (var value : list) {
            if (! (value instanceof NodeInfo peer)) {
                return Causes.cause("Invalid metadata peer").result();
            }

            peers.add(peer);
        }

        return Result.success(Unit.unit());
    }

    private static Result<Unit> decodeEntries(Object decoded, Map<StructuredKey, Object> entries) {
        if (! (decoded instanceof Map<?, ?> map)) {
            return Causes.cause("Invalid metadata scope").result();
        }

        for (var entry : map.entrySet()) {
            if (! (entry.getKey() instanceof StructuredKey key) || entry.getValue() == null) {
                return Causes.cause("Invalid metadata entry").result();
            }

            var prior = entries.putIfAbsent(key, entry.getValue());

            if (prior != null && !prior.equals(entry.getValue())) {
                return Causes.cause("Mixed metadata cuts").result();
            }
        }

        return Result.success(Unit.unit());
    }

    private Result<Unit> installProjection(WorkerMetadataMessage.Manifest current, Projection projection) {
        return Result.lift(Causes::fromThrowable,
                           () -> codec.canonical()
                                      .encode(projection.entries()))
                     .flatMap(bytes -> installSnapshot(bytes, projection));
    }

    private Result<Unit> installSnapshot(byte[] bytes, Projection projection) {
        synchronized (store) {
            return directoryReceived.apply(projection.peers())
                                    .flatMap(_ -> store.restoreSnapshot(bytes))
                                    .onSuccess(_ -> {
                                                   endpointDirectoryReceived.accept(projection.endpoints());
                                                   projection.dhtReplication()
                                                             .onPresent(dhtReplicationReceived);
                                                   assignedWorker = projection.entries()
                                                                              .get(new AetherKey.ActivationDirectiveKey(self)) instanceof AetherValue.ActivationDirectiveValue directive && AetherValue.ActivationDirectiveValue.WORKER.equals(directive.role());
                                                   if (assignedWorker) {
                                                   projectionReady.run();
                                               }

                                                   store.replayNotifications();
                                               });
        }
    }

    private void complete(WorkerMetadataMessage.Manifest current) {
        var retained = current.scopes()
                              .stream()
                              .map(WorkerMetadataMessage.ScopeContent::hash)
                              .collect(Collectors.toSet());

        verified.keySet().retainAll(retained);
        installedRevision = current.committedRevision();
        installedIncarnation = current.clusterIncarnation();
        confirmedAtNanos = requestedAtNanos;
        installedScopes = current.scopes();
        waiting = false;
        manifest = Option.none();
        target = Option.none();
        nextPoll = clock.nanoTime() + limits.pollInterval().nanos();
    }

    private void abandon(String reason) {
        confirmedAtNanos = 0;
        waiting = false;
        manifest = Option.none();
        receiving = Option.none();
        target = Option.none();
        buffer = new byte[0];
        verified.clear();
        nextPoll = clock.nanoTime() + limits.pollInterval().nanos();
        report.accept(reason);
    }

    private record Projection(Map<StructuredKey, Object> entries,
                              List<NodeInfo> peers,
                              List<NodeInfo> endpoints,
                              Option<WorkerMetadataMessage.DhtReplication> dhtReplication) {}
}
