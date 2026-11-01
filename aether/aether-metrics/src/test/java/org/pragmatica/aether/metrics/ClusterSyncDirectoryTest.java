// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.metrics;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.metrics.DirectoryFixtures.RecordingNetwork;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPong;
import org.pragmatica.cluster.metrics.DirectoryVersion;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.metrics.DirectoryFixtures.CORE;
import static org.pragmatica.aether.metrics.DirectoryFixtures.GOVERNOR;
import static org.pragmatica.aether.metrics.DirectoryFixtures.LEADER;
import static org.pragmatica.aether.metrics.DirectoryFixtures.SELF;
import static org.pragmatica.aether.metrics.DirectoryFixtures.STRANGER;
import static org.pragmatica.aether.metrics.DirectoryFixtures.directory;
import static org.pragmatica.aether.metrics.DirectoryFixtures.ping;
import static org.pragmatica.aether.metrics.DirectoryFixtures.pong;
import static org.pragmatica.aether.metrics.DirectoryFixtures.version;

/// The collector side of the cluster directory (W-OBS W2): NT-O44 (a member installs the directory from a responder's pong), NT-O45 (the install
/// rule I-W14 with its three arms) and NT-O47 (a node that reports the pair it holds is not sent it again). Each test names the mutation that
/// turns it red.
class ClusterSyncDirectoryTest {
    private ClusterSyncCollector member;
    private RecordingNetwork network;

    @BeforeEach
    void setUp() {
        network = new RecordingNetwork(Set.of(CORE, LEADER));
        member = ClusterSyncCollector.clusterSyncCollector(SELF, network);
        member.setMetricsProducerEligibility(_ -> true);
        member.setPingAuthority(CORE::equals, LEADER::equals);
        member.setDirectoryGovernor(GOVERNOR::equals);
    }

    @Nested
    class MemberInstallsFromAPong {
        /// NT-O44. A member that holds nothing installs the directory a core, the leader or its own governor attaches to a pong.
        /// Mutation: drop the `.onPresent(this::acceptDirectory)` of the pong path (nothing installs).
        @Test
        void aMember_installsTheLeadersDirectory_fromACoreLeaderOrOwnGovernorPong() {
            var held = directory(1, 5, 0, 3);

            member.onClusterSyncPong(pong(CORE, held.version(), Option.some(held)));

            assertThat(member.heldDirectory().isPresent()).isTrue();
            assertThat(member.heldDirectoryVersion()).isEqualTo(held.version());

            var fromGovernor = ClusterSyncCollector.clusterSyncCollector(SELF, network);

            fromGovernor.setPingAuthority(CORE::equals, LEADER::equals);
            fromGovernor.setDirectoryGovernor(GOVERNOR::equals);
            fromGovernor.onClusterSyncPong(pong(GOVERNOR, held.version(), Option.some(held)));

            assertThat(fromGovernor.heldDirectoryVersion()).isEqualTo(held.version());

            var fromLeader = ClusterSyncCollector.clusterSyncCollector(SELF, network);

            fromLeader.setPingAuthority(CORE::equals, LEADER::equals);
            fromLeader.onClusterSyncPong(pong(LEADER, held.version(), Option.some(held)));

            assertThat(fromLeader.heldDirectoryVersion()).isEqualTo(held.version());
        }

        /// NT-O45 arm 3 (sender gate, C-4). Mutation: drop `isDirectorySender` from the pong path.
        @Test
        void aDirectory_fromAnySenderOtherThanACoreTheLeaderOrOwnGovernor_isIgnored() {
            var held = directory(1, 5, 0, 3);

            member.onClusterSyncPong(pong(STRANGER, held.version(), Option.some(held)));

            assertThat(member.heldDirectory().isPresent()).isFalse();
            assertThat(member.heldDirectoryVersion()).isEqualTo(DirectoryVersion.NONE);
        }

        /// NT-O45 arm 1 (C-2). A new leader restarts its generation under a newer epoch; it must install over the old leader's higher one.
        /// Mutation: compare `generation` only in `DirectoryVersion.compare` (the install is refused: red).
        @Test
        void aNewLeadersLowerGeneration_underANewerEpoch_installs() {
            member.onClusterSyncPong(pong(CORE, version(1, 5, 0, 9), Option.some(directory(1, 5, 0, 9))));
            member.onClusterSyncPong(pong(CORE, version(1, 6, 0, 1), Option.some(directory(1, 6, 0, 1))));

            assertThat(member.heldDirectoryVersion()).isEqualTo(version(1, 6, 0, 1));
        }

        /// NT-O45 arm 2 (C-2). A stale leader's higher generation under an older epoch is refused. A member's observed epoch is zero, so
        /// comparing against it (instead of the held pair) would accept it. Mutation: compare the epoch triple against `observedEpoch`.
        @Test
        void aStaleLeadersHigherGeneration_underAnOlderEpoch_isRefused() {
            member.onClusterSyncPong(pong(CORE, version(1, 6, 0, 1), Option.some(directory(1, 6, 0, 1))));
            member.onClusterSyncPong(pong(CORE, version(1, 5, 0, 99), Option.some(directory(1, 5, 0, 99))));

            assertThat(member.heldDirectoryVersion()).isEqualTo(version(1, 6, 0, 1));
        }

        /// I-W14: strictly greater. The same pair is not reinstalled, and an equal pair with other content does not replace the held one.
        /// Mutation: `isStrictlyAfter` → `>= 0`.
        @Test
        void anEqualPair_isNotInstalledAgain() {
            var first = directory(1, 5, 0, 3);
            var sameVersionOtherContent = new org.pragmatica.cluster.metrics.ClusterDirectory(3, 1, 5, 0, List.of(), first.dhtReplication(), Option.none());

            member.onClusterSyncPong(pong(CORE, first.version(), Option.some(first)));
            member.onClusterSyncPong(pong(CORE, first.version(), Option.some(sameVersionOtherContent)));

            assertThat(member.heldDirectory().unwrap()).isSameAs(first);
        }

        /// The epoch triple orders before the generation at every position: incarnation, term, counter. Mutation: skip the incarnation compare.
        @Test
        void theEpochTripleOrdersLexicographically_beforeTheGeneration() {
            assertThat(version(2, 0, 0, 0).isStrictlyAfter(version(1, 9, 9, 9))).as("incarnation first").isTrue();
            assertThat(version(1, 2, 0, 0).isStrictlyAfter(version(1, 1, 9, 9))).as("then term").isTrue();
            assertThat(version(1, 1, 2, 0).isStrictlyAfter(version(1, 1, 1, 9))).as("then counter").isTrue();
            assertThat(version(1, 1, 1, 2).isStrictlyAfter(version(1, 1, 1, 1))).as("then generation").isTrue();
            assertThat(version(1, 1, 1, 1).isStrictlyAfter(version(1, 1, 1, 1))).as("equal is not after").isFalse();
            assertThat(version(1, 1, 1, 1).isStrictlyAfter(version(2, 0, 0, 0))).isFalse();
        }
    }

    @Nested
    class LeaderPingPath {
        /// I-W14 on the ping path: the directory rides only the authority-bearing ping and still passes the authority and term fence.
        /// Mutation: call `cacheDirectory` outside `acceptAuthoritativeView` (an unfenced path).
        @Test
        void aDirectory_onAPingThatFailsTheAuthorityOrTheTermFence_isNotInstalled() {
            var held = directory(1, 5, 0, 3);

            member.onClusterSyncPing(ping(STRANGER, 5, true, DirectoryVersion.NONE, Option.some(held)));
            member.onClusterSyncPing(ping(LEADER, 5, false, DirectoryVersion.NONE, Option.some(held)));

            assertThat(member.heldDirectory().isPresent()).as("not the leader, and not an authority-bearing ping").isFalse();

            member.onClusterSyncPing(ping(LEADER, 5, true, DirectoryVersion.NONE, Option.some(held)));

            assertThat(member.heldDirectoryVersion()).as("the leader's authority ping installs").isEqualTo(held.version());

            member.onClusterSyncPing(ping(LEADER, 4, true, DirectoryVersion.NONE, Option.some(directory(1, 9, 0, 9))));

            assertThat(member.heldDirectoryVersion()).as("a ping below the accepted term is fenced out, whatever it carries").isEqualTo(held.version());
        }
    }

    @Nested
    class ReportingThePair {
        /// A node answers a ping with the pair it holds, and attaches its directory iff strictly newer than the asker's. Mutation: attach
        /// unconditionally (`filter` removed), or compare `>=`.
        @Test
        void aPong_attachesTheDirectory_onlyWhenStrictlyNewerThanTheAskers() {
            var held = directory(1, 5, 0, 3);

            member.onClusterSyncPong(pong(CORE, held.version(), Option.some(held)));
            member.onClusterSyncPing(ping(CORE, 5, false, DirectoryVersion.NONE, Option.none()));
            member.onClusterSyncPing(ping(CORE, 5, false, version(1, 5, 0, 2), Option.none()));
            member.onClusterSyncPing(ping(CORE, 5, false, held.version(), Option.none()));
            member.onClusterSyncPing(ping(CORE, 5, false, version(1, 6, 0, 1), Option.none()));

            var pongs = network.sentTo(CORE, ClusterSyncPong.class);

            assertThat(pongs).extracting(ClusterSyncPong::observedDirectoryGeneration).as("every pong reports the held pair").containsOnly(held.version());
            assertThat(pongs).extracting(pong -> pong.directory().isPresent())
                             .as("attached to the asker holding nothing and the asker holding an older pair; not to an equal or a newer one")
                             .containsExactly(true, true, false, false);
        }

        /// A node that holds no directory attaches none and reports NONE.
        @Test
        void aNodeHoldingNothing_attachesNone_andReportsNone() {
            member.onClusterSyncPing(ping(CORE, 5, false, DirectoryVersion.NONE, Option.none()));

            assertThat(network.sentTo(CORE, ClusterSyncPong.class)).singleElement().satisfies(pong -> {
                assertThat(pong.directory().isPresent()).isFalse();
                assertThat(pong.observedDirectoryGeneration()).isEqualTo(DirectoryVersion.NONE);
            });
        }

        /// The leader's own produced directory is held under the same rule: a producer behind what it holds installs nothing.
        /// Mutation: `installProducedDirectory` assigns without the `isNewerThan` check.
        @Test
        void aProducedDirectory_isHeldUnderTheSameRule() {
            assertThat(member.installProducedDirectory(directory(1, 6, 0, 2))).isTrue();
            assertThat(member.installProducedDirectory(directory(1, 6, 0, 2))).as("an equal pair").isFalse();
            assertThat(member.installProducedDirectory(directory(1, 5, 0, 9))).as("an older epoch").isFalse();
            assertThat(member.heldDirectoryVersion()).isEqualTo(version(1, 6, 0, 2));
        }
    }
}
