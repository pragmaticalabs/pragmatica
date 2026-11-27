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

import java.io.PrintWriter;
import java.io.StringWriter;

import org.pragmatica.lang.Result;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// #3723: [Causes#fromThrowable] answers a distinguishable [Causes.ThrowableCause] so a client-body renderer can tell a caught
/// throwable from a cause someone wrote text for. Its `message()` is unchanged: the printed stack trace, for logs and every
/// existing caller.
class CausesFromThrowableTest {
    private static final String SENTINEL = "SENTINEL-3723-core";

    private static StringWriter printed(Throwable throwable) {
        var writer = new StringWriter();

        throwable.printStackTrace(new PrintWriter(writer));

        return writer;
    }

    @Test
    void fromThrowable_isTheDistinguishableType() {
        assertInstanceOf(Causes.ThrowableCause.class, Causes.fromThrowable(new IllegalStateException(SENTINEL)));
    }

    @Test
    void fromThrowable_messageIsThePrintedStackTrace_unchanged() {
        var throwable = new IllegalStateException(SENTINEL, new RuntimeException("inner-cause"));

        assertEquals(printed(throwable).toString(), Causes.fromThrowable(throwable).message());
    }

    @Test
    void fromThrowable_behavesAsABareCauseForEveryExistingCaller() {
        var cause = Causes.fromThrowable(new IllegalStateException(SENTINEL));

        assertTrue(cause.source().isEmpty());
        assertFalse(cause.isTerminal());
        assertFalse(cause.isTransient());
        assertTrue(cause.toString().startsWith("Cause: \n  "));
        assertTrue(cause.toString().contains(SENTINEL));
    }

    @Test
    void fromThrowable_liftedFailureCarriesTheDistinguishableType() {
        Result.lift(Causes::fromThrowable, () -> {
                  throw new IllegalStateException(SENTINEL);
              })
              .onSuccess(_ -> fail("must fail"))
              .onFailure(cause -> assertInstanceOf(Causes.ThrowableCause.class, cause));
    }

    @Test
    void bareCauses_areNotThrowableCauses() {
        Object bare = Causes.cause(SENTINEL);
        Object composite = Causes.composite();

        assertFalse(bare instanceof Causes.ThrowableCause);
        assertFalse(composite instanceof Causes.ThrowableCause);
    }
}
