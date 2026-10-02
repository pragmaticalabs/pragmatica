// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.artifact;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;


/// Runs asynchronous tasks one at a time per key, in submission order. A read-merge-write on a DHT key is
/// a lost-update race when two run at once; sequencing them on this node makes concurrent publishes through
/// the SAME node commute. It does nothing across nodes: the DHT offers no compare-and-set, so two nodes
/// rewriting one key can still overwrite each other.
final class KeyedSequencer {
    private final ConcurrentHashMap<String, Promise<Unit>> tails = new ConcurrentHashMap<>();

    /// Starts `task` once every earlier task submitted under `key` has settled, successfully or not.
    <T> Promise<T> sequence(String key, Supplier<Promise<T>> task) {
        var result = Promise.<T> promise();
        var tail = Promise.<Unit> promise();
        var previous = option(tails.put(key, tail));

        previous.or(Promise.unitPromise()).onResult(_ -> run(key, task, result, tail));

        return result;
    }

    @Contract
    private <T> void run(String key, Supplier<Promise<T>> task, Promise<T> result, Promise<Unit> tail) {
        task.get().onResult(outcome -> settle(key, outcome, result, tail));
    }

    @Contract
    private <T> void settle(String key, Result<T> outcome, Promise<T> result, Promise<Unit> tail) {
        result.resolve(outcome);
        tails.remove(key, tail);
        tail.succeed(unit());
    }
}
