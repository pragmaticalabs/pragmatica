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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #1190: the `.encryption-enabled` marker is only ever CREATED, and a boot's fail-closed guard keys
/// on its presence. A crash after `commitMarker` returned but before the create reached the device
/// left the marker absent while ciphertext blocks existed, and the next plain boot mounted a plain
/// tier over ciphertext. A crash cannot be induced in-process, so the pinned mechanism is what the
/// JDK actually issued: `FileChannel.force(true)` on the marker file (its bytes and inode) AND on
/// the parent directory (the directory entry that makes the marker findable), both before
/// `commitMarker` returns -- observed through JFR's `jdk.FileForce` event, which the JDK emits
/// from `FileChannelImpl.force` itself, recorded at threshold ZERO (the default profile's 20 ms
/// would drop every warm-disk fsync). On the unmodified base the recording held zero events. The
/// plain-JUnit control beside it pins that the marker exists with the key id, so the JFR observer
/// is never the only assertion. [unverified: power loss -- what the device does with a completed
/// fsync is outside the JVM; the pin is that the fsyncs were issued.]
class EncryptionMarkerDurabilityTest {
    @TempDir
    Path tempDir;

    @Test
    void commitMarker_forcesTheMarkerFileAndItsDirectory_beforeReturning() {
        var armed = armedOverFreshDirectory();
        var marker = tempDir.resolve(EncryptingStorageTier.MARKER_FILE_NAME);
        var forced = forcedPathsDuring(() -> armed.commitMarker()
                                                  .unwrap());

        assertThat(forced).as("jdk.FileForce events recorded while commitMarker ran")
                  .contains(marker.toAbsolutePath(),
                            tempDir.toAbsolutePath());
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

    /// Records every `jdk.FileForce` the JVM emits while `action` runs. Threshold zero: the
    /// default profile drops forces shorter than 20 ms, which is every fsync on a warm disk.
    static List<Path> forcedPathsDuring(Runnable action) {
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
                                    .map(EncryptionMarkerDurabilityTest::forcedPath)
                                    .toList();
            } finally {
                Files.deleteIfExists(dump);
            }
        } catch (IOException e) {
            return fail("JFR recording failed: " + e.getMessage());
        }
    }

    private static Path forcedPath(RecordedEvent event) {
        return Path.of(event.getString("path")).toAbsolutePath();
    }

    private static EncryptionKeyring singleKeyRing(String keyId) {
        var key = new byte[32];

        new SecureRandom().nextBytes(key);
        var encryptor = BlockEncryptor.aesGcm(key, keyId).fold(c -> {
                                                                   fail("encryptor creation failed: " + c.message());

                                                                   return null;
                                                               },
                                                               e -> e);

        return EncryptionKeyring.encryptionKeyring(Map.of(keyId, encryptor),
                                                   keyId)
                                .fold(c -> {
                                          fail("keyring creation failed: " + c.message());

                                          return null;
                                      },
                                      k -> k);
    }
}
