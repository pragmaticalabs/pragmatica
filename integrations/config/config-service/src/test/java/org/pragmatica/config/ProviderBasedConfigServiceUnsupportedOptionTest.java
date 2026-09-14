// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.config;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.config.source.MapConfigSource;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.config.ProviderBasedConfigService.providerBasedConfigService;

/// #761 — the guarantee: "cannot bind this type" and "nothing was configured" must not be
/// indistinguishable. Before this pin `extractOptionValue` had two silent arms for an `Option<X>`
/// it cannot bind: (a) X a nested generic (`Option<List<String>>`) — the component was handed the
/// raw `Option<String>` unchecked, a live type error; (b) X a class that is neither primitive,
/// enum nor record — `handleOptionalRecord` answered `none()`, indistinguishable from an absent
/// key. Both are now a `ConfigError.UnsupportedType` naming the key and the declared type, whether
/// or not the key is present, and it survives the binder's derived-name and `DEFAULT` fallbacks. The enumeration that licensed this (every `Option<X>` reachable from
/// every record the binder is handed) found NO instance of either case, so nothing that binds
/// today changes; `OptionBindingSupportGateTest` in `aether/dead-surface-gate` keeps it so.
class ProviderBasedConfigServiceUnsupportedOptionTest {
    record NestedGenericOption(String name, Option<List<String>> hosts) {}

    record UnsupportedClassOption(String name, Option<URI> endpoint) {}

    record SupportedOption(String name, Option<String> description) {}

    /// The fallback the refusal must NOT be swallowed by: a record carrying a `DEFAULT` instance.
    record DefaultedUnsupportedOption(String name, Option<URI> endpoint) {
        public static final DefaultedUnsupportedOption DEFAULT = new DefaultedUnsupportedOption("d", Option.none());
    }

    @Test
    void optionOfNestedGeneric_isRefusedAsTypeMismatch_notHandedAnOptionString() {
        var service = serviceWith(Map.of("svc.name", "x", "svc.hosts", "a,b"));

        var result = service.config("svc", NestedGenericOption.class);

        assertThat(result.isFailure()).as("#761 case (a): Option<List<String>> must not bind to an Option<String>")
                                      .isTrue();
        result.onFailure(cause -> {
            assertThat(cause).isInstanceOf(ConfigError.UnsupportedType.class);
            assertThat(cause.message()).contains("svc.hosts")
                                       .contains("List");
        });
    }

    @Test
    void optionOfUnsupportedClass_isRefused_evenWhenTheKeyIsAbsent() {
        var service = serviceWith(Map.of("svc.name", "x"));

        var result = service.config("svc", UnsupportedClassOption.class);

        assertThat(result.isFailure()).as("#761 case (b): an unbindable inner type is a declaration error, not an absent key")
                                      .isTrue();
        result.onFailure(cause -> {
            assertThat(cause).isInstanceOf(ConfigError.UnsupportedType.class);
            assertThat(cause.message()).contains("svc.endpoint")
                                       .contains("URI");
        });
    }

    @Test
    void optionOfUnsupportedClass_isRefused_whenTheKeyIsPresent() {
        var service = serviceWith(Map.of("svc.name", "x", "svc.endpoint", "http://example"));

        assertThat(service.config("svc", UnsupportedClassOption.class).isFailure()).isTrue();
    }

    @Test
    void optionOfUnsupportedClass_isNotSatisfiedByTheRecordsDefault() {
        var result = serviceWith(Map.of("svc.name", "x")).config("svc", DefaultedUnsupportedOption.class);

        assertThat(result.isFailure()).as("#761: a DEFAULT instance would turn 'cannot be configured' into a silent default")
                                      .isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(ConfigError.UnsupportedType.class));
    }

    /// Controls: a supported inner type keeps its meaning — absent is `none()`, present is `some`.
    @Test
    void optionOfSupportedType_absentIsNone_presentIsSome() {
        var absent = serviceWith(Map.of("svc.name", "x")).config("svc", SupportedOption.class);
        var present = serviceWith(Map.of("svc.name", "x", "svc.description", "d")).config("svc", SupportedOption.class);

        assertThat(absent.unwrap().description()).isEqualTo(Option.none());
        assertThat(present.unwrap().description()).isEqualTo(Option.some("d"));
    }

    private static ProviderBasedConfigService serviceWith(Map<String, String> values) {
        var source = MapConfigSource.mapConfigSource("test-source", values).unwrap();
        var provider = ConfigurationProvider.configurationProvider(source);

        return providerBasedConfigService(provider);
    }
}
