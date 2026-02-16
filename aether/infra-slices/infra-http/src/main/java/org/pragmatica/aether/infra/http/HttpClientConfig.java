package org.pragmatica.aether.infra.http;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import java.net.http.HttpClient.Redirect;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;

/// Configuration for HTTP client infrastructure slice.
///
/// @param baseUrl         Optional base URL prepended to all requests
/// @param connectTimeout  Connection timeout
/// @param requestTimeout  Request timeout
/// @param followRedirects Redirect policy
public record HttpClientConfig(Option<String> baseUrl,
                               TimeSpan connectTimeout,
                               TimeSpan requestTimeout,
                               Redirect followRedirects) {
    private static final TimeSpan DEFAULT_CONNECT_TIMEOUT = TimeSpan.timeSpan(10)
                                                                   .seconds();
    private static final TimeSpan DEFAULT_REQUEST_TIMEOUT = TimeSpan.timeSpan(30)
                                                                   .seconds();
    private static final Redirect DEFAULT_REDIRECT = Redirect.NORMAL;

    public static Result<HttpClientConfig> httpClientConfig() {
        return success(new HttpClientConfig(none(), DEFAULT_CONNECT_TIMEOUT, DEFAULT_REQUEST_TIMEOUT, DEFAULT_REDIRECT));
    }

    public static Result<HttpClientConfig> httpClientConfig(String baseUrl) {
        return success(new HttpClientConfig(option(baseUrl),
                                            DEFAULT_CONNECT_TIMEOUT,
                                            DEFAULT_REQUEST_TIMEOUT,
                                            DEFAULT_REDIRECT));
    }

    public static Result<HttpClientConfig> httpClientConfig(String baseUrl,
                                                            TimeSpan connectTimeout,
                                                            TimeSpan requestTimeout) {
        return success(new HttpClientConfig(option(baseUrl), connectTimeout, requestTimeout, DEFAULT_REDIRECT));
    }

    public static Result<HttpClientConfig> httpClientConfig(Option<String> baseUrl,
                                                            TimeSpan connectTimeout,
                                                            TimeSpan requestTimeout,
                                                            Redirect followRedirects) {
        return success(new HttpClientConfig(baseUrl, connectTimeout, requestTimeout, followRedirects));
    }

    public HttpClientConfig withBaseUrl(String url) {
        return httpClientConfig(option(url), connectTimeout, requestTimeout, followRedirects).unwrap();
    }

    public HttpClientConfig withConnectTimeout(TimeSpan timeout) {
        return httpClientConfig(baseUrl, timeout, requestTimeout, followRedirects).unwrap();
    }

    public HttpClientConfig withRequestTimeout(TimeSpan timeout) {
        return httpClientConfig(baseUrl, connectTimeout, timeout, followRedirects).unwrap();
    }

    public HttpClientConfig withFollowRedirects(Redirect policy) {
        return httpClientConfig(baseUrl, connectTimeout, requestTimeout, policy).unwrap();
    }
}
