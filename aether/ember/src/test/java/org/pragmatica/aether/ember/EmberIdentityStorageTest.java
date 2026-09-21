// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import static org.assertj.core.api.Assertions.assertThat;

class EmberIdentityStorageTest {
    @Test void defaultsResolveBeforeAdmissionAndExplicitUnknownRolesStayUnknown() {
        assertThat(EmberCluster.configuredNodeLabels(Map.of())).containsEntry(NodeInfo.LABEL_ROLE, "core");
        assertThat(EmberCluster.roleLabels("")).containsEntry(NodeInfo.LABEL_ROLE, "core");
        assertThat(EmberCluster.configuredNodeLabels(Map.of(NodeInfo.LABEL_ROLE, "", NodeInfo.LABEL_SOURCE, "west")))
            .containsEntry(NodeInfo.LABEL_ROLE, "").containsEntry(NodeInfo.LABEL_SOURCE, "west");
        assertThat(EmberCluster.roleLabels("worker")).containsEntry(NodeInfo.LABEL_ROLE, "worker");
    }

    @Test void defaultStorageIsStablePerNodeAndIsolatedAcrossHarnesses() {
        var first = EmberCluster.emberCluster(3, 34000, 34100, 34200, "storage");
        var second = EmberCluster.emberCluster(3, 34300, 34400, 34500, "storage");
        var node = new NodeId("storage-1");
        var initial = first.perNodeStorageConfig(node).get("artifacts");
        assertThat(first.perNodeStorageConfig(node).get("artifacts")).isEqualTo(initial);
        assertThat(first.perNodeStorageConfig(new NodeId("storage-2")).get("artifacts").snapshotPath()).isNotEqualTo(initial.snapshotPath());
        assertThat(second.perNodeStorageConfig(node).get("artifacts").snapshotPath()).isNotEqualTo(initial.snapshotPath());
        assertThat(Path.of(initial.snapshotPath()).startsWith(Path.of(System.getProperty("java.io.tmpdir")))).isTrue();
        assertThat(initial.diskPath()).contains("storage-1");
    }

    @Test void explicitPersistentBaseRemainsAuthoritative(@TempDir Path directory) {
        var cluster = EmberCluster.emberCluster(3, 34600, 34700, 34800, "storage");
        cluster.withDataBaseDir(directory);
        var config = cluster.perNodeStorageConfig(new NodeId("storage-1")).get("artifacts");
        assertThat(Path.of(config.snapshotPath())).isEqualTo(directory.resolve("storage-1/metadata-snapshots"));
        assertThat(Path.of(config.diskPath())).isEqualTo(directory.resolve("storage-1/storage"));
    }
}
