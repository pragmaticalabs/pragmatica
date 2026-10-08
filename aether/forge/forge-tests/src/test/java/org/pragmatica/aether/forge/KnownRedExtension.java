// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.junit.platform.commons.support.AnnotationSupport;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

/// Implements [KnownRed]. The decision is [#classify], a pure function, so it can be pinned without a test engine.
public final class KnownRedExtension implements InvocationInterceptor {
    public enum Verdict {
        /// The failure carries the declared signature: report the test as aborted, naming the ticket.
        ABSORBED,
        /// Any other failure: let it through untouched.
        REAL_FAILURE,
        /// The test passed although it is declared red : fail it, the ticket's fix has landed.
        TRIPWIRE_FLIPPED
    }

    /// `failure == null` means the invocation passed.
    public static Verdict classify(KnownRed known, Throwable failure) {
        if (failure == null) {
            return Verdict.TRIPWIRE_FLIPPED;
        }

        return matches(known, failure) ? Verdict.ABSORBED : Verdict.REAL_FAILURE;
    }

    static boolean matches(KnownRed known, Throwable failure) {
        return known.exception().isInstance(failure) && messages(failure).stream().anyMatch(message -> message.contains(known.messageContains()));
    }

    private static List<String> messages(Throwable failure) {
        var found = new ArrayList<String>();

        for (var t = failure; t != null && found.size() < 10; t = t.getCause() == t ? null : t.getCause()) {
            found.add(String.valueOf(t.getMessage()));
        }

        return found;
    }

    @Override
    public void interceptTestMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        guard(invocation, extensionContext, true);
    }

    @Override
    public void interceptBeforeAllMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        guard(invocation, extensionContext, false);
    }

    @Override
    public void interceptBeforeEachMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        guard(invocation, extensionContext, false);
    }

    private void guard(Invocation<Void> invocation, ExtensionContext context, boolean testBody) throws Throwable {
        var known = AnnotationSupport.findAnnotation(context.getElement(), KnownRed.class)
                                     .or(() -> AnnotationSupport.findAnnotation(context.getTestClass(), KnownRed.class))
                                     .orElse(null);

        if (known == null) {
            invocation.proceed();

            return;
        }

        Throwable failure = null;

        try {
            invocation.proceed();
        } catch (TestAbortedException aborted) {
            throw aborted;
        } catch (Throwable t) {
            failure = t;
        }

        switch (classify(known, failure)) {
            case ABSORBED -> throw new TestAbortedException("known red " + known.ticket() + ": " + failure.getClass().getSimpleName() + ": " + firstLine(failure.getMessage()), failure);
            case REAL_FAILURE -> throw failure;
            case TRIPWIRE_FLIPPED -> {
                if (testBody) {
                    throw new AssertionFailedError(known.ticket() + " is green: the fix has landed, remove @KnownRed from this test");
                }
            }
        }
    }

    private static String firstLine(String message) {
        var text = message == null ? "" : message.strip();
        var end = text.indexOf('\n');
        var line = end < 0 ? text : text.substring(0, end);

        return line.length() > 160 ? line.substring(0, 160) : line;
    }
}
