// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.netty.buffer.ByteBuf;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

/// #980 — the random-key fallback is a FAIL-OPEN, and this pins that it is LOUD.
///
/// When no cluster secret is available the leg mints a RANDOM admin key. The bootstrap CLI derives its
/// credential from the cluster secret, so it can never match that key: the operator sees
/// `aether cluster bootstrap` take a 401 from a healthy cluster — **exactly the defect #980 exists to
/// fix** — with nothing indicating the derived path was skipped. A substitution that quietly supplies
/// a plausible value in place of a step that did not happen is a shape this project has shipped
/// before, and it passed every internal test when it did.
///
/// The fallback is unreachable via the BOOT PATH — a secretless node does not boot, and
/// `MainClusterSecretStampTest#resolveTls_noClusterSecretAnywhere_failsSoTheNodeCannotBoot` verifies
/// that gate rather than asserting it. It is NOT unreachable in general: `AetherNodeConfig.clusterSecret`
/// is stamped separately, so a SKIPPED STAMP leaves the `Option` empty on a node holding a perfectly
/// good secret — and that stamp's call site is the one hunk no in-JVM test can defend. This WARN is
/// therefore the compensating control for that gap, which is what makes these assertions load-bearing
/// rather than cosmetic.
///
/// **This test is itself an instrument, so it carries a positive control.** The negative assertion
/// (`no WARN when a secret IS present`) is satisfied by an empty capture list, so a renamed logger, a
/// detached appender or a swallowed setup failure would leave it green while examining nothing. The
/// sentinel is emitted through the SAME logger the appender is bound to, before each exercise, and
/// asserted present — without it the class could not fail, which on this project is worse than having
/// no test at all. Log-capture strategy follows `AetherNodeContentStorageWarnBootTest`.
class BootstrapAdminKeyLegFallbackWarnTest {
    private static final String LOGGER_NAME = BootstrapAdminKeyLeg.class.getName();
    private static final String NOT_DERIVED_FRAGMENT = "NO CLUSTER SECRET";
    private static final String CONSEQUENCE_FRAGMENT = "will fail authentication with 401";
    private static final String APPENDER_SENTINEL =
        "positive control: BootstrapAdminKeyLegFallbackWarnTest appender is attached";

    private CapturingAppender appender;
    private LoggerConfig loggerConfig;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        appender = CapturingAppender.create("BootstrapAdminKeyLegFallbackWarnCapture");
        appender.start();
        var ctx = (LoggerContext) LogManager.getContext(false);
        var configuration = ctx.getConfiguration();
        loggerConfig = getOrCreateLoggerConfig(configuration);
        originalLevel = loggerConfig.getLevel();
        loggerConfig.addAppender(appender, Level.WARN, null);
        loggerConfig.setLevel(Level.WARN);
        ctx.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        var ctx = (LoggerContext) LogManager.getContext(false);

        loggerConfig.removeAppender(appender.getName());
        loggerConfig.setLevel(originalLevel);
        ctx.updateLoggers();
        appender.stop();
    }

    /// THE pin. A fail-open must announce itself, at WARN, naming what did not happen AND what will
    /// break because of it — an operator who reads only "generated a random key" learns nothing about
    /// why their bootstrap is about to 401.
    @Test
    void leg_noClusterSecret_warnsThatTheKeyWasNotDerivedAndBootstrapWillFail() {
        emitAppenderSentinel();

        runLeg(Option.none());

        assertAppenderIsLive();
        assertThat(appender.capturedWarns())
            .describedAs("the fallback must say the key was NOT derived")
            .anyMatch(message -> message.contains(NOT_DERIVED_FRAGMENT));
        assertThat(appender.capturedWarns())
            .describedAs("and must name the consequence — a silent fallback reproduces the #980 defect "
                         + "with nothing pointing at the cause")
            .anyMatch(message -> message.contains(CONSEQUENCE_FRAGMENT));
    }

    /// A blank secret is the same condition: `BootstrapContext` defaults the secret to `""`, and
    /// deriving from the empty string would hand every such cluster one publicly-computable ADMIN key.
    @Test
    void leg_blankClusterSecret_warnsTheSameWay() {
        emitAppenderSentinel();

        runLeg(Option.some("   "));

        assertAppenderIsLive();
        assertThat(appender.capturedWarns()).anyMatch(message -> message.contains(NOT_DERIVED_FRAGMENT));
    }

    /// The complement, and the reason the sentinel exists: on the normal derived path the warning must
    /// NOT fire. A fail-open warning that cries wolf on every healthy cluster gets filtered out, and
    /// then the one that matters is invisible.
    @Test
    void leg_clusterSecretPresent_doesNotWarn() {
        emitAppenderSentinel();

        runLeg(Option.some("fallback-warn-test-cluster-secret"));

        assertAppenderIsLive();
        assertThat(appender.capturedWarns())
            .describedAs("the derived path is the healthy one and must stay quiet")
            .noneMatch(message -> message.contains(NOT_DERIVED_FRAGMENT));
    }

    private static void runLeg(Option<String> clusterSecret) {
        var store = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        var result = BootstrapAdminKeyLeg.bootstrapAdminKeyLeg(() -> store,
                                                                () -> true,
                                                                commands -> applyTo(store, commands),
                                                                clusterSecret)
                                          .get()
                                          .await();

        assertThat(result.isSuccess())
            .describedAs("precondition: the leg must actually run, or nothing could have been logged")
            .isTrue();
    }

    private static Promise<List<Object>> applyTo(KVStore<AetherKey, AetherValue> store,
                                                 List<KVCommand<AetherKey>> commands) {
        store.process(store.createBatch(commands));

        return Promise.success(List.of());
    }

    /// Emits [#APPENDER_SENTINEL] on the exact logger [#LOGGER_NAME] the appender is bound to, so the
    /// sentinel survives the same capture window the real assertions read.
    private static void emitAppenderSentinel() {
        LogManager.getLogger(LOGGER_NAME).warn(APPENDER_SENTINEL);
    }

    /// Asserts the capture is actually working. Paired with every assertion above, and load-bearing
    /// for the `noneMatch` one, which an empty list would satisfy.
    private void assertAppenderIsLive() {
        assertThat(appender.capturedWarns())
            .describedAs("the appender must be attached to %s, or these assertions examine nothing",
                         LOGGER_NAME)
            .anyMatch(message -> message.contains(APPENDER_SENTINEL));
    }

    private static LoggerConfig getOrCreateLoggerConfig(Configuration configuration) {
        var existing = configuration.getLoggerConfig(LOGGER_NAME);

        if (LOGGER_NAME.equals(existing.getName())) {
            return existing;
        }

        var fresh = new LoggerConfig(LOGGER_NAME, Level.WARN, false);

        configuration.addLogger(LOGGER_NAME, fresh);

        return fresh;
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }

    /// In-memory log4j2 appender capturing WARN-and-above messages for assertions.
    private static final class CapturingAppender extends AbstractAppender {
        private final List<String> messages = new CopyOnWriteArrayList<>();

        private CapturingAppender(String name, Layout<?> layout) {
            super(name, (Filter) null, layout, true, Property.EMPTY_ARRAY);
        }

        static CapturingAppender create(String name) {
            return new CapturingAppender(name, PatternLayout.createDefaultLayout());
        }

        @Override
        public void append(LogEvent event) {
            if (event.getLevel().isMoreSpecificThan(Level.WARN)) {
                messages.add(event.getMessage().getFormattedMessage());
            }
        }

        List<String> capturedWarns() {
            return List.copyOf(messages);
        }
    }
}
