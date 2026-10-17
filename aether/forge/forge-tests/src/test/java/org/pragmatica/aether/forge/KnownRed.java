// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/// A test that is red for a known, ticketed reason: an ENABLED tripwire, never a `@Disabled` (a disabled test is silence).
///
/// The test keeps asserting the CORRECT behaviour. [KnownRedExtension] gives three outcomes:
///   - the test fails with the declared signature (`exception` instance whose message, or a cause's, contains `messageContains`):
///     reported as ABORTED, i.e. a skipped testcase whose text is `known red #<ticket>: ...`; the nightly lists it in its
///     "Known reds" table and does not fail on it;
///   - the test fails any other way: a REAL failure, so a test broken for a new reason cannot hide behind the old ticket;
///   - the test passes: FAILS with "remove @KnownRed", so the fix landing flips the tripwire. There is deliberately no "tolerate a
///     pass" mode: a tripwire that accepts a pass cannot flip, which makes it a disabled test in disguise.
///
/// Placement: a TEST METHOD (`@Test`, `@RepeatedTest`, `@ParameterizedTest`, `@TestTemplate`, also inside a `@Nested` class), nothing else.
/// The target is `METHOD`, so a class-level use does not compile (a class-level tripwire cannot flip per test, and an absorbed failing
/// `@BeforeAll` leaves no testcase in the report); `KnownRedExtensionTest` pins that target and scans the test classes for the annotation
/// on a method that is not a test. A signature that would absorb everything (blank `messageContains` or `ticket`, or a catch-all exception
/// class) is itself a failure of the annotated test.
///
/// Take the signature from the real failure (the nightly's log), not from the ticket's prose.
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@ExtendWith(KnownRedExtension.class)
public @interface KnownRed {
    /// The ticket, e.g. `#1717`.
    String ticket();

    /// The expected failure's class (matched with `isInstance`); not a catch-all (`Throwable`, `Exception`, `RuntimeException`, `Error`).
    Class<? extends Throwable> exception();

    /// A non-blank substring of the expected failure's message, or of a cause's.
    String messageContains();
}
