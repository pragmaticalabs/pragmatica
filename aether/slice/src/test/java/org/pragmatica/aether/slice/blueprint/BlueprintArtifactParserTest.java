// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;


class BlueprintArtifactParserTest {

    /// Spec event-stream-namespaces §11.1.2 wiring: `BlueprintArtifactParser` walks bundled slice
    /// manifests and produces a `Map<alias, role>` that flows into the deploy-time validator.
    @Nested
    class RoleHintAggregation {

        @Test
        void absentSliceManifestsYieldEmptyRoleHints() {
            var jar = jarBuilder().withBlueprintToml(simpleBlueprintToml()).build();

            var parsed = BlueprintArtifactParser.parse(jar)
                                                .onFailure(cause -> fail(cause.message()))
                                                .unwrap();

            assertThat(parsed.roleHints()).isEmpty();
        }

        @Test
        void singlePublisherProducesProducerHint() {
            var manifest = sliceManifestProps()
                    .withPublisher(0, "streams.orders")
                    .build();
            var jar = jarBuilder().withBlueprintToml(simpleBlueprintToml())
                                  .withSliceManifest("Orders.manifest", manifest)
                                  .build();

            var parsed = BlueprintArtifactParser.parse(jar).unwrap();

            assertThat(parsed.roleHints()).containsExactly(java.util.Map.entry("orders", "producer"));
        }

        @Test
        void singleConsumerProducesConsumerHint() {
            var manifest = sliceManifestProps()
                    .withConsumer(0, "streams.inventory")
                    .build();
            var jar = jarBuilder().withBlueprintToml(simpleBlueprintToml())
                                  .withSliceManifest("Inventory.manifest", manifest)
                                  .build();

            var parsed = BlueprintArtifactParser.parse(jar).unwrap();

            assertThat(parsed.roleHints()).containsExactly(java.util.Map.entry("inventory", "consumer"));
        }

        @Test
        void publisherAndConsumerOnSameAliasCollapseToBoth() {
            var manifest = sliceManifestProps()
                    .withPublisher(0, "streams.orders")
                    .withConsumer(0, "streams.orders")
                    .build();
            var jar = jarBuilder().withBlueprintToml(simpleBlueprintToml())
                                  .withSliceManifest("Orders.manifest", manifest)
                                  .build();

            var parsed = BlueprintArtifactParser.parse(jar).unwrap();

            assertThat(parsed.roleHints()).containsExactly(java.util.Map.entry("orders", "both"));
        }

        @Test
        void crossManifestPublisherAndConsumerCollapseToBoth() {
            var producerManifest = sliceManifestProps().withPublisher(0, "streams.events").build();
            var consumerManifest = sliceManifestProps().withConsumer(0, "streams.events").build();
            var jar = jarBuilder().withBlueprintToml(simpleBlueprintToml())
                                  .withSliceManifest("Producer.manifest", producerManifest)
                                  .withSliceManifest("Consumer.manifest", consumerManifest)
                                  .build();

            var parsed = BlueprintArtifactParser.parse(jar).unwrap();

            assertThat(parsed.roleHints()).containsExactly(java.util.Map.entry("events", "both"));
        }

        @Test
        void nonStreamConfigSectionsAreIgnored() {
            var manifest = sliceManifestProps()
                    .withPublisher(0, "datasource.primary")
                    .build();
            var jar = jarBuilder().withBlueprintToml(simpleBlueprintToml())
                                  .withSliceManifest("Slice.manifest", manifest)
                                  .build();

            var parsed = BlueprintArtifactParser.parse(jar).unwrap();

            assertThat(parsed.roleHints()).isEmpty();
        }

        @Test
        void mixedAliasesProduceDistinctEntries() {
            var manifest = sliceManifestProps()
                    .withPublisher(0, "streams.orders")
                    .withPublisher(1, "streams.notifications")
                    .withConsumer(0, "streams.inventory")
                    .build();
            var jar = jarBuilder().withBlueprintToml(simpleBlueprintToml())
                                  .withSliceManifest("Slice.manifest", manifest)
                                  .build();

            var parsed = BlueprintArtifactParser.parse(jar).unwrap();

            assertThat(parsed.roleHints()).containsOnly(java.util.Map.entry("orders", "producer"),
                                                        java.util.Map.entry("notifications", "producer"),
                                                        java.util.Map.entry("inventory", "consumer"));
        }
    }

    @Nested
    class ParseFailures {

        @Test
        void missingBlueprintTomlYieldsFailure() {
            var jar = jarBuilder().build();

            BlueprintArtifactParser.parse(jar)
                                   .onSuccess(_ -> fail("Expected failure"))
                                   .onFailure(cause -> assertThat(cause.message())
                                           .contains("META-INF/blueprint.toml"));
        }
    }

    // ----------- Test helpers -----------

    private static String simpleBlueprintToml() {
        return """
                id = "com.example:my-app:1.0.0"

                [[slices]]
                artifact = "com.example:my-slice:1.0.0"
                """;
    }

    private static JarBuilder jarBuilder() {
        return new JarBuilder();
    }

    private static SliceManifestPropsBuilder sliceManifestProps() {
        return new SliceManifestPropsBuilder();
    }

    private static final class JarBuilder {
        private final List<Entry> entries = new java.util.ArrayList<>();

        JarBuilder withBlueprintToml(String content) {
            entries.add(new Entry("META-INF/blueprint.toml", content.getBytes(StandardCharsets.UTF_8)));
            return this;
        }

        JarBuilder withSliceManifest(String filename, Properties props) {
            var bytes = serializeProperties(props);
            entries.add(new Entry("META-INF/slice/" + filename, bytes));
            return this;
        }

        byte[] build() {
            try {
                var bos = new ByteArrayOutputStream();
                try (var zos = new ZipOutputStream(bos)) {
                    for (var entry : entries) {
                        zos.putNextEntry(new ZipEntry(entry.name()));
                        zos.write(entry.content());
                        zos.closeEntry();
                    }
                }
                return bos.toByteArray();
            } catch (Exception e) {
                fail("JarBuilder.build failed: " + e.getMessage());
                return new byte[0];  // unreachable — fail() throws
            }
        }

        private static byte[] serializeProperties(Properties props) {
            try {
                var bos = new ByteArrayOutputStream();
                props.store(bos, null);
                return bos.toByteArray();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private record Entry(String name, byte[] content) {}
    }

    private static final class SliceManifestPropsBuilder {
        private final Properties props = new Properties();
        private int publisherCount;
        private int accessCount;

        SliceManifestPropsBuilder withPublisher(int index, String configSection) {
            props.setProperty("stream.publisher." + index + ".config", configSection);
            publisherCount = Math.max(publisherCount, index + 1);
            return this;
        }

        SliceManifestPropsBuilder withConsumer(int index, String configSection) {
            props.setProperty("stream.access." + index + ".config", configSection);
            accessCount = Math.max(accessCount, index + 1);
            return this;
        }

        Properties build() {
            props.setProperty("slice.name", "TestSlice");
            props.setProperty("stream.publishers.count", String.valueOf(publisherCount));
            props.setProperty("stream.access.count", String.valueOf(accessCount));
            return props;
        }
    }
}
