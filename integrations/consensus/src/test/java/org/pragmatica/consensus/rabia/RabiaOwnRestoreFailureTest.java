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


/// #1020 — what happens when a node CANNOT read its own persisted snapshot.
///
/// Before #1020 `activateWithoutAdoption` always activated. It now routes the own-restore arm through
/// `restoreState`, whose `activate()` hangs off `onSuccessRun` — so a `restoreSnapshot` that FAILS
/// skips activation and the engine stays `Syncing`, re-entering the same branch on every retry tick.
/// That is a behaviour change introduced by this ticket, and nothing covered it.
///
/// **This class pins the CURRENT behaviour; it does not endorse it.** Whether a failed restore should
/// wedge the node, whether the wedge should be bounded or terminal, and what the readiness surface
/// should report while it persists are **#1468's** decisions ("Decide the contract for a failed
/// consensus own-restore: the node wedges in JOINING forever and start() never resolves"). #1020
/// deliberately takes none of them — it makes the existing behaviour legible and loud, and leaves an
/// ENABLED tripwire so the decision cannot be taken by accident.
///
/// **Retargeted from #1013 to #1468 (2026-09-23).** #1013 narrowed to the STORAGE metadata-snapshot
/// restore on the boot path and closes with PR #1418; this CONSENSUS own-restore arm is disjoint from
/// it (different files, different symbols — #1418 does not redden this class) and moved to #1468,
/// which carries the readiness trace. Left as-is, an enabled tripwire would have gone on directing
/// readers to a closed ticket nobody will reopen.
///
/// The method name still reads `untilTicket1013Decides`: it records which ticket RAISED the tripwire,
/// not which one owns the decision. The decision is #1468's.
///
/// The tripwire is enabled rather than `@Disabled` on purpose: a disabled test guarantees nothing and
/// sits forgotten, while an enabled one reddens at exactly the moment someone changes the behaviour
/// and hands them the ruling in the failure message.
///
/// **The negative assertion is meaningful only because of the control.** "The node is not ACTIVE after
/// N ms" is satisfied just as well by a fixture that never got anywhere — a dead subject and a
/// suppressed behaviour read identically. `#ownRestoreSucceeds_activates` runs the identical fixture
/// with a SUCCEEDING `restoreSnapshot` and activates well inside the same budget, which is what makes
/// the zero in the tripwire a genuine absence rather than an unreadable one.
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

    /// TRIPWIRE — pins that a node whose own snapshot cannot be restored does NOT activate.
    ///
    /// If you are here from **#1468**, this failure is the decision record, not a bug: #1020 measured
    /// this behaviour, declined to change it, and left this test so the change would be deliberate.
    @Test
    void ownRestoreFails_staysInactive_untilTicket1013Decides() {
        var engine = coldStarted(3, new FailingRestoreStateMachine(), durableAt(OWN_PHASE, OWN_SNAPSHOT));

        engine.processSyncResponse(cold(NODE_2, Phase.phase(4), PEER_SNAPSHOT));

        assertThat(becameActive(engine)).as("""
                                           TRIPWIRE (#1020 → #1468): a node whose own persisted snapshot fails to \
                                           restore currently does NOT activate — it stays Syncing and re-enters this \
                                           branch on every retry tick. #1020 pinned that deliberately WITHOUT \
                                           endorsing it: the choice between activating anyway, wedging with a bounded \
                                           or terminal diagnostic, and what readiness reports meanwhile belongs to \
                                           #1468 ("Decide the contract for a failed consensus own-restore: the node \
                                           wedges in JOINING forever and start() never resolves"). If you are here \
                                           from #1468, this test IS the decision record — change it deliberately and \
                                           say in the commit message which of those you chose and why. This pointer \
                                           was #1013 until 2026-09-23; #1013 narrowed to the storage boot path and \
                                           closed with PR #1418. If you are here from anything else, you have \
                                           probably changed activation on the own-restore path by accident.\
                                           """)
                  .isFalse();
    }

    /// Pins the diagnostic itself. The ERROR is the ONLY operator signal on this path — the periodic
    /// stuck-in-`Syncing` WARN is structurally suppressed here (#1447) — so it must name the
    /// consequence and render the cause, not log a bare object.
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
        assertThat(awaitCondition(() -> network.getMessages()
                                               .stream()
                                               .anyMatch(SyncRequest.class::isInstance))).as("engine must have started its sync round before responses are delivered")
                  .isTrue();

        return engine;
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
