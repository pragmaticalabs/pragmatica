// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.adapter;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.pragmatica.http.JsonCodec;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.serialization.json.JsonTypeCodec;
import org.pragmatica.serialization.json.spi.JsonWriter;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


/// The HTTP JSON seam backed by a slice's generated codecs, with `fallback` (Jackson) for every type they
/// do not cover. Encode dispatches on the value's exact class (records and enums are final), and a
/// collection on its elements' classes; decode dispatches on the request's [TypeToken]: a type with a codec,
/// or a `List<T>` / `Set<T>` whose `T` has one (through [JsonTypeCodec#listOf] / [JsonTypeCodec#setOf]).
///
/// @param codecs   generated codecs by exact class, as a slice's `<Slice>RoutesJsonCodecs.CODECS`
/// @param fallback the codec for anything the generated ones do not cover
public record GeneratedJsonCodec(Map<Class<?>, JsonTypeCodec<?>> codecs, JsonCodec fallback) implements JsonCodec {
    // Exactly these raw types, the ones the combinators' ArrayList and LinkedHashSet satisfy; any other requested
    // collection type (Collection, SortedSet, a concrete class) stays on the fallback rather than being guessed.
    private static final Map<Type, Fn1<JsonTypeCodec<?>, JsonTypeCodec<?>>> CONTAINERS = Map.of(List.class,
                                                                                                JsonTypeCodec::listOf,
                                                                                                Set.class,
                                                                                                JsonTypeCodec::setOf);

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
        return decoderFor(token.token()).map(codec -> decode(codec, bytes, token))
                         .or(() -> fallback.deserialize(bytes, token));
    }

    private Option<JsonTypeCodec<?>> codecFor(Object value) {
        return option(codecs.get(value.getClass()));
    }

    private Option<JsonTypeCodec<?>> decoderFor(Type type) {
        return Option.<JsonTypeCodec<?>> option(codecs.get(type)).orElse(() -> containerCodec(type));
    }

    // A one-argument parameterized type: the element's codec under the raw type's combinator, when both exist. Read
    // off the reflective type directly, so a wildcard or nested argument simply finds no codec.
    private Option<JsonTypeCodec<?>> containerCodec(Type type) {
        return type instanceof ParameterizedType parameterized && parameterized.getActualTypeArguments().length == 1
               ? containerCodec(parameterized)
               : none();
    }

    private Option<JsonTypeCodec<?>> containerCodec(ParameterizedType type) {
        return Option.all(option(CONTAINERS.get(type.getRawType())),
                          option(codecs.get(type.getActualTypeArguments() [0])))
                     .map(Fn1::apply);
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

    // `List<CreateItem>` for a collection body, so the 400 names the element type as well as the container.
    private static String simpleName(TypeToken<?> token) {
        return simpleName(token.token());
    }

    private static String simpleName(Type type) {
        return type instanceof ParameterizedType parameterized
               ? simpleName(parameterized.getRawType()) + typeArguments(parameterized)
               : lastSegment(type.getTypeName());
    }

    private static String typeArguments(ParameterizedType type) {
        return Arrays.stream(type.getActualTypeArguments())
                     .map(GeneratedJsonCodec::simpleName)
                     .collect(Collectors.joining(", ", "<", ">"));
    }

    private static String lastSegment(String name) {
        return name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('$')) + 1);
    }
}
