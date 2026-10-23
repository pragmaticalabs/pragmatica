// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.json;

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.resource.SpiResourceProvider;
import org.pragmatica.aether.slice.JsonCodecSettings;
import org.pragmatica.aether.slice.JsonImplementation;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.DynamicConfigurationProvider;
import org.pragmatica.config.LayeredConfigProvider;
import org.pragmatica.config.source.MapConfigSource;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.testlogging.LogCapture;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;

/// #2378: the live JSON codec settings over the slice composite a node builds (the KV overlay layered over the slice's
/// own `resources.toml`), and the invalid-live-config handling of know 14b6b16b8: the previous selection is kept, one
/// CRITICAL rejection is raised on the transition, none on re-reads, one INFO recovery when a valid value lands.
class LiveJsonCodecSettingsTest {
    private static final TimeSpan TIMEOUT = TimeSpan.timeSpan(5).seconds();
    private static final String SLICE = "org.example:orders:1.0.0";

    private final List<OperatorWarning> events = new CopyOnWriteArrayList<>();
    private DynamicConfigurationProvider kv;
    private ConfigurationProvider composite;

    @BeforeEach
    void setUp() {
        var sliceToml = ConfigurationProvider.builder()
                                             .withSource(MapConfigSource.mapConfigSource("resources.toml",
                                                                                         Map.of("json.default", "a",
                                                                                                "json.codecs.a.implementation", "generated",
                                                                                                "json.codecs.b.implementation", "jackson",
                                                                                                "json.codecs.b.null_inclusion", "exclude"))
                                                                         .unwrap())
                                             .build();

        kv = DynamicConfigurationProvider.dynamicConfigurationProvider(ConfigurationProvider.builder().build());
        composite = LayeredConfigProvider.layered(List.of(kv, sliceToml));
    }

    private JsonCodecSettings provisioned() {
        var context = ProvisioningContext.provisioningContext()
                                         .withExtension(ConfigurationProvider.class, composite)
                                         .withExtension(String.class, SLICE)
                                         .withExtension(OperatorWarningSink.class, OperatorWarningSink.handingOffTo(events::add));

        return SpiResourceProvider.spiResourceProvider()
                                  .provide(JsonCodecSettings.class, "json", context)
                                  .await(TIMEOUT)
                                  .unwrap();
    }

    @Test
    void factory_isDiscoveredThroughTheServiceLoader() {
        assertThat(ServiceLoader.load(ResourceFactory.class)
                                .stream()
                                .map(ServiceLoader.Provider::get)).anyMatch(JsonCodecSettingsFactory.class::isInstance);
    }

    @Test
    void provisioning_bindsTheSlicesSection() {
        assertThat(provisioned().current().name()).isEqualTo("a");
    }

    @Test
    void committedChange_isAdoptedOnRefresh() {
        var settings = provisioned();

        kv.put("json.default", "b");
        settings.refresh();

        assertThat(settings.current().name()).isEqualTo("b");
        assertThat(settings.current().implementation()).isEqualTo(JsonImplementation.JACKSON);
        assertThat(events).isEmpty();
    }

    @Test
    void unchangedSection_keepsTheSameSelectionInstance() {
        var settings = provisioned();
        var before = settings.current();

        settings.refresh();

        assertThat(settings.current()).isSameAs(before);
    }

    @Test
    void invalidChange_keepsThePreviousSelection_raisesOnceOnTheTransition_andRecoversOnce() {
        var settings = provisioned();

        kv.put("json.default", "nope");
        settings.refresh();
        assertThat(settings.current().name()).isEqualTo("a");

        settings.refresh();
        kv.put("json.codecs.a.implementation", "gson");
        settings.refresh();
        assertThat(settings.current().name()).isEqualTo("a");

        kv.put("json.codecs.a.implementation", "generated");
        kv.put("json.default", "b");
        settings.refresh();
        assertThat(settings.current().name()).isEqualTo("b");

        awaitEvents(2);
        assertThat(events).extracting(OperatorWarning::code)
                          .containsExactly(OperatorWarningCode.JSON_CONFIG_REJECTED, OperatorWarningCode.JSON_CONFIG_APPLIED);
        assertThat(events).extracting(OperatorWarning::subject).containsOnly(SLICE + "/json");
        assertThat(events.getFirst().message()).contains("json.default").contains("nope").contains("keeping codec 'a'");
        assertThat(events.getLast().message()).contains("codec 'b'");
    }

    @Test
    void writesPassingThroughAnInvalidState_convergeOnTheLastValidOne() {
        var settings = provisioned();

        kv.put("json.default", "v3");
        settings.refresh();
        kv.put("json.codecs.v3.implementation", "jackson");
        settings.refresh();

        assertThat(settings.current().name()).isEqualTo("v3");
        awaitEvents(2);
        assertThat(events).extracting(OperatorWarning::code)
                          .containsExactly(OperatorWarningCode.JSON_CONFIG_REJECTED, OperatorWarningCode.JSON_CONFIG_APPLIED);
    }

    @Test
    void handleWithoutAComposite_isFixed() {
        var factory = new JsonCodecSettingsFactory();
        var config = JsonSectionBinder.bind(composite, "json").unwrap();
        var contextFree = factory.provision(config).await(TIMEOUT).unwrap();
        var noComposite = factory.provision(config, ProvisioningContext.provisioningContext()).await(TIMEOUT).unwrap();

        kv.put("json.default", "b");
        contextFree.refresh();
        noComposite.refresh();

        assertThat(contextFree.current().name()).isEqualTo("a");
        assertThat(noComposite.current().name()).isEqualTo("a");
    }

    /// A recovery ends the refusal, so a later rejection is a new transition and is raised again.
    @Test
    void rejectRecoverRejectAgain_raisesTheSecondRejection() {
        var settings = provisioned();

        kv.put("json.default", "nope");
        settings.refresh();
        kv.put("json.default", "b");
        settings.refresh();
        kv.put("json.default", "nope2");
        settings.refresh();

        awaitEvents(3);
        assertThat(events).extracting(OperatorWarning::code)
                          .containsExactly(OperatorWarningCode.JSON_CONFIG_REJECTED,
                                           OperatorWarningCode.JSON_CONFIG_APPLIED,
                                           OperatorWarningCode.JSON_CONFIG_REJECTED);
    }

    /// The recovery is raised once; valid re-reads after it raise nothing.
    @Test
    void validRefreshesAfterRecovery_raiseNothingMore() {
        var settings = provisioned();

        kv.put("json.default", "nope");
        settings.refresh();
        kv.put("json.default", "b");
        settings.refresh();
        settings.refresh();
        settings.refresh();

        awaitEvents(2);
        assertThat(events).extracting(OperatorWarning::code)
                          .containsExactly(OperatorWarningCode.JSON_CONFIG_REJECTED, OperatorWarningCode.JSON_CONFIG_APPLIED);
    }

    /// While already refused, a different refusal is logged at ERROR (not raised again); the same one re-read is not.
    @Test
    void changedRefusalWhileRejected_isLoggedAtErrorOnce_andTheSameRefusalIsNot() {
        var settings = provisioned();

        try (var capture = LogCapture.capture(LiveJsonCodecSettings.class, Level.ERROR, true)) {
            kv.put("json.default", "nope");
            settings.refresh();
            settings.refresh();
            kv.put("json.default", "nope2");
            settings.refresh();
            settings.refresh();

            var stillRejected = capture.messages(Level.ERROR)
                                       .stream()
                                       .filter(message -> message.contains("still rejected"))
                                       .toList();

            assertThat(stillRejected).hasSize(1);
            assertThat(stillRejected.getFirst()).contains("nope2").contains(SLICE + "/json");
        }

        awaitEvents(1);
    }

    // The sink hands off on its own thread; wait for the expected count, then make sure nothing more trails in.
    private void awaitEvents(int expected) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);

        while (events.size() < expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        var settle = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);

        while (System.nanoTime() < settle) {
            Thread.onSpinWait();
        }

        assertThat(events).hasSize(expected);
    }
}
