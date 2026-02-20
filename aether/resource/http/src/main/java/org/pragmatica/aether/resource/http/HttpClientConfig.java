package org.pragmatica.aether.resource.http;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import java.net.http.HttpClient.Redirect;
import java.util.Map;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;

/// Configuration for HTTP client resource.
///
/// @param baseUrl         Optional base URL prepended to all requests
/// @param connectTimeout  Connection timeout
/// @param requestTimeout  Request timeout
/// @param followRedirects Redirect policy
/// @param json            Optional JSON serialization configuration
/// @param defaultHeaders  Default headers added to every request
public record HttpClientConfig(Option<String> baseUrl,
                               TimeSpan connectTimeout,
                               TimeSpan requestTimeout,
                               Redirect followRedirects,
                               Option<JsonConfig> json,
                               Map<String, String> defaultHeaders) {
    private static final TimeSpan DEFAULT_CONNECT_TIMEOUT = TimeSpan.timeSpan(10)
                                                                   .seconds();
    private static final TimeSpan DEFAULT_REQUEST_TIMEOUT = TimeSpan.timeSpan(30)
                                                                   .seconds();
    private static final Redirect DEFAULT_REDIRECT = Redirect.NORMAL;

    public static Result<HttpClientConfig> httpClientConfig() {
        return success(new HttpClientConfig(none(), DEFAULT_CONNECT_TIMEOUT, DEFAULT_REQUEST_TIMEOUT, DEFAULT_REDIRECT, none(), Map.of()));
    }

    public static Result<HttpClientConfig> httpClientConfig(String baseUrl) {
        return success(new HttpClientConfig(option(baseUrl),
                                            DEFAULT_CONNECT_TIMEOUT,
                                            DEFAULT_REQUEST_TIMEOUT,
                                            DEFAULT_REDIRECT,
                                            none(),
                                            Map.of()));
    }

    public static Result<HttpClientConfig> httpClientConfig(String baseUrl,
                                                            TimeSpan connectTimeout,
                                                            TimeSpan requestTimeout) {
        return success(new HttpClientConfig(option(baseUrl), connectTimeout, requestTimeout, DEFAULT_REDIRECT, none(), Map.of()));
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
        return success(new HttpClientConfig(baseUrl, connectTimeout, requestTimeout, followRedirects, json, defaultHeaders));
    }

    public HttpClientConfig withBaseUrl(String url) {
        return httpClientConfig(option(url), connectTimeout, requestTimeout, followRedirects, json, defaultHeaders).unwrap();
    }

    public HttpClientConfig withConnectTimeout(TimeSpan timeout) {
        return httpClientConfig(baseUrl, timeout, requestTimeout, followRedirects, json, defaultHeaders).unwrap();
    }

    public HttpClientConfig withRequestTimeout(TimeSpan timeout) {
        return httpClientConfig(baseUrl, connectTimeout, timeout, followRedirects, json, defaultHeaders).unwrap();
    }

    public HttpClientConfig withFollowRedirects(Redirect policy) {
        return httpClientConfig(baseUrl, connectTimeout, requestTimeout, policy, json, defaultHeaders).unwrap();
    }

    public HttpClientConfig withJson(JsonConfig jsonConfig) {
        return httpClientConfig(baseUrl, connectTimeout, requestTimeout, followRedirects, option(jsonConfig), defaultHeaders).unwrap();
    }

    public HttpClientConfig withDefaultHeaders(Map<String, String> headers) {
        return httpClientConfig(baseUrl, connectTimeout, requestTimeout, followRedirects, json, headers).unwrap();
    }
}
