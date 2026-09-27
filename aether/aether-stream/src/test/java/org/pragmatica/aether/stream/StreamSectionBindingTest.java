// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.resource.SpiResourceProvider;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.RetentionMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.aether.slice.blueprint.StreamConfigParser;
import org.pragmatica.aether.slice.blueprint.StreamDeclarationError;
import org.pragmatica.aether.slice.stream.StreamResource;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.source.TomlConfigSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.serialization.Serializer;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #1549: a `[streams.X]` section is provisioned with exactly what deploy validation parsed.
///
/// Before #1549 the stream resource factories left binding to the generic record binder, which reads the
/// snake_case names of `StreamConfig`'s components and resolves anything it does not find from
/// `StreamConfig.DEFAULT`. Every documented key but `partitions` and `replicas` is spelled differently or
/// shaped differently (`retention` is a type plus a value, `max-event-size` a size string), so a section
/// declaring `min-sync-replicas = 2, max-event-size = "64KB"` was provisioned with `0` and `1 MiB`. What
/// each key bound to before the fix, for the record: `min-sync-replicas` → 0, `max-event-size` → 1 MiB,
/// `retention`/`retention-value`/`retention-mode`/`max-age`/`max-count`/`max-bytes` → the default
/// `RetentionPolicy`, `auto-offset-reset` → "earliest", `consistency` → EVENTUAL, `compression` → NONE,
/// `encryption-key-id` → none; `partitions` and `replicas` bound correctly because their spellings agree.
///
/// Every key here is declared with a NON-default value, so a single key falling back to its default is
/// visible in the assertion that covers it.
class StreamSectionBindingTest {
    private static final String SECTION = "streams.orders";
    private static final String ALIAS = "orders";
    private static final long DEFAULT_COUNT = RetentionPolicy.retentionPolicy().maxCount();

    private static final String EVERY_KEY = """
            [streams.orders]
            version = "1.0.0"
            role = "producer"
            partitions = 3
            retention = "compound"
            retention-mode = "all"
            max-age = "2h"
            max-count = "5000"
            max-bytes = "8MB"
            auto-offset-reset = "latest"
            max-event-size = "64KB"
            consistency = "strong"
            replicas = 3
            min-sync-replicas = 2
            compression = "lz4"
            encryption-key-id = "orders-key"
            """;

    private static ConfigurationProvider providerOf(String toml) {
        return ConfigurationProvider.builder()
                                    .withSource(TomlConfigSource.tomlConfigSource(toml).unwrap())
                                    .build();
    }

    @Nested
    class EveryKeyReachesTheRuntime {
        @Test
        void streamPublisherFactory_bindsEveryDeclaredKey_fromTheSection() {
            assertEveryKeyBound(bindWith(new StreamPublisherFactory(), EVERY_KEY));
        }

        @Test
        void streamAccessFactory_bindsEveryDeclaredKey_fromTheSection() {
            assertEveryKeyBound(bindWith(new StreamAccessFactory(), EVERY_KEY));
        }

        @Test
        void countRetention_bindsItsValue() {
            var config = bindWith(new StreamPublisherFactory(), """
                    [streams.orders]
                    retention = "count"
                    retention-value = "4321"
                    """);

            assertThat(config.retention()).isEqualTo(RetentionPolicy.retentionPolicy(4321, Long.MAX_VALUE, Long.MAX_VALUE, RetentionMode.ANY));
        }

        @Test
        void timeRetention_bindsItsValue() {
            var config = bindWith(new StreamPublisherFactory(), """
                    [streams.orders]
                    retention = "time"
                    retention-value = "5m"
                    """);

            assertThat(config.retention()).isEqualTo(RetentionPolicy.retentionPolicy(DEFAULT_COUNT, Long.MAX_VALUE, 300_000L, RetentionMode.ANY));
        }

        @Test
        void sizeRetention_bindsItsValue() {
            var config = bindWith(new StreamPublisherFactory(), """
                    [streams.orders]
                    retention = "size"
                    retention-value = "2MB"
                    """);

            assertThat(config.retention()).isEqualTo(RetentionPolicy.retentionPolicy(DEFAULT_COUNT, 2L * 1024 * 1024, Long.MAX_VALUE, RetentionMode.ANY));
        }

        private static void assertEveryKeyBound(StreamConfig config) {
            assertThat(config.name()).isEqualTo(ALIAS);
            assertThat(config.partitions()).isEqualTo(3);
            assertThat(config.retention()).isEqualTo(RetentionPolicy.retentionPolicy(5000, 8L * 1024 * 1024, 7_200_000L, RetentionMode.ALL));
            assertThat(config.autoOffsetReset()).isEqualTo("latest");
            assertThat(config.maxEventSizeBytes()).isEqualTo(64L * 1024);
            assertThat(config.consistencyMode()).isEqualTo(ConsistencyMode.STRONG);
            assertThat(config.replicas()).isEqualTo(3);
            assertThat(config.minSyncReplicas()).isEqualTo(2);
            assertThat(config.compression()).isEqualTo(StreamCompression.LZ4);
            assertThat(config.encryptionKeyId()).isEqualTo(Option.some("orders-key"));
        }
    }

    /// #1549 blast radius: `time` and `size` retention never reached the runtime before, and built a ring with an
    /// unbounded count that stream creation could not allocate (it threw IndexOutOfBoundsException). Each form a
    /// shipped example or fixture declares must bind to a config a manager can create.
    @Nested
    class EveryRetentionFormCreates {
        @Test
        void timeRetention_bindsAndCreates() {
            assertCreates("""
                    [streams.orders]
                    partitions = 4
                    retention = "time"
                    retention-value = "5m"
                    max-event-size = "64KB"
                    """);
        }

        @Test
        void sizeRetention_bindsAndCreates() {
            assertCreates("""
                    [streams.orders]
                    retention = "size"
                    retention-value = "64MB"
                    """);
        }

        @Test
        void compoundRetentionWithoutMaxCount_bindsAndCreates() {
            assertCreates("""
                    [streams.orders]
                    retention = "compound"
                    max-age = "1h"
                    """);
        }

        @Test
        void unindexableCount_isRefusedTyped_notThrown() {
            var manager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
            try {
                var config = bindWith(new StreamPublisherFactory(), """
                        [streams.orders]
                        retention = "compound"
                        max-count = "9223372036854775807"
                        """);

                manager.createStream(config)
                       .onSuccess(_ -> fail("an unindexable count must be refused"))
                       .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.RetentionCountUnindexable.class));
            } finally {
                manager.close();
            }
        }

        private static void assertCreates(String toml) {
            var manager = StreamPartitionManager.streamPartitionManager(128L * 1024 * 1024);
            try {
                manager.createStream(bindWith(new StreamPublisherFactory(), toml))
                       .onFailure(cause -> fail("stream creation failed: " + cause.message()));
                assertThat(manager.streamInfo(ALIAS).isPresent()).isTrue();
            } finally {
                manager.close();
            }
        }
    }

    /// One source of truth: the deploy-time parse of the blueprint text and the activation-time bind of the
    /// same section produce the same config.
    @Nested
    class DeployAndRuntimeAgree {
        @Test
        void deployParse_and_runtimeBind_produceTheSameConfig() {
            var deployed = StreamConfigParser.parseResources(EVERY_KEY)
                                             .map(resources -> ((StreamResource.Owned) resources.get(ALIAS)).config())
                                             .onFailure(cause -> fail(cause.message()))
                                             .unwrap();

            assertThat(bindWith(new StreamPublisherFactory(), EVERY_KEY)).isEqualTo(deployed);
        }
    }

    /// A key nothing reads is refused at activation too — the path a section reaches when deploy validation
    /// never saw it (a config-only change) — rather than resolved to a default.
    @Nested
    class UnreadKeysFailLoudly {
        @Test
        void snakeCaseKey_isRefused_namingTheDashedSpelling() {
            var toml = """
                    [streams.orders]
                    min_sync_replicas = 2
                    """;

            new StreamPublisherFactory().sectionBinder()
                                        .onEmpty(() -> fail("stream factories must bind their own section"))
                                        .onPresent(binder -> binder.bind(providerOf(toml), SECTION)
                                                                   .onSuccess(config -> fail("unread key must be refused, bound " + config))
                                                                   .onFailure(cause -> assertThat(cause).isInstanceOf(StreamDeclarationError.UnknownStreamKeys.class))
                                                                   .onFailure(cause -> assertThat(cause.message()).contains("'min_sync_replicas'")
                                                                                                                  .contains("did you mean 'min-sync-replicas'")));
        }

        @Test
        void nonIntegerValue_isRefused_notDefaulted() {
            var toml = """
                    [streams.orders]
                    min-sync-replicas = "two"
                    """;

            new StreamPublisherFactory().sectionBinder()
                                        .onEmpty(() -> fail("stream factories must bind their own section"))
                                        .onPresent(binder -> binder.bind(providerOf(toml), SECTION)
                                                                   .onSuccess(config -> fail("non-integer must be refused, bound " + config)));
        }

        @Test
        void consumerSubSection_isNotAKeyOfTheStreamSection() {
            var config = bindWith(new StreamPublisherFactory(), """
                    [streams.orders]
                    partitions = 2

                    [streams.orders.consumers.billing]
                    batch-size = 50
                    """);

            assertThat(config.partitions()).isEqualTo(2);
        }
    }

    /// The full activation seam: [SpiResourceProvider] binds a stream resource's config with the factory's
    /// own binder over the slice's configuration provider, and the stream is created with the declared
    /// `min-sync-replicas`, which the record binder dropped to `0`.
    @Nested
    class ThroughTheResourceProvider {
        @Test
        void provide_bindsWithTheFactorySectionBinder_andTheManagerHoldsTheDeclaredMinSync() {
            var manager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
            var capturing = new CapturingPublisherFactory();
            var provider = SpiResourceProvider.spiResourceProvider(List.of(capturing),
                                                                   (_, _) -> Result.success(StreamConfig.DEFAULT));
            var context = ProvisioningContext.provisioningContext()
                                             .withExtension(StreamPartitionManager.class, manager)
                                             .withExtension(Serializer.class, identitySerializer())
                                             .withExtension(ConfigurationProvider.class, providerOf("""
                                                     [streams.orders]
                                                     partitions = 2
                                                     retention = "count"
                                                     retention-value = "1000"
                                                     max-event-size = "64KB"
                                                     replicas = 3
                                                     min-sync-replicas = 2
                                                     """));
            try {
                provider.provide(StreamPublisher.class, SECTION, context)
                        .await()
                        .onFailure(cause -> fail("provisioning failed: " + cause.message()));

                assertThat(capturing.seen).hasSize(1);
                var bound = capturing.seen.getFirst();
                assertThat(bound.minSyncReplicas()).isEqualTo(2);
                assertThat(bound.maxEventSizeBytes()).isEqualTo(64L * 1024);
                assertThat(bound.retention().maxCount()).isEqualTo(1000L);
                assertThat(manager.minSyncReplicasFor(ALIAS)).isEqualTo(2);
            } finally {
                manager.close();
            }
        }
    }

    private static StreamConfig bindWith(ResourceFactory<?, StreamConfig> factory, String toml) {
        return factory.sectionBinder()
                      .map(binder -> binder.bind(providerOf(toml), SECTION))
                      .or(() -> fail("stream factories must bind their own section"))
                      .onFailure(cause -> fail(cause.message()))
                      .unwrap();
    }

    /// Delegates everything to the real [StreamPublisherFactory] — including its section binder, which is
    /// what is under test — and records the config the provider handed to `provision`.
    private static final class CapturingPublisherFactory implements ResourceFactory<StreamPublisher, StreamConfig> {
        private final StreamPublisherFactory delegate = new StreamPublisherFactory();
        private final List<StreamConfig> seen = new ArrayList<>();

        @Override
        public Class<StreamPublisher> resourceType() {
            return delegate.resourceType();
        }

        @Override
        public Class<StreamConfig> configType() {
            return delegate.configType();
        }

        @Override
        public Promise<StreamPublisher> provision(StreamConfig config) {
            return delegate.provision(config);
        }

        @Override
        public Promise<StreamPublisher> provision(StreamConfig config, ProvisioningContext context) {
            seen.add(config);

            return delegate.provision(config, context);
        }

        @Override
        public Option<SectionBinder<StreamConfig>> sectionBinder() {
            return delegate.sectionBinder();
        }
    }

    private static Serializer identitySerializer() {
        return new Serializer() {
            @SuppressWarnings("unchecked") @Override public <T> byte[] encode(T object) {return (byte[]) object;}

            @Override public <T> void write(ByteBuf byteBuf, T object) {byteBuf.writeBytes((byte[]) object);}
        };
    }
}
