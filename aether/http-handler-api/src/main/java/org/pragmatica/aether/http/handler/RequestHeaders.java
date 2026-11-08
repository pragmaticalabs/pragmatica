// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.handler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.pragmatica.http.Headers;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Codec;


/// The headers of the HTTP request that reached a slice method (#2879), declared as an injected parameter.
///
/// A slice method that takes a `RequestHeaders` parameter, alone or beside a request record, `Principal` or
/// `SecurityContext`, is handed the headers of the request that reached its route. Names are case-insensitive
/// (RFC 9110 §5.1) and a name may carry several values, kept in arrival order. The value is immutable.
///
/// **Edge only.** The headers are captured where the HTTP request arrives and handed to the generated route handler
/// directly. Nothing carries them across a slice-to-slice call: a method that declares the parameter and is invoked
/// by another slice receives whatever `RequestHeaders` its caller passes, exactly as it would for any other
/// argument.
@Codec
public record RequestHeaders(Map<String, List<String>> fields) {
    public RequestHeaders {
        fields = normalize(fields);
    }

    /// Headers from a raw name-to-values map. Names are folded to lower case with [Locale#ROOT] and names that differ
    /// only in case are one field, their values merged in iteration order.
    public static RequestHeaders requestHeaders(Map<String, List<String>> raw) {
        return new RequestHeaders(raw);
    }

    /// The headers of a transport request, as [Headers] presents them.
    public static RequestHeaders requestHeaders(Headers headers) {
        return new RequestHeaders(headers.asMap());
    }

    /// A request that carried no headers.
    public static RequestHeaders empty() {
        return new RequestHeaders(Map.of());
    }

    /// The first value of the named header, or none when the request did not carry it (case-insensitive).
    public Option<String> first(String name) {
        return Option.from(all(name).stream()
                                    .findFirst());
    }

    /// Every value of the named header in arrival order; empty when the request did not carry it (case-insensitive).
    public List<String> all(String name) {
        return Option.option(fields.get(name.toLowerCase(Locale.ROOT)))
                     .or(List::of);
    }

    private static Map<String, List<String>> normalize(Map<String, List<String>> raw) {
        var merged = new LinkedHashMap<String, List<String>>();

        raw.forEach((name, values) -> merged.computeIfAbsent(name.toLowerCase(Locale.ROOT),
                                                             _ -> new ArrayList<>())
                                            .addAll(values));
        merged.replaceAll((_, values) -> List.copyOf(values));
        return Map.copyOf(merged);
    }
}
