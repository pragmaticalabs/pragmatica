package org.pragmatica.aether.example.urlshortener.analytics;

import org.pragmatica.aether.infra.cache.CacheService;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;

import java.util.regex.Pattern;

/**
 * Analytics slice - tracks URL access statistics.
 * <p>
 * Uses CacheService counter operations to track clicks per short code.
 * Key pattern: {@code clicks:{shortCode}}
 */
@Slice
public interface Analytics {
    String CLICKS_PREFIX = "clicks:";

    // === Requests ===
    record RecordClickRequest(String shortCode) {
        private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z0-9]{6,8}$");

        private static final Cause EMPTY_CODE = AnalyticsError.invalidCode("Short code cannot be empty");
        private static final Cause INVALID_CODE_FORMAT = AnalyticsError.invalidCode("Invalid short code format");

        public static Result<RecordClickRequest> recordClickRequest(String shortCode) {
            return Verify.ensure(shortCode, Verify.Is::notNull, EMPTY_CODE)
                         .filter(c -> EMPTY_CODE, Verify.Is::notBlank)
                         .map(String::trim)
                         .filter(c -> INVALID_CODE_FORMAT,
                                 CODE_PATTERN.asMatchPredicate())
                         .map(RecordClickRequest::new);
        }
    }

    record GetStatsRequest(String shortCode) {
        private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z0-9]{6,8}$");

        private static final Cause EMPTY_CODE = AnalyticsError.invalidCode("Short code cannot be empty");
        private static final Cause INVALID_CODE_FORMAT = AnalyticsError.invalidCode("Invalid short code format");

        public static Result<GetStatsRequest> getStatsRequest(String shortCode) {
            return Verify.ensure(shortCode, Verify.Is::notNull, EMPTY_CODE)
                         .filter(c -> EMPTY_CODE, Verify.Is::notBlank)
                         .map(String::trim)
                         .filter(c -> INVALID_CODE_FORMAT,
                                 CODE_PATTERN.asMatchPredicate())
                         .map(GetStatsRequest::new);
        }
    }

    // === Responses ===
    record RecordClickResponse(String shortCode, long totalClicks) {}

    record GetStatsResponse(String shortCode, long clickCount) {}

    // === Errors ===
    sealed interface AnalyticsError extends Cause {
        record InvalidCode(String reason) implements AnalyticsError {
            @Override
            public String message() {
                return "Invalid short code: " + reason;
            }
        }

        record StorageError(String operation, Throwable cause) implements AnalyticsError {
            @Override
            public String message() {
                return "Analytics storage error during " + operation + ": " + cause.getMessage();
            }
        }

        static InvalidCode invalidCode(String reason) {
            return new InvalidCode(reason);
        }
    }

    // === Operations ===
    Promise<RecordClickResponse> recordClick(RecordClickRequest request);

    Promise<GetStatsResponse> getStats(GetStatsRequest request);

    // === Factory ===
    static Analytics analytics(CacheService cache) {
        record analytics(CacheService cache) implements Analytics {
            @Override
            public Promise<RecordClickResponse> recordClick(RecordClickRequest request) {
                var key = CLICKS_PREFIX + request.shortCode();
                return cache.increment(key)
                            .map(count -> new RecordClickResponse(request.shortCode(),
                                                                  count));
            }

            @Override
            public Promise<GetStatsResponse> getStats(GetStatsRequest request) {
                var key = CLICKS_PREFIX + request.shortCode();
                return cache.get(key)
                            .map(maybeCount -> maybeCount.map(Long::parseLong)
                                                         .or(0L))
                            .map(count -> new GetStatsResponse(request.shortCode(),
                                                               count));
            }
        }
        return new analytics(cache);
    }

    // === No-op analytics for testing ===
    static Analytics noopAnalytics() {
        return new Analytics() {
            @Override
            public Promise<RecordClickResponse> recordClick(RecordClickRequest request) {
                return Promise.success(new RecordClickResponse(request.shortCode(), 0));
            }

            @Override
            public Promise<GetStatsResponse> getStats(GetStatsRequest request) {
                return Promise.success(new GetStatsResponse(request.shortCode(), 0));
            }
        };
    }
}
