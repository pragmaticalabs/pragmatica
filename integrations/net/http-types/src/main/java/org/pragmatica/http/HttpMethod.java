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

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.option;


/// HTTP request methods.
public enum HttpMethod {
    GET,
    POST,
    PUT,
    DELETE,
    PATCH,
    HEAD,
    OPTIONS,
    TRACE,
    CONNECT;
    private static final Map<String, HttpMethod> FROM_NAME = Stream.of(HttpMethod.values()).collect(Collectors.toMap(HttpMethod::name,
                                                                                                                     value -> value));
    /// Parse HTTP method from string, absent for unknown methods. The same parser as [#httpMethod]: it accepts exactly
    /// what that one accepts.
    ///
    /// @param name HTTP method string (ASCII case-insensitive)
    /// @return Option containing the HttpMethod or empty for unknown methods
    public static Option<HttpMethod> fromString(String name) {
        return httpMethod(name).option();
    }
    /// Parse HTTP method from string. The match is case-insensitive over ASCII letters only: a letter outside ASCII that a
    /// locale-aware fold would turn into a method letter (`ſ` U+017F into `S`, `ı` U+0131 into `I`) does not make a method.
    ///
    /// @param method HTTP method string
    /// @return Result containing the HttpMethod or an error for unknown methods
    public static Result<HttpMethod> httpMethod(String method) {
        return option(FROM_NAME.get(asciiUpperCase(method))).toResult(new UnknownMethod(method));
    }
    private static String asciiUpperCase(String name) {
        return name.chars()
                   .map(HttpMethod::asciiUpper)
                   .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                   .toString();
    }
    private static int asciiUpper(int codePoint) {
        return codePoint >= 'a' && codePoint <= 'z'
               ? codePoint - ('a' - 'A')
               : codePoint;
    }
    @Deprecated(forRemoval = true)
    public static Result<HttpMethod> from(String method) {
        return httpMethod(method);
    }
    /// Error for unknown HTTP method.
    public record UnknownMethod(String method) implements Cause {
        @Override
        public String message() {
            return "Unknown HTTP method: " + method;
        }
    }
}
