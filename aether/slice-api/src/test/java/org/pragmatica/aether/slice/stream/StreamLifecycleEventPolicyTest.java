// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.stream.StreamAddress.streamAddress;


class StreamLifecycleEventPolicyTest {

    @Test
    void doesNotEmitForSystemNamespace() {
        var address = streamAddress("system:cluster-events:1.0.0").unwrap();

        assertThat(StreamLifecycleEventPolicy.shouldEmit(address)).isFalse();
    }

    @Test
    void emitsForAppNamespace() {
        var address = streamAddress("com.example.app:orders:1.0.0").unwrap();

        assertThat(StreamLifecycleEventPolicy.shouldEmit(address)).isTrue();
    }

    @Test
    void emitsForAppNamespaceAcrossMultipleVersions() {
        assertThat(StreamLifecycleEventPolicy.shouldEmit(
                streamAddress("io.acme.billing.invoice-service:invoices:1.0.0").unwrap())).isTrue();
        assertThat(StreamLifecycleEventPolicy.shouldEmit(
                streamAddress("io.acme.billing.invoice-service:invoices:2.5.0").unwrap())).isTrue();
    }

    @Test
    void coversAllRegisteredSystemStreamAddresses() {
        for (var addr : SystemStreams.ALL) {
            assertThat(StreamLifecycleEventPolicy.shouldEmit(addr))
                    .as("system address %s must be filtered", addr)
                    .isFalse();
        }
    }
}
