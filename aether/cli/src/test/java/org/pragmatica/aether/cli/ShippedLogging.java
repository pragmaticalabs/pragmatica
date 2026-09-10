// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import java.net.URI;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Configurator;
import org.pragmatica.lang.Contract;

/// Makes the CLI's SHIPPED `log4j2.xml` the active configuration for a test.
///
/// This exists because of a repo-wide fact that is easy to miss and silently inverts any test of
/// default logging behaviour: the `test-logging` module puts `log4j2-test.xml` on every test
/// classpath, and log4j2 prefers `log4j2-test.xml` over `log4j2.xml`. So in a test JVM the active
/// configuration is `test-logging`'s (root at INFO, console at SYSTEM_OUT), NEVER the CLI's own —
/// which means a test that simply reads the live configuration is measuring `test-logging`, and a
/// test of "what does a default invocation print" would read INFO as the default and conclude the
/// opposite of the truth.
///
/// `test-logging` is test-scoped, so it is absent from the shaded `aether.jar`; the divergence is
/// confined to test JVMs, which is exactly where it misleads.
sealed interface ShippedLogging {
    /// Classpath location of the configuration that ships inside `aether.jar`.
    String RESOURCE = "/log4j2.xml";

    /// Load the shipped configuration into the live logger context, replacing whatever the test
    /// classpath supplied, and return it.
    static Configuration activate() {
        Configurator.reconfigure(shippedUri());

        return active();
    }

    /// Hand the JVM back to whatever the test classpath would otherwise supply, so a test that
    /// activated the shipped configuration does not leak root-at-OFF into sibling test classes
    /// sharing the fork.
    @Contract
    static void restoreTestDefault() {
        Configurator.reconfigure();
    }

    static Configuration active() {
        return ((LoggerContext) LogManager.getContext(false)).getConfiguration();
    }

    /// Resolved from the classpath rather than from a hard-coded path, so this reads the artifact
    /// the module actually produces.
    static URI shippedUri() {
        return URI.create(String.valueOf(ShippedLogging.class.getResource(RESOURCE)));
    }

    record unused() implements ShippedLogging {}
}
