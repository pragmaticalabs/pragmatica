// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.json;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.resource.ResourceProvisioningError;
import org.pragmatica.aether.slice.JsonCodecSelection;
import org.pragmatica.aether.slice.JsonCodecSettings;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.SliceLoadingFailure;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.source.MapConfigSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.SpiResourceProvider.spiResourceProvider;

/// #2378, the second intentional exception to #547: with no slice composite to read it from, the implicit `[json]`
/// section binds as absent (defaults), while every other section binder keeps its refusal; with a composite, a present
/// `[json]` is strict.
class SpiResourceProviderJsonAbsenceTest {
    private static final TimeSpan TIMEOUT = TimeSpan.timeSpan(5).seconds();

    record Other(String section) {}

    record OtherConfig(String section) {}

    static final class JsonFactory implements ResourceFactory<JsonCodecSettings, JsonSectionConfig> {
        @Override
        public Class<JsonCodecSettings> resourceType() {
            return JsonCodecSettings.class;
        }

        @Override
        public Class<JsonSectionConfig> configType() {
            return JsonSectionConfig.class;
        }

        @Override
        public Promise<JsonCodecSettings> provision(JsonSectionConfig config) {
            return Promise.success(JsonCodecSettings.fixed(config.selected()));
        }

        @Override
        public Option<SectionBinder<JsonSectionConfig>> sectionBinder() {
            return Option.some((provider, section, _) -> JsonSectionBinder.bind(provider, section));
        }
    }

    static final class OtherFactory implements ResourceFactory<Other, OtherConfig> {
        @Override
        public Class<Other> resourceType() {
            return Other.class;
        }

        @Override
        public Class<OtherConfig> configType() {
            return OtherConfig.class;
        }

        @Override
        public Promise<Other> provision(OtherConfig config) {
            return Promise.success(new Other(config.section()));
        }

        @Override
        public Option<SectionBinder<OtherConfig>> sectionBinder() {
            return Option.some((_, section, _) -> Result.success(new OtherConfig(section)));
        }
    }

    private static org.pragmatica.aether.resource.SpiResourceProvider provider() {
        return spiResourceProvider(List.of(new JsonFactory(), new OtherFactory()),
                                   (_, _) -> Causes.cause("no config service in this test").result());
    }

    @Test
    void noComposite_jsonSettingsAreTheDefaults() {
        var settings = provider().provide(JsonCodecSettings.class, "json").await(TIMEOUT).unwrap();
        var withContext = provider().provide(JsonCodecSettings.class, "json", ProvisioningContext.provisioningContext())
                                    .await(TIMEOUT)
                                    .unwrap();

        assertThat(settings.current()).isEqualTo(JsonCodecSelection.DEFAULT);
        assertThat(withContext.current()).isEqualTo(JsonCodecSelection.DEFAULT);
    }

    @Test
    void noComposite_anyOtherSectionBinderIsStillRefused() {
        var result = provider().provide(Other.class, "other").await(TIMEOUT);

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> {
            assertThat(cause).isInstanceOf(SliceLoadingFailure.Fatal.ConfigurationFailed.class);
            assertThat(cause.source().unwrap()).isInstanceOf(ResourceProvisioningError.SectionBinderNeedsProvider.class);
        });
    }

    @Test
    void withComposite_aPresentJsonSectionIsStrict() {
        var composite = ConfigurationProvider.builder()
                                             .withSource(MapConfigSource.mapConfigSource("slice", Map.of("json.default", "nope")).unwrap())
                                             .build();
        var context = ProvisioningContext.provisioningContext()
                                         .withExtension(ConfigurationProvider.class, composite);

        var result = provider().provide(JsonCodecSettings.class, "json", context).await(TIMEOUT);

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(SliceLoadingFailure.Fatal.ConfigurationFailed.class));
    }
}
