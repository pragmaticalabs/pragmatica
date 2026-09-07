// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.fail;

/// #915 — every lifecycle await in this module is bounded, and every expiry NAMES its step.
///
/// ## The defect this closes
///
/// `cluster.start().await()`, `cluster.stop().await()` and `cluster.addNode(..).await()` were untimed
/// across this module. An untimed [Promise#await] parks until the promise resolves and consults
/// nothing else — so a lifecycle step that never settles is not merely slow, it is unreachable by any
/// caller-side deadline. #914 records why the framework cannot rescue it either: `await()` never
/// checks the interrupt flag, so JUnit's lifecycle backstop interrupts a thread that simply re-parks.
/// The observable result on CI was a class-level ERROR at ~515 s (the 8-minute lifecycle backstop
/// plus a ~35 s constant prefix) whose entire text was the backstop's own timeout — naming the class,
/// but never the step that stalled nor the state it stalled in.
///
/// ## Why a bound alone is not the fix
///
/// [Promise#await(TimeSpan)] expires into `CoreError.Timeout("Promise is not resolved within
/// specified timeout")`. That string is identical for a cluster that never elected a leader and a
/// node that never bound its port. Bounding without naming converts a slow silent failure into a fast
/// silent one. So every expiry here carries the step's own name, the bound it exceeded, and a
/// snapshot of what the cluster was doing when it gave up.
///
/// ## Two failure policies, because the base had two
///
/// [#settled] throws; [#bestEffort] logs. Which one a call site gets is decided by what the site did
/// BEFORE this ticket, not by preference: a site that already threw on failure keeps throwing, and a
/// site whose `await()` read no `Result` at all keeps not failing the test. Promoting ~40 discarded
/// teardown results to test failures is a real improvement and a SEPARATE change — it is a new
/// failure mode in 30+ classes this branch only compiles, and a cleanup hiccup that reddens an
/// otherwise passing test is the same flakiness this ticket exists to remove. The bound is the fix
/// for #915; the error policy is not this ticket's to change.
///
/// ## The snapshot
///
/// Rendering is [ClusterSnapshot]'s, not this class's. #913 landed that renderer for the same need on
/// the same package, and it already handles the case that matters most here: since #913,
/// `EmberCluster.start` settles on the FIRST node failure and its abort clears `nodes`/`nodeInfos`
/// before the caller sees the failure, so a start-failure dump read off `status()` alone is empty on
/// exactly the failure it exists for. [EmberCluster#lastStartFailure] retains it, and
/// [ClusterSnapshot] reads it. A second renderer here would diverge from that one on the common path.
///
/// Note for readers of the first revision of this file: it omitted [EmberCluster.NodeStatus#state] on
/// the grounds that `toNodeStatus` passed the string literal `"healthy"` for every node. #913 removed
/// that literal — the field is now [EmberCluster#observedState], read from [AetherNode#isReady] — so
/// the omission is obsolete and the field is rendered. The `ready` field this class used to derive
/// itself was the SAME sample read a second time, and the weaker of the two: it re-read the live
/// registry, which a start-failure abort has already cleared.
///
/// ## Bounds
///
/// Measured on 2026-09-07 in `pragmatica-stream-h` (16-core box, load average ~10 — two sibling trees
/// building, so these are LOADED-box numbers, deliberately: a quiet-box baseline would understate
/// what a CI runner does). `MultiSourceCommunitySmokeTest` green in 42.09 s: cluster start resolved
/// in ~3-8 s, whole formation 18.5 s, `addNode` 1.1 s, `stop` 16.6 s.
///
/// [#LIFECYCLE_BOUND] is 240 s — ~30x the measured start and ~14x the measured stop. It matches the
/// 240 s guard #913 gives `SliceInvocationTest` and the longest existing internal guard in the
/// non-Heavy set, which is what keeps `junit-platform.properties`' stated invariant true: the 8 m
/// lifecycle backstop is double the longest internal guard, so the test's own await is the mechanism
/// that fires and the backstop stays the outer net it was designed to be.
///
/// [#NODE_BOUND] is 120 s — ~110x the measured 1.1 s, and half [#LIFECYCLE_BOUND]. A single node join
/// or kill is a far smaller step than standing a cluster up, so a stalled one should be reported
/// sooner; but the measurement behind it comes from a 5-node cluster, and the scale-up and churn
/// probes drive joins into larger ones under deliberate stress. Half the cluster bound keeps the
/// faster diagnosis while staying far outside any healthy time this module has been observed to take
/// — the failure mode of guessing too LOW here is a red run on a healthy cluster, which is the exact
/// flakiness this ticket exists to remove.
final class LifecycleAwait {
    private static final Logger log = LoggerFactory.getLogger(LifecycleAwait.class);

    /// The named absence for a field that was never assigned — a `@BeforeAll` that threw before the
    /// constructor ran leaves `cluster` null, and `null` printed into a dump reads as a value.
    static final String NO_CLUSTER = "  no cluster: the field was never assigned";

    /// Cluster-wide start and stop.
    static final TimeSpan LIFECYCLE_BOUND = TimeSpan.timeSpan(240).seconds();
    /// Single-node join, kill and blackhole.
    static final TimeSpan NODE_BOUND = TimeSpan.timeSpan(120).seconds();

    private LifecycleAwait() {}

    /// Await `promise` under `bound`, returning its value, or fail with a named [AssertionError].
    ///
    /// The failure path covers BOTH a promise that expired and a promise that resolved to a failure:
    /// each one leaves the lifecycle step undone, and each one is worth the same dump. The step name
    /// is supplied by the caller because only the caller knows which of several awaits in the same
    /// method this is.
    static <T> T settled(String step, EmberCluster cluster, TimeSpan bound, Promise<T> promise) {
        return promise.await(bound)
                      .fold(cause -> fail(report(step, bound, cause.message(), cluster)),
                            value -> value);
    }

    /// The cluster-wide bound, for `start` and `stop`.
    static <T> T settled(String step, EmberCluster cluster, Promise<T> promise) {
        return settled(step, cluster, LIFECYCLE_BOUND, promise);
    }

    /// The single-node bound, for `addNode`, `killNode` and `blackhole`.
    static <T> T nodeSettled(String step, EmberCluster cluster, Promise<T> promise) {
        return settled(step, cluster, NODE_BOUND, promise);
    }

    /// Bounded exactly as [#settled] is, but an expiry or failure is LOGGED rather than thrown.
    ///
    /// This is what a call site gets when its `await()` read no `Result` before this ticket. The
    /// stall is closed either way — the wait now ends at the bound instead of parking to the
    /// backstop — while the test's own verdict stays the test's. Reporting is strictly more than the
    /// bare `await()` did, which was nothing at all.
    static Result<Unit> bestEffort(String step, EmberCluster cluster, TimeSpan bound, Promise<?> promise) {
        return promise.await(bound)
                      .onFailure(cause -> log.error("#915 lifecycle step did not settle (not failing the test — "
                                                    + "this step's result was discarded before #915):\n{}",
                                                    report(step, bound, cause.message(), cluster)))
                      .mapToUnit();
    }

    /// The cluster-wide bound, for a `stop` whose result was previously discarded.
    static Result<Unit> bestEffort(String step, EmberCluster cluster, Promise<?> promise) {
        return bestEffort(step, cluster, LIFECYCLE_BOUND, promise);
    }

    /// The single-node bound, for a `killNode` or `blackhole` whose result was previously discarded.
    static Result<Unit> nodeBestEffort(String step, EmberCluster cluster, Promise<?> promise) {
        return bestEffort(step, cluster, NODE_BOUND, promise);
    }

    static String report(String step, TimeSpan bound, String cause, EmberCluster cluster) {
        return step + " did not settle within " + bound
               + ": " + cause
               + "\nCluster state when the wait ended:\n" + snapshot(cluster);
    }

    /// Delegates to [ClusterSnapshot], which is the module's one renderer. The only thing decided
    /// here is the case that renderer cannot be asked about: a cluster reference that is null.
    static String snapshot(EmberCluster cluster) {
        return Option.option(cluster)
                     .map(present -> snapshot(present.status(), present.lastStartFailure()))
                     .or(NO_CLUSTER);
    }

    /// Split from the accessor above for the reason #913 split [ClusterSnapshot#render]: the branches
    /// that matter — a populated registry, and a registry a failed start has already cleared — are
    /// drivable from a test only if the inputs can be supplied directly.
    static String snapshot(EmberCluster.ClusterStatus live, Option<EmberCluster.StartFailure> startFailure) {
        return ClusterSnapshot.render(live, startFailure);
    }
}
