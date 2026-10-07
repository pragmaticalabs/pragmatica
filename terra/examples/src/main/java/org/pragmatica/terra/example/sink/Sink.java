// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.terra.example.sink;

import java.lang.annotation.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.slice.Subscriber;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.pragmatica.terra.example.events.Events.EVENTS;


@Slice
public interface Sink {
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @ResourceQualifier(type = Subscriber.class, config = "EVENTS")
    @interface Incoming {}

    @Incoming
    Promise<Unit> receive(String event);

    Promise<Integer> count();

    static org.pragmatica.lang.Option<Sink> sink() {
        record sink(AtomicInteger counter) implements Sink {
            public Promise<Unit> receive(String event) {
                counter.incrementAndGet();

                return Promise.unitPromise();
            }

            public Promise<Integer> count() {
                return Promise.success(counter.get());
            }
        }

        return org.pragmatica.lang.Option.some(new sink(new AtomicInteger()));
    }
}
