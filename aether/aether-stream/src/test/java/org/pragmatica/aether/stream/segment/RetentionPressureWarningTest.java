// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.stream.SegmentTierPressure;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import static org.assertj.core.api.Assertions.assertThat;

/// #1604: at [SegmentTierPressure#WARN_AT] retention warns once per pressure episode, and relieves the pressure
/// (the snapshot-bounded collection) on every pass while it lasts; below it, neither.
class RetentionPressureWarningTest {
    @Test
    void warnsOncePerEpisode_andRelievesOnEveryPassUnderPressure() {
        var utilization = new AtomicReference<>(0.9);
        var reliefs = new AtomicInteger();
        var storage = StorageInstance.storageInstance("pressure", List.of(MemoryTier.memoryTier(1 << 20)));
        var index = new SegmentIndex();
        var enforcer = RetentionEnforcer.retentionEnforcer(storage,
                                                           index,
                                                           60_000L,
                                                           RetentionEnforcer.SegmentRetentionFloor.NONE,
                                                           RetentionEnforcer.FloorDurability.LIVE,
                                                           SegmentReader.segmentReader(storage, index),
                                                           utilization::get,
                                                           reliefs::incrementAndGet);
        var capture = Capture.attach();

        try {
            enforcer.enforceNow().await();
            enforcer.enforceNow().await();
            assertThat(capture.warns).as("one WARN for the episode").hasSize(1);
            assertThat(reliefs.get()).as("relieved on each pass under pressure").isEqualTo(2);

            utilization.set(0.5);
            enforcer.enforceNow().await();
            assertThat(reliefs.get()).as("no relief below the threshold").isEqualTo(2);

            utilization.set(0.9);
            enforcer.enforceNow().await();
            assertThat(capture.warns).as("a new episode warns again").hasSize(2);
        } finally {
            capture.detach();
        }
    }

    private static final class Capture extends AbstractAppender {
        private static final String LOGGER = RetentionEnforcer.class.getName();
        private final List<String> warns = new CopyOnWriteArrayList<>();

        private Capture() {
            super("RetentionPressureCapture", (Filter) null, PatternLayout.createDefaultLayout(), true, Property.EMPTY_ARRAY);
        }

        static Capture attach() {
            var capture = new Capture();
            var ctx = (LoggerContext) LogManager.getContext(false);
            var loggerConfig = new LoggerConfig(LOGGER, Level.ALL, true);

            capture.start();
            ctx.getConfiguration().addLogger(LOGGER, loggerConfig);
            loggerConfig.addAppender(capture, Level.ALL, null);
            ctx.updateLoggers();

            return capture;
        }

        void detach() {
            var ctx = (LoggerContext) LogManager.getContext(false);

            ctx.getConfiguration().removeLogger(LOGGER);
            ctx.updateLoggers();
            stop();
        }

        @Override
        public void append(LogEvent event) {
            if (event.getLevel() == Level.WARN) {
                warns.add(event.getMessage().getFormattedMessage());
            }
        }
    }
}
