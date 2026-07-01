// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.lang.Option;

import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// The Management-API publish path (`POST /api/streams/publish/{name}`) auto-creates a missing stream
/// via {@link StreamRoutes#ensureStreamExists}. Before the fix it ALWAYS committed a fabricated
/// `replicas=1/min-sync=0` default, discarding an app/blueprint stream's committed replication knobs —
/// disarming the sync barrier and collapsing the replica set to RF=1. It must now leave an already-
/// materialized stream's config intact and only fall back to the management default for a genuinely new
/// management-only stream.
class StreamRoutesEnsureStreamExistsTest {
    private static final RetentionPolicy RETENTION = RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 60_000);

    private static ManageableNode nodeWith(StreamPartitionManager manager) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> stubbed(method.getName(), manager));
    }

    private static Object stubbed(String method, StreamPartitionManager manager) {
        return switch (method) {
            case "streamPartitionManager" -> manager;
            default -> throw new UnsupportedOperationException("Not stubbed in test proxy: " + method);
        };
    }

    private static StreamRoutes routesFor(StreamPartitionManager manager) {
        return StreamRoutes.streamRoutes(() -> nodeWith(manager), null, null);
    }

    @Test
    void ensureStreamExists_existingAppStream_preservesReplicationConfig() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        try {
            var appConfig = StreamConfig.streamConfig("app-stream",
                                                      4,
                                                      RETENTION,
                                                      "latest",
                                                      1_048_576L,
                                                      ConsistencyMode.EVENTUAL,
                                                      2,
                                                      2,
                                                      StreamCompression.NONE,
                                                      Option.none());
            manager.createStream(appConfig).onFailure(_ -> fail("app stream create must succeed"));
            assertThat(manager.minSyncReplicasFor("app-stream")).isEqualTo(2);

            routesFor(manager).ensureStreamExists("app-stream")
                              .onFailure(_ -> fail("ensureStreamExists must succeed for an existing stream"));

            assertThat(manager.minSyncReplicasFor("app-stream"))
                .as("a management publish must NOT downgrade an app stream's min-sync-replicas")
                .isEqualTo(2);
            manager.streamInfo("app-stream")
                   .onPresent(si -> assertThat(si.partitions()).isEqualTo(4));
        } finally {
            manager.close();
        }
    }

    @Test
    void ensureStreamExists_newManagementStream_createsWithDefault() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        try {
            routesFor(manager).ensureStreamExists("mgmt-stream")
                              .onFailure(_ -> fail("ensureStreamExists must auto-create a new management stream"));

            assertThat(manager.streamInfo("mgmt-stream").isPresent())
                .as("a genuinely new management stream must be auto-created")
                .isTrue();
            assertThat(manager.minSyncReplicasFor("mgmt-stream")).isEqualTo(0);
        } finally {
            manager.close();
        }
    }
}
