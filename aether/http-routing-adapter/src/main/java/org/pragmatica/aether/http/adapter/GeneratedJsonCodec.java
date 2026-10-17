// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.adapter;

import java.util.Collection;
import java.util.Map;

import org.pragmatica.http.JsonCodec;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.serialization.json.JsonTypeCodec;
import org.pragmatica.serialization.json.JsonWriter;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


/// The HTTP JSON seam backed by a slice's generated codecs, with `fallback` (Jackson) for every type they
/// do not cover. Encode dispatches on the value's exact class (records and enums are final), and a
/// collection on its elements' classes; decode dispatches on the request's [TypeToken].
///
/// @param codecs   generated codecs by exact class, as a slice's `<Slice>RoutesJsonCodecs.CODECS`
/// @param fallback the codec for anything the generated ones do not cover
public record GeneratedJsonCodec(Map<Class<?>, JsonTypeCodec<?>> codecs, JsonCodec fallback) implements JsonCodec {
    public static GeneratedJsonCodec generatedJsonCodec(Map<Class<?>, JsonTypeCodec<?>> codecs, JsonCodec fallback) {
        return new GeneratedJsonCodec(Map.copyOf(codecs), fallback);
    }

    @Override
    public Result<byte[]> serialize(Object value) {
        return codecFor(value).map(codec -> success(encode(codec, value)))
                       .or(() -> serializeCollection(value));
    }

    @Override
    public <T> Result<T> deserialize(byte[] bytes, TypeToken<T> token) {
        return option(codecs.get(token.token())).map(codec -> decode(codec, bytes, token))
                     .or(() -> fallback.deserialize(bytes, token));
    }

    private Option<JsonTypeCodec<?>> codecFor(Object value) {
        return option(codecs.get(value.getClass()));
    }

    private Result<byte[]> serializeCollection(Object value) {
        return value instanceof Collection<?> collection && collection.stream()
                                                                      .allMatch(element -> codecs.containsKey(element.getClass()))
               ? success(encodeArray(collection))
               : fallback.serialize(value);
    }

    private byte[] encodeArray(Collection<?> collection) {
        var out = JsonWriter.jsonWriter(256);

        out.writeByte('[');
        var first = true;

        for (var element : collection) {
            if (!first) {
                out.writeByte(',');
            }

            first = false;
            writeElement(out, element);
        }

        out.writeByte(']');

        return out.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private void writeElement(JsonWriter out, Object element) {
        ((JsonTypeCodec<Object>) codecs.get(element.getClass())).writer().write(out, element);
    }

    @SuppressWarnings("unchecked")
    private static byte[] encode(JsonTypeCodec<?> codec, Object value) {
        return ((JsonTypeCodec<Object>) codec).encode(value);
    }

    // A refused body names its target type: the 400 a client gets says which request did not parse.
    @SuppressWarnings("unchecked")
    private static <T> Result<T> decode(JsonTypeCodec<?> codec, byte[] bytes, TypeToken<T> token) {
        return ((JsonTypeCodec<T>) codec).decode(bytes)
                                         .mapError(cause -> JsonBodyRejected.jsonBodyRejected(simpleName(token),
                                                                                              cause));
    }

    private static String simpleName(TypeToken<?> token) {
        var name = token.token().getTypeName();

        return name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('$')) + 1);
    }
}
