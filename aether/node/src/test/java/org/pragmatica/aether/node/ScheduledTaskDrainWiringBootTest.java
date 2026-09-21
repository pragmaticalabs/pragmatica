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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.slice.ExecutionMode;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskValue;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.serialization.FrameworkCodecs;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// #273 item 1 round 2: pins that a REAL drain reaches `ScheduledTaskManager.onDrainInitiated`.
///
/// Round 1 keyed the scheduler's drain gate on `MembershipDecision.NodeDraining` / `NodeFailedDrain`
/// and pinned it with a unit test that CONSTRUCTED and dispatched those events itself. Nothing in
/// production emits either — the per-node lifecycle-projection layer was removed in the membership-v2
/// finale, so `MembershipDeltaProjector` emits only `NodeJoined` / `NodeRemoved`. The code was present,
/// the test was green, and the behaviour could not occur. A test that supplies its own event cannot
/// falsify the premise that the event arrives.
///
/// So this test supplies no event of its own. It boots a real node and delivers a real
/// `ClusterSyncPing` whose `drainNodes` names this node — the wire message the leader actually sends
/// for an operator drain — then follows production wiring the whole way:
///
/// ```text
///   ClusterSyncPing(drainNodes=[self])          the leader's heartbeat, injected at the router
///     -> MessageRouter entry ClusterSyncPing -> ClusterSyncCollector.onClusterSyncPing
///     -> handleDrainCommand  (logs "includes self", the CONTROL below)
///     -> AetherNode.commandedDrain -> DrainProcedure.initiate(COMMANDED)
///     -> emitDrainInitiatedSafely -> AetherNode.drainInitiatedEmitter   <- the hunk under test
///     -> ScheduledTaskManager.onDrainInitiated -> TaskOps.cancelAllModeTimers  (the ASSERTION)
/// ```
///
/// `initiate` is the single funnel for all three drain triggers (QUORUM_LOSS, CORE_ABSENCE,
/// COMMANDED), so pinning one pins the edge for all three. COMMANDED is the only one reachable on a
/// single node: QUORUM_LOSS is suppressed by the co-confirmation gate at `coreCount=1`, and
/// CORE_ABSENCE is suppressed because a self-forming node is itself core.
///
/// **Scope, stated so it is not over-read.** This exercises production wiring from the inbound-message
/// boundary onward. It does NOT exercise the leader-side half (HTTP route -> `DrainCommandRegistry` ->
/// ping stamping), which needs >= 2 nodes and a management HTTP server — `[unverified: leader-side
/// drain command origination]`. What this test owns is the edge round 1 got wrong: that a drain the
/// node did not invent reaches the scheduler. That the scheduler then cancels and stays cancelled is
/// owned by `ScheduledTaskManagerTest` in `aether/aether-invoke`.
///
/// The node is booted with an injected `jvmExit` (the Forge/Ember overload), so the drain running to
/// completion does not halt the test JVM.
class ScheduledTaskDrainWiringBootTest {
    /// #1276: node storage lives here, never under the machine-global `/data/aether/...` default.
    @TempDir
    Path tempDir;

    private static final String LOGGER_NAME = "org.pragmatica.aether";
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:drain-wiring:1.0.0").unwrap();
    private static final String DRAIN_COMMAND_SEEN = "includes self — invoking local drain handler";
    private static final String TIMERS_CANCELLED = "cancelled 1 ALL-mode scheduled timer(s)";
    private static final TimeSpan START_BOUND = timeSpan(30).seconds();
    private static final Duration LINE_BOUND = Duration.ofSeconds(20);

    private AetherNode node;
    private MessageRouter.DelegateRouter delegateRouter;
    private final AtomicInteger jvmExits = new AtomicInteger();
    private CapturingAppender appender;
    private LoggerConfig loggerConfig;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        appender = CapturingAppender.create("ScheduledTaskDrainWiringCapture");
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
    @Timeout(value = 180, unit = SECONDS)
    void aRealDrainCommandReachesTheSchedulerAndCancelsItsAllModeTimers() {
        boot();

        // An ALL-mode task in the cluster registry, applied through the node's own KV apply path — the
        // same path consensus drives. `cancelAllModeTimers` enumerates the REGISTRY, so this gives the
        // assertion a non-zero count without needing a really-loaded slice: a count of 1 proves the
        // drain edge reached the scheduler AND that the scheduler read its ALL-mode tasks. A bare
        // "cancelled 0" line would prove only the former.
        apply(new KVCommand.Put<>(ScheduledTaskKey.scheduledTaskKey("cache",
                                                                    ARTIFACT,
                                                                    MethodName.methodName("cleanup").unwrap()),
                                  ScheduledTaskValue.intervalTask(node.self(), "1s", ExecutionMode.ALL)));

        assertThat(count(TIMERS_CANCELLED)).as("control: the scheduler has NOT seen a drain before the ping")
                  .isZero();

        commitDrainAuthority();
        delegateRouter.route(drainPing());

        await().atMost(LINE_BOUND)
               .untilAsserted(() -> assertThat(appender.messages())
                   .as("control: the injected ping reached ClusterSyncCollector.handleDrainCommand")
                   .anyMatch(line -> line.contains(DRAIN_COMMAND_SEEN)));

        await().atMost(LINE_BOUND)
               .untilAsserted(() -> assertThat(appender.messages())
                   .as("a real DrainProcedure.initiate must reach ScheduledTaskManager.onDrainInitiated "
                       + "through AetherNode.drainInitiatedEmitter — round 1 keyed this on an event with "
                       + "no producer, so nothing got here")
                   .anyMatch(line -> line.contains(TIMERS_CANCELLED)));
    }

    private void boot() {
        delegateRouter = MessageRouter.DelegateRouter.delegate();
        node = AetherNode.aetherNode(minimalConfig(tempDir),
                                      delegateRouter,
                                      NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs()),
                                      jvmExits::incrementAndGet)
                          .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                          .unwrap();
        node.start()
            .await(START_BOUND)
            .onFailure(cause -> fail("start() must succeed: " + cause.message()));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void commitDrainAuthority() {
        var leader = node.self();
        var store = node.kvStore();
        KVCommand command = new KVCommand.Put<>(org.pragmatica.cluster.state.kvstore.LeaderKey.INSTANCE,
            new org.pragmatica.cluster.state.kvstore.LeaderValue(leader, 1));
        store.process(store.createBatch(List.of(command)));
    }

    /// The leader's heartbeat naming this node in `drainNodes` — the operator-drain wire form.
    private ClusterSyncPing drainPing() {
        return new ClusterSyncPing(node.self(), Map.of(), 1L, 0L, 0L, Set.of(), Set.of(node.self()), Map.of(), Set.of(), true, true);
    }

    private void apply(KVCommand<AetherKey> command) {
        var kvStore = node.kvStore();

        kvStore.process(kvStore.createBatch(List.of(command)));
    }

    private long count(String fragment) {
        return appender.messages()
                       .stream()
                       .filter(line -> line.contains(fragment))
                       .count();
    }

    /// The #858 single-node boot fixture: `self` in `coreNodes`, mutual self-signed QUIC TLS, management
    /// and app HTTP off.
    private static AetherNodeConfig minimalConfig(Path storageRoot) {
        var self = NodeId.nodeId("scheduled-drain-wiring-boot-" + UUID.randomUUID()).unwrap();
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
