package org.pragmatica.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

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
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.some;

/// #1569 A3/A4/A10 on the storage engine's logs: looking at a volume changes nothing on it, and cutting a
/// torn tail -- the one place a log discards bytes -- happens only on an explicit open, loudly.
class AppendLogVolumeReadOnlyTest {
    private static final AppendLog.EpochKey EPOCH_ONE = AppendLog.EpochKey.epochKey("1").unwrap();

    private static final byte[] TORN = {0, 0, 0, 40, 1, 2, 3};

    @TempDir
    Path volume;

    /// A node that loses its claim on a volume must leave it byte-identical. Building the tiers and the
    /// instance, listing its logs, inspecting a log with a torn tail and reading its epoch history must not
    /// change the size, the timestamp or the contents of any file or directory on the volume -- with a
    /// leftover partial block on it (the tier's OPEN sweeps those, construction must not), and with a tier
    /// whose base directory and an instance whose log root do not exist yet (construction must not create
    /// them).
    @Test
    void constructListAndInspect_leaveTheVolumeByteIdentical() throws Exception {
        prepareVolumeWithTornLog();
        leaveAPartialBlock();
        var before = snapshot();

        var disk = LocalDiskTier.localDiskTier(volume.resolve("blocks"), 1 << 20).unwrap();
        var unopened = LocalDiskTier.localDiskTier(volume.resolve("missing-blocks"), 1 << 20).unwrap();
        var rootless = StorageInstance.storageInstance("other",
                                                       List.of(MemoryTier.memoryTier(1 << 20), unopened),
                                                       MetadataStore.inMemoryMetadataStore("other"),
                                                       WritePolicy.WRITE_THROUGH,
                                                       some(volume.resolve("missing-logs")));

        assertThat(rootless.listLogs().unwrap()).isEmpty();
        var storage = StorageInstance.storageInstance("streams",
                                                      List.of(MemoryTier.memoryTier(1 << 20), disk),
                                                      MetadataStore.inMemoryMetadataStore("streams"),
                                                      WritePolicy.WRITE_THROUGH,
                                                      some(volume.resolve("logs")));

        assertThat(storage.listLogs().unwrap()).containsExactly("orders/0");

        var extent = storage.inspectLog("orders/0").unwrap();

        assertThat(extent.lowOffset()).isZero();
        assertThat(extent.headOffset()).isEqualTo(2L);
        assertThat(extent.fileBytes() - extent.validBytes()).as("the torn tail is reported, not cut").isEqualTo(TORN.length);
        assertThat(AppendLog.readEpochHistory(logFile()).unwrap()).containsExactly(new AppendLog.EpochStart(EPOCH_ONE, 0));
        assertThat(snapshot()).isEqualTo(before);
    }

    /// The explicit open cuts the torn tail, and says so: a WARN naming the log, the byte range cut and the
    /// last valid offset, and the same fact to the instance's listener (which the node turns into an event).
    @Test
    void openLog_cutsATornTail_warnsAndReportsItToTheListener() throws Exception {
        prepareVolumeWithTornLog();
        var extent = AppendLog.inspect(logFile()).unwrap();
        var reported = new CopyOnWriteArrayList<AppendLog.TornTail>();
        var storage = StorageInstance.storageInstance("streams",
                                                      List.of(MemoryTier.memoryTier(1 << 20)),
                                                      MetadataStore.inMemoryMetadataStore("streams"),
                                                      WritePolicy.WRITE_THROUGH,
                                                      some(volume.resolve("logs")),
                                                      tail -> record(reported, tail));
        var capture = Capture.attach();

        try {
            storage.openLog("orders/0").onFailure(cause -> fail(cause.message())).onSuccess(AppendLog::close);
        } finally {
            capture.detach();
        }

        assertThat(reported).containsExactly(new AppendLog.TornTail(logFile().toAbsolutePath(),
                                                                    extent.validBytes(),
                                                                    extent.fileBytes(),
                                                                    2L));
        assertThat(Files.size(logFile())).isEqualTo(extent.validBytes());
        assertThat(capture.warns()).singleElement()
                                   .asString()
                                   .contains(logFile().toAbsolutePath().toString())
                                   .contains("[" + extent.validBytes() + ", " + extent.fileBytes() + ")")
                                   .contains("offset 2");
    }

    /// What a write the process did not survive leaves behind: a `<hex>.<n>.partial` beside the shards.
    private void leaveAPartialBlock() throws IOException {
        var shard = volume.resolve("blocks").resolve("ab").resolve("cd");

        Files.createDirectories(shard);
        Files.write(shard.resolve("abcd0000.7.partial"), new byte[]{9, 9, 9});
    }

    private void prepareVolumeWithTornLog() throws IOException {
        var disk = LocalDiskTier.localDiskTier(volume.resolve("blocks"), 1 << 20).unwrap();
        var block = "sealed segment".getBytes(StandardCharsets.UTF_8);

        disk.put(BlockId.blockId(block).unwrap(), block).await().onFailure(c -> fail(c.message()));

        var wal = AppendLog.open(logFile()).unwrap();

        for (var i = 0; i < 3; i++) {
            wal.append(i, ("e" + i).getBytes(StandardCharsets.UTF_8), 1L).await().onFailure(c -> fail(c.message()));
        }
        wal.recordEpochStart(EPOCH_ONE, 0, (later, earlier) -> false).onFailure(c -> fail(c.message()));
        wal.close();
        Files.write(logFile(), TORN, StandardOpenOption.APPEND);
    }

    /// Every path under the volume -> size, modification time and (for files) content digest.
    private Map<String, String> snapshot() throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        var result = new TreeMap<String, String>();

        try (Stream<Path> paths = Files.walk(volume)) {
            for (var path : paths.toList()) {
                var content = Files.isRegularFile(path)
                              ? HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)))
                              : "dir";

                result.put(volume.relativize(path).toString(),
                           Files.size(path) + "|" + Files.getLastModifiedTime(path).toMillis() + "|" + content);
            }
        }

        return result;
    }

    private Path logFile() {
        return volume.resolve("logs").resolve("orders").resolve("0.wal");
    }

    private static Unit record(List<AppendLog.TornTail> reported, AppendLog.TornTail tail) {
        reported.add(tail);

        return Unit.unit();
    }

    private static final class Capture extends AbstractAppender {
        private static final String LOGGER = AppendLog.class.getName();
        private final List<String> warns = new CopyOnWriteArrayList<>();

        private Capture() {
            super("AppendLogTornTailCapture", (Filter) null, PatternLayout.createDefaultLayout(), true, Property.EMPTY_ARRAY);
        }

        static Capture attach() {
            var capture = new Capture();
            var ctx = (LoggerContext) LogManager.getContext(false);
            var config = ctx.getConfiguration();
            var loggerConfig = new LoggerConfig(LOGGER, Level.ALL, true);

            capture.start();
            config.addLogger(LOGGER, loggerConfig);
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

        List<String> warns() {
            return warns;
        }
    }
}
