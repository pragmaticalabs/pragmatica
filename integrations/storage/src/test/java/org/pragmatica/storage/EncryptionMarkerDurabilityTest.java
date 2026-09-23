// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.pragmatica.lang.io.FileOps;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import static java.util.Comparator.comparing;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #1190: the `.encryption-enabled` marker is only ever CREATED, and a boot's fail-closed guard keys
/// on its presence. A crash after `commitMarker` returned but before the create reached the device
/// left the marker absent while ciphertext blocks existed, and the next plain boot mounted a plain
/// tier over ciphertext. A crash cannot be induced in-process, so the pinned mechanism is what the
/// JDK actually issued: `FileChannel.force(true)` on the marker file (its bytes and inode) AND THEN
/// on the parent directory (the directory entry that makes the marker findable), both before
/// `commitMarker` returns -- the ORDER and the `force(true)` are each load-bearing and each
/// asserted -- observed through JFR's `jdk.FileForce` event, which the JDK emits
/// from `FileChannelImpl.force` itself, recorded at threshold ZERO (the default profile's 20 ms
/// would drop every warm-disk fsync). On the unmodified base the recording held zero events. The
/// plain-JUnit control beside it pins that the marker exists with the key id, so the JFR observer
/// is never the only assertion. [unverified: power loss -- what the device does with a completed
/// fsync is outside the JVM; the pin is that the fsyncs were issued.]
class EncryptionMarkerDurabilityTest {
    @TempDir
    Path tempDir;

    /// The ORDER and the METADATA flag are both asserted, not just that a force happened against
    /// each path: forcing the directory BEFORE the marker is created syncs an entry that does not
    /// name the marker yet -- #1190's defect exactly -- and `force(false)` (fdatasync) need not
    /// make a freshly created file's inode durable. An unordered, path-only assertion is green
    /// under both.
    @Test
    void commitMarker_forcesTheMarkerFileAndItsDirectory_beforeReturning() {
        var armed = armedOverFreshDirectory();
        var marker = tempDir.resolve(EncryptingStorageTier.MARKER_FILE_NAME);
        var forced = forcedFilesDuring(() -> armed.commitMarker()
                                                  .unwrap());

        assertThat(forced).as("jdk.FileForce events recorded while commitMarker ran")
                  .containsSubsequence(new ForcedFile(marker.toAbsolutePath(), true),
                                       new ForcedFile(tempDir.toAbsolutePath(), true));
    }

    /// Plain control, no JFR: the marker exists and carries the active key id once `commitMarker`
    /// returns, so the durability pin above is never the only assertion on this path.
    @Test
    void commitMarker_createsTheMarkerHoldingTheActiveKeyId() {
        var armed = armedOverFreshDirectory();
        var marker = tempDir.resolve(EncryptingStorageTier.MARKER_FILE_NAME);

        assertThat(FileOps.exists(marker)).as("nothing is written before commit").isFalse();
        armed.commitMarker().unwrap();
        assertThat(FileOps.isRegularFile(marker)).isTrue();
        assertThat(FileOps.readBytes(marker).unwrap()).containsExactly("key-1190".getBytes(StandardCharsets.UTF_8));
    }

    private EncryptingStorageTier.ArmedLocalDisk armedOverFreshDirectory() {
        var keyring = singleKeyRing("key-1190");
        var disk = LocalDiskTier.localDiskTier(tempDir, 1024 * 1024).unwrap();
        var armed = EncryptingStorageTier.armLocalDisk(disk, tempDir, keyring).unwrap();

        assertThat(armed.pendingKeyId().isPresent()).as("fresh directory: the marker write is pending").isTrue();

        return armed;
    }

    /// Records every `jdk.FileForce` the JVM emits while `action` runs, in the order the JDK
    /// issued them. Threshold zero: the default profile drops forces shorter than 20 ms, which is
    /// every fsync on a warm disk. Sorted by start time explicitly, so the caller may assert ORDER
    /// without depending on the order `RecordingFile` happens to replay a chunk in. `metaData` is
    /// the event's own field: `true` for `force(true)`/fsync, `false` for `force(false)`/fdatasync.
    /// (Deliberate twin of `FileOpsTest.forcedFilesDuring` -- different modules, not shareable;
    /// a change to either belongs in both.)
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
                                    .filter(event -> event.getEventType()
                                                          .getName()
                                                          .equals("jdk.FileForce"))
                                    .sorted(comparing(RecordedEvent::getStartTime))
                                    .map(EncryptionMarkerDurabilityTest::forcedFile)
                                    .toList();
            } finally {
                Files.deleteIfExists(dump);
            }
        } catch (IOException e) {
            return fail("JFR recording failed: " + e.getMessage());
        }
    }

    private static ForcedFile forcedFile(RecordedEvent event) {
        return new ForcedFile(Path.of(event.getString("path")).toAbsolutePath(), event.getBoolean("metaData"));
    }

    /// One `jdk.FileForce`: what was forced, and whether the force carried the file's metadata
    /// (`force(true)`) or only its contents (`force(false)`).
    record ForcedFile(Path path, boolean metaData) {}

    private static EncryptionKeyring singleKeyRing(String keyId) {
        var key = new byte[32];

        new SecureRandom().nextBytes(key);
        var encryptor = BlockEncryptor.aesGcm(key, keyId)
                                      .fold(c -> fail("encryptor creation failed: " + c.message()),
                                            e -> e);

        return EncryptionKeyring.encryptionKeyring(Map.of(keyId, encryptor), keyId)
                                .fold(c -> fail("keyring creation failed: " + c.message()),
                                      k -> k);
    }
}
