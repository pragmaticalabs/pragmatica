// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.testkit.fake;

import java.net.http.HttpHeaders;
import java.util.Map;

import org.pragmatica.http.HttpResult;


/// Builders for scripted [HttpResult] response bodies used with [FakeHttpClient].
///
/// `HttpResult` itself has no status-based factory, so the kit supplies these thin constructors
/// (the spec sketch's `HttpResult.ok(...)` is provided here instead).
public sealed interface HttpResults {
    /// A 200 OK response with the given string body.
    static HttpResult<String> ok(String body) {
        return status(200, body);
    }

    /// A response with the given status code and string body.
    static HttpResult<String> status(int statusCode, String body) {
        return new HttpResult<>(statusCode,
                                HttpHeaders.of(Map.of(), HttpResults::acceptAll),
                                body);
    }

    private static boolean acceptAll(String name, String value) {
        return true;
    }

    record unused() implements HttpResults {}
}
