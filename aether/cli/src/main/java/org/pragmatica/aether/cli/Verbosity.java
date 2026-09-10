// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import org.pragmatica.lang.Unit;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.config.Configurator;


/// The `-v` verbosity ladder: how many times the top-level `-v` was repeated decides the level
/// applied to [#AETHER_LOGGER].
///
/// The ladder moves `org.pragmatica`, NOT the CLI's own package. A flag that raised only
/// `org.pragmatica.aether.cli` would leave the interesting diagnostics dark: the line that says what
/// was actually sent to a cloud provider is emitted by the provider
/// (`org.pragmatica.aether.environment.hetzner.HetznerComputeProvider`), which is where a 422 has to
/// be diagnosed from.
///
/// Third-party logging is not on the ladder at all: `log4j2.xml` pins Root to OFF, so netty, the
/// cloud SDKs and the http client stay silent even at [#TRACE].
public enum Verbosity {
    /// No `-v`. Matches the `org.pragmatica` level declared in `log4j2.xml`, so an invocation with
    /// no flag behaves exactly as if the ladder did not exist.
    DEFAULT(Level.WARN),
    /// `-v`
    VERBOSE(Level.INFO),
    /// `-vv`
    DEBUG(Level.DEBUG),
    /// `-vvv`, and anything beyond it
    TRACE(Level.TRACE);
    /// The logger the ladder moves. Every Aether and Pragmatica logger sits under it.
    public static final String AETHER_LOGGER = "org.pragmatica";
    private static final Verbosity[] RUNGS = values();
    private final Level level;
    Verbosity(Level level) {
        this.level = level;
    }
    public Level level() {
        return level;
    }
    /// Repeat count to rung. Saturates rather than failing: `-vvvv` and beyond are [#TRACE], which
    /// is already everything there is to say, and a usage error over an extra `v` would be a worse
    /// answer than the most verbose output available.
    public static Verbosity verbosity(int repeatCount) {
        return RUNGS[Math.clamp(repeatCount, 0, RUNGS.length - 1)];
    }
    /// Raise [#AETHER_LOGGER] to this rung on the live logger context.
    ///
    /// Reconfiguring the live context (rather than setting a system property read by `log4j2.xml`)
    /// is what makes this correct regardless of when log4j2 loaded its configuration: by the time
    /// picocli has parsed the top-level options, some CLI plumbing may already have initialised
    /// logging, and a property set after that point would be read by nothing.
    public Unit apply() {
        return Unit.toUnit(Configurator.setLevel(LogManager.getLogger(AETHER_LOGGER), level));
    }
}
