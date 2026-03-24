package org.pragmatica.aether.http.security;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Result.success;

/// Fetches, caches, and manages JWKS (JSON Web Key Set) public keys.
///
/// Keys are cached with a configurable TTL. On cache miss for an unknown kid,
/// the store refreshes from the remote JWKS endpoint (supporting key rotation).
@SuppressWarnings({"JBCT-RET-03", "JBCT-EX-01", "JBCT-PAT-01"})
class JwksKeyStore implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(JwksKeyStore.class);
    private static final JsonMapper JSON = JsonMapper.defaultJsonMapper();

    private static final TypeToken<Map<String, Object>> MAP_TYPE = new TypeToken<>() {};

    private static final Base64.Decoder BASE64URL = Base64.getUrlDecoder();

    private final String jwksUrl;
    private final long cacheTtlMs;
    private final HttpClient httpClient;
    final ConcurrentHashMap<String, PublicKey> keyCache = new ConcurrentHashMap<>();
    final AtomicLong lastFetchTime = new AtomicLong(0);
    private final AtomicReference<List<Map<String, Object>>> rawKeys = new AtomicReference<>(List.of());

    JwksKeyStore(String jwksUrl, long cacheTtlSeconds) {
        this.jwksUrl = jwksUrl;
        this.cacheTtlMs = cacheTtlSeconds * 1000;
        this.httpClient = HttpClient.newHttpClient();
    }

    /// Package-private constructor for testing with injected HttpClient.
    JwksKeyStore(String jwksUrl, long cacheTtlSeconds, HttpClient httpClient) {
        this.jwksUrl = jwksUrl;
        this.cacheTtlMs = cacheTtlSeconds * 1000;
        this.httpClient = httpClient;
    }

    @Override
    @Contract
    public void close() {
        httpClient.close();
    }

    /// Find a public key by kid and algorithm.
    /// If kid not found in cache, attempts a refresh from the JWKS endpoint.
    Result<PublicKey> findKey(String kid, String alg) {
        refreshIfExpired();
        return Option.option(keyCache.get(kid))
                     .toResult(new SecurityError.KeyNotFound("Key not found for kid: " + kid))
                     .orElse(() -> refreshAndRetry(kid));
    }

    private void refreshIfExpired() {
        var now = System.currentTimeMillis();
        var lastFetch = lastFetchTime.get();
        if (now - lastFetch > cacheTtlMs && lastFetchTime.compareAndSet(lastFetch, now)) {
            fetchAndCacheKeys();
        }
    }

    private Result<PublicKey> refreshAndRetry(String kid) {
        fetchAndCacheKeys();
        return Option.option(keyCache.get(kid))
                     .toResult(new SecurityError.KeyNotFound("Key not found for kid: " + kid + " after JWKS refresh"));
    }

    private void fetchAndCacheKeys() {
        fetchJwks().onSuccess(this::applyJwksResponse)
                 .onFailure(cause -> log.warn("Failed to fetch JWKS from {}: {}",
                                              jwksUrl,
                                              cause.message()));
    }

    @SuppressWarnings("unchecked")
    private void applyJwksResponse(Map<String, Object> jwksMap) {
        Option.option((List<Map<String, Object>>) jwksMap.get("keys"))
              .onPresent(this::refreshKeyCache);
    }

    private void refreshKeyCache(List<Map<String, Object>> keys) {
        var newCache = new ConcurrentHashMap<String, PublicKey>();
        keys.forEach(keyData -> cacheKeyInto(newCache, keyData));
        rawKeys.set(keys);
        keyCache.clear();
        keyCache.putAll(newCache);
        lastFetchTime.set(System.currentTimeMillis());
        log.debug("JWKS refreshed: {} keys cached from {}", keys.size(), jwksUrl);
    }

    private void cacheKeyInto(ConcurrentHashMap<String, PublicKey> targetCache, Map<String, Object> keyData) {
        var kid = stringValue(keyData, "kid");
        var kty = stringValue(keyData, "kty");
        if (kid.isEmpty()) {
            return;
        }
        buildPublicKey(kty, keyData).onSuccess(key -> targetCache.put(kid, key));
    }

    private void cacheKey(Map<String, Object> keyData) {
        var kid = stringValue(keyData, "kid");
        var kty = stringValue(keyData, "kty");
        if (kid.isEmpty()) {
            return;
        }
        buildPublicKey(kty, keyData).onSuccess(key -> keyCache.put(kid, key));
    }

    private static Result<PublicKey> buildPublicKey(String kty, Map<String, Object> keyData) {
        return switch (kty) {
            case "RSA" -> buildRsaPublicKey(keyData);
            case "EC" -> buildEcPublicKey(keyData);
            default -> new SecurityError.JwksFetchFailed("Unsupported key type: " + kty).result();
        };
    }

    private static Result<PublicKey> buildRsaPublicKey(Map<String, Object> keyData) {
        return Result.lift(JwksKeyStore::keyBuildFailed, () -> buildRsaKey(keyData));
    }

    private static PublicKey buildRsaKey(Map<String, Object> keyData) throws Exception {
        var n = decodeBigInteger(stringValue(keyData, "n"));
        var e = decodeBigInteger(stringValue(keyData, "e"));
        var spec = new RSAPublicKeySpec(n, e);
        return KeyFactory.getInstance("RSA")
                         .generatePublic(spec);
    }

    private static Result<PublicKey> buildEcPublicKey(Map<String, Object> keyData) {
        return Result.lift(JwksKeyStore::keyBuildFailed, () -> buildEcKey(keyData));
    }

    private static PublicKey buildEcKey(Map<String, Object> keyData) throws Exception {
        var x = decodeBigInteger(stringValue(keyData, "x"));
        var y = decodeBigInteger(stringValue(keyData, "y"));
        var crv = stringValue(keyData, "crv");
        var ecParams = resolveEcParams(crv);
        var point = new ECPoint(x, y);
        var spec = new ECPublicKeySpec(point, ecParams);
        return KeyFactory.getInstance("EC")
                         .generatePublic(spec);
    }

    private static ECParameterSpec resolveEcParams(String crv) throws Exception {
        var curveName = switch (crv) {
            case "P-256" -> "secp256r1";
            case "P-384" -> "secp384r1";
            case "P-521" -> "secp521r1";
            default -> throw new IllegalArgumentException("Unsupported curve: " + crv);
        };
        var params = java.security.AlgorithmParameters.getInstance("EC");
        params.init(new java.security.spec.ECGenParameterSpec(curveName));
        return params.getParameterSpec(ECParameterSpec.class);
    }

    private Result<Map<String, Object>> fetchJwks() {
        return Result.lift(JwksKeyStore::fetchFailed, this::doFetchJwks);
    }

    private Map<String, Object> doFetchJwks() throws Exception {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create(jwksUrl))
                                 .header("Accept", "application/json")
                                 .GET()
                                 .build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("JWKS fetch returned HTTP " + response.statusCode());
        }
        return JSON.readString(response.body(),
                               MAP_TYPE)
                   .unwrap();
    }

    private static SecurityError fetchFailed(Throwable t) {
        return new SecurityError.JwksFetchFailed("JWKS fetch failed: " + t.getMessage());
    }

    private static SecurityError keyBuildFailed(Throwable t) {
        return new SecurityError.JwksFetchFailed("Failed to build public key: " + t.getMessage());
    }

    private static BigInteger decodeBigInteger(String base64url) {
        var bytes = BASE64URL.decode(base64url);
        return new BigInteger(1, bytes);
    }

    private static String stringValue(Map<String, Object> map, String key) {
        return Option.option(map.get(key))
                     .map(Object::toString)
                     .or("");
    }
}
