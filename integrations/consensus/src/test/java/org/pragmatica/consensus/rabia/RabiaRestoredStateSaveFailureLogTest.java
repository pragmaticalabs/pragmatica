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

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

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
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.StateMachine;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestClusterNetwork;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestCommand;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestStateMachine;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestTopologyManager;
import org.pragmatica.consensus.rabia.RabiaPersistence.SavedState;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.SyncRequest;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.SyncResponse;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.assertj.core.api.Assertions.assertThat;


/// #1020 → #1468 — the re-persist that follows restoring a node's own durable history.
///
/// **Decided (owner ruling, session 27): fail closed.** rc4 (#1020) let the node ACTIVATE after that
/// save failed, on the restore it held in memory, and pinned only that the failure was logged. That is
/// a durability risk: the node serves and votes on state its disk does not hold, and loses it one
/// restart later. Under #1390's boot recovery the save is the checkpoint that publishes the recovered
/// prefix (`RabiaEngine.recoverLocalState` → `saveAuthority`), and its failure stops consensus
/// participation — the node never activates and says so at ERROR. #1468 stays OPEN for bounded wedge
/// vs termination and for the start promise.
///
/// **This test is an instrument, and its assertions are deliberately positive.** A renamed logger or a
/// detached appender leaves the capture EMPTY, which fails `isNotEmpty()`. The control runs the
/// identical restore with a SUCCEEDING save and asserts the node activates and logs no failure — it is
/// what makes the failing arm's inactivity a genuine refusal rather than a fixture that never ran.
class RabiaRestoredStateSaveFailureLogTest {
    private static final String LOGGER_NAME = RabiaEngine.class.getName();
    private static final String FAILURE_FRAGMENT = "stopped consensus participation because durable history failed";
    private static final NodeId NODE_1 = nodeId("node-1").unwrap();
    private static final NodeId NODE_2 = nodeId("node-2").unwrap();
    private static final long ACTIVATION_TIMEOUT_MILLIS = 5_000;
    private static final byte[] OWN_SNAPSHOT = "own".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PEER_SNAPSHOT = "peer".getBytes(StandardCharsets.UTF_8);
    private static final Phase OWN_PHASE = Phase.phase(5);
    private static final Cause DISK_FULL = Causes.cause("no space left on device");

    private final List<RabiaEngine<TestCommand>> engines = new CopyOnWriteArrayList<>();

    private CapturingAppender appender;
    private LoggerConfig loggerConfig;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        appender = CapturingAppender.create("RabiaRestoredStateSaveFailureCapture");
        appender.start();
        var ctx = (LoggerContext) LogManager.getContext(false);
        var configuration = ctx.getConfiguration();

        loggerConfig = getOrCreateLoggerConfig(configuration);
        originalLevel = loggerConfig.getLevel();
        loggerConfig.addAppender(appender, Level.ERROR, null);
        loggerConfig.setLevel(Level.ERROR);
        ctx.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        engines.forEach(engine -> engine.stop()
                                        .await());

        var ctx = (LoggerContext) LogManager.getContext(false);

        loggerConfig.removeAppender(appender.getName());
        loggerConfig.setLevel(originalLevel);
        ctx.updateLoggers();
        appender.stop();
    }

    /// A restart from disk restores its own snapshot, and the re-persist that should make the restore
    /// durable fails. The node must fail closed — never activate on history its disk does not hold —
    /// and the ERROR must name that consensus participation stopped, with the cause.
    @Test
    void restoredStateSaveFails_failsClosedAndLogsAtError() {
        var stateMachine = new RecordingStateMachine();
        var started = started(3, stateMachine, failingSaveAt(OWN_PHASE, OWN_SNAPSHOT));

        started.engine().processSyncResponse(cold(NODE_2, Phase.phase(4), PEER_SNAPSHOT));

        assertThat(awaitActive(started.engine())).as("""
                                                    a failed re-persist of restored own history must FAIL CLOSED (owner ruling, \
                                                    session 27, #1468): rc4 activated here on in-memory state its disk did not hold\
                                                    """)
                  .isFalse();
        assertThat(stateMachine.lastRestored()).as("precondition: the restore itself must have run — only the save failed")
                  .isEqualTo(OWN_SNAPSHOT);
        assertThat(started.network().getMessages())
            .as("fail-closed never enters synchronization")
            .noneMatch(SyncRequest.class::isInstance);
        assertThat(appender.capturedErrors()).as("the failed re-persist must be logged at ERROR, naming the consequence and the cause")
                  .isNotEmpty()
                  .anyMatch(message -> message.contains(FAILURE_FRAGMENT)
                                       && message.contains(DISK_FULL.message()));
    }

    /// CONTROL — the identical restore with a SUCCEEDING save logs no failure line. Without the test
    /// above this would be satisfied by an appender that never worked; with it, the pair separates
    /// "nothing to report" from "nothing observed".
    @Test
    void restoredStateSaveSucceeds_logsNoFailure() {
        var stateMachine = new RecordingStateMachine();
        var engine = coldStarted(3, stateMachine, durableAt(OWN_PHASE, OWN_SNAPSHOT));

        engine.processSyncResponse(cold(NODE_2, Phase.phase(4), PEER_SNAPSHOT));

        assertThat(awaitActive(engine)).isTrue();
        assertThat(stateMachine.lastRestored()).as("precondition: the same restore path must have run")
                  .isEqualTo(OWN_SNAPSHOT);
        assertThat(appender.capturedErrors()).as("a save that succeeded must not report a persist failure")
                  .noneMatch(message -> message.contains(FAILURE_FRAGMENT));
    }

    /// Persistence holding a durable snapshot whose every `save` FAILS: a node restarted from disk
    /// onto a backup path it can no longer write.
    private static RabiaPersistence<TestCommand> failingSaveAt(Phase phase, byte[] snapshot) {
        record failingSave(Phase phase, byte[] snapshot) implements RabiaPersistence<TestCommand> {
            @Override
            public Result<Unit> save(StateMachine<TestCommand> stateMachine,
                                     Phase lastCommittedPhase,
                                     Collection<Batch<TestCommand>> pendingBatches) {
                return DISK_FULL.result();
            }

            @Override
            public Option<SavedState<TestCommand>> load() {
                return Option.some(SavedState.savedState(snapshot, phase, List.of()));
            }
        }

        return new failingSave(phase, snapshot);
    }

    /// CONTROL fixture — the same durable snapshot with a save that succeeds.
    private static RabiaPersistence<TestCommand> durableAt(Phase phase, byte[] snapshot) {
        record durable(Phase phase, byte[] snapshot) implements RabiaPersistence<TestCommand> {
            @Override
            public Result<Unit> save(StateMachine<TestCommand> stateMachine,
                                     Phase lastCommittedPhase,
                                     Collection<Batch<TestCommand>> pendingBatches) {
                return Result.success(Unit.unit());
            }

            @Override
            public Option<SavedState<TestCommand>> load() {
                return Option.some(SavedState.savedState(snapshot, phase, List.of()));
            }
        }

        return new durable(phase, snapshot);
    }

    private static SyncResponse<TestCommand> cold(NodeId sender, Phase phase, byte[] snapshot) {
        return new SyncResponse<>(sender,
                                  SavedState.savedState(snapshot, phase, List.of()),
                                  ResponderState.COLD);
    }

    private RabiaEngine<TestCommand> coldStarted(int clusterSize,
                                                 StateMachine<TestCommand> stateMachine,
                                                 RabiaPersistence<TestCommand> persistence) {
        var started = started(clusterSize, stateMachine, persistence);

        assertThat(awaitCondition(() -> started.network()
                                               .getMessages()
                                               .stream()
                                               .anyMatch(SyncRequest.class::isInstance))).as("engine must have started its sync round before responses are delivered")
                  .isTrue();

        return started.engine();
    }

    private record Started(RabiaEngine<TestCommand> engine, TestClusterNetwork network) {}

    /// Constructs and notifies the engine without waiting for a sync round — the fail-closed arm never
    /// starts one.
    private Started started(int clusterSize,
                            StateMachine<TestCommand> stateMachine,
                            RabiaPersistence<TestCommand> persistence) {
        var network = new TestClusterNetwork();
        var engine = new RabiaEngine<>(new TestTopologyManager(NODE_1, clusterSize),
                                       network,
                                       stateMachine,
                                       ProtocolConfig.consensusConfig(timeSpan(60).seconds(), timeSpan(60).seconds()),
                                       ConsensusMetrics.noop(),
                                       false,
                                       persistence,
                                       timeSpan(50).millis());

        engines.add(engine);
        engine.clusterState(ClusterStateNotification.active());

        return new Started(engine, network);
    }

    private static LoggerConfig getOrCreateLoggerConfig(Configuration configuration) {
        var existing = configuration.getLoggerConfig(LOGGER_NAME);

        if (LOGGER_NAME.equals(existing.getName())) {
            return existing;
        }

        var fresh = new LoggerConfig(LOGGER_NAME, Level.ERROR, false);

        configuration.addLogger(LOGGER_NAME, fresh);

        return fresh;
    }

    private static final class RecordingStateMachine extends TestStateMachine {
        private volatile byte[] lastRestored;

        @Override
        public Result<Unit> restoreSnapshot(byte[] snapshot) {
            lastRestored = snapshot;

            return super.restoreSnapshot(snapshot);
        }

        byte[] lastRestored() {
            return lastRestored;
        }
    }

    /// In-memory log4j2 appender capturing ERROR-and-above messages for assertions.
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
            if (event.getLevel().isMoreSpecificThan(Level.ERROR)) {
                messages.add(event.getMessage().getFormattedMessage());
            }
        }

        List<String> capturedErrors() {
            return List.copyOf(messages);
        }
    }

    private static boolean awaitActive(RabiaEngine<TestCommand> engine) {
        return awaitCondition(engine::isActive);
    }

    private static boolean awaitCondition(BooleanSupplier condition) {
        var deadline = System.nanoTime() + MILLISECONDS.toNanos(ACTIVATION_TIMEOUT_MILLIS);

        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }

            Thread.onSpinWait();
        }

        return condition.getAsBoolean();
    }
}
