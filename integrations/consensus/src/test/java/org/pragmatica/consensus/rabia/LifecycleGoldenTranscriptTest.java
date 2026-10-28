// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.consensus.rabia;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.StateMachine;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Decision;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.rabia.LifecycleRig.N2;
import static org.pragmatica.consensus.rabia.LifecycleRig.N3;
import static org.pragmatica.consensus.rabia.LifecycleRig.N4;
import static org.pragmatica.consensus.rabia.LifecycleRig.N5;

/// L-0 golden transcript of today's engine lifecycle (spec `l-lifecycle-spec.md` section 7, L-0 (a)).
///
/// Every scenario reaches one lifecycle mode, fires one event, and records `(mode, engine events, outgoing messages,
/// startPromise, participation records, replays, hooks, restores)` as emitted since the event. The matrix is the
/// cross product of 11 reachable modes and 11 events (121 scenarios); the named scenarios add the sequences the matrix
/// cannot express (restore ok and failed, a voter demoted by a roster, genesis agreed and joined, an observer promoted).
/// The transcript is the neutral-PR proof: L-1, L-5 and L-10 must leave it byte for byte unchanged, and the
/// behaviour-changing PRs (L-2, L-3, L-4) must change exactly the lines they name.
///
/// Regenerate with `-Dlifecycle.golden.update=true` (writes the file under `src/test/resources`); review the diff.
class LifecycleGoldenTranscriptTest {
    private static final String GOLDEN = "lifecycle-transcript.golden";

    enum Reach {
        COLD("Stopped", false),
        GATED("Stopped", true),
        GATED_HELD("Stopped", true),
        SYNCING("Syncing", false),
        IDLE("Idle", false),
        IN_PHASE("InPhase", false),
        OBSERVING("Observing", true),
        PAUSED_ACTIVE("Paused", false),
        PAUSED_SYNCING("Paused", false),
        PAUSED_OBSERVING("Paused", true),
        TERMINATED("Stopped", false);

        final String mode;
        final boolean gated;

        Reach(String mode, boolean gated) {
            this.mode = mode;
            this.gated = gated;
        }

        LifecycleRig build() {
            var rig = LifecycleRig.rig(gated).started();

            switch (this) {
                case COLD, GATED -> {}
                case GATED_HELD -> rig.active();
                case SYNCING -> rig.active();
                case IDLE -> idle(rig);
                case IN_PHASE -> inPhase(rig);
                case OBSERVING -> observing(rig);
                case PAUSED_ACTIVE -> {
                    idle(rig);
                    rig.passive();
                }
                case PAUSED_SYNCING -> {
                    rig.active();
                    rig.passive();
                }
                case PAUSED_OBSERVING -> {
                    observing(rig);
                    rig.passive();
                }
                case TERMINATED -> rig.stop();
            }
            // The reach must land where it claims; a drifting reach would turn the matrix into noise.
            assertThat(this == TERMINATED ? "Stopped" : rig.mode()).as("reach %s", this).isEqualTo(mode);
            return rig.mark();
        }

        private static void idle(LifecycleRig rig) {
            rig.active();
            rig.emptyAnswers();
        }

        private static void inPhase(LifecycleRig rig) {
            idle(rig);
            rig.engine.apply(List.of(new RabiaEngineTest.TestCommand("x")));
            rig.settle();
        }

        private static void observing(LifecycleRig rig) {
            rig.engine.authorizeObservation();
            rig.active();
            rig.emptyAnswers();
        }
    }

    enum Fire {
        ACTIVE(LifecycleRig::active),
        PASSIVE(LifecycleRig::passive),
        AUTHORIZE_ACTIVATION(rig -> {
            rig.engine.authorizeActivation();
            rig.settle();
        }),
        AUTHORIZE_OBSERVATION(rig -> {
            rig.engine.authorizeObservation();
            rig.settle();
        }),
        SYNC_TICK(LifecycleRig::tick),
        SYNC_ANSWERS(LifecycleRig::emptyAnswers),
        DECISION_CURRENT(rig -> rig.decisionAt(0)),
        DECISION_FAR_FUTURE(rig -> rig.decisionAt(LifecycleRig.FAR)),
        PROPOSE_CURRENT(rig -> rig.proposeAt(0)),
        PROPOSE_FAR_FUTURE(rig -> rig.proposeAt(LifecycleRig.FAR)),
        STOP(LifecycleRig::stop);

        final Consumer<LifecycleRig> action;

        Fire(Consumer<LifecycleRig> action) {
            this.action = action;
        }
    }

    @Test
    void transcript_matchesTheGolden() throws IOException {
        var transcript = String.join("\n", matrix()) + "\n" + String.join("\n", named()) + "\n";

        if (Boolean.getBoolean("lifecycle.golden.update")) {
            Files.writeString(Path.of("src/test/resources/org/pragmatica/consensus/rabia", GOLDEN), transcript);
        }

        assertThat(transcript).isEqualTo(golden());
    }

    @Test
    void transcript_isStableAcrossRuns() {
        assertThat(String.join("\n", matrix())).isEqualTo(String.join("\n", matrix()));
    }

    private static String golden() throws IOException {
        try (InputStream in = LifecycleGoldenTranscriptTest.class.getResourceAsStream(GOLDEN)) {
            assertThat(in).as("golden resource %s", GOLDEN).isNotNull();

            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> matrix() {
        var lines = new ArrayList<String>();

        for (var reach : Reach.values()) {
            for (var fire : Fire.values()) {
                var rig = reach.build();

                try {
                    fire.action.accept(rig);
                    lines.add("matrix/" + reach + "/" + fire + " " + rig.delta());
                } finally {
                    rig.stop();
                }
            }
        }

        return lines;
    }

    private static List<String> named() {
        var lines = new ArrayList<String>();

        lines.add(scenario("restore-ok-nonempty-snapshot", rig -> {
            rig.active();
            rig.snapshotAnswers(10);
        }, false));
        lines.add(scenario("restore-ok-empty-snapshot", rig -> {
            rig.active();
            rig.emptyAnswers();
        }, false));
        lines.add(scenario("restore-persist-fails-fences", LifecycleRig.rig(false, LifecycleRig.failingSave()).started(), rig -> {
            rig.active();
            rig.snapshotAnswers(10);
            rig.snapshotAnswers(10);
        }));
        lines.add(scenario("restore-refused-participation-then-pause-resume", LifecycleGoldenTranscriptTest::refusedThenResume, false));
        lines.add(scenario("restore-undecodable-snapshot-three-rounds", LifecycleGoldenTranscriptTest::undecodable, false));
        lines.add(scenario("observer-restore-then-promotion", LifecycleGoldenTranscriptTest::observerPromoted, true));
        lines.add(fiveNode("voter-demoted-by-roster-removal", LifecycleGoldenTranscriptTest::demoted));
        lines.add(fiveNode("demoted-observer-pause-resume", LifecycleGoldenTranscriptTest::demotedThenResume));
        lines.add(fiveNode("roster-observer-pause-resume", LifecycleGoldenTranscriptTest::rosterObserverResume));
        lines.add(scenario("gated-observing-held-notification-then-authorize", LifecycleGoldenTranscriptTest::heldOnObserving, true));
        lines.add(scenario("gated-held-then-authorized", rig -> {
            rig.active();
            rig.engine.authorizeActivation();
            rig.settle();
        }, true));
        lines.add(scenario("stopped-cold-unsolicited-sync-answers", rig -> rig.emptyAnswers(), false));
        lines.add(scenario("idle-completed-slot-by-decision", rig -> {
            rig.active();
            rig.emptyAnswers();
            rig.decisionAt(0);
        }, false));
        lines.add(scenario("paused-applies-current-decision-stays-paused", rig -> {
            rig.active();
            rig.emptyAnswers();
            rig.passive();
            rig.decisionAt(0);
        }, false));
        lines.add(scenario("paused-far-future-decision-is-buffered", rig -> {
            rig.active();
            rig.emptyAnswers();
            rig.passive();
            rig.decisionAt(LifecycleRig.FAR);
        }, false));
        lines.add(genesis("genesis-agreed-forms-epoch-zero", false));
        lines.add(genesis("genesis-joins-formed-electorate", true));

        return lines;
    }

    private static String scenario(String name, Consumer<LifecycleRig> body, boolean gated) {
        return scenario(name, LifecycleRig.rig(gated).started(), body);
    }

    private static String fiveNode(String name, Consumer<LifecycleRig> body) {
        return scenario(name, LifecycleRig.fiveNodeRig().started(), body);
    }

    private static String scenario(String name, LifecycleRig rig, Consumer<LifecycleRig> body) {
        try {
            body.accept(rig);

            return "named/" + name + " " + rig.delta();
        } finally {
            rig.stop();
        }
    }

    private static void refusedThenResume(LifecycleRig rig) {
        rig.marker.refuse = true;
        rig.active();
        rig.snapshotAnswers(10);
        rig.passive();
        rig.marker.refuse = false;
        rig.active();
    }

    private static void undecodable(LifecycleRig rig) {
        rig.machine.undecodable = true;
        rig.active();
        for (var round = 0; round < 3; round++) {
            rig.snapshotAnswers(10);
            rig.tick();
        }
    }

    private static void observerPromoted(LifecycleRig rig) {
        rig.engine.authorizeObservation();
        rig.active();
        rig.snapshotAnswers(10);
        rig.engine.authorizeActivation();
        rig.settle();
    }

    private static void heldOnObserving(LifecycleRig rig) {
        rig.engine.authorizeObservation();
        rig.active();
        rig.emptyAnswers();
        rig.holdQuorumNotification();
        rig.engine.authorizeActivation();
        rig.settle();
    }

    private static void install(LifecycleRig rig) {
        rig.engine.initializeVoters(new VoterConfiguration(0, new ClusterConfig(List.of(LifecycleRig.N1, N2, N3))));
        rig.active();
        rig.emptyAnswers();
    }

    private static void remove(LifecycleRig rig) {
        var removal = ReconfigurationCommand.reconfigurationCommand(0, new ClusterConfig(List.of(N2, N3, N4)));

        rig.engine.processDecision(new Decision<>(N2, 0, Phase.phase(0), StateValue.V1, StateMachine.Batch.<RabiaEngineTest.TestCommand>emptyBatch(), Option.some(removal)));
        rig.settle();
    }

    private static void demoted(LifecycleRig rig) {
        install(rig);
        remove(rig);
    }

    private static void demotedThenResume(LifecycleRig rig) {
        install(rig);
        remove(rig);
        rig.passive();
        rig.active();
    }

    private static void rosterObserverResume(LifecycleRig rig) {
        install(rig);
        rig.engine.authorizeObservation();
        rig.passive();
        rig.active();
    }

    private static String genesis(String name, boolean formedAnswer) {
        var rig = LifecycleRig.genesisPendingRig();

        try {
            rig.engine.clusterState(org.pragmatica.consensus.topology.ClusterStateNotification.active());
            rig.engine.runGenesisRoundForTesting();
            rig.engine.runGenesisRoundForTesting();
            rig.settle();
            if (formedAnswer) {
                var formed = new VoterConfiguration(1, new ClusterConfig(List.of(LifecycleRig.N1, N2, N3)));

                rig.engine.genesisAnnouncement(new RabiaProtocolMessage.Asynchronous.GenesisAnnouncement(LifecycleRig.N1, 0, Option.some(formed.roster()), Option.some(formed)));
                rig.settle();
            }
            var view = new ClusterConfig(List.of(N2, N4, N5));

            for (long round = 1; round <= 2; round++) {
                for (var sender : List.of(N4, N5)) {
                    rig.engine.genesisAnnouncement(new RabiaProtocolMessage.Asynchronous.GenesisAnnouncement(sender, round, Option.some(view), Option.none()));
                }
            }
            rig.settle();

            return "named/" + name + " " + rig.delta() + " voters=" + rig.engine.voterConfiguration() + " joined=" + rig.engine.joinedFormedElectorate() + " pending=" + rig.engine.isGenesisPending();
        } finally {
            rig.stop();
        }
    }
}
