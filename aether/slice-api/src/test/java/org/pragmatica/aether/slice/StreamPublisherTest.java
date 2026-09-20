// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.slice;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.stream.PublishOutcome;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class StreamPublisherTest {

    /// #1342: `publishBatch` has no default (a batch derived from `publish` could not report offsets), so the
    /// SPI is no longer a functional interface; a stub implements both methods.
    @Nested
    class PublishContract {

        @Test
        void publish_returnsSuccessPromise() {
            var result = stubPublisher().publish("test-event").await();

            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        void publishBatch_reportsOneOutcomePerEvent_inInputOrder() {
            var outcomes = stubPublisher().publishBatch(List.of("a", "b")).await().unwrap();

            assertThat(outcomes).containsExactly(new PublishOutcome.Published(0L), new PublishOutcome.Published(1L));
        }
    }

    private static StreamPublisher<String> stubPublisher() {
        return new StreamPublisher<>() {
            @Override
            public Promise<Unit> publish(String event) {
                return Promise.success(Unit.unit());
            }

            @Override
            public Promise<List<PublishOutcome>> publishBatch(List<String> events) {
                return Promise.success(IntStream.range(0, events.size())
                                                .<PublishOutcome> mapToObj(PublishOutcome.Published::new)
                                                .toList());
            }
        };
    }
}
