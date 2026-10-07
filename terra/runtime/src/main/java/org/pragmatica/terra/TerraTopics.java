// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.terra;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;


/// Application-owned ephemeral fan-out. Completion means every accepted handler has settled.
public final class TerraTopics {
    private final Map<String, List<Fn1<Promise<Unit>, Object>>> subscribers = new LinkedHashMap<>();
    private final Promise<Unit> drained = Promise.promise();
    private boolean accepting;
    private boolean closing;
    private int inFlight;

    public synchronized <T> Result<Unit> subscribe(String topic, Class<T> type, Fn1<Promise<Unit>, T> handler) {
        if (accepting || closing) {
            return new TerraError.NotRunning("Subscriptions can only be bound during startup").result();
        }

        subscribers.computeIfAbsent(topic, _ -> new ArrayList<>()).add(value -> handler.apply(type.cast(value)));

        return Result.unitResult();
    }

    synchronized Unit start() {
        accepting = true;

        return Unit.unit();
    }

    public Promise<Unit> publish(String topic, Object message) {
        return admit(topic).async()
                    .flatMap(handlers -> deliver(handlers, message));
    }

    private synchronized Result<List<Fn1<Promise<Unit>, Object>>> admit(String topic) {
        if (!accepting) {
            return new TerraError.NotRunning("Terra is not accepting publications").result();
        }

        inFlight++;

        return Result.success(List.copyOf(subscribers.getOrDefault(topic, List.of())));
    }

    private Promise<Unit> deliver(List<Fn1<Promise<Unit>, Object>> handlers, Object message) {
        var deliveries = handlers.stream().map(handler -> invoke(handler, message)).toList();

        return Promise.allOf(deliveries)
                      .flatMap(results -> Result.allOf(results)
                                                .async()
                                                .mapToUnit())
                      .replaceResult(this::completed);
    }

    private static Promise<Unit> invoke(Fn1<Promise<Unit>, Object> handler, Object message) {
        return Result.lift(Causes::fromThrowable,
                           () -> java.util.Objects.requireNonNull(handler.apply(message),
                                                                  "Subscriber returned null Promise"))
                     .fold(Promise::failure, promise -> promise);
    }

    private synchronized Result<Unit> completed(Result<Unit> result) {
        inFlight--;
        finishDrain();

        return result;
    }

    public synchronized Promise<Unit> close() {
        accepting = false;
        closing = true;
        finishDrain();

        return drained;
    }

    private void finishDrain() {
        if (closing && inFlight == 0) {
            subscribers.clear();
            drained.succeed(Unit.unit());
        }
    }
}
