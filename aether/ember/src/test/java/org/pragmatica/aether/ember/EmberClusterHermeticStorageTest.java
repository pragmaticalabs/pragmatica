// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.io.TimeSpan;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #1276: a cluster built without [EmberCluster#withDataBaseDir] must root every node's storage in its
/// own uncreatable temp dir, never in the production default `/data/aether/...`, where all clusters on a
/// machine with a writable `/data` shared one directory across runs, trees and branches. Both tests read
/// the config the cluster hands its nodes, so they hold on any machine: they need neither a writable nor
/// an unwritable `/data`, and neither starts a node.
class EmberClusterHermeticStorageTest {
    private static final TimeSpan STOPPED = TimeSpan.timeSpan(10).seconds();
    private static final int BASE_PORT = 45_300;
    private static final int BASE_MGMT_PORT = 45_400;

    @Test
    void perNodeStorageConfig_rootsEachNodeUnderTheClustersUncreatableTempDir_neverUnderData() {
        var cluster = emberCluster(3, BASE_PORT, BASE_MGMT_PORT, "hermetic");
        var nodeId = NodeId.nodeId("hermetic-1").unwrap();

        var artifacts = cluster.perNodeStorageConfig(nodeId).get("artifacts");

        assertThat(artifacts).as("a node built without a data dir must get an explicit 'artifacts' instance -- "
                                 + "an empty map resolves the production default /data/aether/storage")
                             .isNotNull();

        var blocker = cluster.unwritableStorageBase()
                             .onEmpty(() -> fail("the cluster must have created its storage temp dir"))
                             .unwrap();

        assertThat(Files.isRegularFile(blocker)).as("the storage root must sit under a regular FILE, so no "
                                                    + "directory can be created beneath it")
                                                .isTrue();
        // Lexical `Path.startsWith`, not AssertJ's path assertion: that one resolves the real path, which
        // by design cannot exist beneath the blocker file.
        assertThat(Path.of(artifacts.diskPath()).startsWith(blocker)).as("disk path %s must be under %s",
                                                                         artifacts.diskPath(), blocker)
                                                                     .isTrue();
        assertThat(Path.of(artifacts.snapshotPath()).startsWith(blocker)).as("snapshot path %s must be under %s",
                                                                             artifacts.snapshotPath(), blocker)
                                                                         .isTrue();
        assertThat(Path.of(artifacts.diskPath()).startsWith(Path.of("/data"))).as("disk path %s must not be under /data",
                                                                                  artifacts.diskPath())
                                                                              .isFalse();

        cluster.stop().await(STOPPED);
    }

    @Test
    @Timeout(30)
    void stop_deletesTheClustersStorageTempDir() {
        var cluster = emberCluster(3, BASE_PORT, BASE_MGMT_PORT, "hermetic-stop");

        cluster.perNodeStorageConfig(NodeId.nodeId("hermetic-stop-1").unwrap());

        var blocker = cluster.unwritableStorageBase().unwrap();
        var tempDir = blocker.getParent();

        assertThat(Files.exists(tempDir)).as("CONTROL: the temp dir exists before stop()").isTrue();

        cluster.stop()
               .await(STOPPED)
               .onFailure(cause -> fail("stop must succeed: " + cause.message()));

        assertThat(Files.exists(tempDir)).as("stop() must delete the cluster's storage temp dir %s", tempDir)
                                         .isFalse();
    }
}
