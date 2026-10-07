// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.terra;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.lang.utils.Causes.cause;

class TerraTopicsTest {
    @Test void publish_oneFailure_waitsForEveryHandlerAndDrain() {
        var topics = new TerraTopics();
        var count = new AtomicInteger();
        var slow = Promise.<Unit>promise();
        topics.subscribe("events", String.class, _ -> { count.incrementAndGet(); return cause("failure").promise(); }).unwrap();
        topics.subscribe("events", String.class, _ -> { count.incrementAndGet(); return slow; }).unwrap();
        topics.start();
        var delivery = topics.publish("events", "hello");
        var closed = topics.close();
        assertThat(delivery.isResolved()).isFalse();
        assertThat(closed.isResolved()).isFalse();
        assertThat(topics.publish("events", "late").await(timeSpan(5).seconds()).isFailure()).isTrue();
        slow.succeed(Unit.unit());
        assertThat(delivery.await(timeSpan(5).seconds()).isFailure()).isTrue();
        assertThat(closed.await(timeSpan(5).seconds()).isSuccess()).isTrue();
        assertThat(count).hasValue(2);
    }

    @Test void publish_throwingSubscriber_stillInvokesOtherSubscriber() {
        var topics = new TerraTopics();
        var count = new AtomicInteger();
        topics.subscribe("events", String.class, _ -> { throw new IllegalStateException("defect"); }).unwrap();
        topics.subscribe("events", String.class, _ -> { count.incrementAndGet(); return Promise.unitPromise(); }).unwrap();
        topics.start();
        assertThat(topics.publish("events", "hello").await(timeSpan(5).seconds()).isFailure()).isTrue();
        assertThat(count).hasValue(1);
        assertThat(topics.close().await(timeSpan(5).seconds()).isSuccess()).isTrue();
    }

    @Test void publish_recursivePublication_completesWithoutLockingHandlers() {
        var topics = new TerraTopics();
        topics.subscribe("outer", String.class, event -> topics.publish("inner", event)).unwrap();
        topics.subscribe("inner", String.class, _ -> Promise.unitPromise()).unwrap();
        topics.start();
        assertThat(topics.publish("outer", "hello").await(timeSpan(5).seconds()).isSuccess()).isTrue();
        assertThat(topics.publish("empty", "hello").await(timeSpan(5).seconds()).isSuccess()).isTrue();
        topics.close().await(timeSpan(5).seconds()).unwrap();
    }
}
