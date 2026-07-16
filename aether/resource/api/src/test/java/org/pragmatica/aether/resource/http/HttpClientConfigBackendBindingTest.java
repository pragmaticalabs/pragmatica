// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.resource.http;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.source.MapConfigSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.config.ProviderBasedConfigService.providerBasedConfigService;

/// Proves the backend selector type ([HttpClientConfig.HttpBackend]) binds from a config source
/// through the existing reflective binder (ProviderBasedConfigService): `backend = "netty"`
/// parses case-insensitively to NETTY, and an absent key resolves to none() — which
/// JdkHttpClient maps to the JDK default.
///
/// The selector is bound in isolation via a probe record rather than the whole HttpClientConfig
/// because the binder cannot resolve HttpClientConfig's `org.pragmatica.lang.io.TimeSpan` fields
/// — its `primitiveParser` guard tests against `org.pragmatica.lang.parse.TimeSpan`, a different
/// class — so the full record is not TOML-bindable today. That pre-existing binder limitation is
/// orthogonal to backend selection.
public class HttpClientConfigBackendBindingTest {

    public record BackendProbe(String name, Option<HttpClientConfig.HttpBackend> backend) {}

    private static Result<BackendProbe> bindProbe(Map<String, String> values) {
        var source = MapConfigSource.mapConfigSource("test", values).unwrap();
        var provider = ConfigurationProvider.builder().withSource(source).build();

        return providerBasedConfigService(provider).config("probe", BackendProbe.class);
    }

    @Test
    void config_parsesNettyBackend_fromConfigSource() {
        var probe = bindProbe(Map.of("probe.name", "http",
                                     "probe.backend", "netty"))
            .onFailure(cause -> Assertions.fail(cause.message()))
            .unwrap();

        assertThat(probe.backend().isPresent()).isTrue();
        probe.backend().onPresent(backend -> assertThat(backend).isEqualTo(HttpClientConfig.HttpBackend.NETTY));
    }

    @Test
    void config_defaultsBackendToNone_whenKeyAbsent() {
        var probe = bindProbe(Map.of("probe.name", "http"))
            .onFailure(cause -> Assertions.fail(cause.message()))
            .unwrap();

        assertThat(probe.backend().isEmpty()).isTrue();
    }
}
