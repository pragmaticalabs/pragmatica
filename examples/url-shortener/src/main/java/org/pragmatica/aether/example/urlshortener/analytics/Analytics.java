package org.pragmatica.aether.example.urlshortener.analytics;

import org.pragmatica.aether.infra.db.Database;
import org.pragmatica.aether.infra.db.DatabaseConnector;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import java.util.regex.Pattern;

/// Analytics slice - tracks URL access statistics.
///
/// Uses database storage with clicks table to track click counts per short code.
@Slice
public interface Analytics {
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
    static Analytics analytics(@Database DatabaseConnector db) {
        return new analytics(db);
    }

    record analytics(DatabaseConnector db) implements Analytics {
        private static final String INSERT_CLICK = "INSERT INTO clicks (short_code) VALUES (?)";
        private static final String COUNT_CLICKS = "SELECT COUNT(*) as click_count FROM clicks WHERE short_code = ?";

        @Override
        public Promise<RecordClickResponse> recordClick(RecordClickRequest request) {
            var shortCode = request.shortCode();
            return db.update(INSERT_CLICK, shortCode)
                     .flatMap(_ -> getClickCount(shortCode))
                     .map(count -> new RecordClickResponse(shortCode, count));
        }

        @Override
        public Promise<GetStatsResponse> getStats(GetStatsRequest request) {
            var shortCode = request.shortCode();
            return getClickCount(shortCode).map(count -> new GetStatsResponse(shortCode, count));
        }

        private Promise<Long> getClickCount(String shortCode) {
            return db.queryOne(COUNT_CLICKS, row -> row.getLong("click_count"), shortCode);
        }
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
