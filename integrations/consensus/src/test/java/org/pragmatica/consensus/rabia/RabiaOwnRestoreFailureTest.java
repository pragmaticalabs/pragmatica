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
/// **Decided (owner ruling, session 27): fail closed.** A node whose own persisted history cannot be
/// restored never activates. #1390's boot recovery, which also kept such a node out of every sync
/// round, is removed with the vote WAL (owner ruling, session 28: cores run in-memory Rabia), so the
/// own-restore arm is again `activateWithoutAdoption` → `restoreState`: a failed restore skips
/// `activate()`, records the cause as the authority failure (reported by `voterReconfigurationStatus()`
/// and fencing activation on later sync rounds), and `logRestoreFailure` reports it at ERROR.
/// **#1468 stays OPEN** for whether that stop is bounded (wedge) or terminal (exit), and what the start
/// promise and readiness surface report meanwhile.
///
/// **The negative assertions are meaningful only because of the control.** `#ownRestoreSucceeds_activates`
/// runs the identical fixture with a SUCCEEDING `restoreSnapshot` and activates well inside the same
/// budget, so the tripwire's zeros are a genuine absence rather than a dead subject.
class RabiaOwnRestoreFailureTest {
    private static final String LOGGER_NAME = RabiaEngine.class.getName();
    private static final String FAILURE_FRAGMENT = "FAILED to restore state and is NOT active";
    private static final String CONSEQUENCE_FRAGMENT = "serves no requests";
    private static final NodeId NODE_1 = nodeId("node-1").unwrap();
    private static final NodeId NODE_2 = nodeId("node-2").unwrap();
    private static final long ACTIVATION_BUDGET_MILLIS = 2_000;
    private static final byte[] OWN_SNAPSHOT = "own".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PEER_SNAPSHOT = "peer".getBytes(StandardCharsets.UTF_8);
    private static final Phase OWN_PHASE = Phase.phase(5);
    private static final String STUCK_FRAGMENT = "still SYNCING after";
    private static final int WARN_ROUNDS = 6;
    private static final long FAST_RETRY_MILLIS = 20;
    private static final org.pragmatica.lang.io.TimeSpan FAST_RETRY = timeSpan(FAST_RETRY_MILLIS).millis();
    private static final long STUCK_BUDGET_MILLIS = 5_000;
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
        loggerConfig.addAppender(appender, Level.WARN, null);
        loggerConfig.setLevel(Level.WARN);
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
    /// closed. The node never activates on history it cannot read.
    @Test
    void ownRestoreFails_failsClosed_neverActivates() {
        var engine = coldStarted(3, new FailingRestoreStateMachine(), durableAt(OWN_PHASE, OWN_SNAPSHOT));

        engine.processSyncResponse(cold(NODE_2, Phase.phase(4), PEER_SNAPSHOT));
        assertThat(becameActive(engine)).as("""
                                           TRIPWIRE (#1468, decided by owner ruling, session 27): a node whose own \
                                           persisted snapshot fails to restore FAILS CLOSED and never activates. #1468 \
                                           stays open only for bounded-wedge vs termination and for the start promise; \
                                           if you are changing activation on this path, you are taking that decision — \
                                           record it.\
                                           """)
                  .isFalse();
        assertThat(engine.voterReconfigurationStatus().failure())
            .as("#1468: the own-restore failure is reported as the authority failure, not only logged")
            .contains(UNREADABLE_SNAPSHOT.message());
    }

    /// Pins the diagnostic itself. The ERROR is the per-attempt operator signal on this path, so it must
    /// name the consequence and render the cause, not log a bare object. The periodic stuck-in-`Syncing`
    /// WARN reports the stall's length on top of it (#1447, pinned below).
    @Test
    void ownRestoreFails_logsAtErrorNamingTheConsequence() {
        var engine = coldStarted(3, new FailingRestoreStateMachine(), durableAt(OWN_PHASE, OWN_SNAPSHOT));

        engine.processSyncResponse(cold(NODE_2, Phase.phase(4), PEER_SNAPSHOT));
        becameActive(engine);
        assertThat(appender.capturedErrors()).as("a failed own-restore must report the consequence and the cause, not a bare object")
                  .isNotEmpty()
                  .anyMatch(message -> message.contains(FAILURE_FRAGMENT)
                                       && message.contains(CONSEQUENCE_FRAGMENT)
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

    /// #1447 — the stuck-sync WARN was structurally suppressed on the adoption loop: `adoptCollectedState`
    /// reset the round counter on every entry, and at `clusterSize` 1 `doSynchronize` returned on the
    /// adoption branch before counting at all. A node that cannot restore its own history must now report
    /// the stall within [RabiaEngine#WARN_EVERY_N_SYNC_ROUNDS] retries.
    ///
    /// Size 1: adoption runs on every round with zero responses (the unreachable arm). Mutation that
    /// reddens it: restore the early `return` on the adoption branch of `doSynchronize`.
    @Test
    void ownRestoreFails_singleNode_reportsTheStuckSyncWithinBoundedRetries() {
        var engine = started(1, new FailingRestoreStateMachine(), durableAt(OWN_PHASE, OWN_SNAPSHOT), FAST_RETRY).engine();

        assertThat(awaitCondition(() -> !stuckWarnings().isEmpty(), STUCK_BUDGET_MILLIS))
            .as("#1447: a single node looping on a failed own-restore must emit the stuck-sync WARN; errors seen: %s",
                appender.capturedErrors())
            .isTrue();
        assertThat(stuckWarnings()).allMatch(message -> message.contains("adoption threshold is met")
                                                        && message.contains(UNREADABLE_SNAPSHOT.message()));
        assertThat(engine.isActive()).as("and it still fails closed (#1468)").isFalse();
    }

    /// Size 3: a peer answers every sync request, so adoption re-enters on every round. Mutation that
    /// reddens it: restore `syncRounds.set(0)` at the top of `adoptCollectedState`.
    @Test
    void ownRestoreFails_threeNodes_reportsTheStuckSyncWithinBoundedRetries() {
        var network = new AnsweringNetwork();
        var engine = started(3, new FailingRestoreStateMachine(), durableAt(OWN_PHASE, OWN_SNAPSHOT), FAST_RETRY, network).engine();

        network.answerWith(engine);
        assertThat(awaitCondition(() -> !stuckWarnings().isEmpty(), STUCK_BUDGET_MILLIS))
            .as("#1447: a node re-entering adoption every round must emit the stuck-sync WARN; answered %s requests",
                network.answered())
            .isTrue();
        assertThat(network.answered()).as("adoption was re-entered on several rounds, not once")
                                      .isGreaterThanOrEqualTo(WARN_ROUNDS);
        assertThat(engine.isActive()).isFalse();
    }

    /// CONTROL for both — the same fixtures with a SUCCEEDING restore activate and stay silent for longer
    /// than it takes the failing arm to warn, so the WARN is caused by the stall and adds no noise to a
    /// healthy adoption.
    @Test
    void ownRestoreSucceeds_emitsNoStuckSyncWarning() {
        var single = started(1, new RecordingStateMachine(), durableAt(OWN_PHASE, OWN_SNAPSHOT), FAST_RETRY).engine();
        var network = new AnsweringNetwork();
        var trio = started(3, new RecordingStateMachine(), durableAt(OWN_PHASE, OWN_SNAPSHOT), FAST_RETRY, network).engine();

        network.answerWith(trio);
        assertThat(becameActive(single)).isTrue();
        assertThat(becameActive(trio)).isTrue();
        awaitCondition(() -> false, FAST_RETRY_MILLIS * WARN_ROUNDS * 3);
        assertThat(stuckWarnings()).as("a healthy adoption reports no stall").isEmpty();
    }

    private List<String> stuckWarnings() {
        return appender.capturedWarnings()
                       .stream()
                       .filter(message -> message.contains(STUCK_FRAGMENT))
                       .toList();
    }

    /// Answers every `SyncRequest` the engine sends with a COLD response from NODE_2, as a live peer would.
    private static final class AnsweringNetwork extends TestClusterNetwork {
        private final java.util.concurrent.atomic.AtomicReference<RabiaEngine<TestCommand>> engine = new java.util.concurrent.atomic.AtomicReference<>();
        private final java.util.concurrent.atomic.AtomicInteger answered = new java.util.concurrent.atomic.AtomicInteger();

        void answerWith(RabiaEngine<TestCommand> target) {
            engine.set(target);
            target.processSyncResponse(cold(NODE_2, Phase.phase(4), PEER_SNAPSHOT));
        }

        int answered() {
            return answered.get();
        }

        @Override
        public <M extends org.pragmatica.consensus.ProtocolMessage> Unit send(NodeId nodeId, M message) {
            var result = super.send(nodeId, message);

            if (message instanceof SyncRequest && nodeId.equals(NODE_2)) {
                Option.option(engine.get()).onPresent(target -> {
                    answered.incrementAndGet();
                    target.processSyncResponse(cold(NODE_2, Phase.phase(4), PEER_SNAPSHOT));
                });
            }

            return result;
        }
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

    /// Constructs and notifies the engine without waiting for a sync round.
    private Started started(int clusterSize,
                            StateMachine<TestCommand> stateMachine,
                            RabiaPersistence<TestCommand> persistence) {
        return started(clusterSize, stateMachine, persistence, timeSpan(60).seconds());
    }

    private Started started(int clusterSize,
                            StateMachine<TestCommand> stateMachine,
                            RabiaPersistence<TestCommand> persistence,
                            org.pragmatica.lang.io.TimeSpan syncRetry) {
        return started(clusterSize, stateMachine, persistence, syncRetry, new TestClusterNetwork());
    }

    private Started started(int clusterSize,
                            StateMachine<TestCommand> stateMachine,
                            RabiaPersistence<TestCommand> persistence,
                            org.pragmatica.lang.io.TimeSpan syncRetry,
                            TestClusterNetwork network) {
        var engine = new RabiaEngine<>(new TestTopologyManager(NODE_1, clusterSize),
                                       network,
                                       stateMachine,
                                       ProtocolConfig.consensusConfig(timeSpan(60).seconds(), syncRetry),
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

    /// In-memory log4j2 appender capturing WARN-and-above messages for assertions.
    private static final class CapturingAppender extends AbstractAppender {
        private final List<String> messages = new CopyOnWriteArrayList<>();
        private final List<String> warnings = new CopyOnWriteArrayList<>();

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
            } else if (event.getLevel().isMoreSpecificThan(Level.WARN)) {
                warnings.add(event.getMessage().getFormattedMessage());
            }
        }

        List<String> capturedErrors() {
            return List.copyOf(messages);
        }

        List<String> capturedWarnings() {
            return List.copyOf(warnings);
        }
    }

    private static boolean becameActive(RabiaEngine<TestCommand> engine) {
        return awaitCondition(engine::isActive);
    }

    private static boolean awaitCondition(BooleanSupplier condition) {
        return awaitCondition(condition, ACTIVATION_BUDGET_MILLIS);
    }

    private static boolean awaitCondition(BooleanSupplier condition, long budgetMillis) {
        var deadline = System.nanoTime() + MILLISECONDS.toNanos(budgetMillis);

        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }

            Thread.onSpinWait();
        }

        return condition.getAsBoolean();
    }
}
