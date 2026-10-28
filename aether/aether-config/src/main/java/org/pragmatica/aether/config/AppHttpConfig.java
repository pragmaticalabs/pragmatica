// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Result.success;


public record AppHttpConfig(boolean enabled,
                            int port,
                            Map<String, ApiKeyEntry> apiKeys,
                            int maxRequestSize,
                            SecurityMode securityMode,
                            Option<JwtConfig> jwtConfig,
                            HttpProtocol httpProtocol,
                            ApiVersioningDetection apiVersioningDetection,
                            String apiVersionHeaderName,
                            Option<AppTls> tls,
                            TimeSpan readTimeout,
                            int minBodyRate,
                            Option<SecurityMode> managementSecurityMode,
                            UploadConfig upload) {
    /// Operator-supplied TLS identity for the USER-facing app-HTTP listener.
    ///
    /// The app port must not reuse the cluster identity. That certificate is issued by the
    /// cluster's own auto-generated CA and its subject is the NODE ID (`CN=primary-core-0`), so an
    /// ordinary HTTP client gets both an untrusted issuer and a name that is not the service it
    /// dialled. Node identity and service identity are different things; only the operator can
    /// supply the latter (#967).
    ///
    /// Absent — the default — the listener falls back to the cluster identity with client
    /// authentication stripped. That is reachable by clients which trust the cluster CA
    /// (service-to-service), and is NOT suitable for public traffic.
    public record AppTls(String certPath, String keyPath) {}

    /// The shape before `read_timeout`, `min_body_rate` and `[management] security_mode` existed: the listener takes
    /// the default request-body bounds, and the management plane inherits [#securityMode].
    public AppHttpConfig(boolean enabled,
                         int port,
                         Map<String, ApiKeyEntry> apiKeys,
                         int maxRequestSize,
                         SecurityMode securityMode,
                         Option<JwtConfig> jwtConfig,
                         HttpProtocol httpProtocol,
                         ApiVersioningDetection apiVersioningDetection,
                         String apiVersionHeaderName,
                         Option<AppTls> tls) {
        this(enabled,
             port,
             apiKeys,
             maxRequestSize,
             securityMode,
             jwtConfig,
             httpProtocol,
             apiVersioningDetection,
             apiVersionHeaderName,
             tls,
             DEFAULT_READ_TIMEOUT,
             DEFAULT_MIN_BODY_RATE,
             Option.empty());
    }

    /// The shape before `[app-http.upload]` existed: upload routes take the default bounds.
    public AppHttpConfig(boolean enabled,
                         int port,
                         Map<String, ApiKeyEntry> apiKeys,
                         int maxRequestSize,
                         SecurityMode securityMode,
                         Option<JwtConfig> jwtConfig,
                         HttpProtocol httpProtocol,
                         ApiVersioningDetection apiVersioningDetection,
                         String apiVersionHeaderName,
                         Option<AppTls> tls,
                         TimeSpan readTimeout,
                         int minBodyRate,
                         Option<SecurityMode> managementSecurityMode) {
        this(enabled,
             port,
             apiKeys,
             maxRequestSize,
             securityMode,
             jwtConfig,
             httpProtocol,
             apiVersioningDetection,
             apiVersionHeaderName,
             tls,
             readTimeout,
             minBodyRate,
             managementSecurityMode,
             UploadConfig.DEFAULT);
    }

    public static final int DEFAULT_APP_HTTP_PORT = 8070;
    public static final int DEFAULT_MAX_REQUEST_SIZE = 10 * 1024 * 1024;
    public static final String DEFAULT_API_VERSION_HEADER = "API-Version";
    /// `[app-http] read_timeout`: how long the listener waits on a client's request bytes before giving up on the
    /// connection (HTTP/1.1: no byte while no response is pending; HTTP/3: no body frame). 60 s mirrors
    /// nginx's `client_body_timeout` default and the http-server default; it is a guess, not a measurement. A
    /// zero or negative value disables the bound.
    public static final TimeSpan DEFAULT_READ_TIMEOUT = TimeSpan.timeSpan(60).seconds();
    /// `[app-http] min_body_rate`: the minimum rate, in bytes per second, a request body must arrive at once its 10 s
    /// grace has passed, averaged over 10 s windows (http-server `BodyRateLimit`), on HTTP/1.1 and HTTP/3 alike. 1 KiB/s
    /// is a guess in the spirit of Apache's mod_reqtimeout `MinRate=500`. A zero or negative value disables the bound.
    public static final int DEFAULT_MIN_BODY_RATE = 1024;

    public AppHttpConfig {
        apiKeys = Map.copyOf(apiKeys);
        maxRequestSize = normalizeMaxRequestSize(maxRequestSize);
        apiVersionHeaderName = normalizeHeaderName(apiVersionHeaderName);
    }

    public static Result<AppHttpConfig> appHttpConfig(boolean enabled,
                                                      int port,
                                                      Map<String, ApiKeyEntry> apiKeys,
                                                      int maxRequestSize,
                                                      SecurityMode securityMode,
                                                      Option<JwtConfig> jwtConfig,
                                                      HttpProtocol httpProtocol,
                                                      ApiVersioningDetection apiVersioningDetection,
                                                      String apiVersionHeaderName) {
        return success(new AppHttpConfig(enabled,
                                         port,
                                         apiKeys,
                                         maxRequestSize,
                                         securityMode,
                                         jwtConfig,
                                         httpProtocol,
                                         apiVersioningDetection,
                                         apiVersionHeaderName,
                                         Option.empty(),
                                         DEFAULT_READ_TIMEOUT,
                                         DEFAULT_MIN_BODY_RATE,
                                         Option.empty()));
    }

    /// Attach an operator-supplied TLS identity to this listener. See [AppTls].
    public AppHttpConfig withTls(AppTls appTls) {
        return new AppHttpConfig(enabled,
                                 port,
                                 apiKeys,
                                 maxRequestSize,
                                 securityMode,
                                 jwtConfig,
                                 httpProtocol,
                                 apiVersioningDetection,
                                 apiVersionHeaderName,
                                 Option.some(appTls),
                                 readTimeout,
                                 minBodyRate,
                                 managementSecurityMode,
                                 upload);
    }

    /// Replace the read timeout. See [#DEFAULT_READ_TIMEOUT].
    public AppHttpConfig withReadTimeout(TimeSpan readTimeout) {
        return new AppHttpConfig(enabled,
                                 port,
                                 apiKeys,
                                 maxRequestSize,
                                 securityMode,
                                 jwtConfig,
                                 httpProtocol,
                                 apiVersioningDetection,
                                 apiVersionHeaderName,
                                 tls,
                                 readTimeout,
                                 minBodyRate,
                                 managementSecurityMode,
                                 upload);
    }

    /// Replace the minimum body rate. See [#DEFAULT_MIN_BODY_RATE].
    public AppHttpConfig withMinBodyRate(int minBodyRate) {
        return new AppHttpConfig(enabled,
                                 port,
                                 apiKeys,
                                 maxRequestSize,
                                 securityMode,
                                 jwtConfig,
                                 httpProtocol,
                                 apiVersioningDetection,
                                 apiVersionHeaderName,
                                 tls,
                                 readTimeout,
                                 minBodyRate,
                                 managementSecurityMode,
                                 upload);
    }

    /// Replace the upload-route bounds (`[app-http.upload]`). See [UploadConfig].
    public AppHttpConfig withUpload(UploadConfig upload) {
        return new AppHttpConfig(enabled,
                                 port,
                                 apiKeys,
                                 maxRequestSize,
                                 securityMode,
                                 jwtConfig,
                                 httpProtocol,
                                 apiVersioningDetection,
                                 apiVersionHeaderName,
                                 tls,
                                 readTimeout,
                                 minBodyRate,
                                 managementSecurityMode,
                                 upload);
    }

    /// Give the Management API its own security mode (#962). `Option.none()` restores inheritance of [#securityMode].
    public AppHttpConfig withManagementSecurityMode(Option<SecurityMode> mode) {
        return new AppHttpConfig(enabled,
                                 port,
                                 apiKeys,
                                 maxRequestSize,
                                 securityMode,
                                 jwtConfig,
                                 httpProtocol,
                                 apiVersioningDetection,
                                 apiVersionHeaderName,
                                 tls,
                                 readTimeout,
                                 minBodyRate,
                                 mode,
                                 upload);
    }

    /// Whether the Management API authenticates (#962): its own `[management] security_mode` when set, else
    /// [#securityEnabled]. The management plane has no JWT mode, so only `NONE` disables it.
    public boolean managementSecurityEnabled() {
        return managementSecurityMode.or(securityMode) != SecurityMode.NONE;
    }

    public static Result<AppHttpConfig> appHttpConfig(boolean enabled,
                                                      int port,
                                                      Map<String, ApiKeyEntry> apiKeys,
                                                      int maxRequestSize,
                                                      SecurityMode securityMode,
                                                      Option<JwtConfig> jwtConfig,
                                                      HttpProtocol httpProtocol) {
        return appHttpConfig(enabled,
                             port,
                             apiKeys,
                             maxRequestSize,
                             securityMode,
                             jwtConfig,
                             httpProtocol,
                             ApiVersioningDetection.PATH,
                             DEFAULT_API_VERSION_HEADER);
    }

    public static AppHttpConfig appHttpConfig() {
        return appHttpConfig(false,
                             DEFAULT_APP_HTTP_PORT,
                             Map.of(),
                             DEFAULT_MAX_REQUEST_SIZE,
                             SecurityMode.API_KEY,
                             Option.empty(),
                             HttpProtocol.H1).unwrap();
    }

    public static AppHttpConfig appHttpConfig(boolean enabled) {
        return appHttpConfig(enabled,
                             DEFAULT_APP_HTTP_PORT,
                             Map.of(),
                             DEFAULT_MAX_REQUEST_SIZE,
                             SecurityMode.API_KEY,
                             Option.empty(),
                             HttpProtocol.H1).unwrap();
    }

    public static AppHttpConfig appHttpConfig(int port) {
        return appHttpConfig(true,
                             port,
                             Map.of(),
                             DEFAULT_MAX_REQUEST_SIZE,
                             SecurityMode.API_KEY,
                             Option.empty(),
                             HttpProtocol.H1).unwrap();
    }

    /// Enabled app-HTTP config on the given port with an explicit #198 detection mode (§7) and
    /// version header name. Used by in-JVM harnesses (Ember/Forge) to deploy a slice in header mode.
    ///
    /// @param port                  the app-HTTP port
    /// @param apiVersioningDetection the API-version detection mode
    /// @param apiVersionHeaderName   the version header name (header mode)
    /// @return the config
    public static AppHttpConfig appHttpConfig(int port,
                                              ApiVersioningDetection apiVersioningDetection,
                                              String apiVersionHeaderName) {
        return appHttpConfig(true,
                             port,
                             Map.of(),
                             DEFAULT_MAX_REQUEST_SIZE,
                             SecurityMode.API_KEY,
                             Option.empty(),
                             HttpProtocol.H1,
                             apiVersioningDetection,
                             apiVersionHeaderName).unwrap();
    }

    public static AppHttpConfig appHttpConfig(int port, Set<String> apiKeys) {
        return appHttpConfig(true,
                             port,
                             wrapSimpleKeys(apiKeys),
                             DEFAULT_MAX_REQUEST_SIZE,
                             SecurityMode.API_KEY,
                             Option.empty(),
                             HttpProtocol.H1).unwrap();
    }

    /// The explicit opt-out (#665, owner ruling 2026-08-27: insecurity is explicit, never a
    /// default). Every other builder that does not take a [SecurityMode] yields
    /// [SecurityMode#API_KEY] -- with no keys that is fail-closed, nothing can authenticate until a
    /// key exists -- so an in-JVM harness or a unit test that wants an OPEN listener must say so by
    /// name. `NONE` serves routes with an unspecified policy as public and refuses auth-requiring
    /// ones outright; it is never appropriate for anything reachable over a network.
    public static AppHttpConfig insecureAppHttpConfig(int port) {
        return appHttpConfig(true,
                             port,
                             Map.of(),
                             DEFAULT_MAX_REQUEST_SIZE,
                             SecurityMode.NONE,
                             Option.empty(),
                             HttpProtocol.H1).unwrap();
    }

    public Set<String> apiKeyValues() {
        return Set.copyOf(apiKeys.keySet());
    }

    public int portFor(int nodeIndex) {
        return port + nodeIndex;
    }

    public boolean securityEnabled() {
        return securityMode != SecurityMode.NONE;
    }

    private static int normalizeMaxRequestSize(int maxRequestSize) {
        return maxRequestSize > 0
               ? maxRequestSize
               : DEFAULT_MAX_REQUEST_SIZE;
    }

    private static String normalizeHeaderName(String headerName) {
        return ! Verify.Is.present(headerName)
               ? DEFAULT_API_VERSION_HEADER
               : headerName.trim();
    }

    private static Map<String, ApiKeyEntry> wrapSimpleKeys(Set<String> keys) {
        var map = new HashMap<String, ApiKeyEntry>();

        keys.forEach(key -> map.put(key, ApiKeyEntry.defaultEntry(key)));

        return Map.copyOf(map);
    }
}
