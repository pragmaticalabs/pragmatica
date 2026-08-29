// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.resource.ResourceAddress;

import static org.assertj.core.api.Assertions.assertThat;

class StreamManagerTest {
    @Test
    void engineKey_systemNamespaceAddress_returnsBareName() {
        var address = ResourceAddress.resourceAddress("system", "diagnostics", "1.0.0").unwrap();

        assertThat(StreamManager.engineKey(address)).isEqualTo("diagnostics");
    }

    @Test
    void engineKey_appNamespaceAddress_returnsFullCatalogAddress() {
        var address = ResourceAddress.resourceAddress("orders", "events", "2.0.0").unwrap();

        assertThat(StreamManager.engineKey(address)).isEqualTo("orders:events:2.0.0");
    }

    @Test
    void systemAddress_mintsSystemNamespaceVersionOne() {
        var address = StreamManager.systemAddress("diagnostics").unwrap();

        assertThat(address.namespace().value()).isEqualTo("system");
        assertThat(address.name().value()).isEqualTo("diagnostics");
        assertThat(address.version().asString()).isEqualTo("1.0.0");
    }

    @Test
    void systemAddress_engineKeyRoundTrip_returnsBareName() {
        var address = StreamManager.systemAddress("harness-probe").unwrap();

        assertThat(StreamManager.engineKey(address)).isEqualTo("harness-probe");
    }
}
