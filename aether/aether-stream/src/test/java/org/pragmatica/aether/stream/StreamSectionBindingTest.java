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
import org.pragmatica.aether.slice.ReplicationContext;
import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.aether.slice.ReplicationFactorsError;
import org.pragmatica.aether.slice.ReplicationWarning;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.aether.slice.blueprint.StreamConfigParser;
import org.pragmatica.aether.slice.blueprint.StreamDeclarationError;
import org.pragmatica.aether.slice.stream.StreamResource;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.source.TomlConfigSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.serialization.Serializer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    /// #1564: the activation binder resolves the declared factors against the node\'s replication context.
    private static final ProvisioningContext BINDING_CONTEXT = ProvisioningContext.provisioningContext()
                                                                                  .withExtension(ReplicationContext.Source.class,
                                                                                                 ReplicationContext.Source.fixed(ReplicationContext.BUILT_IN));
    private static final String SECTION = "streams.orders";
    private static final String ALIAS = "orders";
    private static final RetentionPolicy DEFAULTS = RetentionPolicy.retentionPolicy();

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
            replication_factor = 3
            confirmation_factor = 2
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

            assertThat(config.retention()).isEqualTo(RetentionPolicy.retentionPolicy(DEFAULTS.maxCount(), DEFAULTS.maxBytes(), 300_000L, RetentionMode.ANY));
        }

        @Test
        void sizeRetention_bindsItsValue() {
            var config = bindWith(new StreamPublisherFactory(), """
                    [streams.orders]
                    retention = "size"
                    retention-value = "2MB"
                    """);

            assertThat(config.retention()).isEqualTo(RetentionPolicy.retentionPolicy(DEFAULTS.maxCount(), 2L * 1024 * 1024, DEFAULTS.maxAgeMs(), RetentionMode.ANY));
        }

        private static void assertEveryKeyBound(StreamConfig config) {
            assertThat(config.name()).isEqualTo(ALIAS);
            assertThat(config.partitions()).isEqualTo(3);
            assertThat(config.retention()).isEqualTo(RetentionPolicy.retentionPolicy(5000, 8L * 1024 * 1024, 7_200_000L, RetentionMode.ALL));
            assertThat(config.autoOffsetReset()).isEqualTo("latest");
            assertThat(config.maxEventSizeBytes()).isEqualTo(64L * 1024);
            assertThat(config.consistencyMode()).isEqualTo(ConsistencyMode.STRONG);
            assertThat(config.replicationFactor()).isEqualTo(3);
            assertThat(config.confirmationFactor()).isEqualTo(2);
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

        /// The shipped example, read from the repository, not copied: its `retention = "time"`, `"5m"` must
        /// resolve to 5 minutes with the default count and byte caps, and its stream must create.
        @Test
        void notificationHubExample_streamsResolveAndCreate() throws IOException {
            var service = Files.readString(Path.of("../../examples/notification-hub/notification-service/src/main/resources/resources.toml"));
            var expected = RetentionPolicy.retentionPolicy(DEFAULTS.maxCount(), DEFAULTS.maxBytes(), 300_000L, RetentionMode.ANY);
            var config = bindWith(new StreamPublisherFactory(), service, "streams.notifications");

            System.out.println("#1549 notification-hub resolved retention: " + config.retention() + " maxEventSizeBytes=" + config.maxEventSizeBytes());
            assertThat(config.retention()).isEqualTo(expected);
            assertThat(config.maxEventSizeBytes()).isEqualTo(64L * 1024);
            assertCreates(service, "streams.notifications", "notifications");

            for (var consumer : List.of("notification-analytics", "notification-emailer")) {
                var toml = Files.readString(Path.of("../../examples/notification-hub/" + consumer + "/src/main/resources/resources.toml"));
                assertThat(bindWith(new StreamAccessFactory(), toml, "streams.notifications").retention()).isEqualTo(expected);
            }
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
            assertCreates(toml, SECTION, ALIAS);
        }

        private static void assertCreates(String toml, String section, String alias) {
            var manager = StreamPartitionManager.streamPartitionManager(128L * 1024 * 1024);
            try {
                manager.createStream(bindWith(new StreamPublisherFactory(), toml, section))
                       .onFailure(cause -> fail("stream creation failed: " + cause.message()));
                assertThat(manager.streamInfo(alias).isPresent()).isTrue();
            } finally {
                manager.close();
            }
        }
    }

    /// #1549, per bound: every bound a `time`/`size`/`compound` form does not declare resolves to the
    /// RetentionPolicy default, and every bound the engine cannot honour is refused as a typed Result —
    /// never a throw out of the ring build.
    @Nested
    class RetentionBounds {
        @Test
        void compoundDeclaringOnlyCount_defaultsBytesAndAge() {
            assertThat(retentionOf("retention = \"compound\"\nmax-count = \"500\""))
                .isEqualTo(RetentionPolicy.retentionPolicy(500, DEFAULTS.maxBytes(), DEFAULTS.maxAgeMs(), RetentionMode.ANY));
        }

        @Test
        void compoundDeclaringOnlyBytes_defaultsCountAndAge() {
            assertThat(retentionOf("retention = \"compound\"\nmax-bytes = \"8MB\""))
                .isEqualTo(RetentionPolicy.retentionPolicy(DEFAULTS.maxCount(), 8L * 1024 * 1024, DEFAULTS.maxAgeMs(), RetentionMode.ANY));
        }

        @Test
        void compoundDeclaringOnlyAge_defaultsCountAndBytes() {
            assertThat(retentionOf("retention = \"compound\"\nmax-age = \"2h\""))
                .isEqualTo(RetentionPolicy.retentionPolicy(DEFAULTS.maxCount(), DEFAULTS.maxBytes(), 7_200_000L, RetentionMode.ANY));
        }

        /// The parser refuses these first (StreamConfigParserTest); the engine backstop refuses the same bounds
        /// on a config built by any other path, typed, never a throw out of the ring build.
        @Test
        void engineBackstop_zeroCount_isRefusedTyped() {
            assertEngineRefuses(RetentionPolicy.retentionPolicy(0, DEFAULTS.maxBytes(), DEFAULTS.maxAgeMs()),
                                new StreamError.RetentionBoundInvalid(ALIAS, "max-count", 0));
        }

        @Test
        void engineBackstop_negativeCount_isRefusedTyped() {
            assertEngineRefuses(RetentionPolicy.retentionPolicy(-5, DEFAULTS.maxBytes(), DEFAULTS.maxAgeMs()),
                                new StreamError.RetentionBoundInvalid(ALIAS, "max-count", -5));
        }

        @Test
        void engineBackstop_zeroBytes_isRefusedTyped() {
            assertEngineRefuses(RetentionPolicy.retentionPolicy(DEFAULTS.maxCount(), 0, DEFAULTS.maxAgeMs()),
                                new StreamError.RetentionBoundInvalid(ALIAS, "max-bytes", 0));
        }

        @Test
        void engineBackstop_negativeAge_isRefusedTyped() {
            assertEngineRefuses(RetentionPolicy.retentionPolicy(DEFAULTS.maxCount(), DEFAULTS.maxBytes(), -1),
                                new StreamError.RetentionBoundInvalid(ALIAS, "max-age", -1));
        }

        @Test
        void bindTimeRefusal_isTheParsersTypedCause() {
            new StreamPublisherFactory().sectionBinder()
                                        .onEmpty(() -> fail("stream factories must bind their own section"))
                                        .onPresent(binder -> binder.bind(providerOf(compound("max-count = \"0\"")), SECTION, BINDING_CONTEXT)
                                                                   .onSuccess(config -> fail("count 0 must be refused, bound " + config))
                                                                   .onFailure(cause -> assertThat(cause).isEqualTo(new StreamDeclarationError.ValueOutOfRange(ALIAS,
                                                                                                                                                              "max-count",
                                                                                                                                                              "0",
                                                                                                                                                              1))));
        }

        @Test
        void countOnePastTheIndexableCapacity_isRefusedTyped() {
            assertRefused("max-count = \"" + (OffHeapRingBuffer.MAX_CAPACITY + 1) + "\"",
                          new StreamError.RetentionCountUnindexable(ALIAS, OffHeapRingBuffer.MAX_CAPACITY + 1, OffHeapRingBuffer.MAX_CAPACITY));
        }

        /// At the capacity itself the ring is indexable in principle but not affordable: the refusal is the
        /// budget's typed one, not a throw.
        @Test
        void countAtTheIndexableCapacity_isRefusedByTheBudget_typed() {
            assertRefused("max-count = \"" + OffHeapRingBuffer.MAX_CAPACITY + "\"", StreamError.General.STREAM_MEMORY_EXCEEDED);
        }

        @Test
        void declaredUnboundedBytesAndAge_create() {
            var manager = StreamPartitionManager.streamPartitionManager(128L * 1024 * 1024);
            try {
                manager.createStream(bindWith(new StreamPublisherFactory(),
                                              compound("max-bytes = \"9223372036854775807\"\nmax-age = \"9223372036854775807\"")))
                       .onFailure(cause -> fail("declared unbounded bytes/age must create: " + cause.message()));
            } finally {
                manager.close();
            }
        }

        private static RetentionPolicy retentionOf(String retentionLines) {
            return bindWith(new StreamPublisherFactory(), "[streams.orders]\n" + retentionLines + "\n").retention();
        }

        private static String compound(String lines) {
            return "[streams.orders]\nretention = \"compound\"\n" + lines + "\n";
        }

        private static void assertEngineRefuses(RetentionPolicy retention, Cause expected) {
            var defaults = StreamConfig.streamConfig(ALIAS);
            var config = StreamConfig.streamConfig(ALIAS,
                                                   1,
                                                   retention,
                                                   defaults.autoOffsetReset(),
                                                   defaults.maxEventSizeBytes(),
                                                   defaults.consistencyMode(),
                                                   defaults.replicationFactor(),
                                                   defaults.confirmationFactor(),
                                                   defaults.compression(),
                                                   defaults.encryptionKeyId());
            var manager = StreamPartitionManager.streamPartitionManager(128L * 1024 * 1024);
            try {
                manager.createStream(config)
                       .onSuccess(_ -> fail("expected " + expected))
                       .onFailure(cause -> assertThat(cause).isEqualTo(expected));
            } finally {
                manager.close();
            }
        }

        private static void assertRefused(String boundLine, Cause expected) {
            var manager = StreamPartitionManager.streamPartitionManager(128L * 1024 * 1024);
            try {
                manager.createStream(bindWith(new StreamPublisherFactory(), compound(boundLine)))
                       .onSuccess(_ -> fail("expected " + expected))
                       .onFailure(cause -> assertThat(cause).isEqualTo(expected));
            } finally {
                manager.close();
            }
        }
    }

    /// v1557 probed two cases where deploy (TOML text) and activation (flattened provider) disagreed; both views
    /// now refuse them with the same typed cause.
    @Nested
    class DeployAndActivationRefuseAlike {
        @Test
        void quotedDottedKey_isRefusedAtBoth() {
            var toml = """
                    [streams.orders]
                    version = "1.0.0"
                    "retention.value" = "5"
                    """;

            assertBothRefuseWith(toml, StreamDeclarationError.UnknownStreamKeys.class);
        }

        @Test
        void subTableOtherThanConsumers_isRefusedAtBoth() {
            var toml = """
                    [streams.orders]
                    version = "1.0.0"

                    [streams.orders.retention]
                    value = "5"
                    """;

            assertBothRefuseWith(toml, StreamDeclarationError.UnknownStreamKeys.class);
        }

        @Test
        void fractionalPartitions_isNotAnIntegerAtBoth() {
            assertBothRefuseWith("[streams.orders]\nversion = \"1.0.0\"\npartitions = 3.0\n", StreamDeclarationError.NotAnInteger.class);
        }

        @Test
        void partitionsBeyondTheIntRange_isNotAnIntegerAtBoth() {
            assertBothRefuseWith("[streams.orders]\nversion = \"1.0.0\"\npartitions = 2147483648\n", StreamDeclarationError.NotAnInteger.class);
        }

        private static void assertBothRefuseWith(String toml, Class<? extends Cause> expected) {
            StreamConfigParser.parseResources(toml, ReplicationFactors.BUILT_IN)
                              .onSuccess(resources -> fail("deploy must refuse, parsed " + resources))
                              .onFailure(cause -> assertThat(cause).as("deploy").isInstanceOf(expected));
            new StreamPublisherFactory().sectionBinder()
                                        .onEmpty(() -> fail("stream factories must bind their own section"))
                                        .onPresent(binder -> binder.bind(providerOf(toml), SECTION, BINDING_CONTEXT)
                                                                   .onSuccess(config -> fail("activation must refuse, bound " + config))
                                                                   .onFailure(cause -> assertThat(cause).as("activation").isInstanceOf(expected)));
        }
    }

    /// With no slice configuration provider, a section-binding factory is refused typed — never handed to the
    /// record binder, which would default the section silently.
    @Nested
    class NoProviderIsRefused {
        @Test
        void provide_withoutAComposite_refusesTyped_andNeverConsultsTheRecordBinder() {
            var manager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
            var recordBinderCalls = new java.util.concurrent.atomic.AtomicInteger();
            var provider = SpiResourceProvider.spiResourceProvider(List.of(new StreamPublisherFactory()),
                                                                   (_, _) -> {
                                                                       recordBinderCalls.incrementAndGet();
                                                                       return Result.success(StreamConfig.DEFAULT);
                                                                   });
            var context = ProvisioningContext.provisioningContext()
                                             .withExtension(StreamPartitionManager.class, manager)
                                             .withExtension(ReplicationContext.Source.class, ReplicationContext.Source.fixed(ReplicationContext.BUILT_IN))
                                             .withExtension(Serializer.class, identitySerializer());
            try {
                provider.provide(StreamPublisher.class, SECTION, context)
                        .await()
                        .onSuccess(_ -> fail("must refuse without a configuration provider"))
                        .onFailure(cause -> assertThat(cause.message()).contains("configuration provider"));

                assertThat(recordBinderCalls.get()).as("the record binder must not be consulted").isZero();
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
            var deployed = StreamConfigParser.parseResources(EVERY_KEY, ReplicationFactors.BUILT_IN)
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
                    max_event_size = "64KB"
                    """;

            new StreamPublisherFactory().sectionBinder()
                                        .onEmpty(() -> fail("stream factories must bind their own section"))
                                        .onPresent(binder -> binder.bind(providerOf(toml), SECTION, BINDING_CONTEXT)
                                                                   .onSuccess(config -> fail("unread key must be refused, bound " + config))
                                                                   .onFailure(cause -> assertThat(cause).isInstanceOf(StreamDeclarationError.UnknownStreamKeys.class))
                                                                   .onFailure(cause -> assertThat(cause.message()).contains("'max_event_size'")
                                                                                                                  .contains("did you mean 'max-event-size'")));
        }

        /// #1564: the removed replication keys are refused at activation too, naming their replacement.
        @Test
        void removedReplicationKey_isRefused_namingItsReplacement() {
            new StreamPublisherFactory().sectionBinder()
                                        .onEmpty(() -> fail("stream factories must bind their own section"))
                                        .onPresent(binder -> binder.bind(providerOf("""
                                                                                    [streams.orders]
                                                                                    min-sync-replicas = 2
                                                                                    """), SECTION, BINDING_CONTEXT)
                                                                   .onSuccess(config -> fail("removed key must be refused, bound " + config))
                                                                   .onFailure(cause -> assertThat(cause.message()).contains("did you mean 'confirmation_factor'")));
        }

        @Test
        void nonIntegerValue_isRefused_notDefaulted() {
            var toml = """
                    [streams.orders]
                    confirmation_factor = "two"
                    """;

            new StreamPublisherFactory().sectionBinder()
                                        .onEmpty(() -> fail("stream factories must bind their own section"))
                                        .onPresent(binder -> binder.bind(providerOf(toml), SECTION, BINDING_CONTEXT)
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

    /// #1564 at ACTIVATION (v1680 V1/V5): the node-side bind applies the same core-count check and raises the same
    /// warnings deploy validation does, whatever deploy saw — the committed cluster config may have changed since.
    @Nested
    class ActivationReplicationChecks {
        private static final ProvisioningContext THREE_CORES = ProvisioningContext.provisioningContext()
                                                                                  .withExtension(ReplicationContext.Source.class,
                                                                                                 ReplicationContext.Source.fixed(ReplicationContext.replicationContext(ReplicationFactors.BUILT_IN,
                                                                                                                                                                       3)));

        /// V1: a declared replication_factor above the DESIRED core count is refused at activation, typed.
        @Test
        void bind_factorAboveTheDesiredCoreCount_isRefused() {
            new StreamPublisherFactory().sectionBinder()
                                        .onEmpty(() -> fail("stream factories must bind their own section"))
                                        .onPresent(binder -> binder.bind(providerOf("""
                                                                                    [streams.orders]
                                                                                    replication_factor = 5
                                                                                    """), SECTION, THREE_CORES)
                                                                   .onSuccess(config -> fail("RF 5 on 3 cores must be refused, bound " + config))
                                                                   .onFailure(cause -> assertThat(cause).isEqualTo(new StreamDeclarationError.ReplicationRefused(ALIAS,
                                                                                                                                                                 new ReplicationFactorsError.ExceedsCoreCount(5,
                                                                                                                                                                                                              3)))));
        }

        /// N8: a node that supplies no replication context refuses the bind with a TYPED cause naming the alias,
        /// never resolving the section against a guessed default.
        @Test
        void bind_withoutAReplicationSource_isRefusedTyped() {
            new StreamPublisherFactory().sectionBinder()
                                        .onEmpty(() -> fail("stream factories must bind their own section"))
                                        .onPresent(binder -> binder.bind(providerOf("""
                                                                                    [streams.orders]
                                                                                    partitions = 2
                                                                                    """), SECTION, ProvisioningContext.provisioningContext())
                                                                   .onSuccess(config -> fail("no replication source must be refused, bound " + config))
                                                                   .onFailure(cause -> assertThat(cause).isEqualTo(new StreamDeclarationError.ReplicationContextUnavailable(ALIAS))));
        }

        /// V5: the declaration's warnings are LOGGED at activation — the LOUD RF-below-3 warning among them.
        @Test
        void bind_declaredFactorBelowThree_logsTheLoudWarning() {
            var warnings = new java.util.concurrent.CopyOnWriteArrayList<String>();
            var detach = LogCapture.warningsOf(StreamSectionBinding.class, warnings);

            try {
                bindWith(new StreamPublisherFactory(), """
                        [streams.orders]
                        replication_factor = 1
                        """);
            } finally {
                detach.run();
            }

            assertThat(warnings).anySatisfy(line -> assertThat(line).startsWith("LOUD: ")
                                                                    .contains(ReplicationWarning.FACTOR_BELOW_THREE.code())
                                                                    .contains("stream 'orders'"));
        }
    }

    /// The full activation seam: [SpiResourceProvider] binds a stream resource's config with the factory's
    /// own binder over the slice's configuration provider, and the stream is created with the declared
    /// `confirmation_factor` (pre-#1564 `min-sync-replicas`, which the record binder dropped to `0`).
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
                                             .withExtension(ReplicationContext.Source.class, ReplicationContext.Source.fixed(ReplicationContext.BUILT_IN))
                                             .withExtension(Serializer.class, identitySerializer())
                                             .withExtension(ConfigurationProvider.class, providerOf("""
                                                     [streams.orders]
                                                     partitions = 2
                                                     retention = "count"
                                                     retention-value = "1000"
                                                     max-event-size = "64KB"
                                                     replication_factor = 3
                                                     confirmation_factor = 2
                                                     """));
            try {
                provider.provide(StreamPublisher.class, SECTION, context)
                        .await()
                        .onFailure(cause -> fail("provisioning failed: " + cause.message()));

                assertThat(capturing.seen).hasSize(1);
                var bound = capturing.seen.getFirst();
                assertThat(bound.confirmationFactor()).isEqualTo(2);
                assertThat(bound.maxEventSizeBytes()).isEqualTo(64L * 1024);
                assertThat(bound.retention().maxCount()).isEqualTo(1000L);
                assertThat(manager.confirmationFactorFor(ALIAS)).isEqualTo(2);
            } finally {
                manager.close();
            }
        }
    }

    private static StreamConfig bindWith(ResourceFactory<?, StreamConfig> factory, String toml) {
        return bindWith(factory, toml, SECTION);
    }

    private static StreamConfig bindWith(ResourceFactory<?, StreamConfig> factory, String toml, String section) {
        return factory.sectionBinder()
                      .map(binder -> binder.bind(providerOf(toml), section, BINDING_CONTEXT))
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
