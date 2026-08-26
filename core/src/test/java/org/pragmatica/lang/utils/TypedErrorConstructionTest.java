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
package org.pragmatica.lang.utils;

import java.util.Locale;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Verify;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// The typed-error construction idiom (`core/docs/typed-error-construction.md`).
///
/// Message-text assertions are deliberate here and only here: this suite tests the FORMATTER —
/// the template machinery itself — which is the one sanctioned place to look at rendered text.
/// Domain tests assert on cause type and data components instead.
class TypedErrorConstructionTest {
    sealed interface TransferError extends Cause {
        record ExceededLimit(long requested, long limit, String message) implements TransferError {
            static final Fn2<ExceededLimit, Long, Long> FACTORY =
                Causes.forTwoValues("Requested %d exceeds limit %d", ExceededLimit::new);
        }

        record InvalidEmail(String raw, String message) implements TransferError {
            static final Fn1<InvalidEmail, String> FACTORY =
                Causes.forOneValue("Invalid email: %s", InvalidEmail::new);
        }

        record MessageOnly(String message) implements TransferError {
            static final Fn1<MessageOnly, String> FACTORY =
                Causes.forOneValue("Rejected: %s", MessageOnly::new);
        }

        record PaymentFailed(Cause origin, String message) implements TransferError, Cause.Wrapped {
            static final Fn1<PaymentFailed, Cause> FACTORY =
                Causes.forOneValue("Payment step failed: %s", PaymentFailed::new);
        }

        record Fatal(String message) implements TransferError, Cause.Terminal {}
    }

    @Test
    void dataCarryingFactory_retainsComponents_andFormatsFromThem() {
        var cause = TransferError.ExceededLimit.FACTORY.apply(150L, 100L);

        assertEquals(150L, cause.requested());
        assertEquals(100L, cause.limit());
        assertEquals("Requested 150 exceeds limit 100", cause.message());
    }

    /// The constructor-reference overloads resolve by arity: a message-only record's canonical
    /// constructor (arity 1) picks the `Fn1<C, String>` rung; a data-carrying record's (arity 2)
    /// picks the `Fn2<C, T, String>` rung. Both compiling against the same method name IS the
    /// disambiguation claim of the specification.
    @Test
    void overloadResolution_picksRungByConstructorArity() {
        assertEquals("Rejected: nope", TransferError.MessageOnly.FACTORY.apply("nope").message());

        var invalid = TransferError.InvalidEmail.FACTORY.apply("a@b");

        assertEquals("a@b", invalid.raw());
        assertEquals("Invalid email: a@b", invalid.message());
    }

    /// Formatting pins `Locale.ROOT`: a grouping conversion renders identically whatever the JVM
    /// default locale is. Without the pin, `%,d` under a German default renders `1.234.567`.
    @Test
    void formatting_pinsRootLocale_againstDefaultLocaleDrift() {
        var saved = Locale.getDefault();

        try {
            Locale.setDefault(Locale.GERMANY);

            var cause = Causes.forOneValue("Total %,d", (Fn2<TransferError.MessageOnly, Long, String>)
                                                        (value, message) -> new TransferError.MessageOnly(message))
                              .apply(1_234_567L);

            assertEquals("Total 1,234,567", cause.message());
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    void terminalMixin_classifiesWithoutOverride() {
        assertTrue(new TransferError.Fatal("gone").isTerminal());
        assertFalse(TransferError.MessageOnly.FACTORY.apply("x").isTerminal());
    }

    /// `%s` renders a `Cause` argument through its `toString()`, not `message()` — interfaces
    /// cannot default `toString()`, so this is inherent. A wrap template that wants the origin's
    /// MESSAGE embedded formats `origin.message()` in a hand-rolled factory line; the template
    /// rung is exact only for values whose `toString()` is the intended rendering.
    @Test
    void wrappedMixin_suppliesSourceFromOriginComponent() {
        var origin = Causes.cause("db down");
        var wrapped = TransferError.PaymentFailed.FACTORY.apply(origin);

        assertEquals(Option.some(origin), wrapped.source());
        assertTrue(wrapped.message().startsWith("Payment step failed: "));
        assertTrue(wrapped.message().contains("db down"));
    }

    /// Records the `Option.option` decision: a null origin yields an ABSENT source, never a
    /// present-but-null one.
    @Test
    void wrappedMixin_nullOrigin_yieldsAbsentSource() {
        assertEquals(Option.none(), new TransferError.PaymentFailed(null, "m").source());
    }

    /// Full PECS end to end: the fully-typed factory drops into `Verify.ensure`, `Result.filter`
    /// and `Result.mapError` with no widening and no `::apply` adaptation — this test COMPILING is
    /// the variance claim; the assertions just confirm the values flow.
    @Test
    void typedFactory_dropsIntoCompositionSites_withoutAdaptation() {
        var ensured = Verify.ensure("not-an-email", value -> value.contains("@"),
                                    TransferError.InvalidEmail.FACTORY);

        assertTrue(ensured.isFailure());
        ensured.onFailure(cause -> assertInstanceOf(TransferError.InvalidEmail.class, cause));

        var filtered = org.pragmatica.lang.Result.success("raw")
                                                 .filter(TransferError.InvalidEmail.FACTORY, value -> false);

        assertTrue(filtered.isFailure());

        var mapped = org.pragmatica.lang.Result.<String>failure(Causes.cause("io"))
                                               .mapError(TransferError.PaymentFailed.FACTORY);

        mapped.onFailure(cause -> assertInstanceOf(TransferError.PaymentFailed.class, cause));
    }

    /// The `? super` half: a factory generalised over `Object` serves a `String`-typed site.
    @Test
    void contravariantInput_allowsReusingAGeneralFactory() {
        Fn1<TransferError.MessageOnly, Object> general =
            Causes.forOneValue("Rejected: %s", (Fn1<TransferError.MessageOnly, String>) TransferError.MessageOnly::new);

        var result = Verify.ensure("value", value -> false, general);

        assertTrue(result.isFailure());
    }
}
