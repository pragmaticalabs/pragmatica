/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.consensus.rabia;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestClusterNetwork;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestCommand;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestStateMachine;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestTopologyManager;
import org.pragmatica.consensus.rabia.RabiaPersistence.SavedState;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.SyncRequest;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.SyncResponse;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// A node outside the core electorate (not a voter, not an observer) meets the adoption threshold on
/// every sync round and is refused by `activate()`. That refusal logged a WARN on every retry — one per
/// `syncRetryInterval`, without bound (measured 19–20 in 20 intervals, before and after #1447). It is now
/// reported once per sync episode, while the periodic stuck-sync WARN keeps reporting the stall.
/// Mutation that reddens it: drop the `compareAndSet` guard in `warnOutsideElectorate`.
class RabiaElectorateRefusalLogTest {
    private static final String REFUSAL = "cannot activate outside the core electorate";
    private static final String STUCK = "still SYNCING after";
    private static final long RETRY_MILLIS = 50;
    private static final int MIN_ROUNDS = 12;

    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private final AbstractAppender appender = new AbstractAppender("electorate-refusal", (Filter) null, null, true, Property.EMPTY_ARRAY) {
        @Override
        public void append(LogEvent event) {
            if (event.getLevel() == Level.WARN) {
                warnings.add(event.getMessage().getFormattedMessage());
            }
        }
    };
    private LoggerConfig loggerConfig;
    private Level originalLevel;
    private RabiaEngine<TestCommand> engine;

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.stop().await();
        }
        if (loggerConfig != null) {
            loggerConfig.removeAppender(appender.getName());
            loggerConfig.setLevel(originalLevel);
            ((LoggerContext) LogManager.getContext(false)).updateLoggers();
        }
        appender.stop();
    }

    @Test
    void nodeOutsideTheElectorate_reportsTheRefusalOncePerEpisode_andTheStallPeriodically() {
        capture();
        var requests = new AtomicInteger();
        var target = new AtomicReference<RabiaEngine<TestCommand>>();
        var network = new TestClusterNetwork() {
            @Override
            public <M extends ProtocolMessage> Unit send(NodeId peer, M message) {
                if (message instanceof SyncRequest) {
                    requests.incrementAndGet();
                    target.get().processSyncResponse(new SyncResponse<>(peer,
                                                                        SavedState.savedState(new byte[0], Phase.phase(3), List.of()),
                                                                        ResponderState.LIVE));
                }
                return Unit.unit();
            }
        };
        engine = new RabiaEngine<>(new TestTopologyManager(nodeId("node-9").unwrap(), 3),
                                   network,
                                   new TestStateMachine(),
                                   ProtocolConfig.consensusConfig(timeSpan(60).seconds(), timeSpan(RETRY_MILLIS).millis()));
        target.set(engine);
        engine.clusterState(ClusterStateNotification.active());

        var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && (requests.get() < MIN_ROUNDS * 3 || count(STUCK) == 0)) {
            Thread.onSpinWait();
        }

        assertThat(engine.isActive()).as("the node outside the electorate never activates").isFalse();
        assertThat(requests.get()).as("control: the adoption loop really retried many rounds").isGreaterThanOrEqualTo(MIN_ROUNDS * 3);
        assertThat(count(REFUSAL)).as("the refusal is reported once per sync episode, not once per retry; warnings: %s", warnings)
                                  .isEqualTo(1);
        assertThat(count(STUCK)).as("the stall itself is still reported periodically (#1447)").isPositive();
    }

    private long count(String fragment) {
        return warnings.stream().filter(message -> message.contains(fragment)).count();
    }

    private void capture() {
        appender.start();
        var ctx = (LoggerContext) LogManager.getContext(false);
        var configuration = ctx.getConfiguration();
        var name = RabiaEngine.class.getName();
        var existing = configuration.getLoggerConfig(name);
        if (!name.equals(existing.getName())) {
            existing = new LoggerConfig(name, Level.WARN, false);
            configuration.addLogger(name, existing);
        }
        loggerConfig = existing;
        originalLevel = existing.getLevel();
        loggerConfig.addAppender(appender, Level.WARN, null);
        loggerConfig.setLevel(Level.WARN);
        ctx.updateLoggers();
    }
}
