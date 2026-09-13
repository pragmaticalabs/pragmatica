// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.management.route.ManagementRouteError;
import org.pragmatica.aether.management.route.MatchedRoute;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #1039 receive-side owner guard: what a node does with a management request that reached it by
/// owner forwarding.
///
/// The defect these pin is a REACHABILITY one. A forwarded request is dispatched by `router.handle`
/// directly and never re-enters `dispatchManagementRequest`, so the receiving node runs none of the
/// `RouteTarget` dispatch switch. Before this guard, a receiver whose membership view disagreed with
/// the sender's answered anyway and returned 200 with `servedByOwner=false` — the ambiguous answer
/// #1039 exists to remove — while the shipped docs promised a named 503.
///
/// These drive [ManagementServerImpl#checkForwardedPartitionOwner], the decision the receive path
/// consults. The one line they do NOT observe is the call site inside
/// `dispatchManagementForwardWithinBudget`: `ManagementServerImpl` cannot be constructed without a
/// live node (its constructor builds ~30 route sources off `ManageableNode` and arms a periodic
/// sweep), which is the same limitation that keeps every other dispatch decision in that class
/// pinned as a package-visible static.
class ForwardedOwnerGuardTest {
    private static final NodeId SELF = NodeId.nodeId("node-self").unwrap();
    private static final NodeId SENDER = NodeId.nodeId("node-sender").unwrap();
    private static final NodeId OTHER = NodeId.nodeId("node-other").unwrap();
    private static final String REPLICAS_PATH = "/api/v1/streams/myns/orders/1.0.0/replicas/7";
    private static final String LEADER_PATH = "/api/v1/cluster/status";
    private static final String LOCAL_REPLICAS_PATH = "/api/v1/streams/orders/7/replicas-local";

    @Test
    void checkForwardedPartitionOwner_failsWithOwnerForwardLoop_whenTheReceiverResolvesAnotherOwner() {
        // The skew case, and the one this endpoint meets most often, because it is queried during
        // failover. The sender resolved SELF and forwarded; SELF resolves OTHER. Refusing by name IS
        // the fix — dispatching instead answers 200 with servedByOwner=false.
        var resolver = new RecordingOwnerResolver(Option.some(OTHER));
        var cause = refusalOf(check(REPLICAS_PATH, resolver));

        assertThat(cause).isInstanceOf(ManagementRouteError.OwnerForwardLoop.class);

        var loop = (ManagementRouteError.OwnerForwardLoop) cause;

        assertThat(loop.previousHop())
                .as("the previous hop must be the peer the transport names, not a client-settable header")
                .isEqualTo(SENDER.id());
        assertThat(loop.routeName()).isEqualTo("STREAM_REPLICAS");
        assertThat(resolver.calls())
                .as("the guard must judge the route and partition param the URL names")
                .containsExactly("STREAM_REPLICAS/3");
    }

    @Test
    void checkForwardedPartitionOwner_failsWithOwnerForwardLoop_whenTheReceiverResolvesTheSenderItself() {
        // A<->B disagreement. Bouncing it back is what could cycle, so the refusal is terminal rather
        // than a second hop.
        assertThat(refusalOf(check(REPLICAS_PATH, new RecordingOwnerResolver(Option.some(SENDER)))))
                .isInstanceOf(ManagementRouteError.OwnerForwardLoop.class);
    }

    @Test
    void checkForwardedPartitionOwner_succeeds_whenThisNodeIsTheResolvedOwner() {
        check(REPLICAS_PATH, new RecordingOwnerResolver(Option.some(SELF)))
                .onFailure(cause -> fail("the resolved owner must answer locally: " + cause.message()));
    }

    @Test
    void checkForwardedPartitionOwner_failsWithPartitionOwnerUnresolved_whenNoOwnerResolves() {
        // Empty member view, or the pre-reconcile bootstrap window. Answering locally would emit
        // servedByOwner=false with an empty ring, indistinguishable from a genuinely empty partition.
        assertThat(refusalOf(check(REPLICAS_PATH, new RecordingOwnerResolver(Option.none()))))
                .isInstanceOf(ManagementRouteError.PartitionOwnerUnresolved.class);
    }

    @Test
    void checkForwardedPartitionOwner_succeeds_forALeaderTargetedRoute() {
        // Regression guard on a SHARED receive path: leader-, core-, task-group- and node-targeted
        // forwards travel through the same method and must dispatch exactly as before.
        assertDispatchedWithoutOwnerLookup(LEADER_PATH);
    }

    @Test
    void checkForwardedPartitionOwner_succeeds_forALocalTargetedRoute() {
        assertDispatchedWithoutOwnerLookup(LOCAL_REPLICAS_PATH);
    }

    @Test
    void checkForwardedPartitionOwner_succeeds_forAnUnmatchedPath() {
        // The 404 belongs to `router.handle` and the legacy handlers below it; refusing here would
        // turn an unknown path into a 503.
        assertDispatchedWithoutOwnerLookup("/api/v1/no/such/route");
    }

    @Test
    void checkForwardedPartitionOwner_succeeds_forAnUnparseableMethod() {
        var resolver = new RecordingOwnerResolver(Option.some(OTHER));

        ManagementServerImpl.checkForwardedPartitionOwner("NOTAMETHOD", REPLICAS_PATH, SELF, SENDER, resolver::resolve)
                            .onFailure(cause -> fail("an unparseable method is the router's problem, not a 503: "
                                                    + cause.message()));
        assertThat(resolver.calls()).isEmpty();
    }

    private static void assertDispatchedWithoutOwnerLookup(String path) {
        var resolver = new RecordingOwnerResolver(Option.some(OTHER));

        check(path, resolver).onFailure(cause -> fail("a non-owner-targeted forward must dispatch: " + cause.message()));
        assertThat(resolver.calls())
                .as("control: this resolver answers OTHER for every input, so an empty call list is the "
                   + "only evidence that the route was never judged as an ownership question")
                .isEmpty();
    }

    private static Result<Unit> check(String path, RecordingOwnerResolver resolver) {
        return ManagementServerImpl.checkForwardedPartitionOwner("GET", path, SELF, SENDER, resolver::resolve);
    }

    private static Cause refusalOf(Result<Unit> result) {
        return result.fold(cause -> cause,
                           _ -> fail("expected a named refusal, got a dispatch"));
    }

    private static final class RecordingOwnerResolver {
        private final Option<NodeId> owner;
        private final List<String> calls = new ArrayList<>();

        RecordingOwnerResolver(Option<NodeId> owner) {
            this.owner = owner;
        }

        List<String> calls() {return List.copyOf(calls);}

        Option<NodeId> resolve(MatchedRoute matched, int partitionParamIndex) {
            calls.add(matched.route().name() + "/" + partitionParamIndex);

            return owner;
        }
    }
}
