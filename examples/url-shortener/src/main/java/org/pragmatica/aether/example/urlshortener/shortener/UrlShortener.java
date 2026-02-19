package org.pragmatica.aether.example.urlshortener.shortener;

import org.pragmatica.aether.example.urlshortener.analytics.Analytics;
import org.pragmatica.aether.resource.db.Sql;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;

/// URL Shortener slice - creates and resolves short URLs.
///
/// Uses database storage with two tables:
///
///   - `urls` - maps short code to original URL
///   - `clicks` - tracks click analytics
///
@Slice
public interface UrlShortener {
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
    record ShortenResponse(String shortCode, String originalUrl) {
        public static ShortenResponse shortenResponse(String shortCode, String originalUrl) {
            return new ShortenResponse(shortCode, originalUrl);
        }
    }

    record ResolveResponse(String shortCode, String originalUrl) {
        public static ResolveResponse resolveResponse(String shortCode, String originalUrl) {
            return new ResolveResponse(shortCode, originalUrl);
        }
    }

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
    static UrlShortener urlShortener(@Sql SqlConnector db, Analytics analytics) {
        return new urlShortener(db, analytics);
    }

    record urlShortener(SqlConnector db, Analytics analytics) implements UrlShortener {
        private static final String SELECT_BY_URL = "SELECT short_code FROM urls WHERE original_url = ?";
        private static final String SELECT_BY_CODE = "SELECT original_url FROM urls WHERE short_code = ?";
        private static final String INSERT_URL = "INSERT INTO urls (short_code, original_url) VALUES (?, ?)";

        @Override
        public Promise<ShortenResponse> shorten(ShortenRequest request) {
            var url = request.url();
            return db.queryOptional(SELECT_BY_URL,
                                    row -> row.getString("short_code"),
                                    url)
                     .flatMap(existing -> existing.map(code -> Promise.success(ShortenResponse.shortenResponse(code, url)))
                                                  .or(() -> createNewShortUrl(url)));
        }

        @Override
        public Promise<ResolveResponse> resolve(ResolveRequest request) {
            var shortCode = request.shortCode();
            return db.queryOptional(SELECT_BY_CODE,
                                    row -> row.getString("original_url"),
                                    shortCode)
                     .flatMap(maybeUrl -> maybeUrl.map(url -> recordClickAndRespond(shortCode, url))
                                                  .or(UrlError.NotFound.INSTANCE::promise));
        }

        private Promise<ResolveResponse> recordClickAndRespond(String shortCode, String url) {
            return Analytics.RecordClickRequest.recordClickRequest(shortCode)
                            .async()
                            .flatMap(analytics::recordClick)
                            .map(_ -> ResolveResponse.resolveResponse(shortCode, url));
        }

        private Promise<ShortenResponse> createNewShortUrl(String url) {
            var shortCode = computeShortCode(url);
            return db.update(INSERT_URL, shortCode, url)
                     .map(_ -> ShortenResponse.shortenResponse(shortCode, url));
        }

        private static String computeShortCode(String url) {
            var digest = getSha256Digest();
            var hashBytes = digest.digest(url.getBytes(StandardCharsets.UTF_8));
            var hexHash = formatHashBytes(hashBytes);
            return toBase62(hexHash, 7);
        }

        private static MessageDigest getSha256Digest() {
            // SHA-256 is guaranteed available in all Java implementations (JCA spec)
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new AssertionError("SHA-256 algorithm must be available per JCA specification", e);
            }
        }

        private static String formatHashBytes(byte[] hashBytes) {
            var sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hashBytes[i]));
            }
            return sb.toString();
        }

        private static String toBase62(String hexHash, int length) {
            var value = Long.parseUnsignedLong(hexHash.substring(0, 12), 16);
            var sb = new StringBuilder();
            while (value > 0 && sb.length() < length) {
                sb.insert(0, BASE62_CHARS.charAt((int) (value % 62)));
                value /= 62;
            }
            while (sb.length() < length) {
                sb.insert(0, '0');
            }
            return sb.toString();
        }
    }

    // === Convenience factory for testing without database ===
    static UrlShortener urlShortener(SqlConnector db) {
        return urlShortener(db, Analytics.noopAnalytics());
    }
}
