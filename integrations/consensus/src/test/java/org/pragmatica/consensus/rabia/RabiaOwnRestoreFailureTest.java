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


/// #1020 → #1468 — what happens when a node CANNOT read its own persisted snapshot.
///
/// **Decided (owner ruling, session 27): fail closed.** A node whose own durable history cannot be
/// restored never activates. #1390's boot recovery (`RabiaEngine.ensureRecovered`) restores the
/// persisted checkpoint BEFORE any sync round; when that restore fails it records the cause as the
/// authority failure (reported through `voterReconfigurationStatus()`), stops consensus participation,
/// logs one ERROR, and never starts a sync round — so no peer response can activate it.
///
/// This REPLACES the behaviour #1020 (rc4) pinned here: stay `Syncing` and re-enter the own-restore
/// branch on every retry tick. #1020 pinned that wedge-and-retry deliberately WITHOUT endorsing it, and
/// it was never endorsed; the owner's session-27 ruling took #1468's decision for this arm in favour of
/// #1390's fail-closed contract. **#1468 stays OPEN** for what remains: whether the stop is bounded
/// (wedge) or terminal (exit), and what the start promise and readiness surface report meanwhile.
///
/// The tripwire stays ENABLED: it now pins the fail-closed contract, and it discriminates it from
/// rc4's wedge — both leave the node inactive, so inactivity alone cannot tell them apart; the
/// never-started sync round and the reported authority failure can.
///
/// **The negative assertions are meaningful only because of the control.** `#ownRestoreSucceeds_activates`
/// runs the identical fixture with a SUCCEEDING `restoreSnapshot` and activates well inside the same
/// budget, so the tripwire's zeros are a genuine absence rather than a dead subject.
class RabiaOwnRestoreFailureTest {
    private static final String LOGGER_NAME = RabiaEngine.class.getName();
    private static final String FAILURE_FRAGMENT = "stopped consensus participation because durable history failed";
    private static final NodeId NODE_1 = nodeId("node-1").unwrap();
    private static final NodeId NODE_2 = nodeId("node-2").unwrap();
    private static final long ACTIVATION_BUDGET_MILLIS = 2_000;
    private static final byte[] OWN_SNAPSHOT = "own".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PEER_SNAPSHOT = "peer".getBytes(StandardCharsets.UTF_8);
    private static final Phase OWN_PHASE = Phase.phase(5);
    private static final Cause UNREADABLE_SNAPSHOT = Causes.cause("snapshot is corrupt and cannot be decoded");

    private final List<RabiaEngine<TestCommand>> engines = new CopyOnWriteArrayList<>();

    private CapturingAppender appender;
    private LoggerConfig loggerConfig;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        appender = CapturingAppender.create("RabiaOwnRestoreFailureCapture");
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

    /// TRIPWIRE — pins #1468's decision for the restore-failure arm (owner ruling, session 27): fail
    /// closed. The node never activates, never starts a sync round, and reports the cause.
    @Test
    void ownRestoreFails_failsClosed_neverActivates() {
        var started = started(3, new FailingRestoreStateMachine(), durableAt(OWN_PHASE, OWN_SNAPSHOT));

        started.engine().processSyncResponse(cold(NODE_2, Phase.phase(4), PEER_SNAPSHOT));
        assertThat(becameActive(started.engine())).as("""
                                                     TRIPWIRE (#1468, decided by owner ruling, session 27): a node whose own \
                                                     persisted snapshot fails to restore FAILS CLOSED and never activates. This \
                                                     replaced the wedge-and-retry rc4 pinned here under #1020, which was never \
                                                     endorsed. #1468 stays open only for bounded-wedge vs termination and for the \
                                                     start promise; if you are changing activation on this path, you are taking \
                                                     that decision — record it.\
                                                     """)
                  .isFalse();
        assertThat(started.network().getMessages())
            .as("fail-closed never enters synchronization: rc4's wedge started a sync round and retried it forever")
            .noneMatch(SyncRequest.class::isInstance);
        assertThat(started.engine().voterReconfigurationStatus().failure())
            .as("the restore failure is reported as the authority failure, not only logged")
            .contains(UNREADABLE_SNAPSHOT.message());
    }

    /// Pins the diagnostic itself: the ERROR names that consensus participation stopped because
    /// durable history failed, and renders the cause.
    @Test
    void ownRestoreFails_logsAtErrorNamingTheConsequence() {
        var started = started(3, new FailingRestoreStateMachine(), durableAt(OWN_PHASE, OWN_SNAPSHOT));

        becameActive(started.engine());
        assertThat(appender.capturedErrors()).as("a failed own-restore must report the consequence and the cause, not a bare object")
                  .isNotEmpty()
                  .anyMatch(message -> message.contains(FAILURE_FRAGMENT)
                                       && message.contains(UNREADABLE_SNAPSHOT.message()));
    }

    /// CONTROL — the identical fixture with a SUCCEEDING `restoreSnapshot` activates well inside the
    /// same budget, and logs no restore failure. Without this, the tripwire's `isFalse()` would be
    /// satisfied by a fixture that never reached the restore at all.
    @Test
    void ownRestoreSucceeds_activates() {
        var stateMachine = new RecordingStateMachine();
        var engine = coldStarted(3, stateMachine, durableAt(OWN_PHASE, OWN_SNAPSHOT));

        engine.processSyncResponse(cold(NODE_2, Phase.phase(4), PEER_SNAPSHOT));

        assertThat(becameActive(engine)).as("control: the same path DOES activate when the snapshot restores, so the tripwire's zero is a real absence")
                  .isTrue();
        assertThat(stateMachine.lastRestored()).as("control: the own-restore branch is the one exercised").isEqualTo(OWN_SNAPSHOT);
        assertThat(appender.capturedErrors()).as("a successful restore reports no failure")
                  .noneMatch(message -> message.contains(FAILURE_FRAGMENT));
    }

    /// A state machine that cannot read its own snapshot: a corrupt or truncated `state.toml`.
    private static final class FailingRestoreStateMachine extends TestStateMachine {
        @Override
        public Result<Unit> restoreSnapshot(byte[] snapshot) {
            return UNREADABLE_SNAPSHOT.result();
        }
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

    /// Constructs and notifies the engine without waiting for a sync round — the fail-closed arms never
    /// start one.
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

    private static boolean becameActive(RabiaEngine<TestCommand> engine) {
        return awaitCondition(engine::isActive);
    }

    private static boolean awaitCondition(BooleanSupplier condition) {
        var deadline = System.nanoTime() + MILLISECONDS.toNanos(ACTIVATION_BUDGET_MILLIS);

        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }

            Thread.onSpinWait();
        }

        return condition.getAsBoolean();
    }
}
