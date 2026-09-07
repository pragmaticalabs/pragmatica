// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.util.List;
import java.util.Map;

import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// #915 — the pin for [LifecycleAwait]. No cluster is started here.
///
/// The defect being closed is a DIAGNOSTIC one, and a diagnostic cannot be pinned by a test that only
/// ever runs against a healthy system: the whole point is what the message says on the path that does
/// not happen in a green run. So every input here is supplied directly — an unresolved promise, a
/// failed promise, a cluster status carrying nodes, the state a failed start retained — which makes
/// the failure text itself the assertion subject and keeps the class down in the milliseconds.
///
/// The pair that matters is [#aPromiseThatNeverSettles_expiresAtTheBound_andNamesTheStep] and
/// [#aPromiseThatSettles_returnsItsValue]. Without the second, a `settled` rewritten to throw
/// unconditionally would satisfy the first — the failure test alone cannot tell "expires correctly"
/// from "never works".
///
/// Review round 1 recorded that the first revision of this class asserted only on the EARLY-RETURN
/// branch of the snapshot: every case passed an unstarted cluster, whose registry is empty, so no
/// test ever reached a node line and a mutation that fabricated one stayed green. The populated and
/// captured-failure cases below exist to close that, and they are the two a healthy cluster never
/// produces.
class LifecycleAwaitTest {
    private static final TimeSpan SHORT_BOUND = TimeSpan.timeSpan(2).seconds();
    /// Generous: the assertion below only needs to separate "the bound ended it" from "something
    /// else did", not to measure scheduling precision on a loaded box.
    private static final long SLACK_MS = 20_000;

    private record TestCause(String message) implements Cause {}

    @Test
    void aPromiseThatNeverSettles_expiresAtTheBound_andNamesTheStep() {
        var neverSettles = Promise.<Unit> promise();
        var cluster = unstartedCluster();
        var startedAtMs = System.currentTimeMillis();

        assertThatThrownBy(() -> LifecycleAwait.settled("5-core cluster start", cluster, SHORT_BOUND, neverSettles)).isInstanceOf(AssertionError.class)
                          .describedAs("the expiry must name the step, which is the whole defect: the 8m backstop "
                                      + "named only the class")
                          .hasMessageContaining("5-core cluster start")
                          .hasMessageContaining("did not settle within")
                          .describedAs("and must carry the cluster state, so the next reader does not need a rerun")
                          .hasMessageContaining("Cluster state when the wait ended:");
        var elapsedMs = System.currentTimeMillis() - startedAtMs;

        assertThat(elapsedMs).describedAs("the BOUND must be what ended the wait — an unbounded await would still be "
                                         + "parked here, which is exactly how ~515s class-level ERRORs were produced")
                  .isGreaterThanOrEqualTo(SHORT_BOUND.duration().toMillis())
                  .isLessThan(SHORT_BOUND.duration().toMillis() + SLACK_MS);
    }

    /// The positive control. A `settled` that always threw would pass the test above.
    @Test
    void aPromiseThatSettles_returnsItsValue() {
        var settled = Promise.<String> promise().resolve(org.pragmatica.lang.Result.success("msrc-6"));
        var value = LifecycleAwait.settled("worker join", unstartedCluster(), SHORT_BOUND, settled);

        assertThat(value).isEqualTo("msrc-6");
    }

    /// A lifecycle step that FAILED and one that never settled both leave the step undone, and both
    /// get the same named report.
    @Test
    void aPromiseThatFails_isReportedWithTheSameNamedShape() {
        var failed = Promise.<Unit> failure(new TestCause("Address already in use"));

        assertThatThrownBy(() -> LifecycleAwait.settled("cluster stop",
                                                        unstartedCluster(),
                                                        SHORT_BOUND,
                                                        failed)).isInstanceOf(AssertionError.class)
                          .hasMessageContaining("cluster stop")
                          .hasMessageContaining("Address already in use");
    }

    /// #915 review round 1 — the ~40 sites whose `await()` read no `Result` before this ticket keep
    /// not failing the test. Only the bound is new there; promoting a discarded cleanup result to a
    /// test failure is a separate change, and this pins that this branch did not make it.
    @Test
    void bestEffort_boundsTheWaitButDoesNotFailTheTest() {
        var failed = Promise.<Unit> failure(new TestCause("stop rejected"));
        var neverSettles = Promise.<Unit> promise();
        var startedAtMs = System.currentTimeMillis();

        assertThatCode(() -> LifecycleAwait.bestEffort("cluster stop in tearDown()",
                                                       unstartedCluster(),
                                                       SHORT_BOUND,
                                                       failed)).doesNotThrowAnyException();
        assertThatCode(() -> LifecycleAwait.bestEffort("cluster stop in tearDown()",
                                                       unstartedCluster(),
                                                       SHORT_BOUND,
                                                       neverSettles)).doesNotThrowAnyException();

        assertThat(System.currentTimeMillis() - startedAtMs).describedAs("the never-settling arm must have been ended by the BOUND — "
                                                                       + "not throwing must not mean not returning")
                  .isGreaterThanOrEqualTo(SHORT_BOUND.duration().toMillis())
                  .isLessThan(SHORT_BOUND.duration().toMillis() + SLACK_MS);
    }

    /// Pins the delegation itself: [ClusterSnapshot#NO_STATE] is the OTHER class's constant, so a
    /// `LifecycleAwait` that went back to rendering its own empty-registry sentence fails here.
    @Test
    void aClusterWithNothingToReport_isRenderedByTheModulesOneRenderer() {
        assertThat(LifecycleAwait.snapshot(unstartedCluster())).isEqualTo(ClusterSnapshot.NO_STATE);
    }

    @Test
    void anAbsentCluster_isNamedRatherThanNullPrinted() {
        var snapshot = LifecycleAwait.snapshot(null);

        assertThat(snapshot).contains("no cluster: the field was never assigned");
        assertThat(snapshot).doesNotContain("null");
    }

    /// Review round 1, BLOCKING 1 — the node line, which nothing in the first revision reached.
    ///
    /// Asserted as a whole rendered block rather than by `contains`, so anything ADDED to the line is
    /// a failure too: the mutation the reviewer used to prove the old pin vacuous was an addition.
    /// The two nodes differ in state, leader and both ports, which makes each rendered field evidence
    /// about its own node rather than a constant that happens to read correctly.
    @Test
    void aPopulatedRegistry_rendersEveryNodeLine_withObservedStateAndPorts() {
        var live = new EmberCluster.ClusterStatus(List.of(node("p921-1", 27700, 27740, EmberCluster.STATE_ACTIVE, true),
                                                          node("p921-2", 27701, 27741, EmberCluster.STATE_INACTIVE, false)),
                                                  "p921-1");

        assertThat(LifecycleAwait.snapshot(live, Option.none()))
                  .isEqualTo("  leader=p921-1\n"
                            + "  p921-1 state=active leader=true port=27700 mgmt=27740\n"
                            + "  p921-2 state=inactive leader=false port=27701 mgmt=27741");
    }

    /// Review round 1, BLOCKING 2 — the branch #913 made the COMMON one.
    ///
    /// Since #913, `EmberCluster.start` settles on the first node failure and the abort clears
    /// `nodes`/`nodeInfos` before the caller sees the failure. A dump read off `status()` alone is
    /// therefore empty on exactly the failure it exists for, which is why this must not render
    /// [ClusterSnapshot#NO_STATE].
    @Test
    void aClearedRegistryAfterAFailedStart_rendersWhatTheStartFailureRetained() {
        var captured = new EmberCluster.StartFailure(
            new EmberCluster.ClusterStatus(List.of(node("p921-1", 27700, 27740, EmberCluster.STATE_INACTIVE, false)),
                                           "none"),
            Map.of("p921-1", "Address already in use"));

        var rendered = LifecycleAwait.snapshot(emptyStatus(), Option.some(captured));

        assertThat(rendered).describedAs("an empty dump on a failed start is the defect #913's NO_STATE exists to name")
                            .isNotEqualTo(ClusterSnapshot.NO_STATE);
        assertThat(rendered).contains("p921-1 state=inactive leader=false port=27700 mgmt=27740");
        assertThat(rendered).contains("start failures: {p921-1=Address already in use}");
        assertThat(rendered).contains("captured when the start failed");
    }

    private static EmberCluster.NodeStatus node(String id, int port, int mgmtPort, String state, boolean isLeader) {
        return new EmberCluster.NodeStatus(id, port, mgmtPort, state, isLeader);
    }

    private static EmberCluster.ClusterStatus emptyStatus() {
        return new EmberCluster.ClusterStatus(List.of(), "none");
    }

    /// Constructed, never started: [EmberCluster]'s constructor only assigns fields, so this binds no
    /// port and starts no thread.
    private static EmberCluster unstartedCluster() {
        return emberCluster(3, 29900, 29940, 29980, "la-probe");
    }
}
