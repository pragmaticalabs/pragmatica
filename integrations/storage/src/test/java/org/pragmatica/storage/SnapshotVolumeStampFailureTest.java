package org.pragmatica.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.storage.InMemoryMetadataStore.inMemoryMetadataStore;
import static org.pragmatica.storage.SnapshotConfig.snapshotConfig;
import static org.pragmatica.storage.SnapshotManager.snapshotManager;

/// #1473: a sentinel location that cannot be written (here: its parent is a regular file) leaves the snapshot
/// standing and says so once, not on every tick. The guard is the only thing lost, until a later write records it.
class SnapshotVolumeStampFailureTest {
    private static final String LOGGER = DefaultSnapshotManager.class.getName();

    @TempDir
    Path tempDir;

    private final List<String> warns = new CopyOnWriteArrayList<>();
    private AbstractAppender appender;
    private LoggerConfig loggerConfig;
    private Level originalLevel;

    @BeforeEach
    void captureWarns() {
        appender = new AbstractAppender("StampFailureCapture", (Filter) null, PatternLayout.createDefaultLayout(), true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                if (event.getLevel() == Level.WARN) {
                    warns.add(event.getMessage().getFormattedMessage());
                }
            }
        };
        appender.start();
        var ctx = (LoggerContext) LogManager.getContext(false);
        var existing = ctx.getConfiguration().getLoggerConfig(LOGGER);

        loggerConfig = existing.getName().equals(LOGGER) ? existing : new LoggerConfig(LOGGER, Level.ALL, true);
        ctx.getConfiguration().addLogger(LOGGER, loggerConfig);
        originalLevel = loggerConfig.getLevel();
        loggerConfig.addAppender(appender, Level.ALL, null);
        loggerConfig.setLevel(Level.ALL);
        ctx.updateLoggers();
    }

    @AfterEach
    void releaseCapture() {
        var ctx = (LoggerContext) LogManager.getContext(false);

        loggerConfig.removeAppender(appender.getName());
        loggerConfig.setLevel(originalLevel);
        ctx.updateLoggers();
        appender.stop();
    }

    @Test
    void snapshotNow_unwritableSentinelLocation_snapshotStandsAndWarnsExactlyOnce() throws IOException {
        var blocker = tempDir.resolve("blocker");
        Files.writeString(blocker, "no directory can be created beneath a regular file");
        var volume = tempDir.resolve("volume");
        var manager = snapshotManager(inMemoryMetadataStore("stamp-failure"),
                                      snapshotConfig(volume, "node-1").withVolumeSentinel(Option.some(blocker.resolve("sentinels").resolve("v.id"))));

        var first = manager.snapshotNow();
        var second = manager.snapshotNow();

        assertThat(first.isSuccess()).as("the snapshot stands").isTrue();
        assertThat(second.isSuccess()).isTrue();
        assertThat(Files.exists(volume.resolve("LATEST"))).isTrue();
        assertThat(warns.stream().filter(w -> w.contains("Volume identity could not be recorded")).toList())
            .as("one WARN, not one per snapshot")
            .hasSize(1);
        assertThat(warns.getFirst()).contains(volume.toString());
    }
}
