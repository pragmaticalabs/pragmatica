// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.config;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// Operator events across failure episodes: an ordinary clean delivery raises nothing, a recovery is announced once
/// per episode, and every new episode warns again.
class DefaultConfigChangeFeedEventsTest {
    private final DefaultConfigChangeFeed feed = DefaultConfigChangeFeed.defaultConfigChangeFeed(() -> 0L,
                                                                                               ConfigReadProtocol.unprotected(),
                                                                                               TimeSpan.timeSpan(2).seconds());
    private final List<OperatorWarningCode> events = new CopyOnWriteArrayList<>();
    private final AtomicInteger value = new AtomicInteger(1);
    private final List<Integer> delivered = new CopyOnWriteArrayList<>();

    private void subscribeRefusing(int first, int second) {
        feed.setOperatorWarningSink(OperatorWarningSink.handingOffTo(warning -> events.add(warning.code())));
        feed.<Integer> subscribe("o:a:1", "app", () -> Result.success(value.get()), seen -> record(seen, first, second));
    }

    private Promise<Unit> record(int seen, int first, int second) {
        delivered.add(seen);
        return seen == first || seen == second
               ? Causes.cause("callback refused " + seen).promise()
               : Promise.unitPromise();
    }

    private void change(int to) {
        value.set(to);
        feed.publish("app.x");
        await().atMost(Duration.ofSeconds(5)).until(() -> delivered.contains(to));
        await().pollDelay(Duration.ofMillis(50)).atMost(Duration.ofSeconds(5)).until(() -> true);
    }

    @Test
    void cleanDeliveries_raiseNoEvent() {
        subscribeRefusing(99, 98);
        change(2);
        change(3);

        assertThat(delivered).containsExactly(1, 2, 3);
        assertThat(events).as("normal deliveries raise nothing, in particular no recovery").isEmpty();
    }

    @Test
    void everyNewFailureEpisode_warnsAgain_andEachIsRecoveredExactlyOnce() {
        subscribeRefusing(2, 5);
        change(2);
        change(3);
        change(4);
        change(5);
        change(6);

        assertThat(events).containsExactly(OperatorWarningCode.CONFIG_WATCH_CALLBACK_FAILED,
                                           OperatorWarningCode.CONFIG_WATCH_CALLBACK_RECOVERED,
                                           OperatorWarningCode.CONFIG_WATCH_CALLBACK_FAILED,
                                           OperatorWarningCode.CONFIG_WATCH_CALLBACK_RECOVERED);
    }
}
