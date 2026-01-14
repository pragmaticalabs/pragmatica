package org.pragmatica.jbct.slice.routing;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorTypeMatcherTest {

    @Nested
    class ExactMatch {

        @Test
        void matches_returnsTrue_forExactMatch() {
            assertThat(ErrorTypeMatcher.matches("UserNotFound", "UserNotFound")).isTrue();
        }

        @Test
        void matches_returnsFalse_forDifferentNames() {
            assertThat(ErrorTypeMatcher.matches("UserNotFound", "OrderNotFound")).isFalse();
        }

        @Test
        void matches_returnsFalse_forPartialMatch() {
            assertThat(ErrorTypeMatcher.matches("UserNotFoundError", "UserNotFound")).isFalse();
        }

        @Test
        void matches_returnsTrue_forEmptyPatternAndEmptyType() {
            assertThat(ErrorTypeMatcher.matches("", "")).isTrue();
        }
    }

    @Nested
    class PrefixMatch {

        @Test
        void matches_returnsTrue_forPrefixPattern() {
            assertThat(ErrorTypeMatcher.matches("UserNotFound", "User*")).isTrue();
        }

        @Test
        void matches_returnsTrue_forPrefixWithLongerType() {
            assertThat(ErrorTypeMatcher.matches("UserNotFoundError", "User*")).isTrue();
        }

        @Test
        void matches_returnsFalse_forNonMatchingPrefix() {
            assertThat(ErrorTypeMatcher.matches("OrderNotFound", "User*")).isFalse();
        }

        @Test
        void matches_returnsTrue_forExactPrefixMatch() {
            assertThat(ErrorTypeMatcher.matches("User", "User*")).isTrue();
        }
    }

    @Nested
    class SuffixMatch {

        @Test
        void matches_returnsTrue_forSuffixPattern() {
            assertThat(ErrorTypeMatcher.matches("UserNotFound", "*NotFound")).isTrue();
        }

        @Test
        void matches_returnsTrue_forDifferentPrefixWithSameSuffix() {
            assertThat(ErrorTypeMatcher.matches("OrderNotFound", "*NotFound")).isTrue();
        }

        @Test
        void matches_returnsFalse_forNonMatchingSuffix() {
            assertThat(ErrorTypeMatcher.matches("UserNotFoundError", "*NotFound")).isFalse();
        }

        @Test
        void matches_returnsTrue_forExactSuffixMatch() {
            assertThat(ErrorTypeMatcher.matches("NotFound", "*NotFound")).isTrue();
        }
    }

    @Nested
    class ContainsMatch {

        @Test
        void matches_returnsTrue_forContainsPattern() {
            assertThat(ErrorTypeMatcher.matches("UserNotFoundError", "*NotFound*")).isTrue();
        }

        @Test
        void matches_returnsTrue_whenLiteralAtStart() {
            assertThat(ErrorTypeMatcher.matches("NotFoundError", "*NotFound*")).isTrue();
        }

        @Test
        void matches_returnsTrue_whenLiteralAtEnd() {
            assertThat(ErrorTypeMatcher.matches("UserNotFound", "*NotFound*")).isTrue();
        }

        @Test
        void matches_returnsTrue_forExactContainsMatch() {
            assertThat(ErrorTypeMatcher.matches("NotFound", "*NotFound*")).isTrue();
        }

        @Test
        void matches_returnsFalse_whenLiteralNotContained() {
            assertThat(ErrorTypeMatcher.matches("InvalidEmail", "*NotFound*")).isFalse();
        }
    }

    @Nested
    class CaseSensitivity {

        @Test
        void matches_returnsFalse_forDifferentCase() {
            assertThat(ErrorTypeMatcher.matches("usernotfound", "UserNotFound")).isFalse();
        }

        @Test
        void matches_returnsFalse_forDifferentCaseWithPrefixPattern() {
            assertThat(ErrorTypeMatcher.matches("userNotFound", "User*")).isFalse();
        }

        @Test
        void matches_returnsFalse_forDifferentCaseWithSuffixPattern() {
            assertThat(ErrorTypeMatcher.matches("UserNOTFOUND", "*NotFound")).isFalse();
        }

        @Test
        void matches_returnsFalse_forDifferentCaseWithContainsPattern() {
            assertThat(ErrorTypeMatcher.matches("USERNOTFOUNDERROR", "*NotFound*")).isFalse();
        }
    }

    @Nested
    class NullAndEdgeCases {

        @Test
        void matches_returnsFalse_forNullTypeName() {
            assertThat(ErrorTypeMatcher.matches(null, "UserNotFound")).isFalse();
        }

        @Test
        void matches_returnsFalse_forNullPattern() {
            assertThat(ErrorTypeMatcher.matches("UserNotFound", null)).isFalse();
        }

        @Test
        void matches_returnsFalse_forBothNull() {
            assertThat(ErrorTypeMatcher.matches(null, null)).isFalse();
        }
    }

    @Nested
    class ExtractLiteral {

        @Test
        void extractLiteral_returnsLiteral_forPrefixPattern() {
            assertThat(ErrorTypeMatcher.extractLiteral("User*")).isEqualTo("User");
        }

        @Test
        void extractLiteral_returnsLiteral_forSuffixPattern() {
            assertThat(ErrorTypeMatcher.extractLiteral("*NotFound")).isEqualTo("NotFound");
        }

        @Test
        void extractLiteral_returnsLiteral_forContainsPattern() {
            assertThat(ErrorTypeMatcher.extractLiteral("*NotFound*")).isEqualTo("NotFound");
        }

        @Test
        void extractLiteral_returnsFullString_forExactPattern() {
            assertThat(ErrorTypeMatcher.extractLiteral("UserNotFound")).isEqualTo("UserNotFound");
        }

        @Test
        void extractLiteral_returnsEmpty_forNullPattern() {
            assertThat(ErrorTypeMatcher.extractLiteral(null)).isEmpty();
        }

        @Test
        void extractLiteral_returnsEmpty_forEmptyPattern() {
            assertThat(ErrorTypeMatcher.extractLiteral("")).isEmpty();
        }

        @Test
        void extractLiteral_returnsEmpty_forWildcardOnly() {
            assertThat(ErrorTypeMatcher.extractLiteral("*")).isEmpty();
        }

        @Test
        void extractLiteral_returnsEmpty_forDoubleWildcard() {
            assertThat(ErrorTypeMatcher.extractLiteral("**")).isEmpty();
        }
    }
}
