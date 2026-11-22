// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.config.RepositoryType;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.http.HttpRoutePublisher;
import org.pragmatica.aether.http.RouteTable;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.source.MapConfigSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
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

    /// As [#PORTS] with room for nodes that join after the start.
    private static final EmberTestPorts.Block JOINABLE_PORTS = new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                                       EmberTestPorts.POOL_LAST,
                                                                                       EmberTestPorts.POOL_STEP,
                                                                                       2 * 3 + 4,
                                                                                       40,
                                                                                       80);

    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(90).seconds();
    private static final TimeSpan JOIN_BOUND = TimeSpan.timeSpan(90).seconds();
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
    void seams_reachTheNodes_repositories_configSource_publicationHold_routeTable() {
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
                                           .map(HttpRoutePublisher::routePublicationHeld)
                                           .or(false))
                          .distinct()
                          .toList()).as("every node's publisher got the hold")
                  .containsExactly(true);
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

    /// The release half of the hold seam: it reaches every node's publisher (each reads not-held afterwards) and a node that
    /// has nothing pending completes it. The pending-set behaviour itself is pinned at the publisher
    /// (`HttpRoutePublisherPublicationHoldTest`); an app-less cluster has nothing pending.
    ///
    /// v3031h survivor e2: the release also ends the cluster's own hold, so a node that joins AFTER it is not held.
    @Test
    @Timeout(300)
    void releaseRoutePublication_endsTheHoldOnEveryNode_andOnANodeThatJoinsLater() {
        cluster = EmberTestPorts.startedCluster(JOINABLE_PORTS, base -> heldCluster(base), START_BOUND);
        assertThat(heldNodes()).as("CONTROL: held before the release").containsExactly(true);
        assertThat(cluster.releaseRoutePublication().await().isSuccess()).isTrue();
        assertThat(heldNodes()).as("every node's publisher released").containsExactly(false);
        assertThat(cluster.addNode().await(JOIN_BOUND).isSuccess()).as("a node joins after the release").isTrue();
        assertThat(cluster.allNodes()).as("CONTROL: the joined node is among the nodes read").hasSize(4);
        assertThat(heldNodes()).as("the node that joined after the release is not held either").containsExactly(false);
    }

    private List<Boolean> heldNodes() {
        return cluster.allNodes()
                      .stream()
                      .map(node -> node.appHttpServer()
                                       .httpRoutePublisher()
                                       .map(HttpRoutePublisher::routePublicationHeld)
                                       .or(false))
                      .distinct()
                      .toList();
    }

    private static EmberCluster heldCluster(int base) {
        var built = emberCluster(3, base, base + 40, base + 80, "seam-hold");

        assertThat(built.withRoutePublicationHeld().isSuccess()).isTrue();

        return built;
    }

    @Test
    @Timeout(60)
    void withConfigSource_isRefused_whenTheClusterWasBuiltWithAConfigProvider() {
        var provider = Option.some(ConfigurationProvider.builder().withSource(source("ctor")).build());
        var built = emberCluster(3, PORTS.first(), PORTS.first() + 40, PORTS.first() + 80, "cfg-ctor", provider);

        assertThat(built.withConfigSource(source("extra")).isFailure()).isTrue();
        String refusal = built.withConfigSource(source("extra")).fold(cause -> cause.message(), _ -> "");

        assertThat(refusal).contains("configProvider");
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

    /// The refusal cause, or a marker cause when the call unexpectedly succeeded (so the type assertion goes red).
    private static Cause failureOf(Result<?> result) {
        return result.fold(cause -> cause, _ -> Causes.cause("unexpected success"));
    }

    /// A4 of the #3031 verification: an empty list left the node with no repository at all, and a null (or a null entry) was an
    /// NPE out of `List.copyOf`, where the sibling `SliceConfig.sliceConfigFromNames` refuses an empty list with a typed cause.
    @Test
    @Timeout(60)
    void withSliceRepositories_refusesNullEmptyAndNullEntry_withATypedCause_andStillAcceptsAValidList() {
        var built = emberCluster(3, PORTS.first(), PORTS.first() + 40, PORTS.first() + 80, "seam-repos");
        var withNullEntry = new ArrayList<RepositoryType>();

        withNullEntry.add(null);

        assertThat(failureOf(built.withSliceRepositories(null))).as("null list")
                  .isInstanceOf(SliceConfig.SliceConfigError.InvalidSliceConfig.class);
        assertThat(failureOf(built.withSliceRepositories(List.of()))).as("empty list")
                  .isInstanceOf(SliceConfig.SliceConfigError.InvalidSliceConfig.class);
        assertThat(failureOf(built.withSliceRepositories(withNullEntry))).as("null entry")
                  .isInstanceOf(SliceConfig.SliceConfigError.InvalidSliceConfig.class);
        assertThat(built.withSliceRepositories(List.of(new RepositoryType.Local())).isSuccess()).as("CONTROL: a valid list").isTrue();
        built.stop().await();
    }

    @Test
    @Timeout(180)
    void everySeam_isRefusedAfterStart() {
        cluster = EmberTestPorts.startedCluster(PORTS,
                                                base -> emberCluster(3, base, base + 40, base + 80, "seam-late"),
                                                START_BOUND);
        assertThat(heldNodes()).as("v3031h e3: no hold was configured, so no node is held").containsExactly(false);
        assertThat(failureOf(cluster.withSliceRepositories(List.of(new RepositoryType.Local()))).message()).contains("Slice repositories",
                                                                                                                      "must be configured before start");
        assertThat(failureOf(cluster.withConfigSource(source("late"))).message()).contains("Config source", "must be configured before start");
        assertThat(failureOf(cluster.withHarnessTempRoot(tempRoot.resolve("late"))).message()).contains("Harness temp root",
                                                                                                         "must be configured before start");
        assertThat(failureOf(cluster.withRoutePublicationHeld()).message()).as("v3031h e5: the refusal names the seam")
                  .contains("Route publication hold", "must be configured before start");
    }

    /// v3031b survivor m29: a cluster whose only node is HELD BACK is already started for the seams (`nodes` is empty, the
    /// held-back registry is not), so each seam refuses.
    @Test
    @Timeout(60)
    void everySeam_isRefusedWhileOnlyAHeldBackNodeExists() {
        var built = emberCluster(3, PORTS.first(), PORTS.first() + 40, PORTS.first() + 80, "seam-held");

        built.adoptHeldBackNode("held-1", placeholderNode());

        assertThat(built.withSliceRepositories(List.of(new RepositoryType.Local())).isFailure()).isTrue();
        assertThat(built.withConfigSource(source("held")).isFailure()).isTrue();
        assertThat(built.withHarnessTempRoot(tempRoot.resolve("held")).isFailure()).isTrue();
        assertThat(built.withRoutePublicationHeld().isFailure()).isTrue();
    }

    /// An `AetherNode` the seams never touch: it only has to be present in the held-back registry.
    private static AetherNode placeholderNode() {
        return (AetherNode) Proxy.newProxyInstance(AetherNode.class.getClassLoader(),
                                                   new Class<?>[]{AetherNode.class},
                                                   (proxy, method, args) -> unanswered(method.getName()));
    }

    private static Object unanswered(String method) {
        throw new UnsupportedOperationException("placeholder AetherNode does not answer " + method);
    }

    private static EmberCluster seamedCluster(int base, RepositoryType.Staged staged) {
        var built = emberCluster(3, base, base + 40, base + 80, "seam");
        var callersList = new ArrayList<RepositoryType>(List.of(staged));

        assertThat(built.withSliceRepositories(callersList).isSuccess()).isTrue();
        callersList.add(new RepositoryType.Local()); // v3031h e9: a later change to the caller's list must not reach the nodes
        assertThat(built.withConfigSource(new MapConfigSource("seam",
                                                              Map.of("ember.seam.key", "seam-value"),
                                                              10))
                        .isSuccess()).isTrue();
        assertThat(built.withRoutePublicationHeld().isSuccess()).isTrue();

        return built;
    }

    private static MapConfigSource source(String name) {
        return new MapConfigSource(name, Map.of("k", "v"), 10);
    }
}
