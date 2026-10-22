// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.json;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.JsonCodecSelection;
import org.pragmatica.aether.slice.JsonImplementation;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.source.MapConfigSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.json.JsonOptions;

import static org.assertj.core.api.Assertions.assertThat;

/// #2378: the `[json]` binder. Absence is the implicit default; a present section is strict and reports every
/// fault of one read, each naming its key.
class JsonSectionBinderTest {
    private static Result<JsonSectionConfig> bind(Map<String, String> keys) {
        var provider = ConfigurationProvider.builder()
                                            .withSource(MapConfigSource.mapConfigSource("test", keys).unwrap())
                                            .build();

        return JsonSectionBinder.bind(provider, "json");
    }

    private static List<Cause> faults(Result<JsonSectionConfig> result) {
        assertThat(result.isFailure()).as("expected a refusal, got " + result).isTrue();

        return result.fold(JsonSectionBinderTest::leaves, _ -> List.of());
    }

    private static List<Cause> leaves(Cause cause) {
        return cause instanceof Causes.CompositeCause composite
               ? composite.stream().flatMap(inner -> leaves(inner).stream()).toList()
               : List.of(cause);
    }

    @Test
    void noJsonKey_isTheImplicitDefault() {
        var config = bind(Map.of("orders.topic_name", "x")).unwrap();

        assertThat(config.selected()).isEqualTo(JsonCodecSelection.DEFAULT);
        assertThat(config.codecs()).isEmpty();
        assertThat(config.section()).isEqualTo("json");
    }

    @Test
    void oneCodecWithoutDefault_isSelected_withEveryOptionParsed() {
        var config = bind(Map.of("json.codecs.strict.implementation", "jackson",
                                 "json.codecs.strict.null_inclusion", "exclude",
                                 "json.codecs.strict.fail_on_unknown", "true",
                                 "json.codecs.strict.fail_on_duplicate", "TRUE",
                                 "json.codecs.strict.max_depth", "64",
                                 "json.codecs.strict.max_string_length", "65536",
                                 "json.codecs.strict.max_number_length", "100",
                                 "json.codecs.strict.max_document_size", "1048576")).unwrap();
        var expected = JsonOptions.jsonOptions(JsonOptions.NullInclusion.EXCLUDE, true, true, 64, 65536, 100, 1048576)
                                  .unwrap();

        assertThat(config.selected()).isEqualTo(JsonCodecSelection.jsonCodecSelection("strict",
                                                                                      JsonImplementation.JACKSON,
                                                                                      expected));
    }

    @Test
    void unsetOptions_takeTheDefaults() {
        var config = bind(Map.of("json.codecs.plain.implementation", "generated")).unwrap();

        assertThat(config.selected()).isEqualTo(JsonCodecSelection.jsonCodecSelection("plain",
                                                                                      JsonImplementation.GENERATED,
                                                                                      JsonOptions.DEFAULTS));
    }

    @Test
    void defaultChoosesAmongSeveral_andEveryCodecIsKept() {
        var config = bind(Map.of("json.default", "b",
                                 "json.codecs.a.implementation", "generated",
                                 "json.codecs.b.implementation", "jackson")).unwrap();

        assertThat(config.selected().name()).isEqualTo("b");
        assertThat(config.codecs()).containsOnlyKeys("a", "b");
    }

    @Test
    void severalCodecsWithoutDefault_isMissingDefault() {
        assertThat(faults(bind(Map.of("json.codecs.a.implementation", "generated",
                                      "json.codecs.b.implementation", "jackson"))))
            .containsExactly(new JsonSettingsError.MissingDefault("json.default", Set.of("a", "b")));
    }

    @Test
    void defaultNamingNoCodec_isUnknownDefaultCodec() {
        assertThat(faults(bind(Map.of("json.default", "nope"))))
            .containsExactly(new JsonSettingsError.UnknownDefaultCodec("json.default", "nope", Set.of()));
        assertThat(faults(bind(Map.of("json.default", "nope", "json.codecs.a.implementation", "generated"))))
            .containsExactly(new JsonSettingsError.UnknownDefaultCodec("json.default", "nope", Set.of("a")));
    }

    @Test
    void unknownImplementation_andMissingImplementation_areRefused() {
        assertThat(faults(bind(Map.of("json.codecs.a.implementation", "gson"))))
            .containsExactly(new JsonSettingsError.UnknownImplementation("json.codecs.a.implementation", "gson"));
        assertThat(faults(bind(Map.of("json.codecs.a.fail_on_unknown", "true"))))
            .containsExactly(new JsonSettingsError.MissingImplementation("json.codecs.a.implementation"));
    }

    @Test
    void naming_isBuildTime() {
        assertThat(faults(bind(Map.of("json.naming", "snake_case"))))
            .containsExactly(new JsonSettingsError.NamingIsBuildTime("json.naming", "snake_case"));
    }

    @Test
    void unknownKeys_areRefused_includingTopLevelOptions() {
        assertThat(faults(bind(Map.of("json.fail_on_unknown", "true"))))
            .containsExactly(new JsonSettingsError.UnknownKey("json.fail_on_unknown"));
        assertThat(faults(bind(Map.of("json.codecs.a.implementation", "generated", "json.codecs.a.colour", "red"))))
            .containsExactly(new JsonSettingsError.UnknownKey("json.codecs.a.colour"));
        assertThat(faults(bind(Map.of("json.codecs.a.b.implementation", "generated"))))
            .containsExactly(new JsonSettingsError.UnknownKey("json.codecs.a.b.implementation"));
        assertThat(faults(bind(Map.of("json.codecs.a.implementation", "generated",
                                      "json.codecs.a.implementation.extra", "x"))))
            .containsExactly(new JsonSettingsError.UnknownKey("json.codecs.a.implementation.extra"));
    }

    @Test
    void malformedAndOutOfRangeValues_areInvalidOptions_namingTheirKeys() {
        var found = faults(bind(Map.of("json.codecs.a.implementation", "generated",
                                       "json.codecs.a.fail_on_unknown", "yes",
                                       "json.codecs.a.null_inclusion", "never",
                                       "json.codecs.a.max_depth", "deep",
                                       "json.codecs.a.max_string_length", "10")));

        assertThat(found).hasSize(4)
                         .allSatisfy(fault -> assertThat(fault).isInstanceOf(JsonSettingsError.InvalidOption.class));
        assertThat(found.stream().map(fault -> ((JsonSettingsError.InvalidOption) fault).key()))
            .containsExactlyInAnyOrder("json.codecs.a.fail_on_unknown",
                                       "json.codecs.a.null_inclusion",
                                       "json.codecs.a.max_depth",
                                       "json.codecs.a.max_string_length");
    }

    @Test
    void everyFaultOfOneRead_isReported() {
        var found = faults(bind(Map.of("json.naming", "snake_case",
                                       "json.default", "nope",
                                       "json.codecs.a.implementation", "gson",
                                       "json.extra", "1")));

        assertThat(found).containsExactlyInAnyOrder(new JsonSettingsError.NamingIsBuildTime("json.naming", "snake_case"),
                                                    new JsonSettingsError.UnknownDefaultCodec("json.default", "nope", Set.of("a")),
                                                    new JsonSettingsError.UnknownImplementation("json.codecs.a.implementation", "gson"),
                                                    new JsonSettingsError.UnknownKey("json.extra"));
    }

    @Test
    void messages_nameTheKeyAndTheValue() {
        assertThat(new JsonSettingsError.UnknownImplementation("json.codecs.a.implementation", "gson").message())
            .contains("json.codecs.a.implementation").contains("gson");
        assertThat(new JsonSettingsError.NamingIsBuildTime("json.naming", "snake_case").message())
            .contains("routes.toml");
    }
}
