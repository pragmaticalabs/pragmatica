package org.pragmatica.aether.example.urlshortener.shortener;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.example.urlshortener.analytics.Analytics;
import org.pragmatica.aether.example.urlshortener.shortener.UrlShortener.ResolveRequest;
import org.pragmatica.aether.example.urlshortener.shortener.UrlShortener.ShortenRequest;
import org.pragmatica.aether.example.urlshortener.shortener.UrlShortener.UrlError;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class UrlShortenerTest {
    private InMemoryDatabaseConnector db;
    private Analytics analytics;
    private UrlShortener urlShortener;

    @BeforeEach
    void setup() {
        db = InMemoryDatabaseConnector.inMemoryDatabaseConnector();
        analytics = Analytics.analytics(db);
        urlShortener = UrlShortener.urlShortener(db, analytics);
    }

    @Nested
    class ShortenRequestValidation {
        @Test
        void shortenRequest_succeeds_forValidHttpUrl() {
            ShortenRequest.shortenRequest("http://example.com")
                          .onFailureRun(() -> fail("Expected success"))
                          .onSuccess(req -> assertThat(req.url()).isEqualTo("http://example.com"));
        }

        @Test
        void shortenRequest_succeeds_forValidHttpsUrl() {
            ShortenRequest.shortenRequest("https://example.com/path?query=value")
                          .onFailureRun(() -> fail("Expected success"))
                          .onSuccess(req -> assertThat(req.url()).isEqualTo("https://example.com/path?query=value"));
        }

        @Test
        void shortenRequest_fails_forEmptyUrl() {
            ShortenRequest.shortenRequest("")
                          .onSuccessRun(() -> fail("Expected failure"));
        }

        @Test
        void shortenRequest_fails_forNullUrl() {
            ShortenRequest.shortenRequest(null)
                          .onSuccessRun(() -> fail("Expected failure"));
        }

        @Test
        void shortenRequest_fails_forInvalidUrl() {
            ShortenRequest.shortenRequest("not-a-url")
                          .onSuccessRun(() -> fail("Expected failure"));
        }

        @Test
        void shortenRequest_fails_forFtpUrl() {
            ShortenRequest.shortenRequest("ftp://files.example.com")
                          .onSuccessRun(() -> fail("Expected failure"));
        }
    }

    @Nested
    class ResolveRequestValidation {
        @Test
        void resolveRequest_succeeds_forValidCode() {
            ResolveRequest.resolveRequest("abc1234")
                          .onFailureRun(() -> fail("Expected success"))
                          .onSuccess(req -> assertThat(req.shortCode()).isEqualTo("abc1234"));
        }

        @Test
        void resolveRequest_succeeds_forSixCharCode() {
            ResolveRequest.resolveRequest("AbC123")
                          .onFailureRun(() -> fail("Expected success"))
                          .onSuccess(req -> assertThat(req.shortCode()).isEqualTo("AbC123"));
        }

        @Test
        void resolveRequest_succeeds_forEightCharCode() {
            ResolveRequest.resolveRequest("AbCd1234")
                          .onFailureRun(() -> fail("Expected success"))
                          .onSuccess(req -> assertThat(req.shortCode()).isEqualTo("AbCd1234"));
        }

        @Test
        void resolveRequest_fails_forEmptyCode() {
            ResolveRequest.resolveRequest("")
                          .onSuccessRun(() -> fail("Expected failure"));
        }

        @Test
        void resolveRequest_fails_forTooShortCode() {
            ResolveRequest.resolveRequest("abc12")
                          .onSuccessRun(() -> fail("Expected failure"));
        }

        @Test
        void resolveRequest_fails_forTooLongCode() {
            ResolveRequest.resolveRequest("abc123456")
                          .onSuccessRun(() -> fail("Expected failure"));
        }

        @Test
        void resolveRequest_fails_forInvalidCharacters() {
            ResolveRequest.resolveRequest("abc-123")
                          .onSuccessRun(() -> fail("Expected failure"));
        }
    }

    @Nested
    class ShortenOperation {
        @Test
        void shorten_succeeds_forValidUrl() {
            var request = ShortenRequest.shortenRequest("https://example.com/long/path").unwrap();

            urlShortener.shorten(request)
                        .await()
                        .onFailureRun(() -> fail("Expected success"))
                        .onSuccess(response -> {
                            assertThat(response.shortCode()).hasSize(7);
                            assertThat(response.shortCode()).matches("[A-Za-z0-9]+");
                            assertThat(response.originalUrl()).isEqualTo("https://example.com/long/path");
                        });
        }

        @Test
        void shorten_returnsSameCode_forDuplicateUrl() {
            var request = ShortenRequest.shortenRequest("https://example.com/duplicate").unwrap();

            var firstCode = urlShortener.shorten(request)
                                        .await()
                                        .unwrap()
                                        .shortCode();

            urlShortener.shorten(request)
                        .await()
                        .onFailureRun(() -> fail("Expected success"))
                        .onSuccess(response -> assertThat(response.shortCode()).isEqualTo(firstCode));
        }

        @Test
        void shorten_returnsDifferentCodes_forDifferentUrls() {
            var request1 = ShortenRequest.shortenRequest("https://example.com/path1").unwrap();
            var request2 = ShortenRequest.shortenRequest("https://example.com/path2").unwrap();

            var code1 = urlShortener.shorten(request1)
                                    .await()
                                    .unwrap()
                                    .shortCode();

            var code2 = urlShortener.shorten(request2)
                                    .await()
                                    .unwrap()
                                    .shortCode();

            assertThat(code1).isNotEqualTo(code2);
        }
    }

    @Nested
    class ResolveOperation {
        @Test
        void resolve_succeeds_forExistingCode() {
            var url = "https://example.com/to-resolve";
            var shortenedCode = urlShortener.shorten(ShortenRequest.shortenRequest(url).unwrap())
                                            .await()
                                            .unwrap()
                                            .shortCode();

            urlShortener.resolve(ResolveRequest.resolveRequest(shortenedCode).unwrap())
                        .await()
                        .onFailureRun(() -> fail("Expected success"))
                        .onSuccess(response -> {
                            assertThat(response.shortCode()).isEqualTo(shortenedCode);
                            assertThat(response.originalUrl()).isEqualTo(url);
                        });
        }

        @Test
        void resolve_fails_forNonexistentCode() {
            urlShortener.resolve(ResolveRequest.resolveRequest("NoExist1").unwrap())
                        .await()
                        .onSuccessRun(() -> fail("Expected failure"))
                        .onFailure(cause -> assertThat(cause).isInstanceOf(UrlError.NotFound.class));
        }

        @Test
        void resolve_recordsClick_forExistingCode() {
            var url = "https://example.com/track-clicks";
            var shortenedCode = urlShortener.shorten(ShortenRequest.shortenRequest(url).unwrap())
                                            .await()
                                            .unwrap()
                                            .shortCode();

            var resolveRequest = ResolveRequest.resolveRequest(shortenedCode).unwrap();

            // Resolve multiple times
            urlShortener.resolve(resolveRequest).await();
            urlShortener.resolve(resolveRequest).await();
            urlShortener.resolve(resolveRequest).await();

            // Check analytics
            analytics.getStats(Analytics.GetStatsRequest.getStatsRequest(shortenedCode).unwrap())
                     .await()
                     .onFailureRun(() -> fail("Expected success"))
                     .onSuccess(stats -> assertThat(stats.clickCount()).isEqualTo(3));
        }
    }

    @Nested
    class RoundTrip {
        @Test
        void shortenThenResolve_returnsOriginalUrl() {
            var originalUrl = "https://github.com/pragmatica-lite/aether";

            var shortCode = urlShortener.shorten(ShortenRequest.shortenRequest(originalUrl).unwrap())
                                        .await()
                                        .unwrap()
                                        .shortCode();

            urlShortener.resolve(ResolveRequest.resolveRequest(shortCode).unwrap())
                        .await()
                        .onFailureRun(() -> fail("Expected success"))
                        .onSuccess(response -> assertThat(response.originalUrl()).isEqualTo(originalUrl));
        }
    }
}
