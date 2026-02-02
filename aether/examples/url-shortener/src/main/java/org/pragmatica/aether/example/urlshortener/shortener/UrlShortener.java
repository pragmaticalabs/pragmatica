package org.pragmatica.aether.example.urlshortener.shortener;

import org.pragmatica.aether.example.urlshortener.analytics.Analytics;
import org.pragmatica.aether.infra.cache.CacheService;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/**
 * URL Shortener slice - creates and resolves short URLs.
 * <p>
 * Uses key-value storage with two key patterns:
 * <ul>
 *   <li>{@code url:{shortCode}} - maps short code to original URL</li>
 *   <li>{@code rev:{hash}} - maps URL hash to short code for deduplication</li>
 * </ul>
 */
@Slice
public interface UrlShortener {
    String URL_PREFIX = "url:";
    String REV_PREFIX = "rev:";
    String BASE62_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    // === Requests ===
    record ShortenRequest(String url) {
        private static final Pattern URL_PATTERN = Pattern.compile("^https?://[^\\s/$.?#].[^\\s]*$");
        private static final Cause EMPTY_URL = UrlError.invalidUrl("URL cannot be empty");
        private static final Cause INVALID_URL_FORMAT = UrlError.invalidUrl("Invalid URL format");

        public static Result<ShortenRequest> shortenRequest(String url) {
            return Verify.ensure(url, Verify.Is::notNull, EMPTY_URL)
                         .filter(u -> EMPTY_URL, Verify.Is::notBlank)
                         .map(String::trim)
                         .filter(u -> INVALID_URL_FORMAT,
                                 URL_PATTERN.asMatchPredicate())
                         .map(ShortenRequest::new);
        }
    }

    record ResolveRequest(String shortCode) {
        private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z0-9]{6,8}$");

        private static final Cause EMPTY_CODE = UrlError.invalidCode("Short code cannot be empty");
        private static final Cause INVALID_CODE_FORMAT = UrlError.invalidCode("Invalid short code format");

        public static Result<ResolveRequest> resolveRequest(String shortCode) {
            return Verify.ensure(shortCode, Verify.Is::notNull, EMPTY_CODE)
                         .filter(c -> EMPTY_CODE, Verify.Is::notBlank)
                         .map(String::trim)
                         .filter(c -> INVALID_CODE_FORMAT,
                                 CODE_PATTERN.asMatchPredicate())
                         .map(ResolveRequest::new);
        }
    }

    // === Responses ===
    record ShortenResponse(String shortCode, String originalUrl) {}

    record ResolveResponse(String shortCode, String originalUrl) {}

    // === Errors ===
    sealed interface UrlError extends Cause {
        record InvalidUrl(String reason) implements UrlError {
            @Override
            public String message() {
                return "Invalid URL: " + reason;
            }
        }

        record InvalidCode(String reason) implements UrlError {
            @Override
            public String message() {
                return "Invalid short code: " + reason;
            }
        }

        enum NotFound implements UrlError {
            INSTANCE;
            @Override
            public String message() {
                return "Short URL not found";
            }
        }

        record StorageError(String operation, Throwable cause) implements UrlError {
            @Override
            public String message() {
                return "Storage error during " + operation + ": " + cause.getMessage();
            }
        }

        static InvalidUrl invalidUrl(String reason) {
            return new InvalidUrl(reason);
        }

        static InvalidCode invalidCode(String reason) {
            return new InvalidCode(reason);
        }
    }

    // === Operations ===
    Promise<ShortenResponse> shorten(ShortenRequest request);

    Promise<ResolveResponse> resolve(ResolveRequest request);

    // === Factory ===
    static UrlShortener urlShortener(CacheService cache, Analytics analytics) {
        record urlShortener(CacheService cache, Analytics analytics) implements UrlShortener {
            @Override
            public Promise<ShortenResponse> shorten(ShortenRequest request) {
                var url = request.url();
                var hash = computeHash(url);
                var revKey = REV_PREFIX + hash;
                return cache.get(revKey)
                            .flatMap(existingCode -> existingCode.map(code -> Promise.success(new ShortenResponse(code,
                                                                                                                  url)))
                                                                 .or(() -> createNewShortUrl(url, hash)));
            }

            @Override
            public Promise<ResolveResponse> resolve(ResolveRequest request) {
                var urlKey = URL_PREFIX + request.shortCode();
                return cache.get(urlKey)
                            .flatMap(maybeUrl -> maybeUrl.map(url -> recordClickAndRespond(request.shortCode(),
                                                                                           url))
                                                         .or(UrlError.NotFound.INSTANCE::promise));
            }

            private Promise<ResolveResponse> recordClickAndRespond(String shortCode, String url) {
                return analytics.recordClick(new Analytics.RecordClickRequest(shortCode))
                                .map(_ -> new ResolveResponse(shortCode, url));
            }

            private Promise<ShortenResponse> createNewShortUrl(String url, String hash) {
                var shortCode = toBase62(hash, 7);
                var urlKey = URL_PREFIX + shortCode;
                var revKey = REV_PREFIX + hash;
                return cache.set(urlKey, url)
                            .flatMap(_ -> cache.set(revKey, shortCode))
                            .map(_ -> new ShortenResponse(shortCode, url));
            }

            private String computeHash(String url) {
                try{
                    var digest = MessageDigest.getInstance("SHA-256");
                    var hashBytes = digest.digest(url.getBytes(StandardCharsets.UTF_8));
                    var sb = new StringBuilder();
                    for (int i = 0; i < 8; i++) {
                        sb.append(String.format("%02x", hashBytes[i]));
                    }
                    return sb.toString();
                } catch (NoSuchAlgorithmException e) {
                    // SHA-256 is guaranteed to be available in Java
                    return "";
                }
            }

            private String toBase62(String hexHash, int length) {
                var value = Long.parseUnsignedLong(hexHash.substring(0, 12), 16);
                var sb = new StringBuilder();
                while (value > 0 && sb.length() < length) {
                    sb.insert(0, BASE62_CHARS.charAt((int)(value % 62)));
                    value /= 62;
                }
                while (sb.length() < length) {
                    sb.insert(0, '0');
                }
                return sb.toString();
            }
        }
        return new urlShortener(cache, analytics);
    }

    // === Convenience factory for testing without analytics ===
    static UrlShortener urlShortener(CacheService cache) {
        return urlShortener(cache, Analytics.noopAnalytics());
    }
}
