// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.lang;

import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// JBCT M7-c (#2103): a `Cause.Wrapped` reports its origin's classification unless it overrides it, and so does the bare
/// `Causes.cause(message, source)`. Deleting the defaults turns the delegation tests red.
class CauseWrappedClassificationTest {
    record Wrapper(Cause origin, String message) implements Cause.Wrapped {}

    record Overriding(Cause origin, String message) implements Cause.Wrapped {
        @Override
        public boolean isTerminal() {
            return false;
        }

        @Override
        public boolean isTransient() {
            return false;
        }
    }

    @Test
    void wrapped_aroundATerminalOrigin_isTerminal() {
        var wrapper = new Wrapper(Causes.terminal("gone"), "wrapper");

        assertThat(wrapper.isTerminal()).isTrue();
        assertThat(wrapper.isTransient()).isFalse();
    }

    @Test
    void wrapped_aroundATransientOrigin_isTransient() {
        var wrapper = new Wrapper(Causes.transientCause("timeout"), "wrapper");

        assertThat(wrapper.isTransient()).isTrue();
        assertThat(wrapper.isTerminal()).isFalse();
    }

    @Test
    void wrapped_aroundAnUnclassifiedOrigin_isNeither() {
        var wrapper = new Wrapper(Causes.cause("plain"), "wrapper");

        assertThat(wrapper.isTerminal()).isFalse();
        assertThat(wrapper.isTransient()).isFalse();
    }

    @Test
    void wrapped_withoutAnOrigin_isNeither() {
        var wrapper = new Wrapper(null, "wrapper");

        assertThat(wrapper.isTerminal()).isFalse();
        assertThat(wrapper.isTransient()).isFalse();
    }

    @Test
    void wrapped_aroundAWrapper_reportsTheClassificationAtTheEndOfTheChain() {
        var outer = new Wrapper(new Wrapper(Causes.terminal("gone"), "inner"), "outer");

        assertThat(outer.isTerminal()).isTrue();
    }

    @Test
    void wrapped_thatOverrides_keepsItsOwnAnswerWhateverTheOriginSays() {
        assertThat(new Overriding(Causes.terminal("gone"), "o").isTerminal()).isFalse();
        assertThat(new Overriding(Causes.transientCause("timeout"), "o").isTransient()).isFalse();
    }

    @Test
    void bareCauseWithASource_reportsTheSourcesClassification() {
        assertThat(Causes.cause("outer", some(Causes.terminal("gone"))).isTerminal()).isTrue();
        assertThat(Causes.cause("outer", some(Causes.transientCause("timeout"))).isTransient()).isTrue();
    }

    @Test
    void bareCause_withoutASourceOrWithAnUnclassifiedOne_isNeither() {
        assertThat(Causes.cause("outer", none()).isTerminal()).isFalse();
        assertThat(Causes.cause("outer", none()).isTransient()).isFalse();
        assertThat(Causes.cause("outer", some(Causes.cause("plain"))).isTerminal()).isFalse();
        assertThat(Causes.cause("outer", some(Causes.cause("plain"))).isTransient()).isFalse();
    }
}
