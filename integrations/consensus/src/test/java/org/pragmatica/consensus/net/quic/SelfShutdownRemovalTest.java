/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package org.pragmatica.consensus.net.quic;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.quic.TransportTripwireFixtures.TripwireTopology;
import org.pragmatica.consensus.topology.TransportObservation;
import org.pragmatica.consensus.topology.TransportObservation.PeerDisconnected;
import org.pragmatica.consensus.topology.TransportObservation.PeerJoined;
import org.pragmatica.consensus.topology.TransportObservation.SelfShutdown;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.AWAIT;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.awaitTrue;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.connected;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.infoOf;
import static org.pragmatica.consensus.net.quic.TransportTripwireFixtures.startedNetwork;

/// NT-0C1 (cluster T, PR-0c): a graceful `QuicClusterNetwork.stop()` emits no `TransportObservation.SelfShutdown` and logs
/// no "Ignoring view change ... for self node" WARN, and the peer still sees the close. The stop path used to call
/// `processViewChange(SHUTDOWN, self.id())`, which returned at its self guard with that WARN, so the SHUTDOWN branch that
/// built the event was unreachable; the producer is deleted (`SelfShutdown` itself and its consumers stay until the
/// deployment state machine sources self-departure from the stop path). Mutation: restore the call and the branch; the
/// WARN comes back and this test turns red.
@Timeout(60)
class SelfShutdownRemovalTest {
    private static final String LOGGER_NAME = "org.pragmatica.consensus.net.quic.QuicClusterNetwork";
    private static final NodeId STOPPING = new NodeId("aaa-stopping");
    private static final NodeId PEER = new NodeId("zzz-peer");

    private final List<QuicClusterNetwork> networks = new ArrayList<>();
    private CapturingAppender appender;
    private LoggerConfig loggerConfig;

    @BeforeEach
    void captureTheTransportLog() {
        appender = new CapturingAppender("SelfShutdownRemovalCapture");
        appender.start();
        var context = (LoggerContext) LogManager.getContext(false);

        loggerConfig = new LoggerConfig(LOGGER_NAME, Level.INFO, true);
        loggerConfig.addAppender(appender, Level.INFO, null);
        context.getConfiguration().addLogger(LOGGER_NAME, loggerConfig);
        context.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        var context = (LoggerContext) LogManager.getContext(false);

        networks.forEach(network -> network.stop().await(AWAIT));
        networks.clear();
        context.getConfiguration().removeLogger(LOGGER_NAME);
        context.updateLoggers();
        appender.stop();
    }

    @Test
    void stop_emitsNoSelfShutdownAndNoSelfViewChangeWarn_whileThePeerStillSeesTheClose() {
        var stoppingObserved = new CopyOnWriteArrayList<TransportObservation>();
        var peerObserved = new CopyOnWriteArrayList<TransportObservation>();
        var stoppingRouter = MessageRouter.mutable();
        var peerRouter = MessageRouter.mutable();

        stoppingRouter.addRoute(SelfShutdown.class, stoppingObserved::add);
        stoppingRouter.addRoute(PeerJoined.class, stoppingObserved::add);
        peerRouter.addRoute(PeerDisconnected.class, peerObserved::add);
        var stopping = startedNetwork(new TripwireTopology(STOPPING, false, TimeSpan.timeSpan(30).seconds()), stoppingRouter, networks);
        var peer = startedNetwork(new TripwireTopology(PEER, false, TimeSpan.timeSpan(30).seconds()), peerRouter, networks);

        stopping.dialForTests(infoOf(PEER, peer), false);
        awaitTrue(() -> connected(stopping, PEER) && connected(peer, STOPPING), AWAIT, "both ends CONNECTED");
        awaitTrue(() -> stoppingObserved.stream().anyMatch(PeerJoined.class::isInstance), AWAIT, "PeerJoined at the stopping node");
        stopping.stop().await(AWAIT);
        awaitTrue(() -> peerObserved.stream().anyMatch(PeerDisconnected.class::isInstance), AWAIT, "PeerDisconnected at the peer");

        assertThat(appender.messages(Level.INFO)).as("control: the capture is wired to the transport logger").anyMatch(m -> m.contains("processViewChange: op=ADD"));
        assertThat(stoppingObserved.stream().filter(SelfShutdown.class::isInstance)).as("SelfShutdown routed by the stopping node").isEmpty();
        assertThat(appender.messages(Level.WARN)).as("WARNs logged by the transport across the stop").noneMatch(m -> m.contains("Ignoring view change"));
        assertThat(peerObserved.stream().map(TransportObservation::nodeId)).as("the peer saw the stopping node leave").contains(STOPPING);
    }

    /// In-memory log4j2 appender capturing formatted messages with their levels.
    private static final class CapturingAppender extends AbstractAppender {
        private record Entry(Level level, String message) {}

        private final List<Entry> entries = new CopyOnWriteArrayList<>();

        private CapturingAppender(String name) {
            super(name, (Filter) null, PatternLayout.createDefaultLayout(), true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            entries.add(new Entry(event.getLevel(), event.getMessage().getFormattedMessage()));
        }

        List<String> messages(Level level) {
            return entries.stream().filter(entry -> entry.level() == level).map(Entry::message).toList();
        }
    }
}
