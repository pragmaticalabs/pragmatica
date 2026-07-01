// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.stream.StreamResource;
import org.pragmatica.aether.slice.resource.ResourceVersion;
import org.pragmatica.aether.slice.stream.StreamVersionSpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.blueprint.StreamConfigParser.parseResources;


class StreamConfigParserTest {

    @Nested
    class ResourcesParsing {

        @Test
        void emptyInputYieldsEmptyMap() {
            var result = parseResources("").unwrap();

            assertThat(result).isEmpty();
        }

        @Test
        void blankInputYieldsEmptyMap() {
            var result = parseResources("   \n  ").unwrap();

            assertThat(result).isEmpty();
        }

        @Test
        void ownedWithExactVersion() {
            var toml = """
                    [streams.orders]
                    version = "1.0.0"
                    partitions = 8
                    """;

            var result = parseResources(toml).unwrap();

            assertThat(result).containsOnlyKeys("orders");
            var resource = result.get("orders");
            assertThat(resource).isInstanceOf(StreamResource.Owned.class);
            var owned = (StreamResource.Owned) resource;
            assertThat(owned.alias()).isEqualTo("orders");
            assertThat(owned.version()).isEqualTo(StreamVersionSpec.exact(ResourceVersion.resourceVersion(1, 0, 0).unwrap()));
            assertThat(owned.config().partitions()).isEqualTo(8);
        }

        @Test
        void ownedWithLatestVersion() {
            var toml = """
                    [streams.inventory]
                    version = "latest"
                    """;

            var result = parseResources(toml).unwrap();

            var owned = (StreamResource.Owned) result.get("inventory");
            assertThat(owned.version()).isSameAs(StreamVersionSpec.Latest.INSTANCE);
        }

        @Test
        void externalWithSource() {
            var toml = """
                    [streams.inventory_feed]
                    source = "io.acme.inventory:stock-updates:2.0.0"
                    """;

            var result = parseResources(toml).unwrap();

            assertThat(result).containsOnlyKeys("inventory_feed");
            var resource = result.get("inventory_feed");
            assertThat(resource).isInstanceOf(StreamResource.External.class);
            var external = (StreamResource.External) resource;
            assertThat(external.alias()).isEqualTo("inventory_feed");
            assertThat(external.target()).isEqualTo(
                    ResourceAddress.resourceAddress("io.acme.inventory:stock-updates:2.0.0").unwrap());
        }

        @Test
        void multipleStreamsCoexist() {
            var toml = """
                    [streams.orders]
                    version = "1.0.0"
                    partitions = 4

                    [streams.inventory_feed]
                    source = "io.acme.inventory:stock-updates:2.0.0"

                    [streams.audit_log]
                    version = "1.0.0"
                    partitions = 2
                    """;

            var result = parseResources(toml).unwrap();

            assertThat(result).hasSize(3);
            assertThat(result.get("orders")).isInstanceOf(StreamResource.Owned.class);
            assertThat(result.get("inventory_feed")).isInstanceOf(StreamResource.External.class);
            assertThat(result.get("audit_log")).isInstanceOf(StreamResource.Owned.class);
        }

        @Test
        void ignoresConsumerSubSections() {
            var toml = """
                    [streams.orders]
                    version = "1.0.0"

                    [streams.orders.consumers.analytics]
                    batch-size = 100
                    """;

            var result = parseResources(toml).unwrap();

            assertThat(result).containsOnlyKeys("orders");
        }
    }

    @Nested
    class ResourcesValidation {

        @Test
        void rejectsBothVersionAndSource() {
            var toml = """
                    [streams.orders]
                    version = "1.0.0"
                    source = "io.acme:other:2.0.0"
                    """;

            var result = parseResources(toml);

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("must not set both"));
        }

        @Test
        void rejectsMalformedVersion() {
            var toml = """
                    [streams.orders]
                    version = "1.0"
                    """;

            var result = parseResources(toml);

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void rejectsMinSyncReplicasExceedingReplicas() {
            var toml = """
                    [streams.orders]
                    version = "1.0.0"
                    replicas = 2
                    min-sync-replicas = 3
                    """;

            var result = parseResources(toml);

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("min-sync-replicas")
                                                                    .contains("replicas"));
        }

        @Test
        void acceptsMinSyncReplicasEqualToReplicas() {
            var toml = """
                    [streams.orders]
                    version = "1.0.0"
                    replicas = 3
                    min-sync-replicas = 3
                    """;

            var result = parseResources(toml);

            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        void rejectsMalformedSource() {
            var toml = """
                    [streams.bad_ref]
                    source = "not-a-valid-address"
                    """;

            var result = parseResources(toml);

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void rejectsProducerWithLatestVersion() {
            var toml = """
                    [streams.orders]
                    role = "producer"
                    version = "latest"
                    """;

            var result = parseResources(toml);

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("producer")
                                                                    .contains("latest"));
        }

        /// Reviewer test gap #20 — `role = "both"` is treated as producer for the
        /// producer-rejects-latest rule (spec §11.1.3): a slice that both writes and reads must
        /// pin the version exactly. Direct unit test on the parser; previously this rule was
        /// only covered indirectly via the validator-level test.
        @Test
        void bothRole_withVersionLatest_isRejected() {
            var toml = """
                    [streams.orders]
                    role = "both"
                    version = "latest"
                    """;

            var result = parseResources(toml);

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("both")
                                                                    .contains("latest"));
        }
    }

    @Nested
    class ShortcutDefaults {

        @Test
        void omittedVersionWithProducerRoleDefaultsToOneZeroZero() {
            var toml = """
                    [streams.orders]
                    role = "producer"
                    partitions = 4
                    """;

            var result = parseResources(toml).unwrap();

            var owned = (StreamResource.Owned) result.get("orders");
            assertThat(owned.version()).isEqualTo(StreamVersionSpec.exact(ResourceVersion.resourceVersion(1, 0, 0).unwrap()));
        }

        @Test
        void omittedVersionWithConsumerRoleDefaultsToLatest() {
            var toml = """
                    [streams.inventory]
                    role = "consumer"
                    """;

            var result = parseResources(toml).unwrap();

            var owned = (StreamResource.Owned) result.get("inventory");
            assertThat(owned.version()).isSameAs(StreamVersionSpec.Latest.INSTANCE);
        }

        @Test
        void omittedVersionAndOmittedRoleDefaultsToOneZeroZero() {
            var toml = """
                    [streams.notifications]
                    partitions = 4
                    retention = "time"
                    retention-value = "5m"
                    """;

            var result = parseResources(toml).unwrap();

            var owned = (StreamResource.Owned) result.get("notifications");
            assertThat(owned.version()).isEqualTo(StreamVersionSpec.exact(ResourceVersion.resourceVersion(1, 0, 0).unwrap()));
        }

        @Test
        void consumerRoleCaseInsensitive() {
            var toml = """
                    [streams.inventory]
                    role = "Consumer"
                    """;

            var result = parseResources(toml).unwrap();

            var owned = (StreamResource.Owned) result.get("inventory");
            assertThat(owned.version()).isSameAs(StreamVersionSpec.Latest.INSTANCE);
        }
    }
}
