/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.http;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.pragmatica.lang.Option;


/// HTTP headers with case-insensitive lookup (per HTTP specification).
public interface Headers {
    /// #2310: field names that are never delivered as request trailers (RFC 9110 §6.5.1). Their meaning has to be
    /// settled before the content is read (framing, routing, authentication, request modifiers and content format),
    /// so a value arriving after the body cannot be acted on; [#trailers(Map)] discards them rather than offer a
    /// handler a credential, a content type or a framing field the request was never processed under. Trailers are
    /// kept apart from the header section and are never merged into it (RFC 9110 §6.5.2).
    Set<String> FORBIDDEN_TRAILER_FIELDS = Set.of("content-length",
                                                  "transfer-encoding",
                                                  "trailer",
                                                  "te",
                                                  "connection",
                                                  "keep-alive",
                                                  "proxy-connection",
                                                  "upgrade",
                                                  "host",
                                                  "authorization",
                                                  "proxy-authorization",
                                                  "cookie",
                                                  "expect",
                                                  "max-forwards",
                                                  "cache-control",
                                                  "pragma",
                                                  "range",
                                                  "if-match",
                                                  "if-none-match",
                                                  "if-modified-since",
                                                  "if-unmodified-since",
                                                  "if-range",
                                                  "content-type",
                                                  "content-encoding",
                                                  "content-language",
                                                  "content-location",
                                                  "content-range");

    /// Get the first value for a header (case-insensitive lookup).
    Option<String> get(String name);
    /// Get all values for a header (case-insensitive lookup).
    List<String> getAll(String name);
    /// Get all headers as a map (keys are lowercase).
    Map<String, List<String>> asMap();

    /// Create headers from a raw map (will be normalized to lowercase keys).
    ///
    /// #2310: names are case-folded with [Locale#ROOT], never the JVM default (under `tr-TR` the default turns `I`
    /// into a dotless `ı`). Names that differ only in case are one field: their values are merged, in the raw map's
    /// iteration order, and none is lost.
    static Headers headers(Map<String, List<String>> raw) {
        record headers(Map<String, List<String>> normalized) implements Headers {
            @Override
            public Option<String> get(String name) {
                return Option.option(normalized.get(name.toLowerCase(Locale.ROOT))).flatMap(values -> Option.from(values.stream()
                                                                                                                        .findFirst()));
            }

            @Override
            public List<String> getAll(String name) {
                return Option.option(normalized.get(name.toLowerCase(Locale.ROOT))).or(List::of);
            }

            @Override
            public Map<String, List<String>> asMap() {
                return normalized;
            }
        }
        var merged = new LinkedHashMap<String, List<String>>();

        raw.forEach((k, v) -> merged.computeIfAbsent(k.toLowerCase(Locale.ROOT),
                                                     _ -> new ArrayList<>())
                                    .addAll(v));
        merged.replaceAll((_, v) -> List.copyOf(v));

        return new headers(Map.copyOf(merged));
    }

    /// Create headers from a single-value map (convenience for simple cases).
    static Headers fromSingleValueMap(Map<String, String> raw) {
        var multi = new HashMap<String, List<String>>();

        raw.forEach((k, v) -> multi.put(k, List.of(v)));

        return headers(multi);
    }

    /// #2310: request trailer fields from a raw map, normalized as [#headers(Map)] does (names folded with
    /// [Locale#ROOT], case variants merged), with every name in [#FORBIDDEN_TRAILER_FIELDS] discarded.
    static Headers trailers(Map<String, List<String>> raw) {
        var permitted = new HashMap<>(headers(raw).asMap());

        permitted.keySet().removeAll(FORBIDDEN_TRAILER_FIELDS);

        return headers(permitted);
    }

    /// Empty headers.
    static Headers empty() {
        return headers(Map.of());
    }
}
