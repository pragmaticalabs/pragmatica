// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.ember.EmberCluster.ClusterStatus;
import org.pragmatica.aether.ember.EmberCluster.NodeStatus;
import org.pragmatica.aether.ember.EmberConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// #952 — the startup-deploy failure message must report what Forge checked, not diagnose what it
/// did not.
///
/// The clean-room run that found #952 hit this message with every Maven artifact present and
/// correct; the actual cause was a database container that had been dead for ten seconds. The old
/// text ended by telling the reader to check `mvn install`, so the evidence pointed away from the
/// defect and the next reader spent their effort proving a healthy component healthy.
class ForgeServerMessageTest {
    private static final String COORDINATES = "org.example:demo:1.0.0:blueprint";
    private static final String DETAIL = "HTTP 503: upstream unavailable";

    /// `mvn`/`maven` and `install` in one sentence, however they are spelled or separated - the
    /// family the shipped message must never fall into, rather than the single wording that was
    /// removed from it.
    private static final String MAVEN_INSTALL_ADVICE = "(?i)\\b(mvn|maven)\\b[^.]*\\binstall\\b";

    @Test
    void startupDeployFailure_quotesWhatWasObserved() {
        var message = ForgeServer.startupDeployFailureMessage(COORDINATES, DETAIL);

        assertThat(message).contains(COORDINATES)
                           .contains(DETAIL)
                           .contains("/api/v1/blueprints/deploy");
    }

    /// The load-bearing property: the message states that the cause is undetermined, rather than
    /// naming one. An error that names an unverified cause reads as a diagnosis.
    @Test
    void startupDeployFailure_saysTheCauseWasNotEstablished() {
        var message = ForgeServer.startupDeployFailureMessage(COORDINATES, DETAIL);

        assertThat(message).contains("checked exactly one thing")
                           .contains("did NOT establish why");
    }

    /// Every candidate consistent with the observation is offered, including the one #952 actually
    /// hit, and none is presented as the answer.
    @Test
    void startupDeployFailure_offersCandidatesWithoutRankingThem() {
        var message = ForgeServer.startupDeployFailureMessage(COORDINATES, DETAIL);

        assertThat(message).contains("coordinates")
                           .contains("resolvable repository")
                           .contains("a database, for instance")
                           .contains("Forge probes none of them");
    }

    /// The regression itself. `mvn install` was the instruction that misdirected the #952
    /// investigation, and Forge has never verified it.
    ///
    /// This is a PATTERN and not a substring, because the substring version was evaded. An
    /// adversarial pass put the misdirection back into the shipped message worded `mvn clean
    /// install`, and every test in this class stayed green: `"mvn clean install"` does not contain
    /// `"mvn install"`. A pin that defends one spelling defends nothing, because the next author
    /// reintroduces the defect by paraphrase rather than by copy. The property is "never send the
    /// reader to Maven for a cause Forge did not check", so the match is `mvn` or `maven` followed
    /// by `install` anywhere in the same sentence, case-insensitively.
    @Test
    void startupDeployFailure_neverDirectsTheReaderToAMavenInstall() {
        var message = ForgeServer.startupDeployFailureMessage(COORDINATES, DETAIL);

        assertThat(message).doesNotContainPattern(MAVEN_INSTALL_ADVICE);
    }

    /// #718 shape 4 — the cluster-start failure message.
    ///
    /// The old text was `"Failed to start cluster: " + cause.message()`, which on the timeout path
    /// reads `Promise is not resolved within specified timeout`. That sentence is IDENTICAL whether
    /// nothing formed at all or four of five nodes were consensus-active, so it cannot tell the two
    /// apart — and telling them apart is the whole point of reading it.
    @Nested
    class ClusterStartFailure {
        private static final String TIMEOUT_DETAIL = "Promise is not resolved within specified timeout";

        private static NodeStatus node(int index, String state) {
            return new NodeStatus("node-" + index, 6000 + index - 1, 5150 + index - 1, state, false);
        }

        /// `activeCount` is DERIVED by the code under test from the node list; it is not a value the
        /// fixture hands it. That is what these assertions actually probe.
        private static ClusterStatus status(int active, int inactive, String leaderId) {
            var nodes = new java.util.ArrayList<NodeStatus>();

            for (var i = 1; i <= active; i++) {
                nodes.add(node(i, EmberCluster.STATE_ACTIVE));
            }
            for (var i = active + 1; i <= active + inactive; i++) {
                nodes.add(node(i, EmberCluster.STATE_INACTIVE));
            }

            return new ClusterStatus(List.copyOf(nodes), leaderId);
        }

        private static String message(ClusterStatus status, Map<String, String> nodeFailures) {
            return ForgeServer.clusterStartFailureMessage(EmberConfig.DEFAULT, status, nodeFailures, TIMEOUT_DETAIL);
        }

        @Test
        void clusterStartFailure_namesTheBudgetAndItsSetting() {
            assertThat(message(status(0, 5, "none"), Map.of())).contains("60s start budget")
                                                              .contains("start_timeout_seconds")
                                                              .contains(TIMEOUT_DETAIL);
        }

        /// THE load-bearing property. Nothing-formed and partially-formed must not render the same
        /// sentence — that identity is the defect being fixed.
        @Test
        void clusterStartFailure_distinguishesNothingFormedFromPartialFormation() {
            var nothingFormed = message(status(0, 5, "none"), Map.of());
            var partiallyFormed = message(status(4, 1, "node-1"), Map.of());

            assertThat(nothingFormed).isNotEqualTo(partiallyFormed);
            assertThat(nothingFormed).contains("0 of 5 node(s) consensus-active")
                                     .contains("leader=none");
            assertThat(partiallyFormed).contains("4 of 5 node(s) consensus-active")
                                       .contains("leader=node-1");
        }

        @Test
        void clusterStartFailure_namesEachNodeWithItsStateAndQuicPort() {
            var message = message(status(1, 1, "none"), Map.of());

            assertThat(message).contains("node-1=active(quic 6000)")
                               .contains("node-2=inactive(quic 6001)");
        }

        @Test
        void clusterStartFailure_reportsNodeStartFailures_whenAnyWereRecorded() {
            var message = message(status(0, 3, "none"), Map.of("node-3", "Failed to bind to port 5152"));

            assertThat(message).contains("node-3")
                               .contains("Failed to bind to port 5152");
        }

        /// The absence of recorded failures is itself information: the budget expired mid-start
        /// rather than a node erroring out. The message must say which of the two it was.
        @Test
        void clusterStartFailure_saysNoNodeErrored_whenNoFailuresWereRecorded() {
            assertThat(message(status(0, 5, "none"), Map.of())).contains("No node reported a start failure");
        }

        /// #727's lesson, pinned here. `NodeStatus.state` was once the unconditional literal
        /// `"healthy"`, so every failing cluster reported healthy nodes beside `leader=none`. A
        /// diagnostic that fabricates its own evidence sends the next reader to the wrong place.
        @Test
        void clusterStartFailure_neverCallsANodeHealthy() {
            assertThat(message(status(2, 3, "none"), Map.of())).doesNotContainIgnoringCase("healthy node")
                                                              .doesNotContainPattern("(?i)node[^.]*\\bhealthy\\b");
        }

        /// Same property as `startupDeployFailure_neverDirectsTheReaderToAMavenInstall`: never
        /// present an unchecked candidate as the cause. Forge does not probe forge-data, host load
        /// or consensus, so the message must offer them as candidates and say so outright.
        @Test
        void clusterStartFailure_offersCandidatesWithoutDiagnosingOne() {
            var message = message(status(0, 5, "none"), Map.of());

            assertThat(message).contains("did NOT check")
                               .contains("forge-data")
                               .contains("host load")
                               .doesNotContainPattern("(?i)\\b(caused by|because of|due to)\\b");
        }

        /// Both figures above are read from `EmberConfig.DEFAULT`, whose budget IS 60 and whose base
        /// port IS 6000 — so a hard-coded literal would satisfy them vacuously. This drives the same
        /// message from a NON-default config, which is what makes those pins real.
        @Test
        void clusterStartFailure_readsBudgetAndPortRangeFromTheConfig() {
            var config = EmberConfig.loadFromString("[cluster]\nnodes = 3\nbase_port = 7300\nstart_timeout_seconds = 180\n")
                                    .fold(cause -> fail("must parse: " + cause.message()), parsed -> parsed);
            var message = ForgeServer.clusterStartFailureMessage(config, status(0, 3, "none"), Map.of(), TIMEOUT_DETAIL);

            assertThat(message).contains("180s start budget")
                               .contains("0 of 3 node(s) consensus-active")
                               .contains("7300-7302")
                               .doesNotContain("60s start budget")
                               .doesNotContain("6000-6004");
        }

        /// The one thing Forge DID verify, so the reader can rule it out instead of re-checking it.
        @Test
        void clusterStartFailure_statesThatThePortRangeWasVerifiedFree() {
            assertThat(message(status(0, 5, "none"), Map.of())).contains("6000-6004")
                                                              .contains("ruled out");
        }
    }
}
