/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
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
 *
 */

package org.pragmatica.cloud.gcp;

import org.pragmatica.cloud.gcp.api.TokenResponse;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

/// Manages OAuth2 access tokens for GCP API authentication.
/// Caches tokens and refreshes them 5 minutes before expiry.
public class GcpTokenManager {
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String SCOPE = "https://www.googleapis.com/auth/cloud-platform";
    private static final long REFRESH_MARGIN_MILLIS = 5 * 60 * 1000L;
    private static final long TOKEN_LIFETIME_SECONDS = 3600L;

    private final GcpConfig config;
    private final AtomicReference<TokenState> cachedToken = new AtomicReference<>();

    /// Cached token state.
    record TokenState(String accessToken, long expiresAtMillis) {}

    GcpTokenManager(GcpConfig config) {
        this.config = config;
    }

    /// Package-private no-arg constructor for test subclassing.
    GcpTokenManager() {
        this.config = null;
    }

    /// Creates a new token manager for the given configuration.
    static GcpTokenManager gcpTokenManager(GcpConfig config) {
        return new GcpTokenManager(config);
    }

    /// Returns a valid access token, refreshing if needed.
    Promise<String> getAccessToken(HttpOperations http, JsonMapper mapper) {
        var current = cachedToken.get();
        if (isTokenValid(current)) {
            return Promise.success(current.accessToken());
        }
        return refreshToken(http, mapper);
    }

    private static boolean isTokenValid(TokenState state) {
        return state != null && System.currentTimeMillis() < state.expiresAtMillis() - REFRESH_MARGIN_MILLIS;
    }

    private Promise<String> refreshToken(HttpOperations http, JsonMapper mapper) {
        return createSignedJwt()
            .async()
            .flatMap(jwt -> requestToken(http, mapper, jwt))
            .map(this::cacheToken);
    }

    private String cacheToken(TokenResponse response) {
        var expiresAt = System.currentTimeMillis() + response.expires_in() * 1000L;
        cachedToken.set(new TokenState(response.access_token(), expiresAt));
        return response.access_token();
    }

    private Promise<TokenResponse> requestToken(HttpOperations http, JsonMapper mapper, String jwt) {
        var body = "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=" + jwt;
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create(TOKEN_URL))
                                 .POST(BodyPublishers.ofString(body))
                                 .header("Content-Type", "application/x-www-form-urlencoded")
                                 .build();
        return http.sendString(request)
                   .flatMap(result -> parseTokenResponse(result, mapper));
    }

    private static Promise<TokenResponse> parseTokenResponse(org.pragmatica.http.HttpResult<String> result, JsonMapper mapper) {
        if (result.isSuccess()) {
            return mapper.readString(result.body(), TokenResponse.class).async();
        }
        return new GcpError.AuthError("Token request failed with status " + result.statusCode(), Option.none()).promise();
    }

    Result<String> createSignedJwt() {
        var now = System.currentTimeMillis() / 1000L;
        var header = base64Encode("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        var payload = base64Encode(buildClaimsJson(now));
        var signingInput = header + "." + payload;
        return signRsa256(signingInput).map(signature -> signingInput + "." + signature);
    }

    private String buildClaimsJson(long nowSeconds) {
        return "{\"iss\":\"" + config.serviceAccountEmail() + "\","
               + "\"scope\":\"" + SCOPE + "\","
               + "\"aud\":\"" + TOKEN_URL + "\","
               + "\"iat\":" + nowSeconds + ","
               + "\"exp\":" + (nowSeconds + TOKEN_LIFETIME_SECONDS) + "}";
    }

    private Result<String> signRsa256(String input) {
        return parsePrivateKey(config.privateKeyPem())
            .flatMap(key -> signWithKey(key, input));
    }

    private static Result<String> signWithKey(RSAPrivateKey key, String input) {
        return Result.lift(
            t -> new GcpError.AuthError("JWT signing failed", Option.some(t)),
            () -> performSignature(key, input)
        );
    }

    private static String performSignature(RSAPrivateKey key, String input) throws Exception {
        var signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key);
        signature.update(input.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
    }

    static Result<RSAPrivateKey> parsePrivateKey(String pem) {
        return Result.lift(
            t -> new GcpError.AuthError("Failed to parse private key", Option.some(t)),
            () -> decodePrivateKey(pem)
        );
    }

    private static RSAPrivateKey decodePrivateKey(String pem) throws Exception {
        var stripped = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                         .replace("-----END PRIVATE KEY-----", "")
                         .replaceAll("\\s", "");
        var decoded = Base64.getDecoder().decode(stripped);
        var keySpec = new PKCS8EncodedKeySpec(decoded);
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(keySpec);
    }

    private static String base64Encode(String input) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(input.getBytes(StandardCharsets.UTF_8));
    }
}
