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

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;


/// A password that passed a [Policy], or a presented one that is merely non-blank. Core does no
/// hashing: `value()` hands the raw text to the hasher adapter and nowhere else.
///
/// **Two factories, two meanings.** `password(raw, policy)` is for register and change: it enforces
/// the policy. `presented(raw)` is for login: it checks non-blank only, so tightening the policy
/// never locks out an existing user whose stored password predates it.
///
/// **Default policy** ([Policy#defaultPolicy()]): 8 to 64 code points, at most 256 UTF-8 bytes, no
/// character-class rules, no normalisation. Against NIST SP 800-63B-4: a minimum of 8 is the
/// multi-factor floor (the specification requires 15 where the password is the only authenticator,
/// so such a deployment passes its own policy with `minCodePoints = 15`); the maximum of 64 is the
/// floor a verifier SHALL permit; composition rules are SHALL NOT, hence off by default. Unicode and
/// spaces are accepted. A bcrypt deployment passes `maxUtf8Bytes = 72`.
///
/// **Never exposes the raw value.** `toString()` is redacted and every failure carries only policy
/// numbers, never the input. `equals` and `hashCode` are the record defaults and compare the raw
/// text; do not put a Password in a log line through them.
///
/// **Prefer this over a hand-rolled password value object** — see the package doc for the full
/// catalog of built-in value objects.
public record Password(String value) {
    private static final String REDACTED = "Password[REDACTED]";

    /// Length, byte, character-class and normalisation rules as data. Class rules and normalisation
    /// are OFF by default.
    ///
    /// @param minCodePoints minimum length in Unicode code points
    /// @param maxCodePoints maximum length in Unicode code points
    /// @param maxUtf8Bytes  maximum UTF-8 length in bytes (72 for bcrypt)
    /// @param classes       required character classes
    /// @param normalization Unicode normalisation applied before the checks, if any
    public record Policy(int minCodePoints,
                         int maxCodePoints,
                         int maxUtf8Bytes,
                         CharacterClasses classes,
                         Option<Normalizer.Form> normalization) {
        private static final Policy DEFAULT = new Policy(8, 64, 256, CharacterClasses.none(), Option.none());

        /// Parse a policy; bounds must be positive and `minCodePoints <= maxCodePoints`.
        ///
        /// @return Result containing the Policy, or [PasswordError.General#INVALID_POLICY]
        public static Result<Policy> policy(int minCodePoints,
                                            int maxCodePoints,
                                            int maxUtf8Bytes,
                                            CharacterClasses classes,
                                            Option<Normalizer.Form> normalization) {
            return Result.success(new Policy(minCodePoints, maxCodePoints, maxUtf8Bytes, classes, normalization)).filter(PasswordError.General.INVALID_POLICY,
                                                                                                                         Policy::consistent);
        }

        /// The documented default policy, see [Password].
        public static Policy defaultPolicy() {
            return DEFAULT;
        }

        private boolean consistent() {
            return minCodePoints >= 1
                   && maxCodePoints >= minCodePoints
                   && maxUtf8Bytes >= 1;
        }
    }

    /// Optional character-class rules; each flag requires at least one such code point.
    ///
    /// @param lowercase require a lower-case letter
    /// @param uppercase require an upper-case letter
    /// @param digit     require a digit
    /// @param symbol    require a code point that is neither letter, digit nor whitespace
    public record CharacterClasses(boolean lowercase, boolean uppercase, boolean digit, boolean symbol) {
        private static final CharacterClasses NONE = new CharacterClasses(false, false, false, false);

        /// Create character-class rules.
        public static CharacterClasses characterClasses(boolean lowercase,
                                                        boolean uppercase,
                                                        boolean digit,
                                                        boolean symbol) {
            return new CharacterClasses(lowercase, uppercase, digit, symbol);
        }

        /// No class is required (the default).
        public static CharacterClasses none() {
            return NONE;
        }

        private boolean lowercaseOk(String text) {
            return ! lowercase || text.codePoints()
                                      .anyMatch(Character::isLowerCase);
        }

        private boolean uppercaseOk(String text) {
            return ! uppercase || text.codePoints()
                                      .anyMatch(Character::isUpperCase);
        }

        private boolean digitOk(String text) {
            return ! digit || text.codePoints()
                                  .anyMatch(Character::isDigit);
        }

        private boolean symbolOk(String text) {
            return ! symbol || text.codePoints()
                                   .anyMatch(CharacterClasses::isSymbol);
        }

        private static boolean isSymbol(int codePoint) {
            return ! Character.isLetterOrDigit(codePoint) && !Character.isWhitespace(codePoint);
        }
    }

    /// Failures of [Password#password(String, Policy)] and [Policy#policy]. Carry policy numbers only,
    /// never the input.
    public sealed interface PasswordError extends Cause.Validation {
        /// Fewer code points than the policy minimum.
        record TooShort(int min, String message) implements PasswordError {
            static final Fn1<TooShort, Integer> FACTORY = Causes.forOneValue("Password must have at least %s characters",
                                                                             TooShort::new);

            /// Create the failure.
            public static TooShort tooShort(int min) {
                return FACTORY.apply(min);
            }
        }

        /// More code points than the policy maximum.
        record TooLong(int max, String message) implements PasswordError {
            static final Fn1<TooLong, Integer> FACTORY = Causes.forOneValue("Password must have at most %s characters",
                                                                            TooLong::new);

            /// Create the failure.
            public static TooLong tooLong(int max) {
                return FACTORY.apply(max);
            }
        }

        /// More UTF-8 bytes than the policy maximum.
        record TooManyBytes(int maxBytes, String message) implements PasswordError {
            static final Fn1<TooManyBytes, Integer> FACTORY = Causes.forOneValue("Password must be at most %s bytes in UTF-8",
                                                                                 TooManyBytes::new);

            /// Create the failure.
            public static TooManyBytes tooManyBytes(int maxBytes) {
                return FACTORY.apply(maxBytes);
            }
        }

        /// Fixed-text failures.
        enum General implements PasswordError {
            BLANK("Password must not be null or blank"),
            MISSING_LOWERCASE("Password must contain a lower-case letter"),
            MISSING_UPPERCASE("Password must contain an upper-case letter"),
            MISSING_DIGIT("Password must contain a digit"),
            MISSING_SYMBOL("Password must contain a symbol"),
            INVALID_POLICY("Password policy bounds are inconsistent");
            private final String message;
            General(String message) {
                this.message = message;
            }
            @Override
            public String message() {
                return message;
            }
        }
    }

    /// Register or change: enforce the policy.
    ///
    /// @param raw    the raw password, untrimmed
    /// @param policy the rules to enforce
    /// @return Result containing the Password, or a [PasswordError]
    public static Result<Password> password(String raw, Policy policy) {
        return presented(raw, policy).filter(PasswordError.TooShort.tooShort(policy.minCodePoints()),
                                             p -> Verify.Is.codePointsBetween(p.value(),
                                                                              policy.minCodePoints(),
                                                                              Integer.MAX_VALUE))
                        .filter(PasswordError.TooLong.tooLong(policy.maxCodePoints()),
                                p -> Verify.Is.codePointsBetween(p.value(),
                                                                 0,
                                                                 policy.maxCodePoints()))
                        .filter(PasswordError.TooManyBytes.tooManyBytes(policy.maxUtf8Bytes()),
                                p -> Verify.Is.utf8LengthAtMost(p.value(),
                                                                policy.maxUtf8Bytes()))
                        .flatMap(p -> checkClasses(p,
                                                   policy.classes()));
    }

    /// Login: non-blank only, no policy and no normalisation.
    ///
    /// @param raw the raw password, untrimmed
    /// @return Result containing the Password, or [PasswordError.General#BLANK]
    public static Result<Password> presented(String raw) {
        return Verify.ensure(raw, Verify.Is::present, PasswordError.General.BLANK).map(Password::new);
    }

    /// Login under a policy that normalises: non-blank, then the policy's normalisation only, so a
    /// deployment that normalises at registration compares like with like. No length or class rule.
    ///
    /// @param raw    the raw password, untrimmed
    /// @param policy the policy whose normalisation to apply
    /// @return Result containing the Password, or [PasswordError.General#BLANK]
    public static Result<Password> presented(String raw, Policy policy) {
        return Verify.ensure(raw, Verify.Is::present, PasswordError.General.BLANK)
                     .map(v -> normalise(v, policy))
                     .map(Password::new);
    }

    @Override
    public String toString() {
        return REDACTED;
    }

    private static String normalise(String raw, Policy policy) {
        return policy.normalization()
                     .map(form -> Normalizer.normalize(raw, form))
                     .or(raw);
    }

    private static Result<Password> checkClasses(Password password, CharacterClasses classes) {
        return Result.success(password)
                     .filter(PasswordError.General.MISSING_LOWERCASE,
                             p -> classes.lowercaseOk(p.value()))
                     .filter(PasswordError.General.MISSING_UPPERCASE,
                             p -> classes.uppercaseOk(p.value()))
                     .filter(PasswordError.General.MISSING_DIGIT,
                             p -> classes.digitOk(p.value()))
                     .filter(PasswordError.General.MISSING_SYMBOL,
                             p -> classes.symbolOk(p.value()));
    }
}
