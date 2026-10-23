// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.adapter;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.routing.JsonCodecAdapter;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.serialization.json.Json;
import org.pragmatica.serialization.json.JsonOptions;
import org.pragmatica.serialization.json.JsonTypeCodec;

import static org.assertj.core.api.Assertions.assertThat;

/// #2378: a GeneratedJsonCodec built with options applies them on every generated path: a body decoded by a type's
/// codec, a collection body decoded by a combinator, and a response array encoded element by element.
class GeneratedJsonCodecOptionsTest {
    @Json
    record Note(String id, Option<String> text) {}

    private static final JsonOptions STRICT = JsonOptions.jsonOptions(JsonOptions.NullInclusion.EXCLUDE,
                                                                      true,
                                                                      false,
                                                                      1000,
                                                                      Integer.MAX_VALUE,
                                                                      Integer.MAX_VALUE,
                                                                      Integer.MAX_VALUE)
                                                         .unwrap();

    private static GeneratedJsonCodec codec(JsonOptions options) {
        var codecs = new HashMap<Class<?>, JsonTypeCodec<?>>(GeneratedJsonCodecOptionsTest_NoteJson.CODECS);

        return GeneratedJsonCodec.generatedJsonCodec(codecs,
                                                     java.util.Map.of(),
                                                     JsonCodecAdapter.forMapper(JsonMapper.valueObjectJsonMapper()),
                                                     options);
    }

    @Test
    void typeBody_refusesAnUnknownField_underTheOptions() {
        var body = bytes("{\"id\":\"1\",\"extra\":true}");

        assertThat(codec(JsonOptions.DEFAULTS).deserialize(body, new TypeToken<Note>() {}).isSuccess()).isTrue();
        assertThat(codec(STRICT).deserialize(body, new TypeToken<Note>() {}).isSuccess()).isFalse();
    }

    @Test
    void collectionBody_refusesAnUnknownField_underTheOptions() {
        var body = bytes("[{\"id\":\"1\",\"extra\":true}]");

        assertThat(codec(JsonOptions.DEFAULTS).deserialize(body, new TypeToken<List<Note>>() {}).isSuccess()).isTrue();
        assertThat(codec(STRICT).deserialize(body, new TypeToken<List<Note>>() {}).isSuccess()).isFalse();
    }

    // An explicit container codec (a slice holder's CONTAINERS entry) is chosen before the combinator, so it must carry
    // the options too.
    @Test
    void explicitContainerCodec_refusesAnUnknownField_underTheOptions() {
        var listOfNotes = new TypeToken<List<Note>>() {};
        var containers = java.util.Map.<java.lang.reflect.Type, JsonTypeCodec<?>> of(listOfNotes.token(),
                                                                                    JsonTypeCodec.listOf(GeneratedJsonCodecOptionsTest_NoteJson.CODEC));
        var body = bytes("[{\"id\":\"1\",\"extra\":true}]");

        assertThat(withContainers(containers, JsonOptions.DEFAULTS).decoderFor(listOfNotes.token())
                                                                    .unwrap()).isNotNull();
        assertThat(withContainers(containers, JsonOptions.DEFAULTS).deserialize(body, listOfNotes).isSuccess()).isTrue();
        assertThat(withContainers(containers, STRICT).deserialize(body, listOfNotes).isSuccess()).isFalse();
    }

    private static GeneratedJsonCodec withContainers(java.util.Map<java.lang.reflect.Type, JsonTypeCodec<?>> containers,
                                                     JsonOptions options) {
        return GeneratedJsonCodec.generatedJsonCodec(java.util.Map.of(),
                                                     containers,
                                                     JsonCodecAdapter.forMapper(JsonMapper.valueObjectJsonMapper()),
                                                     options);
    }

    @Test
    void responseValue_andResponseArray_leaveEmptyOptionsOut_underTheOptions() {
        var note = new Note("1", Option.none());

        assertThat(text(codec(JsonOptions.DEFAULTS).serialize(note).unwrap())).isEqualTo("{\"id\":\"1\",\"text\":null}");
        assertThat(text(codec(STRICT).serialize(note).unwrap())).isEqualTo("{\"id\":\"1\"}");
        assertThat(text(codec(STRICT).serialize(List.of(note, note)).unwrap())).isEqualTo("[{\"id\":\"1\"},{\"id\":\"1\"}]");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
