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

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.vo.Email;
import org.pragmatica.lang.vo.NonBlankString;
import org.pragmatica.lang.vo.Url;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

/// #868 — the validation helpers core owns produce causes carrying [Cause.Validation]; nothing else does.
class ValidationMarkerTest {
    @Test
    void validation_isMarked() {
        assertInstanceOf(Cause.Validation.class, Causes.validation("bad"));
    }

    @Test
    void plainCause_isNotMarked() {
        assertFalse(Causes.cause("bad") instanceof Cause.Validation);
    }

    @Test
    void verifyEnsure_defaultCause_isMarked() {
        assertMarked(Verify.ensure("x", _ -> false));
    }

    @Test
    void email_failure_isMarked() {
        assertMarked(Email.email("not-an-email"));
    }

    @Test
    void url_failure_isMarked() {
        assertMarked(Url.url("no-scheme-or-host"));
    }

    @Test
    void nonBlankString_failure_isMarked() {
        assertMarked(NonBlankString.nonBlankString("  "));
    }

    @Test
    void verifyEnsure_callerSuppliedCause_staysAsSupplied() {
        var supplied = Causes.cause("mine");

        assertSame(supplied, failureOf(Verify.ensure("x", _ -> false, supplied)));
    }

    private static void assertMarked(Result<?> result) {
        assertInstanceOf(Cause.Validation.class, failureOf(result));
    }

    private static Cause failureOf(Result<?> result) {
        return result.fold(cause -> cause, _ -> Causes.cause("unexpected success"));
    }
}
