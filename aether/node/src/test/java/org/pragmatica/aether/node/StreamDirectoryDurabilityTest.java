// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.TlsConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// #1602 N1: the stream WAL base directory and `segments/` are created with their entries forced in their
/// parents -- every file later made durable inside them is only as durable as the entry naming the directory.
/// The boot gates create them first; read from the JDK's own `jdk.FileForce` events.
class StreamDirectoryDurabilityTest {
    @TempDir
    Path root;

    @Test
    void bootGates_createTheWalAndSegmentsDirectories_withTheirEntriesForced() throws IOException {
        var config = minimalConfig(root);
        var forced = forcedPathsDuring(() -> {
            assertThat(AetherNode.verifyWalBootable(config).isSuccess()).isTrue();
            assertThat(AetherNode.verifyStreamSegmentsBootable(config).isSuccess()).isTrue();
        });
        var created = createdDirectoriesNamed("wal", "segments");

        assertThat(created).hasSize(2);
        created.forEach(dir -> assertThat(forced).as("the entry naming %s is forced in its parent", dir)
                                                  .contains(dir.getParent().toAbsolutePath()));
    }

    private List<Path> createdDirectoriesNamed(String... names) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isDirectory)
                        .filter(path -> List.of(names).contains(path.getFileName().toString()))
                        .filter(path -> path.toString().contains("stream-segments"))
                        .toList();
        }
    }

    private static List<Path> forcedPathsDuring(Runnable action) {
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
                                    .map(StreamDirectoryDurabilityTest::forcedPath)
                                    .toList();
            } finally {
                Files.deleteIfExists(dump);
            }
        } catch (IOException e) {
            throw new AssertionError("JFR recording failed: " + e.getMessage(), e);
        }
    }

    private static Path forcedPath(RecordedEvent event) {
        return Path.of(event.getString("path")).toAbsolutePath();
    }

    private static AetherNodeConfig minimalConfig(Path storageRoot) {
        var self = NodeId.nodeId("stream-dirs-" + UUID.randomUUID()).unwrap();
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", 1).unwrap());

        return AetherNodeConfig.builder()
                               .self(self).coreNodes(List.of(selfInfo)).managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
                               .sliceConfig(SliceConfig.sliceConfig()).artifactRepo(DHTConfig.DEFAULT).coreMax(1)
                               .appHttp(AppHttpConfig.appHttpConfig()).tls(Option.none()).quicTls(TlsConfig.selfSignedMutual())
                               .certificateProvider(Option.none()).configProvider(Option.none()).environment(Option.none())
                               .managementHttpProtocol(HttpProtocol.H1)
                               .storageConfig(Map.of("artifacts", HermeticStorage.storageConfigAt(storageRoot.resolve("node"), false)))
                               .build();
    }
}
