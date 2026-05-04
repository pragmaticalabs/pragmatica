// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.jbct.slice;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.jbct.slice.SliceManifest.ResourceConfigRef;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;


class SliceManifestTest {

    @Nested
    class RoleInference {
        @Test
        void streamPublisherEntryGetsProducerRole() {
            var manifestText = """
                    slice.name=ProducerSlice
                    stream.publishers.count=1
                    stream.publisher.0.config=streams.orders
                    stream.publisher.0.eventType=test.dto.OrderEvent
                    stream.publisher.0.role=producer
                    stream.access.count=0
                    """;

            var manifest = SliceManifest.load(new ByteArrayInputStream(manifestText.getBytes(StandardCharsets.UTF_8)));

            manifest.onFailure(cause -> fail("Expected success: " + cause.message()))
                    .onSuccess(loaded -> {
                        var ref = findRef(loaded, "streams.orders");
                        assertThat(ref.role().or(() -> "")).isEqualTo("producer");
                    });
        }

        @Test
        void streamAccessEntryGetsConsumerRole() {
            var manifestText = """
                    slice.name=ConsumerSlice
                    stream.publishers.count=0
                    stream.access.count=1
                    stream.access.0.config=streams.inventory
                    stream.access.0.eventType=test.dto.StockEvent
                    stream.access.0.role=consumer
                    """;

            var manifest = SliceManifest.load(new ByteArrayInputStream(manifestText.getBytes(StandardCharsets.UTF_8)));

            manifest.onFailure(cause -> fail("Expected success: " + cause.message()))
                    .onSuccess(loaded -> {
                        var ref = findRef(loaded, "streams.inventory");
                        assertThat(ref.role().or(() -> "")).isEqualTo("consumer");
                    });
        }

        @Test
        void coexistingPublisherAndAccessForSameConfigMergeToBoth() {
            var manifestText = """
                    slice.name=BothSlice
                    stream.publishers.count=1
                    stream.publisher.0.config=streams.orders
                    stream.publisher.0.eventType=test.dto.OrderEvent
                    stream.publisher.0.role=producer
                    stream.access.count=1
                    stream.access.0.config=streams.orders
                    stream.access.0.eventType=test.dto.OrderEvent
                    stream.access.0.role=consumer
                    """;

            var manifest = SliceManifest.load(new ByteArrayInputStream(manifestText.getBytes(StandardCharsets.UTF_8)));

            manifest.onFailure(cause -> fail("Expected success: " + cause.message()))
                    .onSuccess(loaded -> assertThat(loaded.resourceConfigRefs())
                                                  .extracting(ref -> ref.role().or(() -> ""))
                                                  .contains("both"));
        }

        @Test
        void nonStreamResourceCarriesEmptyRole() {
            var manifestText = """
                    slice.name=DbSlice
                    resources.count=1
                    resource.0.config=database.primary
                    resource.0.type=DatabaseConnector
                    """;

            var manifest = SliceManifest.load(new ByteArrayInputStream(manifestText.getBytes(StandardCharsets.UTF_8)));

            manifest.onFailure(cause -> fail("Expected success: " + cause.message()))
                    .onSuccess(loaded -> {
                        var ref = findRef(loaded, "database.primary");
                        assertThat(ref.role().isEmpty()).isTrue();
                    });
        }
    }

    @Nested
    class BackwardCompatibility {
        @Test
        void twoArgConstructorYieldsAbsentRole() {
            var ref = new ResourceConfigRef("DatabaseConnector", "database.primary");
            assertThat(ref.role().isEmpty()).isTrue();
        }

        @Test
        void manifestWithoutRolePropertyStillLoads() {
            var manifestText = """
                    slice.name=LegacySlice
                    stream.publishers.count=1
                    stream.publisher.0.config=streams.legacy
                    stream.publisher.0.eventType=test.dto.LegacyEvent
                    stream.access.count=0
                    """;

            var manifest = SliceManifest.load(new ByteArrayInputStream(manifestText.getBytes(StandardCharsets.UTF_8)));

            manifest.onFailure(cause -> fail("Expected success: " + cause.message()))
                    .onSuccess(loaded -> {
                        // ManifestGenerator from this commit always writes a role; this test
                        // covers the case where an older manifest format omits the field. The
                        // reader supplies the prefix-default role even when no explicit role
                        // appears in the manifest text — for stream.publisher prefix that is
                        // "producer".
                        var ref = findRef(loaded, "streams.legacy");
                        assertThat(ref.role().or(() -> "")).isEqualTo("producer");
                    });
        }
    }

    private static ResourceConfigRef findRef(SliceManifest manifest, String configSection) {
        return manifest.resourceConfigRefs().stream()
                                          .filter(ref -> ref.configSection().equals(configSection))
                                          .findFirst()
                                          .orElseThrow(() -> new AssertionError("No ref for " + configSection));
    }
}
