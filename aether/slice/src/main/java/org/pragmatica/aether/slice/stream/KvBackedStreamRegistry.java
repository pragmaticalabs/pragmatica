// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamRegistryKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamRegistryValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Consensus-backed [StreamRegistry] implementation.
///
/// Reads use the local KV-Store snapshot (eventually consistent — the same semantics every other
/// runtime read uses). Writes go through `cluster.apply(...)` and reach the registry only once
/// the consensus round commits, per spec event-stream-namespaces §8.5.
///
/// Refcount mutations are designed to piggyback on the slice-state KV write: the FSM that drives
/// slice ACTIVE transitions composes its own [KVCommand] list together with the commands produced
/// by [#acquireCommand] / [#releaseCommand] and submits the combined batch in a single
/// `cluster.apply(...)` call. The {@link #acquireReference} / {@link #releaseReference} entry
/// points exposed on the [StreamRegistry] interface are convenience wrappers used by callers that
/// own the consensus round directly (system-stream bootstrap, durable consumer-group create/delete).
///
/// The implementation collapses the spec's `stream-meta:{addr}` and `stream-refs:{addr}` into a
/// single [StreamRegistryKey] / [StreamRegistryValue] pair so each refcount mutation is one
/// consensus command instead of two — see [StreamRegistryValue] for the rationale.
public final class KvBackedStreamRegistry implements StreamRegistry {
    private static final Logger log = LoggerFactory.getLogger(KvBackedStreamRegistry.class);

    private final ClusterNode<KVCommand<AetherKey>> cluster;
    private final KVStore<AetherKey, AetherValue> kvStore;

    public KvBackedStreamRegistry(ClusterNode<KVCommand<AetherKey>> cluster,
                                  KVStore<AetherKey, AetherValue> kvStore) {
        this.cluster = cluster;
        this.kvStore = kvStore;
    }

    public static KvBackedStreamRegistry kvBackedStreamRegistry(ClusterNode<KVCommand<AetherKey>> cluster,
                                                                KVStore<AetherKey, AetherValue> kvStore) {
        return new KvBackedStreamRegistry(cluster, kvStore);
    }

    /// Build a put command that registers a fresh entry. Caller composes this into a consensus
    /// batch that runs alongside the slice-state write.
    public static KVCommand<AetherKey> registerCommand(StreamRegistryEntry entry) {
        return new KVCommand.Put<>(StreamRegistryKey.streamRegistryKey(entry.address()),
                                   StreamRegistryValue.streamRegistryValue(entry));
    }

    /// Build the put command that increments the refcount on an existing entry, or initialises a
    /// new entry with refcount=1 if none exists. The caller passes the metadata template used when
    /// no entry exists yet (typically derived from blueprint validation output).
    public KVCommand<AetherKey> acquireCommand(StreamRegistryEntry template) {
        var existing = readEntry(template.address());
        var next = existing.map(StreamRegistryEntry::incrementRef).or(template);
        return new KVCommand.Put<>(StreamRegistryKey.streamRegistryKey(template.address()),
                                   StreamRegistryValue.streamRegistryValue(next));
    }

    /// Build the command that decrements the refcount on an existing entry, or removes the entry
    /// outright if the refcount would drop to zero. Returns [Option#none] if no entry exists at
    /// the address — caller treats this as a no-op (idempotent release).
    public Option<KVCommand<AetherKey>> releaseCommand(StreamAddress address) {
        return readEntry(address).map(entry -> entry.refCount() <= 1
                                              ? new KVCommand.Remove<>(StreamRegistryKey.streamRegistryKey(address))
                                              : new KVCommand.Put<>(StreamRegistryKey.streamRegistryKey(address),
                                                                    StreamRegistryValue.streamRegistryValue(entry.decrementRef())));
    }

    @Override public Result<StreamRegistryEntry> register(StreamRegistryEntry entry) {
        if (readEntry(entry.address()).isPresent()) {
            return StreamRegistryError.General.ALREADY_REGISTERED.result();
        }
        cluster.apply(List.of(registerCommand(entry)))
               .onFailure(cause -> log.warn("Stream registry register({}) consensus apply failed: {}",
                                            entry.address().asString(),
                                            cause.message()));
        return Result.success(entry);
    }

    @Override public Option<StreamRegistryEntry> lookup(StreamAddress address) {
        return readEntry(address);
    }

    @Override public Result<StreamRegistryEntry> resolve(String namespace, String stream, StreamVersionSpec spec) {
        return switch (spec) {
            case StreamVersionSpec.Exact exact -> {
                var address = new StreamAddress(namespace, stream, exact.version());
                yield lookup(address).toResult(StreamRegistryError.General.NOT_FOUND);
            }
            case StreamVersionSpec.Latest _ -> resolveLatest(namespace, stream);
        };
    }

    @Override public Result<StreamRegistryEntry> acquireReference(StreamAddress address) {
        var existing = readEntry(address);
        if (existing.isEmpty()) {
            return StreamRegistryError.General.NOT_FOUND.result();
        }
        var incremented = existing.map(StreamRegistryEntry::incrementRef).unwrap();
        cluster.apply(List.of(new KVCommand.Put<>(StreamRegistryKey.streamRegistryKey(address),
                                                  StreamRegistryValue.streamRegistryValue(incremented))))
               .onFailure(cause -> log.warn("Stream registry acquireReference({}) consensus apply failed: {}",
                                            address.asString(),
                                            cause.message()));
        return Result.success(incremented);
    }

    @Override public Result<ReleaseOutcome> releaseReference(StreamAddress address) {
        var existing = readEntry(address);
        if (existing.isEmpty()) {
            return StreamRegistryError.General.NOT_FOUND.result();
        }
        var entry = existing.unwrap();
        if (entry.refCount() <= 1) {
            cluster.apply(List.of(new KVCommand.Remove<>(StreamRegistryKey.streamRegistryKey(address))))
                   .onFailure(cause -> log.warn("Stream registry releaseReference({}) consensus apply failed: {}",
                                                address.asString(),
                                                cause.message()));
            return Result.success(new ReleaseOutcome(entry.decrementRef(), true));
        }
        var decremented = entry.decrementRef();
        cluster.apply(List.of(new KVCommand.Put<>(StreamRegistryKey.streamRegistryKey(address),
                                                  StreamRegistryValue.streamRegistryValue(decremented))))
               .onFailure(cause -> log.warn("Stream registry releaseReference({}) consensus apply failed: {}",
                                            address.asString(),
                                            cause.message()));
        return Result.success(new ReleaseOutcome(decremented, false));
    }

    @Override public List<StreamRegistryEntry> snapshot() {
        var result = new ArrayList<StreamRegistryEntry>();
        kvStore.forEach(StreamRegistryKey.class,
                        StreamRegistryValue.class,
                        (_, value) -> result.add(value.entry()));
        return List.copyOf(result);
    }

    private Option<StreamRegistryEntry> readEntry(StreamAddress address) {
        return kvStore.get(StreamRegistryKey.streamRegistryKey(address))
                      .filter(StreamRegistryValue.class::isInstance)
                      .map(StreamRegistryValue.class::cast)
                      .map(StreamRegistryValue::entry);
    }

    private Result<StreamRegistryEntry> resolveLatest(String namespace, String stream) {
        var matches = new ArrayList<StreamRegistryEntry>();
        kvStore.forEach(StreamRegistryKey.class,
                        StreamRegistryValue.class,
                        (key, value) -> appendIfMatch(matches, key, value, namespace, stream));
        return matches.stream()
                      .max(Comparator.comparing(e -> e.address().version()))
                      .map(Result::success)
                      .orElseGet(() -> StreamRegistryError.General.NO_VERSIONS_REGISTERED.result());
    }

    private static void appendIfMatch(List<StreamRegistryEntry> sink,
                                      StreamRegistryKey key,
                                      StreamRegistryValue value,
                                      String namespace,
                                      String stream) {
        var address = key.address();
        if (address.namespace().equals(namespace) && address.stream().equals(stream)) {
            sink.add(value.entry());
        }
    }
}
