// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.testkit.fake;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// Capturing [Publisher] — records every published event so the test can assert on emitted domain
/// events ("facts", spec §8.5). Generalizes the ad-hoc no-op publishers used in slice unit tests.
public final class CapturingPublisher<E> implements Publisher<E> {
    private final List<E> events = new CopyOnWriteArrayList<>();

    private CapturingPublisher() {}

    public static <E> CapturingPublisher<E> capturing() {
        return new CapturingPublisher<>();
    }

    @Override
    public Promise<Unit> publish(E message) {
        events.add(message);

        return Promise.unitPromise();
    }

    /// Events published so far, in publication order.
    public List<E> published() {
        return List.copyOf(events);
    }
}
