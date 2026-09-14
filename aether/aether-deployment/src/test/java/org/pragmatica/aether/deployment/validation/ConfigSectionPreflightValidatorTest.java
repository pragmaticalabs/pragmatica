// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.validation;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.validation.ConfigSectionPreflightValidator.SliceJar;
import org.pragmatica.aether.slice.topology.SliceTopology;
import org.pragmatica.aether.slice.topology.SliceTopology.ResourceDep;
import org.pragmatica.aether.slice.topology.SliceTopology.TopicPub;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.source.MapConfigSource;
import org.pragmatica.lang.Option;

import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;


class ConfigSectionPreflightValidatorTest {
    private static final Artifact ORDERS_ARTIFACT = Artifact.artifact("com.example:app:1.0.0").unwrap();
    private static final Artifact BILLING_ARTIFACT = Artifact.artifact("com.example:billing:1.0.0").unwrap();

    private static SliceTopology topologyWithResources(String sliceName, ResourceDep... resources) {
        return new SliceTopology(sliceName, "com.example:app:1.0.0", List.of(), List.of(), List.of(resources), List.of(), List.of());
    }

    /// A slice whose only "config section" is a publish-topic's (a [TopicPub], not a [ResourceDep]) —
    /// the shape `ManifestGenerator`/`TopologyParser` produce for `@Publisher`-flavored dependencies,
    /// which never enter `resources()` (#547's approved scope reduction: `ManifestGenerator` excludes
    /// publishers structurally, so there is nothing here for the pre-flight to plumb).
    private static SliceTopology topologyWithPublishTopic(String sliceName, String topicConfigSection) {
        var topicPub = new TopicPub(topicConfigSection, topicConfigSection, "com.example.OrderPlaced");

        return new SliceTopology(sliceName, "com.example:app:1.0.0", List.of(), List.of(), List.of(), List.of(topicPub), List.of());
    }

    /// A slice jar that ships no `META-INF/resources.toml`, so the loader's view is the node composite alone.
    private static SliceJar jarWithoutResourcesToml(SliceTopology... topologies) {
        return SliceJar.sliceJar(ORDERS_ARTIFACT, List.of(topologies), Option.none());
    }

    /// A slice jar shipping the given `META-INF/resources.toml` text (#1067).
    private static SliceJar jarShipping(Artifact artifact, String resourcesToml, SliceTopology... topologies) {
        return SliceJar.sliceJar(artifact, List.of(topologies), Option.some(resourcesToml));
    }

    /// Builds a [ConfigurationProvider] whose composite view has exactly the given sections defined
    /// (one throwaway key per section, since [org.pragmatica.config.ProviderBasedConfigService#hasSection]
    /// only checks key-prefix presence, not content).
    private static ConfigurationProvider providerWithSections(String... sections) {
        var values = new HashMap<String, String>();

        for (var section : sections) {
            values.put(section + ".present", "true");
        }

        var source = MapConfigSource.mapConfigSource("test", values).unwrap();

        return ConfigurationProvider.builder().withSource(source).build();
    }

    @Nested
    class HappyPath {
        @Test
        void validate_emptyResourceList_succeeds() {
            var topology = topologyWithResources("orders-api");
            var result = ConfigSectionPreflightValidator.validate(List.of(jarWithoutResourcesToml(topology)), Option.some(providerWithSections()));

            result.onFailure(cause -> fail("Expected success but got: " + cause.message()));
        }

        @Test
        void validate_allSectionsPresent_succeeds() {
            var topology = topologyWithResources("orders-api",
                                                   new ResourceDep("database", "database.orders"),
                                                   new ResourceDep("cache", "cache.sessions"));
            var provider = providerWithSections("database.orders", "cache.sessions");
            var result = ConfigSectionPreflightValidator.validate(List.of(jarWithoutResourcesToml(topology)), Option.some(provider));

            result.onFailure(cause -> fail("Expected success but got: " + cause.message()));
        }

        @Test
        void validate_noConfigurationProvider_failsOpen() {
            var topology = topologyWithResources("orders-api", new ResourceDep("database", "database.orders"));
            var result = ConfigSectionPreflightValidator.validate(List.of(jarWithoutResourcesToml(topology)), Option.none());

            result.onFailure(cause -> fail("Expected fail-open success but got: " + cause.message()));
        }

        /// TopicConfig-informational asymmetry (#547 condition b): a publish-topic's config section
        /// never reaches [SliceTopology.ResourceDep] in the first place — `publishes()` is a distinct
        /// list the validator never inspects — so a missing section there can never fail this
        /// deploy-time check, unlike a missing generic-resource section which hard-fails
        /// ([MissingSections#validate_missingSection_fails]). This is the same asymmetry
        /// `SpiResourceProvider.topicNameFallback` documents at the runtime layer (#396/#547 Gap-1):
        /// generic resources demand an operator-supplied section; a typed topic's name is already
        /// known to the runtime independent of `resources.toml`, so there is nothing to gate on.
        @Test
        void validate_missingPublishTopicSection_isInvisibleToTheCheck() {
            var topology = topologyWithPublishTopic("orders-api", "orders.placed");
            var result = ConfigSectionPreflightValidator.validate(List.of(jarWithoutResourcesToml(topology)), Option.some(providerWithSections()));

            result.onFailure(cause -> fail("Publish-topic sections are out of #547's scope — expected success but got: "
                                          + cause.message()));
        }
    }

    @Nested
    class MissingSections {
        @Test
        void validate_missingSection_fails() {
            var topology = topologyWithResources("orders-api", new ResourceDep("database", "database.orders"));
            var result = ConfigSectionPreflightValidator.validate(List.of(jarWithoutResourcesToml(topology)), Option.some(providerWithSections()));

            result.onSuccess(_ -> fail("Expected failure for missing [database.orders] section"))
                  .onFailure(cause -> assertThat(cause.message()).contains("orders-api")
                                                                  .contains("database.orders")
                                                                  .contains("database"));
        }

        @Test
        void validate_multipleMissingSections_aggregatesCompleteList() {
            var topology = topologyWithResources("orders-api",
                                                   new ResourceDep("database", "database.orders"),
                                                   new ResourceDep("cache", "cache.sessions"),
                                                   new ResourceDep("http", "http.payments"));
            var result = ConfigSectionPreflightValidator.validate(List.of(jarWithoutResourcesToml(topology)), Option.some(providerWithSections("cache.sessions")));

            result.onSuccess(_ -> fail("Expected failure for two missing sections"))
                  .onFailure(cause -> assertThat(cause.message()).contains("database.orders")
                                                                  .contains("http.payments")
                                                                  .doesNotContain("cache.sessions"));
        }

        @Test
        void validate_missingSectionAcrossMultipleSlices_namesTheOffendingSlice() {
            var withGap = topologyWithResources("payments-api", new ResourceDep("http", "http.gateway"));
            var clean = topologyWithResources("orders-api", new ResourceDep("database", "database.orders"));
            var result = ConfigSectionPreflightValidator.validate(List.of(jarWithoutResourcesToml(withGap, clean)), Option.some(providerWithSections("database.orders")));

            result.onSuccess(_ -> fail("Expected failure for payments-api's missing section"))
                  .onFailure(cause -> assertThat(cause.message()).contains("payments-api")
                                                                  .contains("http.gateway")
                                                                  .doesNotContain("orders-api"));
        }
    }

    /// #1067: the slice jar's own `META-INF/resources.toml` is a layer the loader consults beneath the node
    /// composite — per slice, and only when it parses.
    @Nested
    class SliceJarLayer {
        private static final String ORDERS_DATABASE_TOML = """
                [database.orders]
                url = "postgresql://localhost:5432/orders"
                """;
        // An unterminated array is a parse error by TomlParser's own contract, as in SliceStoreTest.
        private static final String MALFORMED_ORDERS_DATABASE_TOML = """
                [database.orders]
                url = [
                """;

        @Test
        void validate_sectionOnlyInSliceJar_succeeds() {
            var topology = topologyWithResources("orders-api", new ResourceDep("database", "database.orders"));
            var result = ConfigSectionPreflightValidator.validate(List.of(jarShipping(ORDERS_ARTIFACT, ORDERS_DATABASE_TOML, topology)),
                                                                  Option.some(providerWithSections()));

            result.onFailure(cause -> fail("The loader resolves [database.orders] from the slice jar — expected success but got: "
                                          + cause.message()));
        }

        @Test
        void validate_sectionAbsentFromEveryLayer_failsWithMissingConfigSection() {
            var topology = topologyWithResources("orders-api", new ResourceDep("cache", "cache.sessions"));
            var result = ConfigSectionPreflightValidator.validate(List.of(jarShipping(ORDERS_ARTIFACT, ORDERS_DATABASE_TOML, topology)),
                                                                  Option.some(providerWithSections("http.payments")));

            result.onSuccess(_ -> fail("[cache.sessions] is in neither the node composite nor the slice jar"))
                  .onFailure(cause -> assertThat(cause.stream().toList()).isNotEmpty()
                                                                         .allMatch(MissingConfigSection.class::isInstance))
                  .onFailure(cause -> assertThat(cause.message()).contains("cache.sessions"));
        }

        /// Each slice's composite closes over its own jar at load (`DependencyResolver.loadingContextFor`), so a
        /// section one slice ships never satisfies another slice that only declares it.
        @Test
        void validate_sectionOnlyInAnotherSlicesJar_namesTheSliceThatLacksIt() {
            var shipping = topologyWithResources("orders-api", new ResourceDep("database", "database.orders"));
            var borrowing = topologyWithResources("billing-api", new ResourceDep("database", "database.orders"));
            var result = ConfigSectionPreflightValidator.validate(List.of(jarShipping(ORDERS_ARTIFACT, ORDERS_DATABASE_TOML, shipping),
                                                                          SliceJar.sliceJar(BILLING_ARTIFACT, List.of(borrowing), Option.none())),
                                                                  Option.some(providerWithSections()));

            result.onSuccess(_ -> fail("billing-api's loader never reads orders-api's jar — expected failure"))
                  .onFailure(cause -> assertThat(cause.message()).contains("billing-api")
                                                                  .doesNotContain("orders-api"));
        }

        /// A malformed jar `resources.toml` yields no slice composite at load, and provisioning falls back to the
        /// node composite alone — so a section only in that file is not available at runtime.
        @Test
        void validate_sectionOnlyInMalformedSliceJarToml_fails() {
            var topology = topologyWithResources("orders-api", new ResourceDep("database", "database.orders"));
            var result = ConfigSectionPreflightValidator.validate(List.of(jarShipping(ORDERS_ARTIFACT, MALFORMED_ORDERS_DATABASE_TOML, topology)),
                                                                  Option.some(providerWithSections()));

            result.onSuccess(_ -> fail("A malformed slice resources.toml contributes no layer at runtime — expected failure"))
                  .onFailure(cause -> assertThat(cause.message()).contains("database.orders"));
        }

        /// The other half of the fallback: the node composite still answers when the jar's file does not parse.
        @Test
        void validate_malformedSliceJarToml_stillAcceptsNodeCompositeSection() {
            var topology = topologyWithResources("orders-api", new ResourceDep("database", "database.orders"));
            var result = ConfigSectionPreflightValidator.validate(List.of(jarShipping(ORDERS_ARTIFACT, MALFORMED_ORDERS_DATABASE_TOML, topology)),
                                                                  Option.some(providerWithSections("database.orders")));

            result.onFailure(cause -> fail("[database.orders] is in the node composite — expected success but got: " + cause.message()));
        }
    }
}
