// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentState;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.VersionRoutingKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.VersionRoutingValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.TlsConfig;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// #1068 round 3 (review S5): pins the `AetherNode` WIRING of the two node-side observations that re-drive
/// a deferred slice start — `nodeDeploymentManager::onSliceTargetPut` and `::onVersionRoutingPut` in
/// `collectRouteEntries`. Deleting both lines left 0 of 1385 node tests red, because every unit pin
/// hand-dispatches the events; only `SliceMediaTypeTest` under the follower race could have noticed.
///
/// A real self-forming single-node cluster (`AetherNode.aetherNode` + `start()`, the #858 boot shape).
/// The commands are applied through the node's own `KVStore.process`, which is the apply path the
/// consensus engine drives: `handlePut` stores, then routes the `ValuePut` through the same router the
/// wiring under test is bound to. `NodeDeploymentState.Active`'s own log lines are the observation point:
/// the deferral WARN is the control that the artifact-key wiring and the gate are live; the
/// "re-evaluating deferred" INFO is emitted ONLY when the target/routing observation reaches the node FSM
/// — with the wiring deleted the leader's own re-issued LOAD still starts the slice, but through the plain
/// gate, and that line never appears.
class DeferredStartRedriveWiringBootTest {
    /// #1276: node storage lives here, never under the machine-global `/data/aether/...` default.
    @TempDir
    Path tempDir;

    private static final String LOGGER_NAME = NodeDeploymentState.Active.class.getName();
    private static final Artifact V1 = Artifact.artifact("org.example:redrive-wiring:1.0.0").unwrap();
    private static final Version V2 = Version.version("2.0.0").unwrap();
    private static final TimeSpan START_BOUND = timeSpan(30).seconds();
    private static final Duration ACTIVE_BOUND = Duration.ofSeconds(30);
    private static final Duration LINE_BOUND = Duration.ofSeconds(10);
    private static final String MANAGER_ACTIVATED = "NodeDeploymentManager activated";
    private static final String DEFERRED = "defers LOAD of " + V1.asString();
    private static final String REDRIVEN = "re-evaluating deferred LOAD of " + V1.asString();

    private AetherNode node;
    private CapturingAppender appender;
    private LoggerConfig loggerConfig;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        appender = CapturingAppender.create("DeferredStartRedriveWiringCapture");
        appender.start();
        var ctx = (LoggerContext) LogManager.getContext(false);
        loggerConfig = getOrCreateLoggerConfig(ctx.getConfiguration());
        originalLevel = loggerConfig.getLevel();
        loggerConfig.addAppender(appender, Level.INFO, null);
        loggerConfig.setLevel(Level.INFO);
        ctx.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop()
                .await(timeSpan(10).seconds())
                .onFailure(cause -> {});
        }

        var ctx = (LoggerContext) LogManager.getContext(false);
        loggerConfig.removeAppender(appender.getName());
        loggerConfig.setLevel(originalLevel);
        ctx.updateLoggers();
        appender.stop();
        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 120, unit = SECONDS)
    void sliceTargetPut_reachesTheNodeFsm_andRedrivesADeferredLoad() {
        bootAndDeferALoad();

        apply(new KVCommand.Put<>(SliceTargetKey.sliceTargetKey(V1.base()),
                                  SliceTargetValue.sliceTargetValue(V1.version(), 1)));

        await().atMost(LINE_BOUND)
               .untilAsserted(() -> assertThat(appender.messages())
                   .as("the SliceTargetKey put must reach NodeDeploymentManager.onSliceTargetPut through the node's router")
                   .anyMatch(line -> line.contains(REDRIVEN)));
    }

    @Test
    @Timeout(value = 120, unit = SECONDS)
    void versionRoutingPut_reachesTheNodeFsm_andRedrivesADeferredLoad() {
        bootAndDeferALoad();

        // The target names V2; only a routing entry naming V1 as the old version permits it.
        apply(new KVCommand.Put<>(SliceTargetKey.sliceTargetKey(V1.base()),
                                  SliceTargetValue.sliceTargetValue(V2, 1)));
        await().atMost(LINE_BOUND)
               .untilAsserted(() -> assertThat(appender.messages())
                   .as("control: a target for another version re-evaluates and re-defers — the target wiring is live")
                   .filteredOn(line -> line.contains(DEFERRED))
                   .hasSizeGreaterThanOrEqualTo(2));
        var redrivesBeforeRouting = count(REDRIVEN);

        apply(new KVCommand.Put<>(VersionRoutingKey.versionRoutingKey(V1.base()),
                                  VersionRoutingValue.versionRoutingValue(V1.version(), V2)));

        await().atMost(LINE_BOUND)
               .untilAsserted(() -> assertThat(count(REDRIVEN))
                   .as("the VersionRoutingKey put must reach NodeDeploymentManager.onVersionRoutingPut through the node's router")
                   .isGreaterThan(redrivesBeforeRouting));
    }

    private void bootAndDeferALoad() {
        node = AetherNode.aetherNode(minimalConfig(tempDir), () -> {})
                          .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                          .unwrap();
        node.start()
            .await(START_BOUND)
            .onFailure(cause -> fail("start() must succeed: " + cause.message()));

        await().atMost(ACTIVE_BOUND)
               .untilAsserted(() -> assertThat(appender.messages())
                   .as("control: the node deployment manager is Active and the capture on %s sees its lines", LOGGER_NAME)
                   .anyMatch(line -> line.contains(MANAGER_ACTIVATED)));

        // A LOAD for this node with no committed target: the gate defers it. This is also the control
        // that the NodeArtifactKey wiring (pre-existing) delivers puts to the FSM.
        apply(new KVCommand.Put<>(NodeArtifactKey.nodeArtifactKey(node.self(), V1),
                                  NodeArtifactValue.nodeArtifactValue(SliceState.LOAD)));

        await().atMost(LINE_BOUND)
               .untilAsserted(() -> assertThat(appender.messages())
                   .as("control: the LOAD is deferred for want of a target")
                   .anyMatch(line -> line.contains(DEFERRED)));
    }

    private void apply(KVCommand<AetherKey> command) {
        var kvStore = node.kvStore();

        kvStore.process(kvStore.createBatch(List.of(command)));
    }

    private long count(String fragment) {
        return appender.messages().stream().filter(line -> line.contains(fragment)).count();
    }

    /// The #858 single-node boot fixture: `self` in `coreNodes`, mutual self-signed QUIC TLS, management
    /// and app HTTP off.
    private static AetherNodeConfig minimalConfig(Path storageRoot) {
        var self = NodeId.nodeId("deferred-start-redrive-boot-" + UUID.randomUUID()).unwrap();
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", freePort()).unwrap());

        return AetherNodeConfig.builder()
                                .self(self)
                                .coreNodes(List.of(selfInfo))
                                .managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
                                .sliceConfig(SliceConfig.sliceConfig())
                                .artifactRepo(DHTConfig.FULL)
                                .coreMax(1)
                                .appHttp(AppHttpConfig.appHttpConfig())
                                .tls(Option.none())
                                .quicTls(TlsConfig.selfSignedMutual())
                                .certificateProvider(Option.none())
                                .configProvider(Option.some(HermeticStorage.withControlStorageIn(storageRoot,
                                    org.pragmatica.config.ConfigurationProvider.builder().build())))
                                .environment(Option.none())
                                .managementHttpProtocol(HttpProtocol.H1)
                                .storageConfig(HermeticStorage.nodeStorageIn(storageRoot, false))
                                .build();
    }

    private static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static LoggerConfig getOrCreateLoggerConfig(Configuration configuration) {
        var existing = configuration.getLoggerConfig(LOGGER_NAME);

        if (LOGGER_NAME.equals(existing.getName())) {
            return existing;
        }

        var fresh = new LoggerConfig(LOGGER_NAME, Level.INFO, false);

        configuration.addLogger(LOGGER_NAME, fresh);

        return fresh;
    }

    /// In-memory log4j2 appender capturing every message the bound logger emits.
    private static final class CapturingAppender extends AbstractAppender {
        private final List<String> messages = new CopyOnWriteArrayList<>();

        private CapturingAppender(String name, Layout<?> layout) {
            super(name, (Filter) null, layout, true, Property.EMPTY_ARRAY);
        }

        static CapturingAppender create(String name) {
            return new CapturingAppender(name, PatternLayout.createDefaultLayout());
        }

        @Override
        public void append(LogEvent event) {
            messages.add(event.getMessage().getFormattedMessage());
        }

        List<String> messages() {
            return List.copyOf(messages);
        }
    }
}
