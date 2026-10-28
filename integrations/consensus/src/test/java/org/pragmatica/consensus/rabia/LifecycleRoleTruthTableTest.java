// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.consensus.rabia;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// L-0 exhaustive role truth table (spec section 7, L-0 (c)), written before L-1 so that L-1 has an oracle.
///
/// The three observable bits are `declared` (`observerMode`), `rosterPresent` (`voters.isPresent()`) and `member`
/// (`isVoter(self)`). For each of the 13 reader sites of section 1.1 and for each row guard of section 4 that names a role
/// predicate, the OLD expression (kept here verbatim as the oracle) must equal the set of roles the NEW named predicate
/// accepts. The expected sets are written independently, from the spec's role-to-behaviour mapping, never derived from the
/// oracle. L-1 binds the table to the engine: `namedPredicates_equalTheExpectedSets` pins every `RabiaEngine.Role`
/// predicate to those sets, and `engine_inEachOfTheSixStates_reportsThatRole` drives a real engine into each of the six
/// values through the public API and reads `role()`.
class LifecycleRoleTruthTableTest {
    enum Role {
        PRE_GENESIS(false, false, false),
        PRE_GENESIS_OBSERVER(true, false, false),
        VOTER(false, true, true),
        ROSTER_OBSERVER(true, true, true),
        REMOVED(true, true, false),
        REMOVED_UNDECLARED(false, true, false);

        final boolean declared;
        final boolean rosterPresent;
        final boolean member;

        Role(boolean declared, boolean rosterPresent, boolean member) {
            this.declared = declared;
            this.rosterPresent = rosterPresent;
            this.member = member;
        }
    }

    private record Site(String name, Predicate<Role> oracle, Set<Role> expected) {}

    private static final Set<Role> NOT_MEMBER = EnumSet.of(Role.PRE_GENESIS, Role.PRE_GENESIS_OBSERVER, Role.REMOVED, Role.REMOVED_UNDECLARED);
    private static final Set<Role> MEMBER = EnumSet.of(Role.VOTER, Role.ROSTER_OBSERVER);
    private static final Set<Role> DECLARED = EnumSet.of(Role.PRE_GENESIS_OBSERVER, Role.ROSTER_OBSERVER, Role.REMOVED);
    private static final Set<Role> NOT_VOTER_ROLE = EnumSet.complementOf(EnumSet.of(Role.VOTER));
    private static final Set<Role> REMOVED_FROM_ROSTER = EnumSet.of(Role.REMOVED, Role.REMOVED_UNDECLARED);

    private static Map<String, Site> sites() {
        var sites = new LinkedHashMap<String, Site>();

        add(sites, "RE:1112 authorizeActivation   voters.isPresent() && !isVoter", r -> r.rosterPresent && !r.member, REMOVED_FROM_ROSTER);
        add(sites, "RE:1119 authorizeActivation   observerMode", r -> r.declared, DECLARED);
        add(sites, "RE:1256 resumesAsObserver     observerMode || !isVoter", r -> r.declared || !r.member, NOT_VOTER_ROLE);
        add(sites, "RE:1342 admissibleCommand     !isVoter", r -> !r.member, NOT_MEMBER);
        add(sites, "RE:1404 reconfigurationRequest !isVoter", r -> !r.member, NOT_MEMBER);
        add(sites, "RE:1512 settle                isVoter", r -> r.member, MEMBER);
        add(sites, "RE:1525 applyRoster joins     !isVoter (target contains self)", r -> !r.member, NOT_MEMBER);
        add(sites, "RE:2178 startPhase            !isVoter", r -> !r.member, NOT_MEMBER);
        add(sites, "RE:2884 activate refusal      !observerMode && !isVoter", r -> !r.declared && !r.member, EnumSet.of(Role.PRE_GENESIS, Role.REMOVED_UNDECLARED));
        add(sites, "RE:2894 activate observer     observerMode", r -> r.declared, DECLARED);
        add(sites, "RE:3110 handleSyncRequest     observerMode || !isVoter", r -> r.declared || !r.member, NOT_VOTER_ROLE);
        add(sites, "RE:3231 adoptionCandidates    observerMode || !isVoter", r -> r.declared || !r.member, NOT_VOTER_ROLE);
        add(sites, "RE:4117 advancePhase          observerMode", r -> r.declared, DECLARED);

        return sites;
    }

    private static Map<String, Site> rowGuards() {
        var rows = new LinkedHashMap<String, Site>();

        add(rows, "R6   resume to Idle (Role == VOTER)", r -> !(r.declared || !r.member), EnumSet.of(Role.VOTER));
        add(rows, "R13  activate as voter", r -> !r.declared && r.member, EnumSet.of(Role.VOTER));
        add(rows, "R14  activate as observer", r -> r.declared, DECLARED);
        add(rows, "R15  activation refused", r -> !r.declared && !r.member, EnumSet.of(Role.PRE_GENESIS, Role.REMOVED_UNDECLARED));
        add(rows, "R18  slot started needs member", r -> r.member, MEMBER);
        add(rows, "R18j slot joined needs member", r -> r.member, MEMBER);
        add(rows, "R19d demote to Observing", r -> r.declared, DECLARED);
        add(rows, "R19o observer completes slot", r -> r.declared, DECLARED);
        add(rows, "R20  promoteAddedVoter (not declared)", r -> !r.declared, EnumSet.complementOf(EnumSet.copyOf(DECLARED)));
        add(rows, "R21  rosterPresent implies member", r -> !(r.rosterPresent && !r.member), EnumSet.complementOf(EnumSet.copyOf(REMOVED_FROM_ROSTER)));

        return rows;
    }

    private static void add(Map<String, Site> into, String name, Predicate<Role> oracle, Set<Role> expected) {
        into.put(name, new Site(name, oracle, expected));
    }

    @Test
    void thirteenReaderSites_oldExpressionEqualsTheNamedPredicate_onEverySixRoles() {
        var sites = sites();

        assertThat(sites).as("section 1.1 lists exactly 13 reader sites").hasSize(13);
        sites.values().forEach(LifecycleRoleTruthTableTest::assertAgrees);
    }

    @Test
    void rowGuardsNamingARolePredicate_oldExpressionEqualsTheNamedPredicate_onEverySixRoles() {
        var rows = rowGuards();

        assertThat(rows).as("R6, R13, R14, R15, R18, R18j, R19d, R19o, R20, R21").hasSize(10);
        rows.values().forEach(LifecycleRoleTruthTableTest::assertAgrees);
    }

    @Test
    void activationRows_r13_r14_r15_doNotOverlap_andCoverAllSixRoles() {
        var covered = EnumSet.noneOf(Role.class);

        for (var role : Role.values()) {
            var r13 = !role.declared && role.member;
            var r14 = role.declared;
            var r15 = !role.declared && !role.member;

            assertThat(count(r13, r14, r15)).as("exactly one of R13, R14, R15 for %s", role).isEqualTo(1);
            covered.add(role);
        }

        assertThat(covered).containsExactlyInAnyOrder(Role.values());
    }

    @Test
    void roleBits_matchTheSpecTable() {
        assertThat(Role.values()).hasSize(6);
        assertThat(Role.PRE_GENESIS_OBSERVER.declared).isTrue();
        assertThat(Role.ROSTER_OBSERVER.declared && Role.ROSTER_OBSERVER.member).as("the pinned independence of observerMode and isVoter").isTrue();
        assertThat(Role.REMOVED_UNDECLARED.rosterPresent && !Role.REMOVED_UNDECLARED.member && !Role.REMOVED_UNDECLARED.declared).as("transient").isTrue();
    }

    @Test
    void namedPredicates_equalTheExpectedSets() {
        for (var role : Role.values()) {
            var named = RabiaEngine.Role.valueOf(role.name());

            assertThat(named.votes()).as("votes() on %s", role).isEqualTo(MEMBER.contains(role));
            assertThat(named.isDeclaredObserver()).as("isDeclaredObserver() on %s", role).isEqualTo(DECLARED.contains(role));
            assertThat(named.observes()).as("observes() on %s", role).isEqualTo(NOT_VOTER_ROLE.contains(role));
            assertThat(named.isRemoved()).as("isRemoved() on %s", role).isEqualTo(REMOVED_FROM_ROSTER.contains(role));
            assertThat(named.isUndeclaredNonMember()).as("isUndeclaredNonMember() on %s", role)
                                                      .isEqualTo(role == Role.PRE_GENESIS || role == Role.REMOVED_UNDECLARED);
            assertThat(named.rosterPresent()).as("rosterPresent() on %s", role)
                                             .isEqualTo(role != Role.PRE_GENESIS && role != Role.PRE_GENESIS_OBSERVER);
        }
    }

    @Test
    void engine_inEachOfTheSixStates_reportsThatRole() {
        for (var role : Role.values()) {
            var rig = engineIn(role);

            try {
                assertThat(rig.engine.role().name()).as("engine built for %s", role).isEqualTo(role.name());
            } finally {
                rig.stop();
            }
        }
    }

    private static LifecycleRig engineIn(Role role) {
        return switch (role) {
            case PRE_GENESIS -> LifecycleRig.genesisPendingRig();
            case PRE_GENESIS_OBSERVER -> observing(LifecycleRig.genesisPendingRig());
            case VOTER -> LifecycleRig.rig(false);
            case ROSTER_OBSERVER -> observing(LifecycleRig.rig(false));
            case REMOVED_UNDECLARED -> withoutSelf(LifecycleRig.fiveNodeRig());
            case REMOVED -> observing(withoutSelf(LifecycleRig.fiveNodeRig()));
        };
    }

    private static LifecycleRig observing(LifecycleRig rig) {
        rig.engine.authorizeObservation();

        return rig;
    }

    private static LifecycleRig withoutSelf(LifecycleRig rig) {
        var installed = rig.engine.initializeVoters(new VoterConfiguration(0, new ClusterConfig(java.util.List.of(LifecycleRig.N2, LifecycleRig.N3, LifecycleRig.N4))));

        assertThat(installed.isSuccess()).as("control: the roster without this node installs").isTrue();

        return rig;
    }

    private static int count(boolean... flags) {
        var n = 0;

        for (var flag : flags) {
            n += flag ? 1 : 0;
        }

        return n;
    }

    private static void assertAgrees(Site site) {
        for (var role : Role.values()) {
            assertThat(site.oracle().test(role)).as("%s on %s", site.name(), role).isEqualTo(site.expected().contains(role));
        }
    }
}
