// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #915 — the pin for [LifecycleAwait]. No cluster is started here.
///
/// The defect being closed is a DIAGNOSTIC one, and a diagnostic cannot be pinned by a test that only
/// ever runs against a healthy system: the whole point is what the message says on the path that does
/// not happen in a green run. So every input here is supplied directly — an unresolved promise, a
/// failed promise, a cluster that was constructed and never started — which makes the failure text
/// itself the assertion subject and keeps the class down in the milliseconds.
///
/// The pair that matters is [#aPromiseThatNeverSettles_expiresAtTheBound_andNamesTheStep] and
/// [#aPromiseThatSettles_returnsItsValue]. Without the second, a `settled` rewritten to throw
/// unconditionally would satisfy the first — the failure test alone cannot tell "expires correctly"
/// from "never works".
class LifecycleAwaitTest {
    private static final TimeSpan SHORT_BOUND = TimeSpan.timeSpan(2).seconds();
    /// Generous: the assertion below only needs to separate "the bound ended it" from "something
    /// else did", not to measure scheduling precision on a loaded box.
    private static final long SLACK_MS = 20_000;

    private record TestCause(String message) implements Cause {}

    @Test
    void aPromiseThatNeverSettles_expiresAtTheBound_andNamesTheStep() {
        var neverSettles = Promise.<Unit>promise();
        var cluster = unstartedCluster();
        var startedAtMs = System.currentTimeMillis();

        assertThatThrownBy(() -> LifecycleAwait.settled("5-core cluster start", cluster, SHORT_BOUND, neverSettles))
            .isInstanceOf(AssertionError.class)
            .describedAs("the expiry must name the step, which is the whole defect: the 8m backstop "
                         + "named only the class")
            .hasMessageContaining("5-core cluster start")
            .hasMessageContaining("did not settle within")
            .describedAs("and must carry the cluster state, so the next reader does not need a rerun")
            .hasMessageContaining("Cluster state when the wait ended:");

        var elapsedMs = System.currentTimeMillis() - startedAtMs;

        assertThat(elapsedMs)
            .describedAs("the BOUND must be what ended the wait — an unbounded await would still be "
                         + "parked here, which is exactly how ~515s class-level ERRORs were produced")
            .isGreaterThanOrEqualTo(SHORT_BOUND.duration().toMillis())
            .isLessThan(SHORT_BOUND.duration().toMillis() + SLACK_MS);
    }

    /// The positive control. A `settled` that always threw would pass the test above.
    @Test
    void aPromiseThatSettles_returnsItsValue() {
        var settled = Promise.<String>promise().resolve(org.pragmatica.lang.Result.success("msrc-6"));

        var value = LifecycleAwait.settled("worker join", unstartedCluster(), SHORT_BOUND, settled);

        assertThat(value).isEqualTo("msrc-6");
    }

    /// A lifecycle step that FAILED and one that never settled both leave the step undone, and both
    /// used to be discarded here: the bare `c.stop().await()` in several @AfterAll methods read no
    /// Result at all, so a stop that failed outright was silently a success.
    @Test
    void aPromiseThatFails_isReportedWithTheSameNamedShape() {
        var failed = Promise.<Unit>failure(new TestCause("Address already in use"));

        assertThatThrownBy(() -> LifecycleAwait.settled("cluster stop", unstartedCluster(), SHORT_BOUND, failed))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("cluster stop")
            .hasMessageContaining("Address already in use");
    }

    @Test
    void aClusterWithNoRegisteredNode_saysSo_ratherThanRenderingAnEmptyBlock() {
        var snapshot = LifecycleAwait.snapshot(unstartedCluster());

        assertThat(snapshot).contains("no node is registered with this cluster");
        assertThat(snapshot).contains("nodeCount=0");
    }

    @Test
    void anAbsentCluster_isNamedRatherThanNullPrinted() {
        var snapshot = LifecycleAwait.snapshot(null);

        assertThat(snapshot).contains("no cluster: the field was never assigned");
        assertThat(snapshot).doesNotContain("null");
    }

    /// The honesty pin. On this branch `EmberCluster.toNodeStatus` passes the string literal
    /// `"healthy"` for every node, so `NodeStatus.state()` is a constant that cannot distinguish a
    /// formed cluster from a wedged one. Rendering it would put a fabricated observation into the one
    /// artifact a reader consults when things are already wrong. If a later change starts rendering
    /// the field, this goes red rather than the fabrication reaching a CI log unnoticed.
    @Test
    void theSnapshotNeverRendersTheFabricatedHealthyLiteral() {
        assertThat(LifecycleAwait.snapshot(unstartedCluster()))
            .describedAs("NodeStatus.state is a hardcoded literal on this branch, not an observation")
            .doesNotContain("healthy");
    }

    /// Constructed, never started: [EmberCluster]'s constructor only assigns fields, so this binds no
    /// port and starts no thread.
    private static EmberCluster unstartedCluster() {
        return emberCluster(3, 29900, 29940, 29980, "la-probe");
    }
}
