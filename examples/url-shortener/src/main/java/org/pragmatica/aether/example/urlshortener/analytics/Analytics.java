package org.pragmatica.aether.example.urlshortener.analytics;

import org.pragmatica.aether.example.urlshortener.shortener.UrlShortener.ClickEvent;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;

import java.util.regex.Pattern;

@Slice public interface Analytics {
    record RecordClickRequest(String shortCode) {
        private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z0-9]{6,8}$");

        private static final Cause EMPTY_CODE = AnalyticsError.invalidCode("Short code cannot be empty");
        private static final Cause INVALID_CODE_FORMAT = AnalyticsError.invalidCode("Invalid short code format");

        public static Result<RecordClickRequest> recordClickRequest(String shortCode) {
            return Verify.ensure(shortCode, Verify.Is::notNull, EMPTY_CODE).filter(c -> EMPTY_CODE, Verify.Is::notBlank)
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
            return Verify.ensure(shortCode, Verify.Is::notNull, EMPTY_CODE).filter(c -> EMPTY_CODE, Verify.Is::notBlank)
                                .map(String::trim)
                                .filter(c -> INVALID_CODE_FORMAT,
                                        CODE_PATTERN.asMatchPredicate())
                                .map(GetStatsRequest::new);
        }
    }

    record RecordClickResponse(String shortCode, long totalClicks) {
        public static RecordClickResponse recordClickResponse(String shortCode, long totalClicks) {
            return new RecordClickResponse(shortCode, totalClicks);
        }
    }

    record GetStatsResponse(String shortCode, long clickCount) {
        public static GetStatsResponse getStatsResponse(String shortCode, long clickCount) {
            return new GetStatsResponse(shortCode, clickCount);
        }
    }

    sealed interface AnalyticsError extends Cause {
        record InvalidCode(String reason) implements AnalyticsError {
            @Override public String message() {
                return "Invalid short code: " + reason;
            }
        }

        record StorageError(String operation, Throwable cause) implements AnalyticsError {
            @Override public String message() {
                return "Analytics storage error during " + operation + ": " + cause.getMessage();
            }
        }

        static InvalidCode invalidCode(String reason) {
            return new InvalidCode(reason);
        }
    }

    Promise<RecordClickResponse> recordClick(RecordClickRequest request);
    Promise<GetStatsResponse> getStats(GetStatsRequest request);
    @ClickEventSubscription Promise<Unit> onClickEvent(ClickEvent event);

    static Analytics analytics(AnalyticsPersistence persistence) {
        return new analytics(persistence);
    }

    record analytics(AnalyticsPersistence persistence) implements Analytics {
        @Override public Promise<RecordClickResponse> recordClick(RecordClickRequest request) {
            var shortCode = request.shortCode();
            return persistence.insertClick(shortCode)
                              .flatMap(_ -> persistence.countByShortCode(shortCode))
                              .map(count -> RecordClickResponse.recordClickResponse(shortCode, count));
        }

        @Override public Promise<GetStatsResponse> getStats(GetStatsRequest request) {
            var shortCode = request.shortCode();
            return persistence.countByShortCode(shortCode)
                              .map(count -> GetStatsResponse.getStatsResponse(shortCode, count));
        }

        @Override public Promise<Unit> onClickEvent(ClickEvent event) {
            return persistence.insertClick(event.shortCode());
        }
    }

    static Analytics noopAnalytics() {
        return new Analytics() {
            @Override public Promise<RecordClickResponse> recordClick(RecordClickRequest request) {
                return Promise.success(RecordClickResponse.recordClickResponse(request.shortCode(), 0));
            }

            @Override public Promise<GetStatsResponse> getStats(GetStatsRequest request) {
                return Promise.success(GetStatsResponse.getStatsResponse(request.shortCode(), 0));
            }

            @Override public Promise<Unit> onClickEvent(ClickEvent event) {
                return Promise.success(Unit.unit());
            }
        };
    }
}
