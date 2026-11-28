/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.consensus.leader.fsm;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #3846 scan S1, restated over the SINKS (ruling R1): the three ways to move the leader order, found by NAME in every
/// call and every member reference of leader main (`episode::followUp` included, whatever the receiver, so an alias is a
/// hit too), keyed by the ENCLOSING METHOD, never by a line:
/// - `SerialExecutor.execute` (Ctx's `serial`),
/// - `Fsm.dispatch`,
/// - `Fsm.followUp`.
///
/// The scan over-reports on purpose (fails closed): a new method that reaches a sink is a new key, and a new key needs a
/// row of the memo's paths table before this set changes. Each sink clause has its own control, planted through the same
/// entry point; the planted `runRaw` is the failure input of R1.
///
/// "Not claimed" rows: every call out of the drainer into code this scan cannot read (the router, the react executor, and
/// through them the listeners) is a committed row of [#EXITS], so a new exit is a new row, never silence. The observer
/// (`Fsm` line `observer.onTransition`) is outside leader main: S2's scope; production's is `FsmObserver.noop()`.
class LeaderSinkScanTest {
    private static final Path LEADER_MAIN = Path.of("src/main/java/org/pragmatica/consensus/leader");
    private static final String CTX = "fsm/LeaderElectionContext.java";
    private static final String CTX_KEY = "LeaderElectionContext.java:LeaderElectionContext.";
    private static final String STATE_KEY = "LeaderElectionState.java:LeaderElectionState.";

    /// Head set. `serial.execute`: EpisodeHandle.serialized x2, Inbound.serially, Inbound.stop, submitInOrder (r3846p F3).
    /// `fsm.dispatch`: dispatchConfined only. `followUp`: the five hook sites of r3846p F1 plus `fsm.followUp` in
    /// followUpIfLive. Entries leave with lanes C, St and F.
    /// Lane C2 adds `LeaderPreVote.verdict > dispatch`: the scan reads call names, and that call is `EpisodeHandle.dispatch` (the
    /// suspicion handle's own route, which reaches the FSM only through `dispatchIfLive`, a WRAPPER_SET row), not `Fsm.dispatch`.
    /// Lane St's calls through the episode in scope (`handlerEpisode(tx)`, `entryEpisode`) are the same shape and joined this set the
    /// same way: the ten `LeaderElectionState` rows are `EpisodeHandle.dispatch`, which reaches the FSM only through `dispatchIfLive`.
    private static final Map<String, Integer> SINK_SET = Map.ofEntries(Map.entry(CTX_KEY + "EpisodeHandle.serialized > execute", 2),
                                                                       Map.entry(CTX_KEY + "Inbound.serially > execute", 1),
                                                                       Map.entry(CTX_KEY + "Inbound.stop > execute", 1),
                                                                       Map.entry(CTX_KEY + "submitInOrder > execute", 1),
                                                                       Map.entry(CTX_KEY + "dispatchConfined > dispatch", 1),
                                                                       Map.entry(CTX_KEY + "followUpIfLive > followUp", 1),
                                                                       Map.entry(STATE_KEY + "AwaitingKvSync.onEntered > followUp", 1),
                                                                       Map.entry(STATE_KEY + "Led.leftTheViewOnLocalEntry > followUp", 1),
                                                                       Map.entry(STATE_KEY + "QuorumWaiting.advanceOrPoll > followUp", 1),
                                                                       Map.entry(STATE_KEY + "QuorumWaiting.onEntered > followUp", 1),
                                                                       Map.entry(STATE_KEY + "followUpKvLeader > followUp", 1),
                                                                       Map.entry("LeaderPreVote.java:LeaderPreVote.verdict > dispatch", 1),
                                                                       Map.entry(STATE_KEY + "AwaitingKvSync.armGraceTimer > dispatch", 1),
                                                                       Map.entry(STATE_KEY + "adoptLeaderFromKvIfPresent > dispatch", 1),
                                                                       Map.entry(STATE_KEY + "checkLeaderLease > dispatch", 1),
                                                                       Map.entry(STATE_KEY + "electLocallyOnTick > dispatch", 1),
                                                                       Map.entry(STATE_KEY + "holdLeaderToView > dispatch", 1),
                                                                       Map.entry(STATE_KEY + "pollConsensusAndKv > dispatch", 1),
                                                                       Map.entry(STATE_KEY + "rearmGrace > dispatch", 1),
                                                                       Map.entry(STATE_KEY + "scheduleElectionTickInto > dispatch", 1),
                                                                       Map.entry(STATE_KEY + "scheduleStaircaseFirstTick > dispatch", 1),
                                                                       Map.entry(STATE_KEY + "sendProposal > dispatch", 3));

    /// The in-leader wrappers that reach `Fsm.dispatch` (memo S1(b)); lanes C, St and F delete them one by one (lane C1 adds `dispatchIfLive`, the handle's own route, INV-E P3).
    private static final Map<String, Integer> WRAPPER_SET = Map.ofEntries(Map.entry(CTX_KEY + "dispatchTransportView > dispatchConfined", 1),
                                                                          Map.entry(CTX_KEY + "shutdownInOrder > dispatchConfined", 1),
                                                                          Map.entry(CTX_KEY + "dispatchIfLive > dispatchConfined", 1),
                                                                          Map.entry(CTX_KEY + "submitInOrder > dispatchConfined", 2));

    /// NOT CLAIMED: calls out of the drainer into unreadable code. The router delivers to subscribers (listeners) and the
    /// react executor runs its task on whatever executor the host installed (default: inline, so a hook re-enters there).
    private static final Map<String, Integer> EXITS = Map.ofEntries(Map.entry(STATE_KEY + "routeLeaderChange > react", 1),
                                                                    Map.entry(STATE_KEY + "routeLeaderChange > route", 2),
                                                                    Map.entry("LeaderPreVote.java:LeaderPreVote.ask > route", 1),
                                                                    Map.entry("LeaderPreVote.java:LeaderPreVote.reply > route", 1));

    @Test
    void sinks_everyCallOfTheThreeSinksInLeaderMain_equalsTheCommittedSet() throws IOException {
        assertThat(sinks(SourceScans.readDir(LEADER_MAIN))).containsExactlyInAnyOrderEntriesOf(SINK_SET);
    }

    @Test
    void wrappers_everyCallerOfDispatchConfinedAndDispatchSelf_equalsTheCommittedSet() throws IOException {
        assertThat(wrappers(SourceScans.readDir(LEADER_MAIN))).containsExactlyInAnyOrderEntriesOf(WRAPPER_SET);
    }

    @Test
    void exits_everyCallOutOfTheDrainerIntoUnreadableCode_isACommittedNotClaimedRow() throws IOException {
        assertThat(SourceScans.drainerExits(SourceScans.readDir(LEADER_MAIN))).containsExactlyInAnyOrderEntriesOf(EXITS);
    }

    @Test
    void control_aPlantedRunRawThatExecutesOnTheSerialExecutor_isReported() throws IOException {
        var planted = planted("void runRaw(Runnable r) { serial.execute(r); }");

        assertThat(sinks(planted)).containsEntry(CTX_KEY + "runRaw > execute", 1)
                                  .hasSize(SINK_SET.size() + 1);
    }

    @Test
    void control_aPlantedAliasOfTheSerialExecutor_isReported() throws IOException {
        var planted = planted("void viaAlias(Runnable r) { var s = serial; s.execute(r); }");

        assertThat(sinks(planted)).containsEntry(CTX_KEY + "viaAlias > execute", 1);
    }

    @Test
    void control_aPlantedDispatchOnTheFsm_isReported() throws IOException {
        var planted = planted("void rawDispatch(ClusterFsmEvent e) { fsm.dispatch(e); }");

        assertThat(sinks(planted)).containsEntry(CTX_KEY + "rawDispatch > dispatch", 1)
                                  .hasSize(SINK_SET.size() + 1);
    }

    @Test
    void control_aPlantedFollowUpOnTheFsm_isReported() throws IOException {
        var planted = planted("void rawFollowUp(ClusterFsmEvent e) { fsm.followUp(e); }");

        assertThat(sinks(planted)).containsEntry(CTX_KEY + "rawFollowUp > followUp", 1)
                                  .hasSize(SINK_SET.size() + 1);
    }

    @Test
    void control_aPlantedFollowUpMemberReference_isReported() throws IOException {
        var planted = planted("void viaRef(java.util.Optional<ClusterFsmEvent> o, EpisodeHandle h) { o.ifPresent(h::followUp); }");

        assertThat(sinks(planted)).containsEntry(CTX_KEY + "viaRef > followUp", 1);
    }

    @Test
    void control_aPlantedRouteOutOfTheDrainer_isReported() throws IOException {
        var planted = planted("void leak(Object m) { router.route(m); }");

        assertThat(SourceScans.drainerExits(planted)).containsEntry(CTX_KEY + "leak > route", 1)
                                                     .hasSize(EXITS.size() + 1);
    }

    private static Map<String, Integer> sinks(Map<String, String> sources) {
        return only(SourceScans.sinkHits(sources), "execute", "dispatch", "followUp");
    }

    private static Map<String, Integer> wrappers(Map<String, String> sources) {
        return only(SourceScans.sinkHits(sources), "dispatchConfined", "dispatchSelf");
    }

    private static Map<String, Integer> only(Map<String, Integer> hits, String... names) {
        var kept = new TreeMap<String, Integer>();

        hits.forEach((key, count) -> {
            for (var name : names) {
                if (key.endsWith(" > " + name)) {
                    kept.put(key, count);
                }
            }
        });
        return kept;
    }

    /// Leader main with one method added to the context class, in memory: the same entry point as the real run.
    private static Map<String, String> planted(String method) throws IOException {
        var sources = new HashMap<>(SourceScans.readDir(LEADER_MAIN));
        var ctx = sources.get(CTX);

        sources.put(CTX, ctx.substring(0, ctx.lastIndexOf('}')) + "\n    " + method + "\n}\n");
        return sources;
    }
}
