package org.pragmatica.http.routing;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// Unit coverage for the pure #198 §7 header-mode version-selection policy ([VersionSelector]).
///
/// Each branch of §7 is exercised in isolation (no HTTP/routing types): explicit header, unknown
/// version, non-numeric header, missing required header, default-if-missing, and latest-wins.
class VersionSelectorTest {
    private static final String HEADER = "API-Version";

    private static SliceVersionRegistry registry(boolean requireHeader, Option<Integer> defaultVersion) {
        return SliceVersionRegistry.sliceVersionRegistry("/api/orders",
                                                         requireHeader,
                                                         defaultVersion,
                                                         List.of(SliceVersionRegistry.VersionInfo.versionInfo(1, false, Option.none()),
                                                                 SliceVersionRegistry.VersionInfo.versionInfo(2, false, Option.none())));
    }

    private static final Set<Integer> AVAILABLE = Set.of(1, 2);

    @Nested
    class HeaderPresent {
        @Test
        void select_returnsRequestedVersion_whenHeaderNamesAvailableVersion() {
            var result = VersionSelector.select(registry(false, Option.none()), AVAILABLE, HEADER, Option.some("1"));

            result.onFailureRun(() -> { throw new AssertionError("expected success"); })
                  .onSuccess(version -> assertThat(version).isEqualTo(1));
        }

        @Test
        void select_returnsRequestedVersion_evenWhenDefaultDiffers() {
            var result = VersionSelector.select(registry(false, Option.some(2)), AVAILABLE, HEADER, Option.some("1"));

            result.onSuccess(version -> assertThat(version).isEqualTo(1));
        }

        @Test
        void select_failsUnknownVersion_whenHeaderNamesUnavailableVersion() {
            var result = VersionSelector.select(registry(false, Option.none()), AVAILABLE, HEADER, Option.some("9"));

            result.onSuccessRun(() -> { throw new AssertionError("expected failure"); })
                  .onFailure(cause -> assertThat(cause).isInstanceOf(VersionSelectionError.UnknownVersion.class));
        }

        @Test
        void select_failsUnknownVersion_whenHeaderIsNotAnInteger() {
            var result = VersionSelector.select(registry(false, Option.none()), AVAILABLE, HEADER, Option.some("latest"));

            result.onFailure(cause -> assertThat(cause).isInstanceOf(VersionSelectionError.UnknownVersion.class));
        }

        @Test
        void select_ignoresBlankHeader_andAppliesDefault() {
            var result = VersionSelector.select(registry(false, Option.some(2)), AVAILABLE, HEADER, Option.some("  "));

            result.onSuccess(version -> assertThat(version).isEqualTo(2));
        }
    }

    @Nested
    class HeaderAbsent {
        @Test
        void select_failsMissingHeader_whenRequireVersionHeader() {
            var result = VersionSelector.select(registry(true, Option.none()), AVAILABLE, HEADER, Option.none());

            result.onSuccessRun(() -> { throw new AssertionError("expected failure"); })
                  .onFailure(cause -> assertThat(cause).isInstanceOf(VersionSelectionError.MissingVersionHeader.class));
        }

        @Test
        void select_namesConfiguredHeader_inMissingHeaderError() {
            var result = VersionSelector.select(registry(true, Option.none()), AVAILABLE, "X-Api-Version", Option.none());

            result.onFailure(cause -> assertThat(cause.message()).contains("X-Api-Version"));
        }

        @Test
        void select_returnsDefaultVersion_whenSetAndNoHeader() {
            var result = VersionSelector.select(registry(false, Option.some(1)), AVAILABLE, HEADER, Option.none());

            result.onSuccess(version -> assertThat(version).isEqualTo(1));
        }

        @Test
        void select_returnsLatestVersion_whenNoHeaderAndNoDefault() {
            var result = VersionSelector.select(registry(false, Option.none()), AVAILABLE, HEADER, Option.none());

            result.onSuccess(version -> assertThat(version).isEqualTo(2));
        }

        @Test
        void select_fallsBackToLatest_whenDefaultVersionNotAvailable() {
            var result = VersionSelector.select(registry(false, Option.some(9)), AVAILABLE, HEADER, Option.none());

            result.onSuccess(version -> assertThat(version).isEqualTo(2));
        }
    }
}
