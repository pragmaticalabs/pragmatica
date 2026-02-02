package org.pragmatica.aether.example.urlshortener.analytics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.example.urlshortener.analytics.Analytics.GetStatsRequest;
import org.pragmatica.aether.example.urlshortener.analytics.Analytics.RecordClickRequest;
import org.pragmatica.aether.infra.cache.CacheService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class AnalyticsTest {
    private CacheService cache;
    private Analytics analytics;

    @BeforeEach
    void setup() {
        cache = CacheService.cacheService();
        analytics = Analytics.analytics(cache);
    }

    @Nested
    class RecordClickRequestValidation {
        @Test
        void recordClickRequest_succeeds_forValidCode() {
            RecordClickRequest.recordClickRequest("abc1234")
                              .onFailureRun(() -> fail("Expected success"))
                              .onSuccess(req -> assertThat(req.shortCode()).isEqualTo("abc1234"));
        }

        @Test
        void recordClickRequest_fails_forEmptyCode() {
            RecordClickRequest.recordClickRequest("")
                              .onSuccessRun(() -> fail("Expected failure"));
        }

        @Test
        void recordClickRequest_fails_forNullCode() {
            RecordClickRequest.recordClickRequest(null)
                              .onSuccessRun(() -> fail("Expected failure"));
        }

        @Test
        void recordClickRequest_fails_forInvalidCharacters() {
            RecordClickRequest.recordClickRequest("abc-123")
                              .onSuccessRun(() -> fail("Expected failure"));
        }
    }

    @Nested
    class GetStatsRequestValidation {
        @Test
        void getStatsRequest_succeeds_forValidCode() {
            GetStatsRequest.getStatsRequest("abc1234")
                           .onFailureRun(() -> fail("Expected success"))
                           .onSuccess(req -> assertThat(req.shortCode()).isEqualTo("abc1234"));
        }

        @Test
        void getStatsRequest_fails_forEmptyCode() {
            GetStatsRequest.getStatsRequest("")
                           .onSuccessRun(() -> fail("Expected failure"));
        }

        @Test
        void getStatsRequest_fails_forTooShortCode() {
            GetStatsRequest.getStatsRequest("abc12")
                           .onSuccessRun(() -> fail("Expected failure"));
        }
    }

    @Nested
    class RecordClickOperation {
        @Test
        void recordClick_succeeds_andIncrementsCounter() {
            var request = new RecordClickRequest("Test123");

            analytics.recordClick(request)
                     .await()
                     .onFailureRun(() -> fail("Expected success"))
                     .onSuccess(response -> {
                         assertThat(response.shortCode()).isEqualTo("Test123");
                         assertThat(response.totalClicks()).isEqualTo(1);
                     });
        }

        @Test
        void recordClick_incrementsCounter_onMultipleCalls() {
            var request = new RecordClickRequest("Multi12");

            analytics.recordClick(request).await();
            analytics.recordClick(request).await();

            analytics.recordClick(request)
                     .await()
                     .onFailureRun(() -> fail("Expected success"))
                     .onSuccess(response -> assertThat(response.totalClicks()).isEqualTo(3));
        }
    }

    @Nested
    class GetStatsOperation {
        @Test
        void getStats_returnsZero_forNonexistentCode() {
            analytics.getStats(new GetStatsRequest("NoClck1"))
                     .await()
                     .onFailureRun(() -> fail("Expected success"))
                     .onSuccess(stats -> {
                         assertThat(stats.shortCode()).isEqualTo("NoClck1");
                         assertThat(stats.clickCount()).isZero();
                     });
        }

        @Test
        void getStats_returnsCorrectCount_afterClicks() {
            var shortCode = "Stats12";
            analytics.recordClick(new RecordClickRequest(shortCode)).await();
            analytics.recordClick(new RecordClickRequest(shortCode)).await();
            analytics.recordClick(new RecordClickRequest(shortCode)).await();
            analytics.recordClick(new RecordClickRequest(shortCode)).await();
            analytics.recordClick(new RecordClickRequest(shortCode)).await();

            analytics.getStats(new GetStatsRequest(shortCode))
                     .await()
                     .onFailureRun(() -> fail("Expected success"))
                     .onSuccess(stats -> assertThat(stats.clickCount()).isEqualTo(5));
        }
    }

    @Nested
    class NoopAnalytics {
        @Test
        void noopAnalytics_recordClick_returnsZero() {
            var noop = Analytics.noopAnalytics();

            noop.recordClick(new RecordClickRequest("abc1234"))
                .await()
                .onFailureRun(() -> fail("Expected success"))
                .onSuccess(response -> assertThat(response.totalClicks()).isZero());
        }

        @Test
        void noopAnalytics_getStats_returnsZero() {
            var noop = Analytics.noopAnalytics();

            noop.getStats(new GetStatsRequest("abc1234"))
                .await()
                .onFailureRun(() -> fail("Expected success"))
                .onSuccess(stats -> assertThat(stats.clickCount()).isZero());
        }
    }
}
