// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.slice;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;

class StreamPublisherTest {

    @Nested
    class FunctionalInterfaceContract {

        @Test
        void canBeAssignedAsLambda() {
            StreamPublisher<String> publisher = event -> Promise.success(Unit.unit());

            assertThat(publisher).isNotNull();
        }

        @Test
        void canBeAssignedAsMethodReference() {
            StreamPublisher<String> publisher = StreamPublisherTest::stubPublish;

            assertThat(publisher).isNotNull();
        }

        @Test
        void publish_returnsSuccessPromise() {
            StreamPublisher<String> publisher = event -> Promise.success(Unit.unit());

            var result = publisher.publish("test-event").await();

            assertThat(result.isSuccess()).isTrue();
        }
    }

    private static Promise<Unit> stubPublish(String event) {
        return Promise.success(Unit.unit());
    }
}
