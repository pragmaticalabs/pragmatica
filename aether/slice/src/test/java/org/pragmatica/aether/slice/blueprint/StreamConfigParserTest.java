// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.aether.slice.ReplicationFactorsError;
import org.pragmatica.aether.slice.ReplicationWarning;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.stream.StreamResource;
import org.pragmatica.aether.slice.resource.ResourceVersion;
import org.pragmatica.aether.slice.stream.StreamVersionSpec;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.aether.slice.blueprint.StreamConfigParser.parseResources;


class StreamConfigParserTest {

    @Nested
    class ResourcesParsing {

        @Test
        void emptyInputYieldsEmptyMap() {
            var result = parseResources("", ReplicationFactors.BUILT_IN).unwrap();

            assertThat(result).isEmpty();
        }

        @Test
        void blankInputYieldsEmptyMap() {
            var result = parseResources("   \n  ", ReplicationFactors.BUILT_IN).unwrap();

            assertThat(result).isEmpty();
        }

        @Test
        void ownedWithExactVersion() {
            var toml = """
                    [streams.orders]
                    version = "1.0.0"
                    partitions = 8
                    """;

            var result = parseResources(toml, ReplicationFactors.BUILT_IN).unwrap();

            assertThat(result).containsOnlyKeys("orders");
            var resource = result.get("orders");
            assertThat(resource).isInstanceOf(StreamResource.Owned.class);
            var owned = (StreamResource.Owned) resource;
            assertThat(owned.alias()).isEqualTo("orders");
            assertThat(owned.version()).isEqualTo(StreamVersionSpec.exact(ResourceVersion.resourceVersion(1, 0, 0).unwrap()));
            assertThat(owned.config().partitions()).isEqualTo(8);
        }

        @Test
        void autoOffsetResetDefaultsToEarliest() {
            // #576: the parsed default used to be "latest", but the runtime always starts a
            // never-committed consumer at offset 0 (earliest) per the #478 ruling — this default
            // must match what actually happens, since nothing reads the field on the hot path.
            var toml = """
                    [streams.orders]
                    version = "1.0.0"
                    """;

            var result = parseResources(toml, ReplicationFactors.BUILT_IN).unwrap();

            var owned = (StreamResource.Owned) result.get("orders");
            assertThat(owned.config().autoOffsetReset()).isEqualTo("earliest");
        }

        @Test
        void ownedWithLatestVersion() {
            var toml = """
                    [streams.inventory]
                    version = "latest"
                    """;

            var result = parseResources(toml, ReplicationFactors.BUILT_IN).unwrap();

            var owned = (StreamResource.Owned) result.get("inventory");
            assertThat(owned.version()).isSameAs(StreamVersionSpec.Latest.INSTANCE);
        }

        @Test
        void externalWithSource() {
            var toml = """
                    [streams.inventory_feed]
                    source = "io.acme.inventory:stock-updates:2.0.0"
                    """;

            var result = parseResources(toml, ReplicationFactors.BUILT_IN).unwrap();

            assertThat(result).containsOnlyKeys("inventory_feed");
            var resource = result.get("inventory_feed");
            assertThat(resource).isInstanceOf(StreamResource.External.class);
            var external = (StreamResource.External) resource;
            assertThat(external.alias()).isEqualTo("inventory_feed");
            assertThat(external.target()).isEqualTo(
                    ResourceAddress.resourceAddress("io.acme.inventory:stock-updates:2.0.0").unwrap());
        }

        /// The exact shape #1040 migrated `examples/notification-hub`'s consumers to, pinned because two
        /// things in it could silently be wrong and neither would fail loudly.
        ///
        /// FIRST, the namespace carries HYPHENS. A blueprint-derived namespace is `groupId.artifactId`
        /// (`BlueprintNamespace.deriveNamespace`), and this artifactId is `notification-hub-notification-service`
        /// — so the whole migration rests on `validateAppNamespace` admitting hyphens. If it did not, the
        /// address would fail to parse and the consumers would fall back to the bare alias, restoring the
        /// defect in the exact place the fix was applied.
        ///
        /// SECOND, config keys sit ALONGSIDE `source`. They are inert for an external reference (the
        /// producing blueprint owns the authoritative config) and are retained in that example only so a
        /// consumer that materializes the ring first shapes it like the producer's. The parser must treat
        /// the section as External regardless — silently reading it as Owned would namespace it into the
        /// CONSUMER's namespace and point it at a ring nobody writes to.
        @Test
        void externalWithHyphenatedBlueprintNamespaceAndInertConfigKeys() {
            var toml = """
                    [streams.notifications]
                    source = "org.pragmatica.aether.example.notification-hub-notification-service:notifications:1.0.0"
                    partitions = 4
                    retention = "time"
                    retention-value = "5m"
                    max-event-size = "64KB"
                    """;

            var result = parseResources(toml, ReplicationFactors.BUILT_IN).unwrap();

            assertThat(result).containsOnlyKeys("notifications");
            var resource = result.get("notifications");
            assertThat(resource).isInstanceOf(StreamResource.External.class);
            var external = (StreamResource.External) resource;
            assertThat(external.target().namespace().value())
                    .isEqualTo("org.pragmatica.aether.example.notification-hub-notification-service");
            assertThat(external.target().asString())
                    .isEqualTo("org.pragmatica.aether.example.notification-hub-notification-service:notifications:1.0.0");
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

            var result = parseResources(toml, ReplicationFactors.BUILT_IN).unwrap();

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

            var result = parseResources(toml, ReplicationFactors.BUILT_IN).unwrap();

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

            var result = parseResources(toml, ReplicationFactors.BUILT_IN);

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("must not set both"));
        }

        @Test
        void rejectsMalformedVersion() {
            var toml = """
                    [streams.orders]
                    version = "1.0"
                    """;

            var result = parseResources(toml, ReplicationFactors.BUILT_IN);

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void rejectsPartitionsOverCeiling() {
            var toml = """
                    [streams.orders]
                    version = "1.0.0"
                    partitions = 2000
                    """;

            var result = parseResources(toml, ReplicationFactors.BUILT_IN);

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("2000").contains("per-stream ceiling of 1024"));
        }

        @Test
        void acceptsPartitionsAtCeiling() {
            var toml = """
                    [streams.orders]
                    version = "1.0.0"
                    partitions = 1024
                    """;

            var result = parseResources(toml, ReplicationFactors.BUILT_IN);

            assertThat(result.isSuccess()).isTrue();
        }

        /// #1549: a key nothing in the stream parser reads is refused by a typed cause naming the key and the
        /// known key it resembles — never ignored, because the runtime now binds through this same parse.
        @Test
        void rejectsUnknownKey_namingTheKnownKeyItResembles() {
            var toml = """
                    [streams.orders]
                    version = "1.0.0"
                    max_event_size_bytes = 1024
                    min_sync_replicas = 2
                    """;

            parseResources(toml, ReplicationFactors.BUILT_IN).onSuccess(_ -> fail("unknown keys must be refused"))
                                .onFailure(cause -> assertThat(cause).isInstanceOf(StreamDeclarationError.UnknownStreamKeys.class))
                                .onFailure(cause -> assertThat(((StreamDeclarationError.UnknownStreamKeys) cause).keys())
                                                        .containsExactly("max_event_size_bytes", "min_sync_replicas"))
                                .onFailure(cause -> assertThat(cause.message()).contains("did you mean 'confirmation_factor'"));
        }

        @Test
        void rejectsUnknownKey_onAnExternalSection() {
            var toml = """
                    [streams.audit]
                    source = "io.acme.inventory:stock-updates:2.0.0"
                    partitons = 4
                    """;

            parseResources(toml, ReplicationFactors.BUILT_IN).onSuccess(_ -> fail("unknown keys must be refused on external sections too"))
                                .onFailure(cause -> assertThat(cause).isInstanceOf(StreamDeclarationError.UnknownStreamKeys.class));
        }

        @Test
        void rejectsNonIntegerValue_forAnIntegerKey() {
            var toml = """
                    [streams.orders]
                    version = "1.0.0"
                    replication_factor = "three"
                    """;

            parseResources(toml, ReplicationFactors.BUILT_IN).onSuccess(_ -> fail("a non-integer replication_factor must be refused, not defaulted"))
                                .onFailure(cause -> assertThat(cause).isEqualTo(new StreamDeclarationError.NotAnInteger("orders", "replication_factor", "three")));
        }

        /// #1549 (v1557): every value refusal below used to throw, default or wrap once these values reached the
        /// runtime; each is now a typed cause at deploy.
        @Test
        void rejectsZeroCount() {
            assertValueRefused("retention = \"count\"\nretention-value = \"0\"",
                               new StreamDeclarationError.ValueOutOfRange("orders", "retention-value", "0", 1));
        }

        @Test
        void rejectsZeroCompoundMaxCount() {
            assertValueRefused("retention = \"compound\"\nmax-count = \"0\"",
                               new StreamDeclarationError.ValueOutOfRange("orders", "max-count", "0", 1));
        }

        @Test
        void rejectsZeroMaxEventSize() {
            assertValueRefused("max-event-size = \"0\"",
                               new StreamDeclarationError.ValueOutOfRange("orders", "max-event-size", "0", 1));
        }

        @Test
        void rejectsZeroMaxEventSizeWithUnit() {
            assertValueRefused("max-event-size = \"0KB\"",
                               new StreamDeclarationError.ValueOutOfRange("orders", "max-event-size", "0KB", 1));
        }

        @Test
        void rejectsNegativeMaxEventSize() {
            assertValueRefused("max-event-size = \"-1\"",
                               new StreamDeclarationError.MalformedValue("orders", "max-event-size", "-1", StreamValues.SIZE_FORM));
        }

        @Test
        void rejectsNegativeCount() {
            assertValueRefused("retention = \"count\"\nretention-value = \"-5\"",
                               new StreamDeclarationError.MalformedValue("orders", "retention-value", "-5", StreamValues.COUNT_FORM));
        }

        @Test
        void rejectsNegativeSize() {
            assertValueRefused("retention = \"size\"\nretention-value = \"-1\"",
                               new StreamDeclarationError.MalformedValue("orders", "retention-value", "-1", StreamValues.SIZE_FORM));
        }

        @Test
        void rejectsFractionalSize() {
            assertValueRefused("max-event-size = \"1.5MB\"",
                               new StreamDeclarationError.MalformedValue("orders", "max-event-size", "1.5MB", StreamValues.SIZE_FORM));
        }

        @Test
        void rejectsUnknownDurationUnit() {
            assertValueRefused("retention = \"time\"\nretention-value = \"5 min\"",
                               new StreamDeclarationError.MalformedValue("orders", "retention-value", "5 min", StreamValues.DURATION_FORM));
        }

        @Test
        void rejectsCountBeyondTheLongRange() {
            assertValueRefused("retention = \"count\"\nretention-value = \"99999999999999999999\"",
                               new StreamDeclarationError.ValueOverflows("orders", "retention-value", "99999999999999999999"));
        }

        @Test
        void rejectsDurationThatOverflowsInMilliseconds() {
            assertValueRefused("retention = \"time\"\nretention-value = \"999999999999999d\"",
                               new StreamDeclarationError.ValueOverflows("orders", "retention-value", "999999999999999d"));
        }

        @Test
        void rejectsEventSizeThatOverflowsInBytes() {
            assertValueRefused("max-event-size = \"99999999999GB\"",
                               new StreamDeclarationError.ValueOverflows("orders", "max-event-size", "99999999999GB"));
        }

        @Test
        void rejectsZeroPartitions() {
            assertValueRefused("partitions = 0", new StreamDeclarationError.ValueOutOfRange("orders", "partitions", "0", 1));
        }

        @Test
        void rejectsNegativePartitions() {
            assertValueRefused("partitions = -1", new StreamDeclarationError.ValueOutOfRange("orders", "partitions", "-1", 1));
        }

        @Test
        void rejectsUnknownRetentionForm() {
            assertValueRefused("retention = \"tme\"",
                               new StreamDeclarationError.MalformedValue("orders", "retention", "tme", "one of count, time, size, compound"));
        }

        /// #1564: a declared factor below 1 is refused by a typed cause naming the stream; it is never clamped up.
        @Test
        void refusesFactorBelowOne() {
            List.of(-1, 0).forEach(factor -> assertReplicationRefused("replication_factor = " + factor,
                                                                      new ReplicationFactorsError.FactorBelowOne(factor)));
        }

        /// #1564 (owner ruling, know 267792392): a factor below 3 is allowed when the stream declares it, with the
        /// LOUD warning. Before #1564 it was refused (#1547).
        @Test
        void acceptsDeclaredFactorBelowThree_withTheLoudWarning() {
            List.of(1, 2).forEach(factor -> {
                var toml = owned("replication_factor = " + factor);

                parseResources(toml, ReplicationFactors.BUILT_IN).onFailure(cause -> fail(cause.message()));
                assertThat(StreamConfigParser.replicationWarnings(toml, ReplicationFactors.BUILT_IN).get("orders"))
                    .contains(ReplicationWarning.FACTOR_BELOW_THREE);
            });
        }

        /// #1564 R5 pin (streams): a factor below 3 that comes from a DEFAULT is refused — only a declaration may go
        /// below 3. Mutation "drop the explicitness check in ReplicationDeclaration#resolve" turns this red.
        @Test
        void refusesDefaultedFactorBelowThree() {
            parseResources(owned(""), new ReplicationFactors(2, 1)).onSuccess(_ -> fail("a defaulted factor below 3 must be refused"))
                                                                   .onFailure(cause -> assertThat(cause).isEqualTo(new StreamDeclarationError.ReplicationRefused("orders",
                                                                                                                                                                 new ReplicationFactorsError.ImplicitFactorBelowThree(2))));
        }

        @Test
        void acceptsDeclaredFactors() {
            parseResources(owned("replication_factor = 3\nconfirmation_factor = 2"), ReplicationFactors.BUILT_IN).onFailure(cause -> fail(cause.message()))
                                                                                                                .onSuccess(resources -> assertThat(ownedConfig(resources,
                                                                                                                                                               "orders").replication()).isEqualTo(new ReplicationFactors(3,
                                                                                                                                                                                                                         2)));
        }

        /// #1564: absent factors take the committed cluster defaults, which a declared value overrides.
        @Test
        void absentFactors_takeTheDefaults_andDeclaredValuesOverrideThem() {
            var clusterDefaults = new ReplicationFactors(5, 3);

            parseResources(owned(""), clusterDefaults).onFailure(cause -> fail(cause.message()))
                                                      .onSuccess(resources -> assertThat(ownedConfig(resources, "orders").replication()).isEqualTo(clusterDefaults));
            parseResources(owned("replication_factor = 4"), clusterDefaults).onFailure(cause -> fail(cause.message()))
                                                                            .onSuccess(resources -> assertThat(ownedConfig(resources,
                                                                                                                           "orders").replication()).isEqualTo(new ReplicationFactors(4,
                                                                                                                                                                                     3)));
            assertThat(StreamConfig.DEFAULT.replication()).isEqualTo(ReplicationFactors.BUILT_IN);
        }

        @Test
        void refusesConfirmationExceedingFactor() {
            assertReplicationRefused("replication_factor = 3\nconfirmation_factor = 4",
                                     new ReplicationFactorsError.ConfirmationOutOfRange(3, 4));
        }

        /// #1564 (finding c): a negative confirmation used to pass the parser and behave as 0.
        @Test
        void refusesNegativeConfirmation() {
            assertReplicationRefused("confirmation_factor = -1", new ReplicationFactorsError.ConfirmationOutOfRange(3, -1));
        }

        @Test
        void acceptsConfirmationEqualToFactor_withTheWarning() {
            var toml = owned("replication_factor = 3\nconfirmation_factor = 3");

            assertThat(parseResources(toml, ReplicationFactors.BUILT_IN).isSuccess()).isTrue();
            assertThat(StreamConfigParser.replicationWarnings(toml, ReplicationFactors.BUILT_IN).get("orders"))
                .containsExactly(ReplicationWarning.CONFIRMATION_EQUALS_FACTOR);
        }

        /// #1564: the pre-#1564 keys are gone without aliases (pre-GA); each is refused naming its replacement.
        @Test
        void refusesTheRemovedReplicationKeys_namingTheirReplacement() {
            assertThat(unknownKeyMessage("replicas = 3")).contains("did you mean 'replication_factor'");
            assertThat(unknownKeyMessage("min-sync-replicas = 2")).contains("did you mean 'confirmation_factor'");
        }

        @Test
        void rejectsMalformedSource() {
            var toml = """
                    [streams.bad_ref]
                    source = "not-a-valid-address"
                    """;

            var result = parseResources(toml, ReplicationFactors.BUILT_IN);

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void rejectsProducerWithLatestVersion() {
            var toml = """
                    [streams.orders]
                    role = "producer"
                    version = "latest"
                    """;

            var result = parseResources(toml, ReplicationFactors.BUILT_IN);

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

            var result = parseResources(toml, ReplicationFactors.BUILT_IN);

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

            var result = parseResources(toml, ReplicationFactors.BUILT_IN).unwrap();

            var owned = (StreamResource.Owned) result.get("orders");
            assertThat(owned.version()).isEqualTo(StreamVersionSpec.exact(ResourceVersion.resourceVersion(1, 0, 0).unwrap()));
        }

        @Test
        void omittedVersionWithConsumerRoleDefaultsToLatest() {
            var toml = """
                    [streams.inventory]
                    role = "consumer"
                    """;

            var result = parseResources(toml, ReplicationFactors.BUILT_IN).unwrap();

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

            var result = parseResources(toml, ReplicationFactors.BUILT_IN).unwrap();

            var owned = (StreamResource.Owned) result.get("notifications");
            assertThat(owned.version()).isEqualTo(StreamVersionSpec.exact(ResourceVersion.resourceVersion(1, 0, 0).unwrap()));
        }

        @Test
        void consumerRoleCaseInsensitive() {
            var toml = """
                    [streams.inventory]
                    role = "Consumer"
                    """;

            var result = parseResources(toml, ReplicationFactors.BUILT_IN).unwrap();

            var owned = (StreamResource.Owned) result.get("inventory");
            assertThat(owned.version()).isSameAs(StreamVersionSpec.Latest.INSTANCE);
        }
    }

    /// #1549: `checkpoint-interval` threw `NumberFormatException` out of deploy validation; it is refused typed now.
    @Nested
    class ConsumerValues {
        @Test
        void parseConsumers_refusesCheckpointIntervalThatIsNotADuration() {
            assertCheckpointRefused("5 min",
                                    new StreamDeclarationError.MalformedValue("orders",
                                                                              "consumers.billing.checkpoint-interval",
                                                                              "5 min",
                                                                              StreamValues.DURATION_FORM));
        }

        @Test
        void parseConsumers_refusesZeroCheckpointInterval() {
            assertCheckpointRefused("0s",
                                    new StreamDeclarationError.ValueOutOfRange("orders",
                                                                               "consumers.billing.checkpoint-interval",
                                                                               "0s",
                                                                               1));
        }

        @Test
        void parseConsumers_refusesOverflowingCheckpointInterval() {
            assertCheckpointRefused("999999999999999d",
                                    new StreamDeclarationError.ValueOverflows("orders",
                                                                              "consumers.billing.checkpoint-interval",
                                                                              "999999999999999d"));
        }

        @Test
        void parseConsumers_bindsDeclaredCheckpointInterval() {
            StreamConfigParser.parseConsumers(consumerToml("5s"), "orders")
                              .onFailure(cause -> fail(cause.message()))
                              .onSuccess(consumers -> assertThat(consumers.get("billing").checkpointInterval().millis()).isEqualTo(5_000L));
        }

        private static void assertCheckpointRefused(String raw, StreamDeclarationError expected) {
            StreamConfigParser.parseConsumers(consumerToml(raw), "orders")
                              .onSuccess(consumers -> fail("expected " + expected + ", parsed " + consumers))
                              .onFailure(cause -> assertThat(cause).isEqualTo(expected));
        }

        private static String consumerToml(String checkpointInterval) {
            return "[streams.orders]\nversion = \"1.0.0\"\n\n[streams.orders.consumers.billing]\ncheckpoint-interval = \""
                   + checkpointInterval + "\"\n";
        }
    }

    private static void assertValueRefused(String lines, StreamDeclarationError expected) {
        var toml = "[streams.orders]\nversion = \"1.0.0\"\n" + lines + "\n";

        parseResources(toml, ReplicationFactors.BUILT_IN).onSuccess(resources -> fail("expected " + expected + ", parsed " + resources))
                            .onFailure(cause -> assertThat(cause).isEqualTo(expected));
    }

    private static String owned(String lines) {
        return "[streams.orders]\nversion = \"1.0.0\"\n" + lines + "\n";
    }

    private static void assertReplicationRefused(String lines, ReplicationFactorsError expected) {
        parseResources(owned(lines), ReplicationFactors.BUILT_IN).onSuccess(_ -> fail(lines + " must be refused, not clamped"))
                                                                 .onFailure(cause -> assertThat(cause).isEqualTo(new StreamDeclarationError.ReplicationRefused("orders",
                                                                                                                                                               expected)));
    }

    private static String unknownKeyMessage(String line) {
        return parseResources(owned(line), ReplicationFactors.BUILT_IN).fold(cause -> cause.message(), _ -> "accepted");
    }

    private static StreamConfig ownedConfig(Map<String, StreamResource> resources, String alias) {
        return ((StreamResource.Owned) resources.get(alias)).config();
    }
}
