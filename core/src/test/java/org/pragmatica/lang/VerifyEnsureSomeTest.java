package org.pragmatica.lang;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.utils.Causes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class VerifyEnsureSomeTest {
    private static final Cause ABSENT = Causes.cause("absent");
    private static final Cause INVALID = Causes.cause("invalid");

    private static Cause providerCause(Integer value) {
        return Causes.cause("invalid:" + value);
    }

    private static void assertFailsWith(Result<?> result, Cause expected) {
        result.onSuccess(v -> org.junit.jupiter.api.Assertions.fail("expected failure, got " + v))
              .onFailure(cause -> assertSame(expected, cause));
    }

    private static void assertFailsWithMessage(Result<?> result, String expected) {
        result.onSuccess(v -> org.junit.jupiter.api.Assertions.fail("expected failure, got " + v))
              .onFailure(cause -> assertEquals(expected, cause.message()));
    }

    private static void assertSucceedsWith(Result<Integer> result, int expected) {
        result.onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()))
              .onSuccess(v -> assertEquals(expected, v));
    }

    @Test
    void ensureSome_fails_forAbsent() {
        assertFailsWith(Verify.ensureSome(Option.<Integer>none(), Verify.Is::positive, INVALID), INVALID);
    }

    @Test
    void ensureSome_succeeds_forPresentPassing() {
        assertSucceedsWith(Verify.ensureSome(Option.some(5), Verify.Is::positive, INVALID), 5);
    }

    @Test
    void ensureSome_fails_forPresentFailing() {
        assertFailsWith(Verify.ensureSome(Option.some(-5), Verify.Is::positive, INVALID), INVALID);
    }

    @Test
    void ensureSomeWithProvider_failsWithAbsentCause_forAbsent() {
        assertFailsWith(Verify.ensureSome(Option.<Integer>none(), Verify.Is::positive, ABSENT, VerifyEnsureSomeTest::providerCause),
                        ABSENT);
    }

    @Test
    void ensureSomeWithProvider_succeeds_forPresentPassing() {
        assertSucceedsWith(Verify.ensureSome(Option.some(5), Verify.Is::positive, ABSENT, VerifyEnsureSomeTest::providerCause), 5);
    }

    @Test
    void ensureSomeWithProvider_failsWithProvidedCause_forPresentFailing() {
        assertFailsWithMessage(Verify.ensureSome(Option.some(-5), Verify.Is::positive, ABSENT, VerifyEnsureSomeTest::providerCause),
                               "invalid:-5");
    }

    @Test
    void ensureSomeParam1_fails_forAbsent() {
        assertFailsWith(Verify.ensureSome(Option.<Integer>none(), Verify.Is::greaterThan, 3, INVALID), INVALID);
    }

    @Test
    void ensureSomeParam1_succeeds_forPresentPassing() {
        assertSucceedsWith(Verify.ensureSome(Option.some(5), Verify.Is::greaterThan, 3, INVALID), 5);
    }

    @Test
    void ensureSomeParam1_fails_forPresentFailing() {
        assertFailsWith(Verify.ensureSome(Option.some(2), Verify.Is::greaterThan, 3, INVALID), INVALID);
    }

    @Test
    void ensureSomeParam1WithProvider_failsWithAbsentCause_forAbsent() {
        assertFailsWith(Verify.ensureSome(Option.<Integer>none(),
                                          Verify.Is::greaterThan,
                                          3,
                                          ABSENT,
                                          VerifyEnsureSomeTest::providerCause),
                        ABSENT);
    }

    @Test
    void ensureSomeParam1WithProvider_succeeds_forPresentPassing() {
        assertSucceedsWith(Verify.ensureSome(Option.some(5),
                                             Verify.Is::greaterThan,
                                             3,
                                             ABSENT,
                                             VerifyEnsureSomeTest::providerCause),
                           5);
    }

    @Test
    void ensureSomeParam1WithProvider_failsWithProvidedCause_forPresentFailing() {
        assertFailsWithMessage(Verify.ensureSome(Option.some(2),
                                                 Verify.Is::greaterThan,
                                                 3,
                                                 ABSENT,
                                                 VerifyEnsureSomeTest::providerCause),
                               "invalid:2");
    }

    @Test
    void ensureSomeParam2_fails_forAbsent() {
        assertFailsWith(Verify.ensureSome(Option.<Integer>none(), Verify.Is::between, 1, 10, INVALID), INVALID);
    }

    @Test
    void ensureSomeParam2_succeeds_forPresentPassing() {
        assertSucceedsWith(Verify.ensureSome(Option.some(5), Verify.Is::between, 1, 10, INVALID), 5);
    }

    @Test
    void ensureSomeParam2_fails_forPresentFailing() {
        assertFailsWith(Verify.ensureSome(Option.some(11), Verify.Is::between, 1, 10, INVALID), INVALID);
    }

    @Test
    void ensureSomeParam2WithProvider_failsWithAbsentCause_forAbsent() {
        assertFailsWith(Verify.ensureSome(Option.<Integer>none(),
                                          Verify.Is::between,
                                          1,
                                          10,
                                          ABSENT,
                                          VerifyEnsureSomeTest::providerCause),
                        ABSENT);
    }

    @Test
    void ensureSomeParam2WithProvider_succeeds_forPresentPassing() {
        assertSucceedsWith(Verify.ensureSome(Option.some(5),
                                             Verify.Is::between,
                                             1,
                                             10,
                                             ABSENT,
                                             VerifyEnsureSomeTest::providerCause),
                           5);
    }

    @Test
    void ensureSomeParam2WithProvider_failsWithProvidedCause_forPresentFailing() {
        assertFailsWithMessage(Verify.ensureSome(Option.some(11),
                                                 Verify.Is::between,
                                                 1,
                                                 10,
                                                 ABSENT,
                                                 VerifyEnsureSomeTest::providerCause),
                               "invalid:11");
    }

    @Test
    void ensureNone_succeedsWithUnit_forAbsent() {
        Verify.ensureNone(Option.<String>none(), INVALID)
              .onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()))
              .onSuccess(unit -> assertSame(Unit.unit(), unit));
    }

    @Test
    void ensureNone_fails_forPresent() {
        assertFailsWith(Verify.ensureNone(Option.some("x"), INVALID), INVALID);
    }

    @Test
    void ensureNoneWithProvider_succeedsWithUnit_forAbsent() {
        Verify.ensureNone(Option.<String>none(), value -> Causes.cause("unexpected:" + value))
              .onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()))
              .onSuccess(unit -> assertSame(Unit.unit(), unit));
    }

    @Test
    void ensureNoneWithProvider_failsWithCauseFromValue_forPresent() {
        assertFailsWithMessage(Verify.ensureNone(Option.some("x"), value -> Causes.cause("unexpected:" + value)),
                               "unexpected:x");
    }
}
