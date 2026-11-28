/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.consensus.leader.fsm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #3846 scans S3 and S4 (memo r3 §2.4; S1 is [LeaderSinkScanTest], S2 is the statemachine's FsmOrderPathScanTest).
/// Each scan asserts an EXACT committed set over the real sources and has positive controls: a fixture source with one
/// planted hit, scanned through the same entry point, must be reported. A scan without its control is not evidence.
///
/// - S3 lifetime effects: `end()`, `schedule(..)` and `scheduleAtFixedRate(..)` in leader main whose receiver is not
///   `SharedScheduler`. Head set: empty.
/// - S4a: no hook body (`onEntry`, `onEntered`, `onExit`, `onCasLost`) reaches a dispatch. Head set: empty (computed by
///   lane A: the memo's [unverified] is now a measured empty set).
/// - S4b: the `followUp` sites reachable from hook bodies. Head set: FIVE, not the memo's three (r3846p F1): the memo
///   missed the `episode::followUp` member references in `AwaitingKvSync.onEntered` and `followUpKvLeader`.
class LeaderOrderPathScanTest {
    private static final Path LEADER_MAIN = Path.of("src/main/java/org/pragmatica/consensus/leader");
    private static final Path STATE = LEADER_MAIN.resolve("fsm/LeaderElectionState.java");

    private static final Map<String, Integer> HOOK_FOLLOW_UPS = Map.of("LeaderElectionState.AwaitingKvSync.onEntered > followUp", 1,
                                                                       "LeaderElectionState.Led.leftTheViewOnLocalEntry > followUp", 1,
                                                                       "LeaderElectionState.QuorumWaiting.advanceOrPoll > followUp", 1,
                                                                       "LeaderElectionState.QuorumWaiting.onEntered > followUp", 1,
                                                                       "LeaderElectionState.followUpKvLeader > followUp", 1);

    private static final String FIXTURE = """
        class S {
            void onEntry() { helper(); }
            void helper() { dispatchSelf(e); }
            void onEntered() { serialized(() -> dispatchSelf(e)); }
            void onExit() { episode.followUp(e); }
            void onCasLost() { opt.onPresent(episode::followUp); }
        }
        """;

    @Test
    void lifetimeEffects_noEndOrUnsharedScheduleInLeaderMain_isEmpty() throws IOException {
        assertThat(SourceScans.lifetimeEffects(SourceScans.readDir(LEADER_MAIN))).isEmpty();
    }

    @Test
    void control_aPlantedScheduleOnAForeignTimerAndAPlantedEnd_areReportedButSharedSchedulerIsNot() {
        var fixture = Map.of("Fix.java", """
            class C {
                void f(Timer t) { t.schedule(a, 1); SharedScheduler.schedule(b, 2); x.end(); t.scheduleAtFixedRate(a, 1, 1); }
            }
            """);

        assertThat(SourceScans.lifetimeEffects(fixture)).containsOnlyKeys("Fix.java:C.f > schedule(t)",
                                                                           "Fix.java:C.f > end(x)",
                                                                           "Fix.java:C.f > scheduleAtFixedRate(t)");
    }

    @Test
    void hookDispatchers_noHookBodyReachesADispatch_isEmpty() throws IOException {
        assertThat(SourceScans.hookDispatchers(Files.readString(STATE))).isEmpty();
    }

    @Test
    void hookFollowUps_theSitesReachableFromHookBodies_equalTheCommittedFive() throws IOException {
        assertThat(SourceScans.hookFollowUps(Files.readString(STATE))).containsExactlyInAnyOrderEntriesOf(HOOK_FOLLOW_UPS);
    }

    @Test
    void control_aHookCallingAHelperThatDispatches_isReported() {
        assertThat(SourceScans.hookDispatchers(FIXTURE)).contains("S.onEntry");
    }

    @Test
    void control_aHookWhoseSerializedLambdaDispatches_isNotReported() {
        assertThat(SourceScans.hookDispatchers(FIXTURE)).doesNotContain("S.onEntered");
    }

    @Test
    void control_aFollowUpInAnOnExitAndInAnOnCasLostMemberReference_areReported() {
        assertThat(new TreeMap<>(SourceScans.hookFollowUps(FIXTURE))).containsOnlyKeys("S.onExit > followUp", "S.onCasLost > followUp");
    }

    @Test
    void control_aPlantedHelperDispatchingFromAHookInTheRealStateSource_turnsS4aRed() throws IOException {
        var source = Files.readString(STATE);
        var planted = source.substring(0, source.lastIndexOf('}'))
                      + "\n    void onExit(Object e) { dispatchSelf(e); }\n}\n";

        assertThat(SourceScans.hookDispatchers(planted)).contains("LeaderElectionState.onExit");
    }
}
