// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.pragmatica.aether.slice.resource.ResourceAddress;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.resource.ResourceAddress.resourceAddress;


class StreamLifecycleEventPolicyTest {

    @Test
    void doesNotEmitForSystemNamespace() {
        var address = resourceAddress("system:cluster-events:1.0.0").unwrap();

        assertThat(StreamLifecycleEventPolicy.shouldEmit(address)).isFalse();
    }

    @Test
    void emitsForAppNamespace() {
        var address = resourceAddress("com.example.app:orders:1.0.0").unwrap();

        assertThat(StreamLifecycleEventPolicy.shouldEmit(address)).isTrue();
    }

    @Test
    void emitsForAppNamespaceAcrossMultipleVersions() {
        assertThat(StreamLifecycleEventPolicy.shouldEmit(
                resourceAddress("io.acme.billing.invoice-service:invoices:1.0.0").unwrap())).isTrue();
        assertThat(StreamLifecycleEventPolicy.shouldEmit(
                resourceAddress("io.acme.billing.invoice-service:invoices:2.5.0").unwrap())).isTrue();
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
