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

import java.util.HashMap;
import java.util.List;
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
    static Headers headers(Map<String, List<String>> raw) {
        record headers(Map<String, List<String>> normalized) implements Headers {
            @Override
            public Option<String> get(String name) {
                return Option.option(normalized.get(name.toLowerCase()))
                             .flatMap(values -> Option.from(values.stream().findFirst()));
            }

            @Override
            public List<String> getAll(String name) {
                return Option.option(normalized.get(name.toLowerCase())).or(List::of);
            }

            @Override
            public Map<String, List<String>> asMap() {
                return normalized;
            }
        }
        var normalized = new HashMap<String, List<String>>();

        raw.forEach((k, v) -> normalized.put(k.toLowerCase(), List.copyOf(v)));

        return new headers(Map.copyOf(normalized));
    }

    /// Create headers from a single-value map (convenience for simple cases).
    static Headers fromSingleValueMap(Map<String, String> raw) {
        var multi = new HashMap<String, List<String>>();

        raw.forEach((k, v) -> multi.put(k.toLowerCase(), List.of(v)));

        return headers(multi);
    }

    /// #2310: request trailer fields from a raw map, normalized as [#headers(Map)] does, with every name in
    /// [#FORBIDDEN_TRAILER_FIELDS] discarded.
    static Headers trailers(Map<String, List<String>> raw) {
        var permitted = new HashMap<String, List<String>>();

        raw.forEach((k, v) -> permitted.put(k.toLowerCase(), v));
        permitted.keySet().removeAll(FORBIDDEN_TRAILER_FIELDS);

        return headers(permitted);
    }

    /// Empty headers.
    static Headers empty() {
        return headers(Map.of());
    }
}
