// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


class StreamNamespacesServiceTest {

    @Test
    void bootstrapRegistersSystemStreams() {
        var service = StreamNamespacesService.inMemory();

        var result = service.bootstrap().unwrap();

        assertThat(result).hasSize(SystemStreams.ALL.size());
        assertThat(service.snapshot()).hasSize(SystemStreams.ALL.size());
    }

    @Test
    void lookupAfterBootstrapFindsClusterEvents() {
        var service = StreamNamespacesService.inMemory();
        service.bootstrap();

        var entry = service.lookup(SystemStreams.CLUSTER_EVENTS);

        assertThat(entry.isPresent()).isTrue();
    }

    @Test
    void resolveLatestAfterBootstrapResolvesClusterEvents() {
        var service = StreamNamespacesService.inMemory();
        service.bootstrap();

        var result = service.resolve("system", "cluster-events", StreamVersionSpec.latest());

        assertThat(result.unwrap().address()).isEqualTo(SystemStreams.CLUSTER_EVENTS);
    }
}
