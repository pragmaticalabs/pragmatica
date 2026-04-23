// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.stream.StreamRegistry.StreamRegistryError.General;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;


class StreamNamespacesServiceTest {

    private static Cause errorOf(Result<?> result) {
        return result.fold(c -> c, _ -> null);
    }

    @Nested
    class Disabled {

        @Test
        void bootstrapIsNoOp() {
            var service = StreamNamespacesService.disabled();

            var result = service.bootstrap().unwrap();

            assertThat(result).isEmpty();
            assertThat(service.snapshot()).isEmpty();
        }

        @Test
        void lookupReturnsNone() {
            var service = StreamNamespacesService.disabled();

            assertThat(service.lookup(SystemStreams.CLUSTER_EVENTS).isEmpty()).isTrue();
        }

        @Test
        void resolveReturnsNotFound() {
            var service = StreamNamespacesService.disabled();

            var result = service.resolve("system", "cluster-events", StreamVersionSpec.latest());

            assertThat(errorOf(result)).isEqualTo(General.NOT_FOUND);
        }

        @Test
        void enabledIsFalse() {
            assertThat(StreamNamespacesService.disabled().enabled()).isFalse();
        }
    }

    @Nested
    class Enabled {

        @Test
        void bootstrapRegistersSystemStreams() {
            var service = StreamNamespacesService.enabledInMemory();

            var result = service.bootstrap().unwrap();

            assertThat(result).hasSize(SystemStreams.ALL.size());
            assertThat(service.snapshot()).hasSize(SystemStreams.ALL.size());
        }

        @Test
        void lookupAfterBootstrapFindsClusterEvents() {
            var service = StreamNamespacesService.enabledInMemory();
            service.bootstrap();

            var entry = service.lookup(SystemStreams.CLUSTER_EVENTS);

            assertThat(entry.isPresent()).isTrue();
        }

        @Test
        void resolveLatestAfterBootstrapResolvesClusterEvents() {
            var service = StreamNamespacesService.enabledInMemory();
            service.bootstrap();

            var result = service.resolve("system", "cluster-events", StreamVersionSpec.latest());

            assertThat(result.unwrap().address()).isEqualTo(SystemStreams.CLUSTER_EVENTS);
        }

        @Test
        void enabledIsTrue() {
            assertThat(StreamNamespacesService.enabledInMemory().enabled()).isTrue();
        }
    }
}
