// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamRegistryHolderKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamRegistryKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamRegistryHolderValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamRegistryValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;


/// Consensus-backed [StreamRegistry] implementation.
///
/// Reads use the local KV-Store snapshot (eventually consistent — the same semantics every other
/// runtime read uses). Writes go through `cluster.apply(...)` and reach the registry only once
/// the consensus round commits, per spec event-stream-namespaces §8.5.
///
/// **References are holder keys, not a count (#1978, #2160, I-36).** Every reference to an entry is one
/// [StreamRegistryHolderKey] `(stream, node, artifact, attempt)`: acquire puts the holder's OWN key, release removes
/// only that key, and the reference count an entry reports is the NUMBER of holder keys of its address. Nothing reads a
/// count and writes count+1, so two holders acquiring at once keep both references, and a release never removes an
/// entry another holder still needs. A holder whose node crashed is removed by the departure sweep
/// ([#sweepHoldersOf]) when the node leaves committed membership: at most the detection time plus one sweep, not for
/// ever as the absolute count was. An entry is VISIBLE (lookup, resolve, snapshot) only while it has a holder; the entry
/// record itself is metadata and is never removed by a release, because a removal racing a concurrent acquire could
/// drop the entry of a live holder. A holder-less entry record is invisible and is overwritten by the next `register`.
///
/// Refcount mutations piggyback on the slice-state KV write: the FSM that drives slice ACTIVE transitions composes its
/// own [KVCommand] list together with the commands produced by [#acquireCommands] / [#releaseCommand] and submits the
/// combined batch in a single `cluster.apply(...)` call. The {@link #acquireReference} / {@link #releaseReference}
/// entry points exposed on the [StreamRegistry] interface are convenience wrappers for callers that own the consensus
/// round directly (system-stream bootstrap, durable consumer-group create/delete, operator delete); they hold under
/// [#REGISTRY_ARTIFACT].
public final class KvBackedStreamRegistry implements StreamRegistry {
    private static final Logger log = LoggerFactory.getLogger(KvBackedStreamRegistry.class);
    private static final TimeSpan REGISTER_TIMEOUT = TimeSpan.timeSpan(10).seconds();

    /// The artifact of a holder that is not a slice: the registrar of an entry and the holders of the convenience wrappers.
    public static final Artifact REGISTRY_ARTIFACT = Artifact.artifact("org.pragmatica.aether:stream-registry:1.0.0").unwrap();

    /// The attempt of the registrar's own reference.
    private static final long REGISTRAR_ATTEMPT = 0L;

    private final ClusterNode<KVCommand<AetherKey>> cluster;
    private final KVStore<AetherKey, AetherValue> kvStore;
    private final TimeSpan registerTimeout;
    private final AtomicLong attempts = new AtomicLong(System.currentTimeMillis());

    public KvBackedStreamRegistry(ClusterNode<KVCommand<AetherKey>> cluster, KVStore<AetherKey, AetherValue> kvStore) {
        this(cluster, kvStore, REGISTER_TIMEOUT);
    }

    /// Bound seam for [#register] (#968): production wiring uses [#REGISTER_TIMEOUT]; the pin for a
    /// consensus apply that never answers injects a short bound instead of waiting the real 10 s.
    public KvBackedStreamRegistry(ClusterNode<KVCommand<AetherKey>> cluster,
                                  KVStore<AetherKey, AetherValue> kvStore,
                                  TimeSpan registerTimeout) {
        this.cluster = cluster;
        this.kvStore = kvStore;
        this.registerTimeout = registerTimeout;
    }

    public static KvBackedStreamRegistry kvBackedStreamRegistry(ClusterNode<KVCommand<AetherKey>> cluster,
                                                                KVStore<AetherKey, AetherValue> kvStore) {
        return new KvBackedStreamRegistry(cluster, kvStore);
    }

    /// The commands that register a fresh entry: its metadata record and the registrar's own reference. Caller composes
    /// them into a consensus batch that runs alongside the slice-state write.
    public static List<KVCommand<AetherKey>> registerCommands(StreamRegistryEntry entry, NodeId registrar, long nowMs) {
        return List.of(new KVCommand.Put<>(StreamRegistryKey.streamRegistryKey(entry.address()),
                                           StreamRegistryValue.streamRegistryValue(entry)),
                       holderPut(entry.address(), registrar, REGISTRY_ARTIFACT, REGISTRAR_ATTEMPT, nowMs));
    }

    /// The commands that take a reference for `(node, artifact, attempt)`: the holder's own key, and the entry's metadata
    /// from `template` when the store holds none yet. The caller passes the template used when no entry record exists
    /// (typically derived from blueprint validation output). Taking the same reference twice is the same key.
    public List<KVCommand<AetherKey>> acquireCommands(StreamRegistryEntry template,
                                                      NodeId node,
                                                      Artifact artifact,
                                                      long attempt,
                                                      long nowMs) {
        var holder = holderPut(template.address(), node, artifact, attempt, nowMs);

        return rawEntry(template.address()).isPresent()
               ? List.of(holder)
               : List.of(new KVCommand.Put<>(StreamRegistryKey.streamRegistryKey(template.address()),
                                             StreamRegistryValue.streamRegistryValue(template)),
                         holder);
    }

    /// The command that drops the reference `(node, artifact, attempt)` holds: removes that holder's key and nothing else.
    /// Dropping a reference that is not held is a no-op, so a release needs no read.
    public static KVCommand<AetherKey> releaseCommand(ResourceAddress address,
                                                      NodeId node,
                                                      Artifact artifact,
                                                      long attempt) {
        return new KVCommand.Remove<>(StreamRegistryHolderKey.streamRegistryHolderKey(address, node, artifact, attempt));
    }

    /// Awaits the consensus commit (#968): the entry is registered only once the put commits, so a
    /// refused or timed-out apply is THIS call's failure, never a logged-and-dropped one that leaves
    /// the caller reporting `"created"` for an entry no read ever finds. Bounded by
    /// [#REGISTER_TIMEOUT], the same bound `StreamPartitionManager` puts on the sibling
    /// `StreamConfigKey` commit that precedes this one on the create path.
    @Override
    public Result<StreamRegistryEntry> register(StreamRegistryEntry entry) {
        if (visibleEntry(entry.address()).isPresent()) {
            return StreamRegistryError.General.ALREADY_REGISTERED.result();
        }

        return cluster.apply(registerCommands(entry,
                                              cluster.self(),
                                              System.currentTimeMillis()))
                      .await(registerTimeout)
                      .onFailure(cause -> log.warn("Stream registry register({}) consensus apply failed: {}",
                                                   entry.address().asString(),
                                                   cause.message()))
                      .map(_ -> entry.withRefCount(1));
    }

    @Override
    public Option<StreamRegistryEntry> lookup(ResourceAddress address) {
        return visibleEntry(address);
    }

    @Override
    public Result<StreamRegistryEntry> resolve(String namespace, String stream, StreamVersionSpec spec) {
        return switch (spec) {
            case StreamVersionSpec.Exact exact -> ResourceAddress.resourceAddress(namespace, stream, exact.version()).flatMap(address -> lookup(address).toResult(StreamRegistryError.General.NOT_FOUND));
            case StreamVersionSpec.Latest _ -> resolveLatest(namespace, stream);
        };
    }

    @Override
    public Promise<StreamRegistryEntry> acquireReference(ResourceAddress address) {
        return visibleEntry(address).async(StreamRegistryError.General.NOT_FOUND)
                           .flatMap(entry -> applyAcquire(entry,
                                                          cluster.self()));
    }

    private Promise<StreamRegistryEntry> applyAcquire(StreamRegistryEntry entry, NodeId self) {
        var address = entry.address();

        return cluster.<Object> apply(List.of(holderPut(address,
                                                        self,
                                                        REGISTRY_ARTIFACT,
                                                        attempts.incrementAndGet(),
                                                        System.currentTimeMillis())))
                      .onFailure(cause -> log.warn("Stream registry acquireReference({}) consensus apply failed: {}",
                                                   address.asString(),
                                                   cause.message()))
                      .map(_ -> entry.incrementRef());
    }

    /// Drops one reference held under [#REGISTRY_ARTIFACT]: this node's most recent one, else any node's (the operator
    /// deleting a stream registered by another node drops the registrar's reference). A stream held only by slices has none
    /// to drop here: [General#NOT_FOUND], which the delete path tolerates.
    @Override
    public Promise<ReleaseOutcome> releaseReference(ResourceAddress address) {
        return visibleEntry(address).async(StreamRegistryError.General.NOT_FOUND)
                           .flatMap(this::releaseRegistryHolder);
    }

    private Promise<ReleaseOutcome> releaseRegistryHolder(StreamRegistryEntry entry) {
        return registryHolderOf(entry.address(),
                                cluster.self()).async(StreamRegistryError.General.NOT_FOUND)
                               .flatMap(holder -> applyRelease(entry, holder));
    }

    private Promise<ReleaseOutcome> applyRelease(StreamRegistryEntry entry, StreamRegistryHolderKey holder) {
        var remaining = entry.decrementRef();

        return cluster.<Object> apply(List.of(new KVCommand.Remove<>(holder)))
                      .onFailure(cause -> log.warn("Stream registry releaseReference({}) consensus apply failed: {}",
                                                   entry.address().asString(),
                                                   cause.message()))
                      .map(_ -> new ReleaseOutcome(remaining,
                                                   remaining.refCount() <= 0));
    }

    /// The departure sweep (#1978, I-36): removes every reference `departed` held. Called for each node that leaves committed
    /// membership; idempotent, so every core node may run it. A failed sweep is logged and not retried (FER: the references
    /// stay until the same node id is swept again, which is the stated bound -- detection plus one sweep -- failing only when
    /// the apply itself fails).
    public Promise<Unit> sweepHoldersOf(NodeId departed) {
        var held = holderKeys().stream()
                             .filter(key -> key.node()
                                               .equals(departed))
                             .<KVCommand<AetherKey>> map(KVCommand.Remove::new)
                             .toList();

        if (held.isEmpty()) {
            return Promise.unitPromise();
        }

        return cluster.<Object> apply(held)
                      .onSuccess(_ -> log.info("Stream registry swept {} reference(s) held by departed node {}",
                                               held.size(),
                                               departed.id()))
                      .onFailure(cause -> log.warn("Stream registry sweep of node {} failed ({} reference(s) stay): {}",
                                                   departed.id(),
                                                   held.size(),
                                                   cause.message()))
                      .mapToUnit();
    }

    @Override
    public List<StreamRegistryEntry> snapshot() {
        var counts = holderCounts();
        var result = new ArrayList<StreamRegistryEntry>();

        kvStore.forEach(StreamRegistryKey.class,
                        StreamRegistryValue.class,
                        (key, value) -> appendIfHeld(result, counts, key, value));

        return List.copyOf(result);
    }

    private static void appendIfHeld(List<StreamRegistryEntry> sink,
                                     Map<ResourceAddress, Integer> counts,
                                     StreamRegistryKey key,
                                     StreamRegistryValue value) {
        option(counts.get(key.address())).onPresent(held -> sink.add(value.entry().withRefCount(held)));
    }

    private static KVCommand<AetherKey> holderPut(ResourceAddress address,
                                                  NodeId node,
                                                  Artifact artifact,
                                                  long attempt,
                                                  long nowMs) {
        return new KVCommand.Put<>(StreamRegistryHolderKey.streamRegistryHolderKey(address, node, artifact, attempt),
                                   StreamRegistryHolderValue.streamRegistryHolderValue(nowMs));
    }

    /// The entry record, visible or not.
    private Option<StreamRegistryEntry> rawEntry(ResourceAddress address) {
        return kvStore.get(StreamRegistryKey.streamRegistryKey(address))
                      .filter(StreamRegistryValue.class::isInstance)
                      .map(StreamRegistryValue.class::cast)
                      .map(StreamRegistryValue::entry);
    }

    /// The entry, with the reference count it has now: the number of holder keys of its address, none when it has none.
    private Option<StreamRegistryEntry> visibleEntry(ResourceAddress address) {
        var held = holderKeys().stream().filter(key -> key.address()
                                                          .equals(address)).count();

        return held == 0
               ? none()
               : rawEntry(address).map(entry -> entry.withRefCount((int) held));
    }

    private Option<StreamRegistryHolderKey> registryHolderOf(ResourceAddress address, NodeId self) {
        var registryHolders = holderKeys().stream()
                                        .filter(key -> key.address()
                                                          .equals(address) && REGISTRY_ARTIFACT.equals(key.artifact()))
                                        .toList();

        return Option.from(registryHolders.stream()
                                          .filter(key -> key.node()
                                                            .equals(self))
                                          .max(Comparator.comparingLong(StreamRegistryHolderKey::attempt))).orElse(() -> Option.from(registryHolders.stream()
                                                                                                                                                    .min(Comparator.comparing(StreamRegistryHolderKey::asString))));
    }

    private List<StreamRegistryHolderKey> holderKeys() {
        var keys = new ArrayList<StreamRegistryHolderKey>();

        kvStore.forEach(StreamRegistryHolderKey.class, StreamRegistryHolderValue.class, (key, _) -> keys.add(key));

        return keys;
    }

    private Map<ResourceAddress, Integer> holderCounts() {
        var counts = new HashMap<ResourceAddress, Integer>();

        holderKeys().forEach(key -> counts.merge(key.address(), 1, Integer::sum));

        return counts;
    }

    private Result<StreamRegistryEntry> resolveLatest(String namespace, String stream) {
        return snapshot().stream()
                       .filter(entry -> entry.address()
                                             .namespace()
                                             .value()
                                             .equals(namespace) && entry.address()
                                                                        .name()
                                                                        .value()
                                                                        .equals(stream))
                       .max(Comparator.comparing(e -> e.address()
                                                       .version()))
                       .map(Result::success)
                       .orElseGet(() -> StreamRegistryError.General.NO_VERSIONS_REGISTERED.result());
    }
}
