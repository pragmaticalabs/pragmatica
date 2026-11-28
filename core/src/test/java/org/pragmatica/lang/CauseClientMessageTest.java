// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.lang;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #3723: client text is derived only from `clientMessage()`. A cause that holds another cause derives its client text
/// from the held cause's client text, never from its `message()`, at every nesting depth, while `message()` (the log text)
/// stays exactly what it was. Deleting any derivation turns the sentinel assertions red.
class CauseClientMessageTest {
    private static final String SECRET = "SENTINEL-core-3723-secret";

    record Generic(Cause origin, String message) implements Cause.Wrapped {}

    record Fixed(Cause origin, String message) implements Cause.Wrapped {
        @Override
        public String clientMessage() {
            return message();
        }
    }

    record Formatting(Cause origin, String message, String clientMessage) implements Cause.Wrapped {
        static final Fn1<Formatting, Cause> FACTORY = Causes.forOneCause("Formatted: %s", Formatting::new);
    }

    record Plain(String message) implements Cause {}

    private static Cause thrown() {
        return Causes.fromThrowable(new IllegalStateException(SECRET));
    }

    @Test
    void plainCause_clientMessageIsItsMessage() {
        assertThat(new Plain("plain-x").clientMessage()).isEqualTo("plain-x");
        assertThat(Causes.cause("simple-y").clientMessage()).isEqualTo("simple-y");
    }

    @Test
    void throwableCause_clientTextIsFixed_andMessageKeepsTheTrace() {
        var cause = thrown();

        assertThat(cause.clientMessage()).isEqualTo(Causes.ThrowableCause.CLIENT_TEXT).isEqualTo("Internal error");
        assertThat(cause.message()).contains(SECRET).contains("\tat ");
    }

    @Test
    void wrapped_aroundAThrowable_answersTheOriginsClientText() {
        var wrapper = new Generic(thrown(), "wrapped: " + thrown().message());

        assertThat(wrapper.clientMessage()).isEqualTo("Internal error").doesNotContain(SECRET);
        assertThat(wrapper.message()).contains(SECRET);
    }

    @Test
    void wrapped_aroundACauseThatHidesNothing_keepsItsOwnMessage() {
        var wrapper = new Generic(Causes.cause("origin-text"), "wrapper-text");

        assertThat(wrapper.clientMessage()).isEqualTo("wrapper-text");
    }

    @Test
    void wrapped_withNoOrigin_keepsItsOwnMessage() {
        assertThat(new Generic(null, "alone").clientMessage()).isEqualTo("alone");
    }

    @Test
    void wrapped_insideWrapped_aroundAThrowable_hidesIt() {
        var inner = new Generic(thrown(), "inner " + SECRET);
        var outer = new Generic(inner, "outer " + inner.message());

        assertThat(outer.clientMessage()).isEqualTo("Internal error");
    }

    @Test
    void wrapped_aroundAComposite_answersTheCompositesClientText() {
        var composite = Causes.composite(Causes.cause("plain-member").result(), thrown().result());
        var wrapper = new Generic(composite, "wrapper " + composite.message());

        assertThat(wrapper.clientMessage()).isEqualTo("Composite:\n  plain-member\n  Internal error");
    }

    @Test
    void wrapped_thatDeclaresItsOwnClientText_isNotOverriddenByTheDefault() {
        assertThat(new Fixed(thrown(), "fixed text").clientMessage()).isEqualTo("fixed text");
    }

    @Test
    void composite_clientTextHidesTheThrowable_andMessageDoesNot() {
        var composite = Causes.composite(Causes.cause("a").result(), thrown().result());

        assertThat(composite.clientMessage()).isEqualTo("Composite:\n  a\n  Internal error");
        assertThat(composite.message()).contains(SECRET);
    }

    /// `Result.all` over one failing input is a composite of ONE member: its client text is the member's own, with no
    /// `Composite:` header or indent, and the member's own client text still hides a throwable. `message()` keeps the header.
    @Test
    void composite_ofOneMember_isThatMembersClientText() {
        var plain = Causes.composite(Causes.cause("only member").result());
        var hidden = Causes.composite(thrown().result());

        assertThat(plain.clientMessage()).isEqualTo("only member");
        assertThat(plain.message()).isEqualTo("Composite:\n  only member");
        assertThat(hidden.clientMessage()).isEqualTo("Internal error");
    }

    @Test
    void composite_depthTwo_hidesTheThrowable() {
        var inner = Causes.composite(Causes.cause("inner-plain").result(), thrown().result());
        var outer = Causes.composite(inner.result(), Causes.cause("outer-plain").result());

        assertThat(outer.clientMessage()).doesNotContain(SECRET).contains("inner-plain").contains("outer-plain");
    }

    @Test
    void forOneCause_messageIsTheTemplateOverTheOrigin_clientMessageOverTheOriginsClientText() {
        var origin = thrown();
        var cause = Formatting.FACTORY.apply(origin);

        assertThat(cause.origin()).isSameAs(origin);
        assertThat(cause.message()).isEqualTo("Formatted: " + origin).contains(SECRET);
        assertThat(cause.clientMessage()).isEqualTo("Formatted: Internal error");
    }

    @Test
    void forOneCause_messageIsUnchangedFromTheDataRetainingRung() {
        var origin = thrown();
        Fn1<Generic, Cause> legacy = Causes.forOneValue("Formatted: %s", Generic::new);

        assertThat(Formatting.FACTORY.apply(origin).message()).isEqualTo(legacy.apply(origin).message());
    }

    @Test
    void forOneCause_aroundAPlainCause_clientTextEqualsMessage() {
        var cause = Formatting.FACTORY.apply(Causes.cause("plain"));

        assertThat(cause.clientMessage()).isEqualTo("Formatted: plain");
    }

    @Test
    void forOneCause_twice_carriesNothingOfTheThrowable() {
        var twice = Formatting.FACTORY.apply(Formatting.FACTORY.apply(thrown()));

        assertThat(twice.clientMessage()).isEqualTo("Formatted: Formatted: Internal error");
        assertThat(twice.message()).contains(SECRET);
    }
}
