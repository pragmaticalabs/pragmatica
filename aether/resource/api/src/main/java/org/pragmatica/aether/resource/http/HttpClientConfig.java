// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.http;

import java.net.http.HttpClient.Redirect;
import java.util.Map;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


public record HttpClientConfig(Option<String> baseUrl,
                               TimeSpan connectTimeout,
                               TimeSpan requestTimeout,
                               Redirect followRedirects,
                               Option<JsonConfig> json,
                               Map<String, String> defaultHeaders,
                               Option<HttpBackend> backend) {
    private static final TimeSpan DEFAULT_CONNECT_TIMEOUT = TimeSpan.timeSpan(10).seconds();
    private static final TimeSpan DEFAULT_REQUEST_TIMEOUT = TimeSpan.timeSpan(30).seconds();
    private static final Redirect DEFAULT_REDIRECT = Redirect.NORMAL;

    public static Result<HttpClientConfig> httpClientConfig() {
        return success(new HttpClientConfig(none(),
                                            DEFAULT_CONNECT_TIMEOUT,
                                            DEFAULT_REQUEST_TIMEOUT,
                                            DEFAULT_REDIRECT,
                                            none(),
                                            Map.of(),
                                            none()));
    }

    public static Result<HttpClientConfig> httpClientConfig(String baseUrl) {
        return success(new HttpClientConfig(option(baseUrl),
                                            DEFAULT_CONNECT_TIMEOUT,
                                            DEFAULT_REQUEST_TIMEOUT,
                                            DEFAULT_REDIRECT,
                                            none(),
                                            Map.of(),
                                            none()));
    }

    public static Result<HttpClientConfig> httpClientConfig(String baseUrl,
                                                            TimeSpan connectTimeout,
                                                            TimeSpan requestTimeout) {
        return success(new HttpClientConfig(option(baseUrl),
                                            connectTimeout,
                                            requestTimeout,
                                            DEFAULT_REDIRECT,
                                            none(),
                                            Map.of(),
                                            none()));
    }

    public static Result<HttpClientConfig> httpClientConfig(Option<String> baseUrl,
                                                            TimeSpan connectTimeout,
                                                            TimeSpan requestTimeout,
                                                            Redirect followRedirects) {
        return httpClientConfig(baseUrl, connectTimeout, requestTimeout, followRedirects, none(), Map.of());
    }

    public static Result<HttpClientConfig> httpClientConfig(Option<String> baseUrl,
                                                            TimeSpan connectTimeout,
                                                            TimeSpan requestTimeout,
                                                            Redirect followRedirects,
                                                            Option<JsonConfig> json,
                                                            Map<String, String> defaultHeaders) {
        return httpClientConfig(baseUrl, connectTimeout, requestTimeout, followRedirects, json, defaultHeaders, none());
    }

    public static Result<HttpClientConfig> httpClientConfig(Option<String> baseUrl,
                                                            TimeSpan connectTimeout,
                                                            TimeSpan requestTimeout,
                                                            Redirect followRedirects,
                                                            Option<JsonConfig> json,
                                                            Map<String, String> defaultHeaders,
                                                            Option<HttpBackend> backend) {
        return success(new HttpClientConfig(baseUrl,
                                            connectTimeout,
                                            requestTimeout,
                                            followRedirects,
                                            json,
                                            defaultHeaders,
                                            backend));
    }

    /// Selects the [org.pragmatica.http.HttpOperations] backend used by the provisioned client.
    /// Absent ([Option#none]) resolves to [#JDK] — the JDK backend is the default when unset.
    public enum HttpBackend {
        JDK,
        NETTY
    }
}
