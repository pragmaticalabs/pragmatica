/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.lang.vo;

import java.text.Normalizer;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.vo.Password.CharacterClasses;
import org.pragmatica.lang.vo.Password.PasswordError;
import org.pragmatica.lang.vo.Password.Policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class PasswordTest {
    private static final String SECRET = "hunter-SECRET-value";

    private static Policy policy(int min, int max, int bytes, CharacterClasses classes, Option<Normalizer.Form> norm) {
        return Policy.policy(min, max, bytes, classes, norm)
                     .fold(c -> fail("policy: " + c.message()), p -> p);
    }

    private static Policy lengths(int min, int max, int bytes) {
        return policy(min, max, bytes, CharacterClasses.none(), Option.none());
    }

    private static Policy classes(boolean lower, boolean upper, boolean digit, boolean symbol) {
        return policy(1, 64, 256, CharacterClasses.characterClasses(lower, upper, digit, symbol), Option.none());
    }

    private static Cause failure(String raw, Policy policy) {
        return Password.password(raw, policy)
                       .fold(c -> c, _ -> fail("expected failure for: " + raw));
    }

    private static Password success(String raw, Policy policy) {
        return Password.password(raw, policy)
                       .fold(c -> fail("expected success: " + c.message()), p -> p);
    }

    @Nested
    class Length {
        @Test
        void password_success_atMinimum() {
            assertEquals("12345678", success("12345678", Policy.defaultPolicy()).value());
        }

        @Test
        void password_failure_belowMinimum() {
            var cause = failure("1234567", Policy.defaultPolicy());

            assertEquals(new PasswordError.TooShort(8, cause.message()), cause);
            assertEquals(PasswordError.TooShort.tooShort(8), cause);
        }

        @Test
        void password_success_atMaximum() {
            success("a".repeat(64), Policy.defaultPolicy());
        }

        @Test
        void password_failure_aboveMaximum() {
            assertEquals(PasswordError.TooLong.tooLong(64), failure("a".repeat(65), Policy.defaultPolicy()));
        }

        @Test
        void password_success_surrogatePairCountsAsOneCodePoint() {
            // 4 emoji = 4 code points = 8 UTF-16 chars; min 4 passes only if counted by code points
            success("😀".repeat(4), lengths(4, 4, 64));
        }

        @Test
        void password_failure_surrogatePairsBelowMinimumByCodePoints() {
            assertEquals(PasswordError.TooShort.tooShort(5), failure("😀".repeat(4), lengths(5, 64, 256)));
        }

        @Test
        void password_success_spacesAndUnicodeAllowed() {
            success("correct horse battery é", Policy.defaultPolicy());
        }

        @Test
        void password_success_isNotTrimmed() {
            assertEquals("  1234567  ", success("  1234567  ", Policy.defaultPolicy()).value());
        }
    }

    @Nested
    class Utf8Bytes {
        @Test
        void password_success_atByteLimit() {
            success("a".repeat(72), lengths(1, 100, 72));
        }

        @Test
        void password_failure_asciiAboveByteLimit() {
            assertEquals(PasswordError.TooManyBytes.tooManyBytes(72), failure("a".repeat(73), lengths(1, 100, 72)));
        }

        @Test
        void password_failure_multiByteExceedsBytesWithinCodePoints() {
            // 40 x 2-byte code points: 40 code points <= 100, 80 bytes > 72
            assertEquals(PasswordError.TooManyBytes.tooManyBytes(72), failure("é".repeat(40), lengths(1, 100, 72)));
        }

        @Test
        void password_success_multiByteWithinBytes() {
            success("é".repeat(36), lengths(1, 100, 72));
        }
    }

    @Nested
    class Classes {
        @Test
        void password_success_classesOffByDefault() {
            success("aaaaaaaa", Policy.defaultPolicy());
        }

        @Test
        void password_failure_missingLowercase() {
            assertEquals(PasswordError.General.MISSING_LOWERCASE, failure("ABC123!!", classes(true, false, false, false)));
            success("aBC123!!", classes(true, false, false, false));
        }

        @Test
        void password_failure_missingUppercase() {
            assertEquals(PasswordError.General.MISSING_UPPERCASE, failure("abc123!!", classes(false, true, false, false)));
            success("abC123!!", classes(false, true, false, false));
        }

        @Test
        void password_failure_missingDigit() {
            assertEquals(PasswordError.General.MISSING_DIGIT, failure("abcDEF!!", classes(false, false, true, false)));
            success("abcDEF1!", classes(false, false, true, false));
        }

        @Test
        void password_failure_missingSymbol() {
            assertEquals(PasswordError.General.MISSING_SYMBOL, failure("abcDEF12", classes(false, false, false, true)));
            success("abcDEF1!", classes(false, false, false, true));
        }

        @Test
        void password_failure_spaceIsNotASymbol() {
            assertEquals(PasswordError.General.MISSING_SYMBOL, failure("abc DEF 12", classes(false, false, false, true)));
        }

        @Test
        void password_success_allClassesTogether() {
            success("aB3!", classes(true, true, true, true));
        }
    }

    @Nested
    class Normalisation {
        private static final String DECOMPOSED = "é";
        private static final String COMPOSED = "é";

        @Test
        void password_success_normalisationOffKeepsRaw() {
            assertEquals(DECOMPOSED, success(DECOMPOSED, lengths(1, 64, 256)).value());
        }

        @Test
        void password_success_nfcAppliedWhenEnabled() {
            var nfc = policy(1, 64, 256, CharacterClasses.none(), Option.some(Normalizer.Form.NFC));

            assertEquals(COMPOSED, success(DECOMPOSED, nfc).value());
        }

        @Test
        void password_failure_lengthCheckedAfterNormalisation() {
            // 2 code points raw, 1 after NFC: min 2 passes raw, fails when normalised
            var nfc = policy(2, 64, 256, CharacterClasses.none(), Option.some(Normalizer.Form.NFC));

            assertEquals(PasswordError.TooShort.tooShort(2), failure(DECOMPOSED, nfc));
            success(DECOMPOSED, lengths(2, 64, 256));
        }

        @Test
        void presented_success_appliesPolicyNormalisation() {
            var nfc = policy(1, 64, 256, CharacterClasses.none(), Option.some(Normalizer.Form.NFC));

            assertEquals(COMPOSED,
                         Password.presented(DECOMPOSED, nfc).fold(c -> fail(c.message()), p -> p.value()));
        }

        @Test
        void presented_success_withPolicyAppliesNoLengthRule() {
            assertEquals("a", Password.presented("a", Policy.defaultPolicy()).fold(c -> fail(c.message()), p -> p.value()));
        }
    }

    @Nested
    class Blank {
        @Test
        void password_failure_nullEmptyBlank() {
            assertEquals(PasswordError.General.BLANK, failure(null, Policy.defaultPolicy()));
            assertEquals(PasswordError.General.BLANK, failure("", Policy.defaultPolicy()));
            assertEquals(PasswordError.General.BLANK, failure("        ", Policy.defaultPolicy()));
        }

        @Test
        void presented_failure_nullEmptyBlank() {
            Password.presented(null).onSuccess(_ -> fail()).onFailure(c -> assertEquals(PasswordError.General.BLANK, c));
            Password.presented("").onSuccess(_ -> fail()).onFailure(c -> assertEquals(PasswordError.General.BLANK, c));
            Password.presented("  ").onSuccess(_ -> fail()).onFailure(c -> assertEquals(PasswordError.General.BLANK, c));
        }

        @Test
        void presented_success_shortPasswordAccepted() {
            Password.presented("x").onFailure(c -> fail(c.message())).onSuccess(p -> assertEquals("x", p.value()));
        }

        @Test
        void presented_success_isNotTrimmed() {
            Password.presented("  pw  ").onFailure(c -> fail(c.message())).onSuccess(p -> assertEquals("  pw  ", p.value()));
        }

        @Test
        void presented_success_overlongPasswordAccepted() {
            Password.presented("a".repeat(1000)).onFailure(c -> fail(c.message()));
        }
    }

    @Nested
    class PolicyParsing {
        @Test
        void policy_failure_minBelowOne() {
            assertEquals(PasswordError.General.INVALID_POLICY, policyFailure(0, 10, 10));
        }

        @Test
        void policy_failure_maxBelowMin() {
            assertEquals(PasswordError.General.INVALID_POLICY, policyFailure(10, 9, 100));
        }

        @Test
        void policy_failure_bytesBelowOne() {
            assertEquals(PasswordError.General.INVALID_POLICY, policyFailure(1, 10, 0));
        }

        @Test
        void policy_success_oneByteIsEnough() {
            lengths(1, 1, 1);
        }

        @Test
        void policy_success_minEqualsMax() {
            lengths(5, 5, 5);
        }

        @Test
        void policy_defaultIsClassFreeAndNotNormalising() {
            var policy = Policy.defaultPolicy();

            assertEquals(CharacterClasses.none(), policy.classes());
            assertTrue(policy.normalization().isEmpty());
            assertEquals(8, policy.minCodePoints());
            assertEquals(64, policy.maxCodePoints());
        }

        private Cause policyFailure(int min, int max, int bytes) {
            return Policy.policy(min, max, bytes, CharacterClasses.none(), Option.none())
                         .fold(c -> c, _ -> fail("expected failure"));
        }
    }

    @Nested
    class Failures {
        @Test
        void tooShort_carriesMinAndMessage() {
            var cause = (PasswordError.TooShort) failure("12", lengths(5, 9, 100));

            assertEquals(5, cause.min());
            assertEquals("Password must have at least 5 characters", cause.message());
        }

        @Test
        void tooLong_carriesMaxAndMessage() {
            var cause = (PasswordError.TooLong) failure("123456", lengths(1, 5, 256));

            assertEquals(5, cause.max());
            assertEquals("Password must have at most 5 characters", cause.message());
        }

        @Test
        void tooManyBytes_carriesMaxBytesAndMessage() {
            var cause = (PasswordError.TooManyBytes) failure("123456", lengths(1, 64, 5));

            assertEquals(5, cause.maxBytes());
            assertEquals("Password must be at most 5 bytes in UTF-8", cause.message());
        }

        @Test
        void general_messagesAreFixed() {
            assertEquals("Password must not be null or blank", PasswordError.General.BLANK.message());
            assertEquals("Password must contain a lower-case letter", PasswordError.General.MISSING_LOWERCASE.message());
            assertEquals("Password must contain an upper-case letter", PasswordError.General.MISSING_UPPERCASE.message());
            assertEquals("Password must contain a digit", PasswordError.General.MISSING_DIGIT.message());
            assertEquals("Password must contain a symbol", PasswordError.General.MISSING_SYMBOL.message());
            assertEquals("Password policy bounds are inconsistent", PasswordError.General.INVALID_POLICY.message());
        }
    }

    @Nested
    class Equality {
        // Constant-time comparison itself is not unit-testable; these pin the observable contract only.
        @Test
        void equals_sameTextIsEqual() {
            assertEquals(new Password("s3cret-value"), new Password("s3cret-value"));
        }

        @Test
        void equals_differentTextIsNotEqual() {
            assertNotEquals(new Password("s3cret-value"), new Password("s3cret-valuf"));
            assertNotEquals(new Password("s3cret-value"), new Password("s3cret-valu"));
            assertNotEquals(new Password("s3cret-value"), "s3cret-value");
        }

        @Test
        void equals_loneSurrogateIsNotQuestionMark() {
            assertNotEquals(new Password("\uD800"), new Password("?"));
        }

        @Test
        void hashCode_doesNotDependOnContent() {
            assertEquals(new Password("one").hashCode(), new Password("another-secret").hashCode());
            assertNotEquals("one".hashCode(), new Password("one").hashCode());
        }
    }

    @Nested
    class Redaction {
        @Test
        void toString_isRedacted() {
            var password = success(SECRET, Policy.defaultPolicy());

            assertFalse(password.toString().contains(SECRET));
            assertEquals("Password[REDACTED]", password.toString());
            assertFalse(Password.presented(SECRET).fold(c -> fail(), Object::toString).contains(SECRET));
        }

        @Test
        void failureMessages_neverContainInput() {
            var tooShort = Policy.policy(100, 200, 400, CharacterClasses.none(), Option.none()).fold(c -> fail(), p -> p);
            var tooLong = lengths(1, 5, 256);
            var tooManyBytes = lengths(1, 64, 5);
            var needsDigit = classes(false, false, true, false);

            for (var policy : new Policy[]{tooShort, tooLong, tooManyBytes, needsDigit}) {
                var cause = failure(SECRET, policy);

                assertFalse(cause.message().contains(SECRET), cause.message());
                assertFalse(cause.toString().contains(SECRET), cause.toString());
            }
        }

        @Test
        void failures_areValidationCauses() {
            assertInstanceOf(Cause.Validation.class, failure("x", Policy.defaultPolicy()));
        }

        @Test
        void policyToString_doesNotContainInput() {
            assertFalse(Policy.defaultPolicy().toString().contains(SECRET));
        }
    }
}
