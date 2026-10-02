// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.resource.artifact;

import org.pragmatica.dht.DHTClient;
import org.pragmatica.dht.Partition;
import org.pragmatica.dht.QuorumCollector;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.SharedScheduler;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// A DHT test double with N replica maps. A write lands on every replica not marked unreachable for writes,
/// so a test can make one replica MISS a write. A read asks the first `readQuorum` replicas of `readOrder`
/// and combines their answers with the REAL [QuorumCollector#optionCollector] (present beats absent), so the
/// read-merge rule under test is the DHT's own, not a model of it.
///
/// Hooks (all optional): a read or write can be failed by key, and the first read of a key can be held back for
/// a while.
final class ReplicatedTestDht implements DHTClient {
    final List<ConcurrentHashMap<String, byte[]>> replicas = new ArrayList<>();
    final CopyOnWriteArrayList<String> puts = new CopyOnWriteArrayList<>();
    final CopyOnWriteArrayList<String> removes = new CopyOnWriteArrayList<>();
    private final int readQuorum;

    volatile List<Integer> readOrder;
    volatile List<Integer> writeUnreachable = List.of();
    volatile Function<String, Option<Cause>> getFailure = _ -> Option.none();
    volatile Function<String, Option<Cause>> putFailure = _ -> Option.none();
    volatile Predicate<String> delayFirstGetOf = _ -> false;
    volatile long delayMillis = 0;

    private final AtomicBoolean delayed = new AtomicBoolean();

    ReplicatedTestDht(int replicaCount, int readQuorum) {
        for (var i = 0; i < replicaCount; i++) {
            replicas.add(new ConcurrentHashMap<>());
        }

        this.readQuorum = readQuorum;
        this.readOrder = java.util.stream.IntStream.range(0, replicaCount).boxed().toList();
    }

    static ReplicatedTestDht single() {
        return new ReplicatedTestDht(1, 1);
    }

    /// Every key held by ANY replica, with the first replica's copy winning.
    ConcurrentHashMap<String, byte[]> union() {
        var all = new ConcurrentHashMap<String, byte[]>();

        replicas.forEach(replica -> replica.forEach(all::putIfAbsent));

        return all;
    }

    @Override
    public Promise<Unit> put(byte[] key, byte[] value) {
        var name = new String(key, StandardCharsets.UTF_8);
        var failure = putFailure.apply(name);

        if (failure.isPresent()) {
            return failure.map(Cause::<Unit> promise).or(Promise.unitPromise());
        }

        puts.add(name);

        for (var i = 0; i < replicas.size(); i++) {
            if (!writeUnreachable.contains(i)) {
                replicas.get(i).put(name, value.clone());
            }
        }

        return Promise.unitPromise();
    }

    @Override
    public Promise<Option<byte[]>> get(byte[] key) {
        var name = new String(key, StandardCharsets.UTF_8);
        var failure = getFailure.apply(name);

        if (failure.isPresent()) {
            return failure.map(Cause::<Option<byte[]>> promise).or(Promise.success(Option.none()));
        }

        var promise = Promise.<Option<byte[]>> promise();
        var collector = QuorumCollector.optionCollector(readQuorum, readQuorum, promise);

        // The answer is captured NOW, at issue time, as a real read would: a held-back or rendezvoused read
        // delivers the state it saw, not the state at delivery.
        for (var i = 0; i < readQuorum; i++) {
            collector.onSuccess(Option.option(replicas.get(readOrder.get(i)).get(name)));
        }

        return deliver(name, promise);
    }

    private Promise<Option<byte[]>> deliver(String name, Promise<Option<byte[]>> answered) {
        if (delayFirstGetOf.test(name) && delayed.compareAndSet(false, true)) {
            var held = Promise.<Option<byte[]>> promise();

            answered.onResult(result -> SharedScheduler.schedule(() -> held.resolve(result),
                                                                 timeSpan(delayMillis).millis()));

            return held;
        }

        return answered;
    }

    @Override
    public Promise<Boolean> exists(byte[] key) {
        var name = new String(key, StandardCharsets.UTF_8);

        return Promise.success(replicas.stream().anyMatch(replica -> replica.containsKey(name)));
    }

    @Override
    public Promise<Boolean> remove(byte[] key) {
        var name = new String(key, StandardCharsets.UTF_8);

        removes.add(name);

        return Promise.success(replicas.stream().map(replica -> replica.remove(name)).anyMatch(v -> v != null));
    }

    @Override
    public Partition partitionFor(byte[] key) {
        return Partition.partition(Math.abs(new String(key, StandardCharsets.UTF_8).hashCode()) % 1024).unwrap();
    }
}
