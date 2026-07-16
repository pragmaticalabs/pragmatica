package org.pragmatica.http.routing;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.http.routing.SliceVersionRegistry.VersionInfo.versionInfo;

/// Unit coverage for the pure #198 §8.2 deprecation/sunset/successor header policy
/// ([VersionResponseHeaders]).
///
/// Each header is exercised in isolation: deprecation flag, sunset date reformatting (bare-date and
/// full-instant forms, malformed omitted), successor `Link` selection (highest non-deprecated above
/// the served version), and version-agnostic link-path derivation across path/header mounting.
class VersionResponseHeadersTest {
    private static SliceVersionRegistry registry(SliceVersionRegistry.VersionInfo... versions) {
        return SliceVersionRegistry.sliceVersionRegistry("/api/orders", false, Option.none(), List.of(versions));
    }

    @Nested
    class Deprecation {
        @Test
        void headers_addsDeprecationTrue_whenServedVersionDeprecated() {
            var headers = VersionResponseHeaders.headers(registry(versionInfo(1, true, Option.none())),
                                                         1,
                                                         "/api/orders/v1/list");

            assertThat(headers).containsEntry("Deprecation", "true");
        }

        @Test
        void headers_omitsDeprecation_whenServedVersionNotDeprecated() {
            var headers = VersionResponseHeaders.headers(registry(versionInfo(1, false, Option.none())),
                                                         1,
                                                         "/api/orders/v1/list");

            assertThat(headers).doesNotContainKey("Deprecation");
        }

        @Test
        void headers_empty_whenServedVersionUnknownToRegistry() {
            var headers = VersionResponseHeaders.headers(registry(versionInfo(1, true, Option.none())),
                                                         7,
                                                         "/api/orders/v7/list");

            assertThat(headers).isEmpty();
        }
    }

    @Nested
    class Sunset {
        @Test
        void headers_formatsBareIsoDate_asRfc1123Gmt() {
            var headers = VersionResponseHeaders.headers(registry(versionInfo(1, true, Option.some("2026-12-31"))),
                                                         1,
                                                         "/api/orders/v1/list");

            assertThat(headers).containsEntry("Sunset", "Thu, 31 Dec 2026 00:00:00 GMT");
        }

        @Test
        void headers_formatsFullIsoInstant_asRfc1123Gmt() {
            var headers = VersionResponseHeaders.headers(registry(versionInfo(1, true, Option.some("2026-12-31T12:30:00Z"))),
                                                         1,
                                                         "/api/orders/v1/list");

            assertThat(headers).containsEntry("Sunset", "Thu, 31 Dec 2026 12:30:00 GMT");
        }

        @Test
        void headers_omitsSunset_whenDateUnparseable() {
            var headers = VersionResponseHeaders.headers(registry(versionInfo(1, true, Option.some("not-a-date"))),
                                                         1,
                                                         "/api/orders/v1/list");

            assertThat(headers).doesNotContainKey("Sunset");
        }

        @Test
        void headers_omitsSunset_whenNoSunsetDeclared() {
            var headers = VersionResponseHeaders.headers(registry(versionInfo(1, true, Option.none())),
                                                         1,
                                                         "/api/orders/v1/list");

            assertThat(headers).doesNotContainKey("Sunset");
        }
    }

    @Nested
    class SuccessorLink {
        @Test
        void headers_addsSuccessorLink_toHighestNonDeprecatedAbove() {
            var headers = VersionResponseHeaders.headers(registry(versionInfo(1, true, Option.none()),
                                                                  versionInfo(2, false, Option.none()),
                                                                  versionInfo(3, false, Option.none())),
                                                         1,
                                                         "/api/orders/v1/list");

            assertThat(headers).containsEntry("Link", "</api/orders/v3/list>; rel=\"successor-version\"");
        }

        @Test
        void headers_skipsDeprecatedSuccessor_choosingHighestNonDeprecated() {
            var headers = VersionResponseHeaders.headers(registry(versionInfo(1, true, Option.none()),
                                                                  versionInfo(2, false, Option.none()),
                                                                  versionInfo(3, true, Option.none())),
                                                         1,
                                                         "/api/orders/v1/list");

            assertThat(headers).containsEntry("Link", "</api/orders/v2/list>; rel=\"successor-version\"");
        }

        @Test
        void headers_omitsSuccessorLink_whenNoHigherNonDeprecatedVersion() {
            var headers = VersionResponseHeaders.headers(registry(versionInfo(1, false, Option.none()),
                                                                  versionInfo(2, true, Option.none())),
                                                         2,
                                                         "/api/orders/v2/list");

            assertThat(headers).doesNotContainKey("Link");
        }

        @Test
        void headers_derivesAgnosticPath_fromHeaderModeBarePath() {
            var headers = VersionResponseHeaders.headers(registry(versionInfo(1, true, Option.none()),
                                                                  versionInfo(2, false, Option.none())),
                                                         1,
                                                         "/api/orders/list");

            assertThat(headers).containsEntry("Link", "</api/orders/v2/list>; rel=\"successor-version\"");
        }
    }
}
