// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.resource.ResourceAddress;

import static org.assertj.core.api.Assertions.assertThat;

/// Verifies [TopicConfig#address] resolution: explicit `namespace:topic:version` declarations
/// round-trip verbatim, while bare/legacy names are lifted to the placeholder `default` namespace
/// (the deploy path later swaps in the blueprint-derived namespace).
class TopicConfigTest {

    @Test
    void explicitlyNamespacedTopicRoundTrips() {
        var config = new TopicConfig("org.example.shop:order-events:2.1.0");

        var address = config.address().unwrap();

        assertThat(address.namespace()).isEqualTo("org.example.shop");
        assertThat(address.name()).isEqualTo("order-events");
        assertThat(address.version().asString()).isEqualTo("2.1.0");
        assertThat(address.asString()).isEqualTo("org.example.shop:order-events:2.1.0");
    }

    @Test
    void explicitSystemNamespaceParsesForFrameworkTopics() {
        var config = new TopicConfig("system:cluster-events:1.0.0");

        var address = config.address().unwrap();

        assertThat(address.isSystem()).isTrue();
        assertThat(address.asString()).isEqualTo("system:cluster-events:1.0.0");
    }

    @Test
    void bareNameResolvesToDefaultNamespaceAndDefaultVersion() {
        var config = new TopicConfig("order-events");

        var address = config.address().unwrap();

        assertThat(address.namespace()).isEqualTo(ResourceAddress.DEFAULT_NAMESPACE);
        assertThat(address.name()).isEqualTo("order-events");
        assertThat(address.version().asString()).isEqualTo("1.0.0");
    }

    @Test
    void malformedExplicitAddressIsRejected() {
        var config = new TopicConfig("Bad Namespace:order-events:1.0.0");

        assertThat(config.address().isFailure()).isTrue();
    }
}
