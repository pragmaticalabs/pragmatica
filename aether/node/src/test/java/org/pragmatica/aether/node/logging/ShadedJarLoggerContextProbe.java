// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.logging;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.lang.Promise;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;

/// Subprocess side of [ShadedJarLoggerContextIT]: runs with the SHADED `aether-node.jar` on the
/// classpath and prints what the node's own level mechanism (`Configurator.setLevel`, as
/// `LogLevelRegistry.applyLevel` does) and shutdown flush (`LogManager.getContext(false)`, as
/// `Main.flushLogs` does) can reach for a bridged `System.Logger`. One `KEY=value` per line.
public final class ShadedJarLoggerContextProbe {
    private ShadedJarLoggerContextProbe() {}

    public static void main(String[] args) throws ReflectiveOperationException {
        var name = ResourceFactory.class.getName();
        var jpl = System.getLogger(name);
        var field = jpl.getClass().getDeclaredField("logger");
        field.setAccessible(true);
        var jplContext = ((org.apache.logging.log4j.core.Logger) field.get(jpl)).getContext();
        var mainContext = (LoggerContext) LogManager.getContext(false);

        Configurator.setLevel(name, Level.DEBUG);
        System.out.println("finder=" + System.LoggerFinder.getLoggerFinder().getClass().getName());
        System.out.println("sameContext=" + (jplContext == mainContext));
        System.out.println("jplDebugAfterSetLevel=" + jpl.isLoggable(System.Logger.Level.DEBUG));
        new NothingToClose().close(new Object()).await();
        System.out.flush();
    }

    /// The real producer: the default `close` dispatch on a resource with no close convention emits
    /// `ResourceFactory`'s "No close convention" DEBUG line through `System.Logger`.
    static final class NothingToClose implements ResourceFactory<Object, Object> {
        @Override
        public Class<Object> resourceType() {
            return Object.class;
        }

        @Override
        public Class<Object> configType() {
            return Object.class;
        }

        @Override
        public Promise<Object> provision(Object config) {
            return Promise.success(new Object());
        }
    }
}
