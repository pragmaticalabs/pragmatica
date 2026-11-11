// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.config.RepositoryType;
import org.pragmatica.aether.http.RouteTable;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.source.MapConfigSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.assertj.core.api.Assertions.assertThat;


/// #3021 step 1: the seams the Ember JUnit extension builds on reach what the nodes actually run with. Each assertion reads the
/// node's own object (its `AetherNodeConfig`, its `HttpRoutePublisher`, its `AppHttpServer`), never a value the cluster holds
/// beside it.
@PortBudget
class EmberClusterStagedSeamsTest {
    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                               EmberTestPorts.POOL_LAST,
                                                                               EmberTestPorts.POOL_STEP,
                                                                               3,
                                                                               40,
                                                                               80);

    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(90).seconds();
    private static final TimeSpan DELAY = TimeSpan.timeSpan(7).seconds();
    private static final Artifact MODULE = Artifact.artifact("org.example:echo:1.0.0").unwrap();

    @TempDir
    Path tempRoot;

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await();
        }
    }

    @Test
    @Timeout(180)
    void seams_reachTheNodes_repositories_configSource_publicationDelay_routeTable() {
        var staged = RepositoryType.Staged.staged(tempRoot.resolve("repo"), Set.of(MODULE));

        cluster = EmberTestPorts.startedCluster(PORTS, base -> seamedCluster(base, staged), START_BOUND);
        assertThat(cluster.wiredSliceRepositories().or(List.of())).as("the node's own config carries ONLY the staged entry")
                  .containsExactly(staged);
        assertThat(cluster.wiredConfigString("ember.seam.key").or("<absent>")).isEqualTo("seam-value");
        assertThat(cluster.wiredConfigString("cluster.genesis_voters").isPresent()).as("the genesis source still layers in")
                  .isTrue();
        assertThat(cluster.allNodes()
                          .stream()
                          .map(node -> node.appHttpServer()
                                           .httpRoutePublisher()
                                           .map(publisher -> publisher.routePublicationDelay()
                                                                      .millis())
                                           .or(-1L))
                          .distinct()
                          .toList()).as("every node's publisher got the delay")
                  .containsExactly(DELAY.millis());
        assertThat(cluster.allNodes()
                          .stream()
                          .map(AetherNode::self)
                          .allMatch(id -> cluster.routeTable(id.id())
                                                 .isPresent())).isTrue();
        assertThat(cluster.routeTable("no-such-node").isPresent()).isFalse();
        assertThat(cluster.routeTable(cluster.allNodes().getFirst().self().id())
                          .map(RouteTable::localRoutes)
                          .or(Set.of())).as("an app-less cluster serves no routes")
                  .isEmpty();
    }

    @Test
    @Timeout(60)
    void withConfigSource_isRefused_whenTheClusterWasBuiltWithAConfigProvider() {
        var provider = Option.some(ConfigurationProvider.builder().withSource(source("ctor")).build());
        var built = emberCluster(3, PORTS.first(), PORTS.first() + 40, PORTS.first() + 80, "cfg-ctor", provider);

        assertThat(built.withConfigSource(source("extra")).isFailure()).isTrue();
        assertThat(built.withConfigSource(source("extra")).fold(cause -> cause.message(), _ -> "")).contains("configProvider");
    }

    @Test
    @Timeout(60)
    void withHarnessTempRoot_putsTheMarkerDirAndTheStorageRootUnderTheRoot_andCreatesNothingBeforeFirstUse() throws Exception {
        var root = tempRoot.resolve("harness");
        var built = emberCluster(3, PORTS.first(), PORTS.first() + 40, PORTS.first() + 80, "tmp-root");

        assertThat(built.withHarnessTempRoot(root).isSuccess()).isTrue();
        try (var entries = Files.list(root)) {
            assertThat(entries.toList()).as("nothing is created before a node needs it").isEmpty();
        }

        var marker = built.participationMarkerBase().unwrap();
        var storage = built.unwritableStorageBase().unwrap();

        assertThat(marker.startsWith(root)).as("marker dir %s under %s", marker, root).isTrue();
        assertThat(storage.startsWith(root)).as("storage blocker %s under %s", storage, root).isTrue();
        assertThat(built.participationMarkerBase().unwrap()).as("created once per cluster").isEqualTo(marker);
        built.stop().await();
    }

    @Test
    @Timeout(180)
    void everySeam_isRefusedAfterStart() {
        cluster = EmberTestPorts.startedCluster(PORTS,
                                                base -> emberCluster(3, base, base + 40, base + 80, "seam-late"),
                                                START_BOUND);
        assertThat(cluster.withSliceRepositories(List.of(new RepositoryType.Local())).isFailure()).isTrue();
        assertThat(cluster.withConfigSource(source("late")).isFailure()).isTrue();
        assertThat(cluster.withHarnessTempRoot(tempRoot.resolve("late")).isFailure()).isTrue();
        assertThat(cluster.withRoutePublicationDelay(DELAY).isFailure()).isTrue();
    }

    private static EmberCluster seamedCluster(int base, RepositoryType.Staged staged) {
        var built = emberCluster(3, base, base + 40, base + 80, "seam");

        assertThat(built.withSliceRepositories(List.of(staged)).isSuccess()).isTrue();
        assertThat(built.withConfigSource(new MapConfigSource("seam",
                                                              Map.of("ember.seam.key", "seam-value"),
                                                              10))
                        .isSuccess()).isTrue();
        assertThat(built.withRoutePublicationDelay(DELAY).isSuccess()).isTrue();

        return built;
    }

    private static MapConfigSource source(String name) {
        return new MapConfigSource(name, Map.of("k", "v"), 10);
    }
}
