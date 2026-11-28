/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.consensus.leader.fsm;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.pragmatica.consensus.leader.fsm.censusfixture.CensusFixture;
import org.pragmatica.lang.utils.OwnerLifetime;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.TransitionRequest;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #3846 authority census (memo r3 section 2.1 item 3; rulings R1-R2). Reflective, and the authority set is COMPUTED
/// ([AuthorityCensus]), never listed: it enumerates every class compiled from `integrations/consensus/src/main/java` under
/// `org.pragmatica.consensus.leader` and `.leader.fsm`, from the compiled-classes directory.
///
/// CEN-1 (a field whose generic type mentions the authority set), CEN-2 (a non-private method returning it without taking
/// it) and CEN-3 (four visibility clauses) are RATCHETED: the test asserts the EXACT head set, not the empty set. A lane
/// may only remove entries from [#HEAD_SET]: a new violation is red, and so is a fixed violation whose entry was not
/// removed. Lane F reaches the empty set, enables [#census_isEmpty_laneF] and deletes the constant.
///
/// CEN-3 keys carry the member's modifiers, so a clause whose member changes shape without leaving the set (a `final`
/// added to `claimInbound`) is a changed key: red, with a mutant per clause (E07 `claimInbound`, E08 `dispatchConfined`,
/// E09 `dispatchSelf`, E10 `leaderPreVote`).
///
/// `FsmBackedLeaderManager.fsmBackedLeaderManager(context, inbound, localMode)` is not an allow-list row at this head: lane M
/// adds the factory and its row together (it takes an Inbound, so CEN-2 passes it without the row; the row names the one
/// construction route).
class LeaderAuthorityCensusTest {
    private static final String LEADER_PACKAGE = "org/pragmatica/consensus/leader";
    private static final String FIXTURE_PACKAGE = "org/pragmatica/consensus/leader/fsm/censusfixture";

    private static final Set<Class<?>> ROOTS = Set.of(LeaderElectionContext.EpisodeHandle.class,
                                                      LeaderElectionContext.EpisodeIdentity.class,
                                                      OwnerLifetime.class,
                                                      TransitionRequest.class,
                                                      Fsm.class,
                                                      LeaderElectionContext.Inbound.class,
                                                      SerialExecutor.class);

    /// The ONE allow-list, shared by CEN-1 and CEN-2. A field or method key here is not reported.
    private static final Set<String> ALLOWED = Set.of(// private slots of the implementation
                                                      "LeaderElectionContext.fsm",
                                                      "LeaderElectionContext.serial",
                                                      "LeaderElectionContext.nodeLifetime",
                                                      "LeaderElectionContext.NODE_LIFETIME",
                                                      "LeaderElectionContext.NEVER",
                                                      "LeaderElectionContext.EpisodeHandle.identity",
                                                      "LeaderElectionContext.Entry.lifetime",
                                                      // holds source 4, the one grant
                                                      "LeaderManager.FsmBackedLeaderManager.inbound",
                                                      // the one grant, handed to the builder's caller
                                                      "LeaderElectionFsm.FsmWithContext.inbound",
                                                      "LeaderElectionFsm.FsmWithContext.inbound()",
                                                      // the package-private machine of a builder: a test of this package builds a NEW one (#3846 s3.6)
                                                      "LeaderElectionFsm.Machine.context",
                                                      "LeaderElectionFsm.Machine.fsm",
                                                      "LeaderElectionFsm.Machine.inbound",
                                                      "LeaderElectionFsm.Machine.context()",
                                                      "LeaderElectionFsm.Machine.fsm()",
                                                      "LeaderElectionFsm.Machine.inbound()",
                                                      // builders of a NEW machine and a NEW executor
                                                      "LeaderElectionFsm.leaderElectionFsm()",
                                                      "SerialExecutor.serialExecutor()",
                                                      // inert after the one grant: a later claim is refused and counted
                                                      "LeaderElectionContext.claimInbound()");

    /// Members that are allowed AND do not propagate: holding `ctx` is not holding the grant.
    private static final Set<String> NO_PROPAGATE = Set.of("LeaderElectionContext.claimInbound()");

    /// The head set, computed by lane A at its head and committed as a literal. A lane removes entries; it never adds one.
    private static final List<String> HEAD_SET = List.of(
        "CEN-1 LeaderElectionContext.Entry.this$0",
        "CEN-1 LeaderElectionContext.EpisodeHandle.this$0",
        "CEN-1 LeaderElectionContext.Inbound.this$0",
        "CEN-1 LeaderElectionContext.dormant",
        "CEN-1 LeaderElectionContext.quorumLost",
        "CEN-1 LeaderElectionContext.stopped",
        "CEN-1 LeaderElectionFsm.FsmWithContext.context",
        "CEN-1 LeaderElectionState.AwaitingKvSync.ctx",
        "CEN-1 LeaderElectionState.AwaitingKvSync.episodeHandle",
        "CEN-1 LeaderElectionState.Dormant.ctx",
        "CEN-1 LeaderElectionState.Electing.ctx",
        "CEN-1 LeaderElectionState.Electing.episodeHandle",
        "CEN-1 LeaderElectionState.Led.ctx",
        "CEN-1 LeaderElectionState.Passive.ctx",
        "CEN-1 LeaderElectionState.QuorumLost.ctx",
        "CEN-1 LeaderElectionState.QuorumWaiting.ctx",
        "CEN-1 LeaderElectionState.ReElecting.ctx",
        "CEN-1 LeaderElectionState.ReElecting.episodeHandle",
        "CEN-1 LeaderElectionState.Stopped.ctx",
        "CEN-1 LeaderManager.FsmBackedLeaderManager.context",
        "CEN-1 LeaderPreVote.ctx",
        "CEN-2 LeaderElectionContext.awaitingKvSync()",
        "CEN-2 LeaderElectionContext.current()",
        "CEN-2 LeaderElectionContext.dormant()",
        "CEN-2 LeaderElectionContext.electing()",
        "CEN-2 LeaderElectionContext.quorumLost()",
        "CEN-2 LeaderElectionContext.quorumWaiting()",
        "CEN-2 LeaderElectionContext.reElecting()",
        "CEN-2 LeaderElectionContext.stopped()",
        "CEN-2 LeaderElectionContext.unentered()",
        "CEN-2 LeaderElectionFsm.FsmWithContext.context()",
        "CEN-2 LeaderElectionState.AwaitingKvSync.ctx()",
        "CEN-2 LeaderElectionState.AwaitingKvSync.episodeHandle()",
        "CEN-2 LeaderElectionState.Dormant.ctx()",
        "CEN-2 LeaderElectionState.Electing.ctx()",
        "CEN-2 LeaderElectionState.Electing.episodeHandle()",
        "CEN-2 LeaderElectionState.Led.ctx()",
        "CEN-2 LeaderElectionState.Passive.ctx()",
        "CEN-2 LeaderElectionState.QuorumLost.ctx()",
        "CEN-2 LeaderElectionState.QuorumWaiting.ctx()",
        "CEN-2 LeaderElectionState.ReElecting.ctx()",
        "CEN-2 LeaderElectionState.ReElecting.episodeHandle()",
        "CEN-2 LeaderElectionState.Stopped.ctx()",
        "CEN-2 LeaderElectionState.ctx()",
        "CEN-2 LeaderManager.FsmBackedLeaderManager.context()",
        "CEN-3 LeaderElectionContext.dispatchConfined: package-private",
        "CEN-3 LeaderElectionState.dispatchSelf: private static");

    @Test
    void census_atHead_matchesTheCommittedRatchet() throws Exception {
        var actual = headViolations();
        var added = new TreeSet<>(actual);
        var removed = new TreeSet<>(HEAD_SET);

        added.removeAll(HEAD_SET);
        removed.removeAll(actual);

        assertThat(added).as("NEW authority violations (a lane may only remove entries). ACTUAL set, sorted, to paste when the change is deliberate: "
                             + actual)
                         .isEmpty();
        assertThat(removed).as("FIXED violations whose entry was not removed from HEAD_SET (the ratchet only tightens): delete them")
                           .isEmpty();
    }

    /// Lane F's end state: the set is empty. `@Disabled` until lane F lands, which enables it and deletes [#HEAD_SET].
    @Disabled("lane F: enable when the head set is empty, and delete HEAD_SET and census_atHead_matchesTheCommittedRatchet")
    @Test
    void census_isEmpty_laneF() throws Exception {
        assertThat(headViolations()).as("authority violations remaining")
                                    .isEmpty();
    }

    /// The authority set is computed: by assignability the memo names `Entry`, `NodeLifetime`, `Never` and `Suspicion`, and
    /// `Fsm.Entered` is in it through its `lifetime` component. A census that lists names would not need this.
    @Test
    void authoritySet_isComputed_andContainsTheTypesTheMemoNames() throws Exception {
        var authority = leaderReport().authority();

        assertThat(authority).as("assignable to a root, not named")
                             .contains("LeaderElectionContext.Entry",
                                       "LeaderElectionContext.NodeLifetime",
                                       "LeaderElectionContext.Never",
                                       "LeaderPreVote.Suspicion",
                                       "LeaderElectionContext.EpisodeHandle",
                                       "LeaderElectionContext.Inbound");
        assertThat(authority).as("through the public `lifetime` component of another package")
                             .contains("Fsm.Entered");
    }

    /// Instrument validation: the fixture package is compiled into the test classes and scanned through the same entry
    /// point. It must report EACH planted member and nothing of the negative ones. Without this control the census is not
    /// evidence.
    @Test
    void fixtureControl_reportsEveryPlantedMember_andNothingElse() throws Exception {
        var report = AuthorityCensus.scan(classesDirOf(CensusFixture.class), FIXTURE_PACKAGE, ROOTS, Set.of(), Set.of());

        assertThat(report.violations()).as("exactly the planted members: component, accessor, propagated component, "
                                           + "zero-arg and generic returns, the synthetic capture field")
                                       .containsExactlyInAnyOrder("CEN-1 CensusFixture.HandleCell.cell",
                                                                  "CEN-2 CensusFixture.HandleCell.cell()",
                                                                  "CEN-1 CensusFixture.EnteredCell.cell",
                                                                  "CEN-2 CensusFixture.EnteredCell.cell()",
                                                                  "CEN-2 CensusFixture.Minter.mint()",
                                                                  "CEN-2 CensusFixture.OptionalMinter.mint()",
                                                                  "CEN-1 CensusFixture.1.val$handle");
    }

    /// The control's own control: allowing a planted key removes exactly that key, so the allow-list is read.
    @Test
    void fixtureControl_anAllowedMember_isNotReported() throws Exception {
        var report = AuthorityCensus.scan(classesDirOf(CensusFixture.class),
                                          FIXTURE_PACKAGE,
                                          ROOTS,
                                          Set.of("CensusFixture.Minter.mint()"),
                                          Set.of());

        assertThat(report.violations()).as("the allowed key is gone, the others stay")
                                       .doesNotContain("CEN-2 CensusFixture.Minter.mint()")
                                       .contains("CEN-2 CensusFixture.OptionalMinter.mint()");
    }

    private static Set<String> headViolations() throws Exception {
        var all = new TreeSet<>(leaderReport().violations());

        all.addAll(cen3());

        return all;
    }

    private static AuthorityCensus.Report leaderReport() throws Exception {
        return AuthorityCensus.scan(classesDirOf(LeaderElectionContext.class), LEADER_PACKAGE, ROOTS, ALLOWED, NO_PROPAGATE);
    }

    /// CEN-3 (visibility), four clauses, each a violation key that carries the member's modifiers or `missing`:
    /// `claimInbound` is not public (E07); `dispatchConfined` is private (E08); LeaderElectionState declares no method named
    /// `dispatchSelf` (E09); `LeaderPreVote.leaderPreVote` is not public (E10). A member that does not exist is a violation
    /// too, so a rename does not pass a clause silently.
    private static Set<String> cen3() {
        var violations = new TreeSet<String>();
        var claim = methodsNamed(LeaderElectionContext.class, "claimInbound");
        var confined = methodsNamed(LeaderElectionContext.class, "dispatchConfined");
        var self = methodsNamed(LeaderElectionState.class, "dispatchSelf");
        var preVote = methodsNamed(LeaderPreVote.class, "leaderPreVote");

        report(violations, "LeaderElectionContext.claimInbound", claim, method -> !Modifier.isPublic(method.getModifiers()));
        report(violations, "LeaderElectionContext.dispatchConfined", confined, method -> Modifier.isPrivate(method.getModifiers()));
        self.forEach(method -> violations.add("CEN-3 LeaderElectionState.dispatchSelf: " + shape(method)));
        report(violations, "LeaderPreVote.leaderPreVote", preVote, method -> !Modifier.isPublic(method.getModifiers()));

        return violations;
    }

    private static void report(Set<String> violations, String name, List<Method> methods, java.util.function.Predicate<Method> satisfied) {
        if (methods.isEmpty()) {
            violations.add("CEN-3 " + name + ": missing");
        }

        methods.stream()
               .filter(satisfied.negate())
               .forEach(method -> violations.add("CEN-3 " + name + ": " + shape(method)));
    }

    private static String shape(Method method) {
        var modifiers = Modifier.toString(method.getModifiers());

        return modifiers.isEmpty()
               ? "package-private"
               : modifiers;
    }

    private static List<Method> methodsNamed(Class<?> type, String name) {
        var found = new ArrayList<Method>();

        for (var method : type.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                found.add(method);
            }
        }

        return found;
    }

    private static Path classesDirOf(Class<?> anchor) throws URISyntaxException {
        return Path.of(anchor.getProtectionDomain()
                             .getCodeSource()
                             .getLocation()
                             .toURI());
    }
}
