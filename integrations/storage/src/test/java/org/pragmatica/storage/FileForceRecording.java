package org.pragmatica.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import static java.util.Comparator.comparing;

/// Records every `FileChannel.force` the JVM issues while an action runs, through the JDK's own
/// `jdk.FileForce` event -- the real syscall site, not an injected seam, so a test built on it cannot
/// be satisfied by a force that never reached the channel. The same technique as core's `FileOpsTest`.
/// The recording is JVM-wide: assertions filter by path.
final class FileForceRecording {
    private FileForceRecording() {}

    static List<ForcedFile> forcedFilesDuring(Runnable action) {
        try (var recording = new Recording()) {
            recording.enable("jdk.FileForce").withThreshold(Duration.ZERO);
            recording.start();
            action.run();
            recording.stop();

            var dump = Files.createTempFile("file-force", ".jfr");

            try {
                recording.dump(dump);

                return RecordingFile.readAllEvents(dump)
                                    .stream()
                                    .filter(event -> event.getEventType().getName().equals("jdk.FileForce"))
                                    .sorted(comparing(RecordedEvent::getStartTime))
                                    .map(FileForceRecording::forcedFile)
                                    .toList();
            } finally {
                Files.deleteIfExists(dump);
            }
        } catch (IOException e) {
            throw new AssertionError("JFR recording failed: " + e.getMessage(), e);
        }
    }

    private static ForcedFile forcedFile(RecordedEvent event) {
        return new ForcedFile(Path.of(event.getString("path")).toAbsolutePath(), event.getBoolean("metaData"));
    }

    /// One `jdk.FileForce`: what was forced, and whether it carried the file's metadata.
    record ForcedFile(Path path, boolean metaData) {}
}
