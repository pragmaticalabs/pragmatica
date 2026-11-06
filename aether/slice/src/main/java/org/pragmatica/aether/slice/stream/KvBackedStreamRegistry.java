// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

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
/// ([#sweepHoldersOf]) when the node leaves committed membership, and by the level reconcile
/// ([StreamRegistryHolderReconciler]) when the sweep's event was missed or its apply failed. An entry is VISIBLE (lookup,
/// resolve, snapshot) only while it has a holder; the entry record itself is metadata and is never removed by a release,
/// because a removal racing a concurrent acquire could drop the entry of a live holder. A holder-less entry record is
/// invisible, and the next `register` or slice acquire overwrites it, so a deleted stream's metadata never comes back.
///
/// **Node-owned and cluster-owned references.** A slice's reference is NODE-owned: it lives as long as the slice on that
/// node, so the sweep removes it when the node leaves. A reference held under [#REGISTRY_ARTIFACT] (the registrar's, and
/// the convenience wrappers') is CLUSTER-owned: an operator or framework stream is permanent until an explicit delete, and
/// the node that served the create is only where the request landed. The sweep never removes one.
///
/// **Every removal is fenced on the value it read** (`StreamRegistryHolderValue` is a `WitnessedRemoval`). One key names
/// every activation of an artifact on a node, so a release or sweep built before a re-acquire must not delete the reference
/// the re-acquire took: it carries the value it read, and the applier refuses it once the key holds another.
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
    /// them into a consensus batch that runs alongside the slice-state write. A register is admitted only while the entry has
    /// no holder, so the registrar's key holds nothing and its reference is the key's first version.
    public static List<KVCommand<AetherKey>> registerCommands(StreamRegistryEntry entry, NodeId registrar, long nowMs) {
        return List.of(new KVCommand.Put<>(StreamRegistryKey.streamRegistryKey(entry.address()),
                                           StreamRegistryValue.streamRegistryValue(entry)),
                       new KVCommand.Put<>(StreamRegistryHolderKey.streamRegistryHolderKey(entry.address(),
                                                                                           registrar,
                                                                                           REGISTRY_ARTIFACT,
                                                                                           REGISTRAR_ATTEMPT),
                                           StreamRegistryHolderValue.streamRegistryHolderValue(nowMs,
                                                                                               StreamRegistryHolderValue.FIRST_VERSION)));
    }

    /// The commands that take a reference for `(node, artifact, attempt)`: the holder's own key, and the entry's metadata
    /// from `template` when the entry is not visible (no record, or a record no holder references any more: a deleted
    /// stream's metadata must not outlive it). The caller passes the template derived from blueprint validation output.
    /// Taking the same reference twice is the same key; the second take writes the key's next version, so a release or
    /// sweep that read the first one is refused.
    public List<KVCommand<AetherKey>> acquireCommands(StreamRegistryEntry template,
                                                      NodeId node,
                                                      Artifact artifact,
                                                      long attempt,
                                                      long nowMs) {
        var holder = holderPut(StreamRegistryHolderKey.streamRegistryHolderKey(template.address(),
                                                                               node,
                                                                               artifact,
                                                                               attempt),
                               nowMs);

        return visibleEntry(template.address()).isPresent()
               ? List.of(holder)
               : List.of(new KVCommand.Put<>(StreamRegistryKey.streamRegistryKey(template.address()),
                                             StreamRegistryValue.streamRegistryValue(template)),
                         holder);
    }

    /// The command that drops the reference `(node, artifact, attempt)` holds: removes that holder's key and nothing else,
    /// witnessed by the value the key holds now. A release that lands after the same holder acquired again (a late or
    /// retried release of the previous activation) is refused, because the key holds a newer value. Dropping a reference
    /// that is not held carries no witness: a no-op while the key stays empty, refused once it is taken again. Reads only
    /// `kvStore` (the applied mirror), never the cluster.
    public static KVCommand<AetherKey> releaseCommand(KVStore<AetherKey, AetherValue> kvStore,
                                                      ResourceAddress address,
                                                      NodeId node,
                                                      Artifact artifact,
                                                      long attempt) {
        var key = StreamRegistryHolderKey.streamRegistryHolderKey(address, node, artifact, attempt);

        return new KVCommand.Remove<>(key, heldValue(kvStore, key).map(Object.class::cast));
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

        return cluster.<Object> apply(List.of(holderPut(StreamRegistryHolderKey.streamRegistryHolderKey(address,
                                                                                                        self,
                                                                                                        REGISTRY_ARTIFACT,
                                                                                                        attempts.incrementAndGet()),
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

    private Promise<ReleaseOutcome> applyRelease(StreamRegistryEntry entry, Holder holder) {
        var remaining = entry.decrementRef();

        return cluster.<Object> apply(List.of(holder.witnessedRemove()))
                      .onFailure(cause -> log.warn("Stream registry releaseReference({}) consensus apply failed: {}",
                                                   entry.address().asString(),
                                                   cause.message()))
                      .map(_ -> new ReleaseOutcome(remaining,
                                                   remaining.refCount() <= 0));
    }

    /// The departure sweep (#1978, I-36): removes every node-owned reference `departed` held. Called for each node that
    /// leaves committed membership; idempotent, so every core node may run it.
    public Promise<Unit> sweepHoldersOf(NodeId departed) {
        return sweepHoldersOf(Set.of(departed));
    }

    /// Removes every node-owned reference held by a node of `departed`, each witnessed by the value read now: a sweep that
    /// lands after one of those nodes came back and acquired again is refused for that key. Cluster-owned references
    /// ([#REGISTRY_ARTIFACT]) are never swept. A failed apply is logged and not retried here (FER): the references stay
    /// until the next sweep of the node, which the level reconcile ([StreamRegistryHolderReconciler]) runs on its next pass.
    public Promise<Unit> sweepHoldersOf(Set<NodeId> departed) {
        var held = holders().stream()
                          .filter(holder -> holder.nodeOwned() && departed.contains(holder.key().node()))
                          .map(Holder::witnessedRemove)
                          .toList();

        if (held.isEmpty()) {
            return Promise.unitPromise();
        }

        return cluster.<Object> apply(held)
                      .onSuccess(_ -> log.info("Stream registry swept {} reference(s) held by departed node(s) {}",
                                               held.size(),
                                               departed))
                      .onFailure(cause -> log.warn("Stream registry sweep of node(s) {} failed ({} reference(s) stay): {}",
                                                   departed,
                                                   held.size(),
                                                   cause.message()))
                      .mapToUnit();
    }

    /// The nodes that hold a node-owned reference now: what the level reconcile compares against committed membership.
    public Set<NodeId> nodeOwnedHolderNodes() {
        return holders().stream()
                      .filter(Holder::nodeOwned)
                      .map(holder -> holder.key()
                                           .node())
                      .collect(Collectors.toUnmodifiableSet());
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

    /// The Put that takes the reference `key` names: the key's next version when it is held, else its first.
    private KVCommand<AetherKey> holderPut(StreamRegistryHolderKey key, long nowMs) {
        return new KVCommand.Put<>(key,
                                   heldValue(kvStore, key).map(held -> held.reacquired(nowMs))
                                            .or(() -> StreamRegistryHolderValue.streamRegistryHolderValue(nowMs,
                                                                                                          StreamRegistryHolderValue.FIRST_VERSION)));
    }

    private static Option<StreamRegistryHolderValue> heldValue(KVStore<AetherKey, AetherValue> kvStore,
                                                               StreamRegistryHolderKey key) {
        return kvStore.get(key)
                      .filter(StreamRegistryHolderValue.class::isInstance)
                      .map(StreamRegistryHolderValue.class::cast);
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
        var held = holders().stream().filter(holder -> holder.key()
                                                             .address()
                                                             .equals(address)).count();

        return held == 0
               ? none()
               : rawEntry(address).map(entry -> entry.withRefCount((int) held));
    }

    private Option<Holder> registryHolderOf(ResourceAddress address, NodeId self) {
        var registryHolders = holders().stream()
                                     .filter(holder -> holder.key()
                                                             .address()
                                                             .equals(address) && !holder.nodeOwned())
                                     .toList();

        return Option.from(registryHolders.stream()
                                          .filter(holder -> holder.key()
                                                                  .node()
                                                                  .equals(self))
                                          .max(Comparator.comparingLong(holder -> holder.key()
                                                                                        .attempt()))).orElse(() -> Option.from(registryHolders.stream()
                                                                                                                                              .min(Comparator.comparing(holder -> holder.key()
                                                                                                                                                                                        .asString()))));
    }

    /// One committed reference: its key and the value a remover must carry as its witness.
    private record Holder(StreamRegistryHolderKey key, StreamRegistryHolderValue value) {
        /// Whether the reference lives and dies with its node (a slice's), as opposed to a cluster-owned one held under
        /// [#REGISTRY_ARTIFACT].
        boolean nodeOwned() {
            return ! REGISTRY_ARTIFACT.equals(key.artifact());
        }

        KVCommand<AetherKey> witnessedRemove() {
            return new KVCommand.Remove<>(key, Option.<Object> some(value));
        }
    }

    private List<Holder> holders() {
        var holders = new ArrayList<Holder>();

        kvStore.forEach(StreamRegistryHolderKey.class,
                        StreamRegistryHolderValue.class,
                        (key, value) -> holders.add(new Holder(key, value)));

        return holders;
    }

    private Map<ResourceAddress, Integer> holderCounts() {
        var counts = new HashMap<ResourceAddress, Integer>();

        holders().forEach(holder -> counts.merge(holder.key().address(),
                                                 1,
                                                 Integer::sum));

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
