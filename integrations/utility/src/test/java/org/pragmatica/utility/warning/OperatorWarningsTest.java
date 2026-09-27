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
package org.pragmatica.utility.warning;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.pragmatica.utility.warning.OperatorWarningCode.CORE_ABSENCE_FENCE;
import static org.pragmatica.utility.warning.OperatorWarningCode.REPLICA_FSYNC_FAILED;

/// `raise` logs and emits from one call, and emission can never take the log line down with it (#1574).
class OperatorWarningsTest {
    private static final String LOGGER_NAME = "org.pragmatica.utility.warning.OperatorWarningsTest.site";
    private static final Logger SITE_LOG = LoggerFactory.getLogger(LOGGER_NAME);

    private final List<OperatorWarning> emitted = new CopyOnWriteArrayList<>();
    private CapturingAppender appender;
    private LoggerContext context;

    @BeforeEach
    void captureLog() {
        context = (LoggerContext) LogManager.getContext(false);
        appender = new CapturingAppender();
        appender.start();

        var loggerConfig = new LoggerConfig(LOGGER_NAME, Level.TRACE, false);

        loggerConfig.addAppender(appender, Level.TRACE, null);
        context.getConfiguration().addLogger(LOGGER_NAME, loggerConfig);
        context.updateLoggers();
    }

    @AfterEach
    void releaseLog() {
        context.getConfiguration().removeLogger(LOGGER_NAME);
        context.updateLoggers();
        appender.stop();
    }

    @Test
    void raise_logsAndEmits_withTheFormattedMessage() {
        OperatorWarnings.raise(SITE_LOG, emitted::add, REPLICA_FSYNC_FAILED, "orders[3]", "sync failed for {}[{}]", "orders", 3);

        assertThat(emitted).containsExactly(OperatorWarning.operatorWarning(REPLICA_FSYNC_FAILED,
                                                                            "orders[3]",
                                                                            "sync failed for orders[3]"));
        assertThat(appender.events).hasSize(1);
        assertThat(appender.events.getFirst().getLevel()).isEqualTo(Level.WARN);
        assertThat(appender.events.getFirst().getMessage().getFormattedMessage())
            .isEqualTo("[replica-fsync-failed] sync failed for orders[3]");
    }

    @Test
    void raise_criticalCode_logsAtError() {
        OperatorWarnings.raise(SITE_LOG, emitted::add, CORE_ABSENCE_FENCE, "core", "fence firing");

        assertThat(appender.events).hasSize(1);
        assertThat(appender.events.getFirst().getLevel()).isEqualTo(Level.ERROR);
        assertThat(appender.events.getFirst().getMessage().getFormattedMessage())
            .isEqualTo("[core-absence-fence] fence firing");
    }

    /// The log is the fallback: a sink that throws must neither propagate nor suppress the log line.
    @Test
    void raise_throwingSink_doesNotThrow_andTheLogLineStands() {
        OperatorWarningSink throwing = OperatorWarningsTest::explode;

        assertThatCode(() -> OperatorWarnings.raise(SITE_LOG, throwing, REPLICA_FSYNC_FAILED, "orders[3]", "sync failed"))
            .doesNotThrowAnyException();
        assertThat(appender.events).hasSize(2);
        assertThat(appender.events.getFirst().getMessage().getFormattedMessage())
            .isEqualTo("[replica-fsync-failed] sync failed");
        assertThat(appender.events.get(1).getLevel()).isEqualTo(Level.WARN);
        assertThat(appender.events.get(1).getMessage().getFormattedMessage())
            .contains("[replica-fsync-failed] operator warning for orders[3] not emitted")
            .contains("event log unavailable");
    }

    @Test
    void raise_logOnlySink_logsAndEmitsNothing() {
        assertThatCode(() -> OperatorWarnings.raise(SITE_LOG,
                                                    OperatorWarningSink.logOnly(),
                                                    REPLICA_FSYNC_FAILED,
                                                    "orders[3]",
                                                    "sync failed"))
            .doesNotThrowAnyException();
        assertThat(appender.events).hasSize(1);
    }

    private static void explode(OperatorWarning warning) {
        throw new IllegalStateException("event log unavailable");
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new CopyOnWriteArrayList<>();

        private CapturingAppender() {
            super("OperatorWarningsCapture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }
}
