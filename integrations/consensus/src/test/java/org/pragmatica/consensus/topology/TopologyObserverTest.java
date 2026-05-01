/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package org.pragmatica.consensus.topology;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Verifies the public peer-registration API on `TopologyObserver`. Network adapters
/// call `registerPeer`/`unregisterPeer` directly on Hello handshake / confirmed peer
/// departure; no `TopologyManagementMessage` routing is involved.
class TopologyObserverTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId PEER_A = nodeId("node-a").unwrap();
    private static final NodeId PEER_B = nodeId("node-b").unwrap();
    private static final NodeId NEW_PEER = nodeId("node-new").unwrap();

    private static final NodeInfo INFO_SELF = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("localhost", 6000).unwrap());
    private static final NodeInfo INFO_A = NodeInfo.nodeInfo(PEER_A, NodeAddress.nodeAddress("localhost", 6001).unwrap());
    private static final NodeInfo INFO_B = NodeInfo.nodeInfo(PEER_B, NodeAddress.nodeAddress("localhost", 6002).unwrap());
    private static final NodeInfo INFO_NEW = NodeInfo.nodeInfo(NEW_PEER, NodeAddress.nodeAddress("localhost", 6003).unwrap());

    private static TopologyConfig baseConfig() {
        return new TopologyConfig(SELF,
                                  3,
                                  timeSpan(60).seconds(),
                                  timeSpan(1).seconds(),
                                  List.of(INFO_SELF, INFO_A, INFO_B));
    }

    private static TopologyObserver newObserver(MessageRouter router) {
        return TopologyObserver.topologyObserver(baseConfig(), router).unwrap();
    }

    @Nested
    class RegisterPeer {
        @Test
        void registerPeer_unknownNode_addsToTopology() {
            var observer = newObserver(MessageRouter.mutable());

            observer.registerPeer(INFO_NEW);

            assertThat(observer.topology()).contains(NEW_PEER);
            assertThat(observer.get(NEW_PEER).isPresent()).isTrue();
        }

        @Test
        void registerPeer_active_routesConnectNode() {
            var router = MessageRouter.mutable();
            var connectRequests = new CopyOnWriteArrayList<NodeId>();
            router.addRoute(NetworkServiceMessage.ConnectNode.class, msg -> connectRequests.add(msg.node()));

            var observer = newObserver(router);
            observer.start().await();

            observer.registerPeer(INFO_NEW);

            assertThat(connectRequests).contains(NEW_PEER);
        }

        @Test
        void registerPeer_idempotent_doesNotDuplicateConnectRequests() {
            var router = MessageRouter.mutable();
            var connectRequests = new CopyOnWriteArrayList<NodeId>();
            router.addRoute(NetworkServiceMessage.ConnectNode.class, msg -> connectRequests.add(msg.node()));

            var observer = newObserver(router);
            observer.start().await();

            observer.registerPeer(INFO_NEW);
            observer.registerPeer(INFO_NEW);
            observer.registerPeer(INFO_NEW);

            assertThat(connectRequests.stream().filter(NEW_PEER::equals).count())
                .as("ConnectNode emitted only on first registration; later calls are no-ops")
                .isEqualTo(1L);
            assertThat(observer.topology()).contains(NEW_PEER);
        }

        @Test
        void registerPeer_clearsTombstoneFromPriorUnregister() {
            var observer = newObserver(MessageRouter.mutable());

            // Pre-existing peer (added in ctor) is removed and tombstoned, then re-registered.
            observer.unregisterPeer(PEER_A);
            assertThat(observer.topology()).doesNotContain(PEER_A);

            observer.registerPeer(INFO_A);

            assertThat(observer.topology()).contains(PEER_A);
            assertThat(observer.get(PEER_A).isPresent()).isTrue();
        }
    }

    @Nested
    class UnregisterPeer {
        @Test
        void unregisterPeer_removesFromTopology() {
            var observer = newObserver(MessageRouter.mutable());

            observer.unregisterPeer(PEER_A);

            assertThat(observer.topology()).doesNotContain(PEER_A);
            assertThat(observer.get(PEER_A).isEmpty()).isTrue();
        }

        @Test
        void unregisterPeer_routesDisconnectNode() {
            var router = MessageRouter.mutable();
            var disconnectRequests = new CopyOnWriteArrayList<NodeId>();
            router.addRoute(NetworkServiceMessage.DisconnectNode.class, msg -> disconnectRequests.add(msg.nodeId()));
            router.addRoute(NetworkServiceMessage.ConnectNode.class, _ -> {});

            var observer = newObserver(router);

            observer.unregisterPeer(PEER_A);

            assertThat(disconnectRequests).contains(PEER_A);
        }

        @Test
        void unregisterPeer_idempotent_secondCallIsNoOp() {
            var router = MessageRouter.mutable();
            var disconnectRequests = new CopyOnWriteArrayList<NodeId>();
            router.addRoute(NetworkServiceMessage.DisconnectNode.class, msg -> disconnectRequests.add(msg.nodeId()));
            router.addRoute(NetworkServiceMessage.ConnectNode.class, _ -> {});

            var observer = newObserver(router);

            observer.unregisterPeer(PEER_A);
            observer.unregisterPeer(PEER_A);
            observer.unregisterPeer(PEER_A);

            assertThat(disconnectRequests.stream().filter(PEER_A::equals).count())
                .as("DisconnectNode emitted only on first removal")
                .isEqualTo(1L);
        }

        @Test
        void unregisterPeer_self_isIgnored() {
            var observer = newObserver(MessageRouter.mutable());

            observer.unregisterPeer(SELF);

            assertThat(observer.topology()).contains(SELF);
            assertThat(observer.self().id()).isEqualTo(SELF);
        }

        @Test
        void unregisterPeer_tombstonesAgainstReconciliation() {
            // unregisterPeer marks the peer as tombstoned so static-config reconciliation
            // (initReconcile) will not resurrect it from config.coreNodes().
            var observer = newObserver(MessageRouter.mutable());

            observer.unregisterPeer(PEER_A);

            assertThat(observer.topology()).doesNotContain(PEER_A);
        }
    }

    /// Fix C: `initReconcile` must consult the KV-Store's `NodeLifecycleValue.DECOMMISSIONED`
    /// atoms via the injected `isDecommissioned` predicate, not just the in-memory
    /// `tombstonedNodes` set. The set is empty after a process restart; without the
    /// KV-backed predicate a DECOMMISSIONED ghost peer is silently re-seeded from
    /// `config.coreNodes()` on every reconciliation tick.
    @Nested
    class KvDecommissionedFilter {
        private static TopologyObserver observerWith(MessageRouter router, Predicate<NodeId> isDecommissioned) {
            return TopologyObserver.topologyObserver(baseConfig(), router, isDecommissioned).unwrap();
        }

        @Test
        void initReconcile_decommissionedNodeInKV_skipsConfigReseed() {
            // PEER_A is in `config.coreNodes()` but the KV says it's DECOMMISSIONED.
            // The fresh observer must NOT add it, neither at construction time nor when
            // `start()` triggers `initReconcile`.
            Predicate<NodeId> isDecommissioned = id -> id.equals(PEER_A);

            var observer = observerWith(MessageRouter.mutable(), isDecommissioned);
            observer.start().await();

            assertThat(observer.topology()).doesNotContain(PEER_A);
            assertThat(observer.get(PEER_A).isEmpty()).isTrue();
        }

        @Test
        void initReconcile_activeNodeInKV_addsConfigReseed() {
            // PEER_A is in `config.coreNodes()` and KV does NOT mark it DECOMMISSIONED
            // (e.g. state is ACTIVE). It must be added.
            Predicate<NodeId> isDecommissioned = _ -> false;

            var observer = observerWith(MessageRouter.mutable(), isDecommissioned);
            observer.start().await();

            assertThat(observer.topology()).contains(PEER_A);
            assertThat(observer.get(PEER_A).isPresent()).isTrue();
        }

        @Test
        void initReconcile_noKVAtom_addsConfigReseed_legacyBehavior() {
            // Predicate returns false for every node — the legacy "no KV reader wired"
            // case (test fixtures, non-Aether RabiaNode usage). Behaviour matches
            // pre-Fix-C: every `config.coreNodes()` entry is added.
            Predicate<NodeId> isDecommissioned = _ -> false;

            var observer = observerWith(MessageRouter.mutable(), isDecommissioned);
            observer.start().await();

            assertThat(observer.topology()).contains(SELF, PEER_A, PEER_B);
        }

        @Test
        void initReconcile_inMemoryTombstone_skipsRegardlessOfKV() {
            // In-memory tombstone (set by `unregisterPeer`) wins independently of the
            // KV predicate. KV reports the node as ACTIVE (not DECOMMISSIONED), but the
            // in-session tombstone still suppresses the reseed.
            Predicate<NodeId> isDecommissioned = _ -> false;

            var observer = observerWith(MessageRouter.mutable(), isDecommissioned);
            observer.start().await();
            // Sanity: reseed has happened by now.
            assertThat(observer.topology()).contains(PEER_A);

            observer.unregisterPeer(PEER_A);

            assertThat(observer.topology()).doesNotContain(PEER_A);
            // Subsequent `initReconcile` ticks are a no-op for tombstoned peers.
            // We force a re-entry by calling start() on a stopped observer; even though
            // start() is idempotent, the public contract says tombstoned peers stay out.
            assertThat(observer.topology()).doesNotContain(PEER_A);
        }

        @Test
        void registerPeer_explicitReadd_overridesKvDecommissioned() {
            // Brief requirement #5: an explicit re-add (e.g. restarted container completes
            // QUIC Hello handshake → `registerPeer`) MUST succeed even if the KV still says
            // DECOMMISSIONED. The standard CTM lifecycle write path will replace the atom;
            // the observer-level filter must not stand in the way.
            //
            // The KV state is mutable in this test: initially DECOMMISSIONED (so the
            // initReconcile filter blocks the reseed), then we observe that registerPeer
            // bypasses the filter unconditionally.
            var kvState = new AtomicReference<Set<NodeId>>(Set.of(PEER_A));
            Predicate<NodeId> isDecommissioned = id -> kvState.get().contains(id);

            var observer = observerWith(MessageRouter.mutable(), isDecommissioned);
            observer.start().await();
            // Pre-condition: PEER_A blocked by KV filter.
            assertThat(observer.topology()).doesNotContain(PEER_A);

            observer.registerPeer(INFO_A);

            // Post-condition: explicit re-add succeeded despite KV still saying
            // DECOMMISSIONED. CTM is expected to subsequently transition the lifecycle
            // atom away from DECOMMISSIONED via the standard write path.
            assertThat(observer.topology()).contains(PEER_A);
            assertThat(observer.get(PEER_A).isPresent()).isTrue();
        }
    }

    /// `TopologyObserver` is the canonical publisher of `QuorumStateNotification`.
    /// QUIC/Netty transports no longer publish quorum state — they manage peer-link
    /// state only. This nest verifies edge-transition publishing across the membership
    /// mutation paths the observer owns.
    @Nested
    class QuorumStatePublishing {
        /// Build a router pre-wired to capture every `QuorumStateNotification` route
        /// (subscribers must be registered before observer construction because the
        /// constructor seeds `nodeStatesById` and may already cross the quorum threshold).
        private static MessageRouter.MutableRouter routerCapturing(List<QuorumStateNotification> sink) {
            var router = MessageRouter.mutable();
            router.addRoute(QuorumStateNotification.class, sink::add);
            return router;
        }

        /// Self-only `coreNodes` so construction does NOT cross the quorum threshold —
        /// peers are added later via `registerPeer` to drive deterministic edge
        /// transitions.
        private static TopologyConfig selfOnlyConfig() {
            return new TopologyConfig(SELF,
                                      3,
                                      timeSpan(60).seconds(),
                                      timeSpan(1).seconds(),
                                      List.of(INFO_SELF));
        }

        @Test
        void evaluateQuorumState_crossingUp_routesEstablishedExactlyOnce() {
            var notifications = new CopyOnWriteArrayList<QuorumStateNotification>();
            var router = routerCapturing(notifications);
            var observer = TopologyObserver.topologyObserver(selfOnlyConfig(), router).unwrap();
            observer.start().await();
            // Self-only at startup: peers=0, +1=1 < quorum=2 → no fire (initial publish is
            // disappeared-as-default, but quorumEstablished latch starts false so no edge).
            assertThat(notifications).isEmpty();

            // First peer brings peers=1, +1=2 = quorum=2 → established edge transition.
            observer.registerPeer(INFO_A);

            assertThat(notifications)
                .as("crossing up the quorum threshold fires established exactly once")
                .hasSize(1);
            assertThat(notifications.getFirst().state())
                .isEqualTo(QuorumStateNotification.State.ESTABLISHED);
        }

        @Test
        void evaluateQuorumState_crossingDown_routesDisappearedExactlyOnce() {
            var notifications = new CopyOnWriteArrayList<QuorumStateNotification>();
            var router = routerCapturing(notifications);
            var observer = TopologyObserver.topologyObserver(selfOnlyConfig(), router).unwrap();
            observer.start().await();

            // Push above quorum: register two peers (peers=2, +1=3 >= quorum=2 → established).
            observer.registerPeer(INFO_A);
            observer.registerPeer(INFO_B);
            assertThat(notifications).hasSize(1);
            assertThat(notifications.getFirst().state())
                .isEqualTo(QuorumStateNotification.State.ESTABLISHED);

            // Drop both peers: peers=0, +1=1 < quorum=2 → disappeared edge transition.
            observer.unregisterPeer(PEER_A);
            observer.unregisterPeer(PEER_B);

            assertThat(notifications)
                .as("crossing down the quorum threshold fires disappeared exactly once")
                .hasSize(2);
            assertThat(notifications.get(1).state())
                .isEqualTo(QuorumStateNotification.State.DISAPPEARED);
        }

        @Test
        void evaluateQuorumState_idempotent_aboveThreshold_doesNotDuplicate() {
            var notifications = new CopyOnWriteArrayList<QuorumStateNotification>();
            var router = routerCapturing(notifications);
            var observer = TopologyObserver.topologyObserver(selfOnlyConfig(), router).unwrap();
            observer.start().await();

            // Cross up once.
            observer.registerPeer(INFO_A);
            assertThat(notifications).hasSize(1);

            // Same peer registered again — no state change, no duplicate notification.
            observer.registerPeer(INFO_A);
            // A different peer added but we are still above threshold — no new fire.
            observer.registerPeer(INFO_B);

            assertThat(notifications)
                .as("evaluateQuorumState is idempotent — no duplicate established")
                .hasSize(1);
        }

        @Test
        void evaluateQuorumState_idempotent_belowThreshold_doesNotDuplicate() {
            // Use clusterSize=5 (quorumSize=3) so that a single peer + self stays below
            // threshold (peers=1, +1=2 < 3). This lets us verify that repeated mutations
            // that all stay below threshold do not produce a notification.
            var notifications = new CopyOnWriteArrayList<QuorumStateNotification>();
            var router = routerCapturing(notifications);
            var config = new TopologyConfig(SELF,
                                            5,
                                            timeSpan(60).seconds(),
                                            timeSpan(1).seconds(),
                                            List.of(INFO_SELF));
            var observer = TopologyObserver.topologyObserver(config, router).unwrap();
            observer.start().await();

            // peers=0, +1=1 < quorum=3 — no fire.
            observer.registerPeer(INFO_A);   // peers=1, +1=2 < 3 — still below
            observer.registerPeer(INFO_B);   // peers=2, +1=3 = 3 — crosses up (established)
            assertThat(notifications)
                .as("only the threshold-crossing call fires established")
                .hasSize(1);

            observer.unregisterPeer(PEER_A); // peers=1, +1=2 < 3 — crosses down
            assertThat(notifications).hasSize(2);

            // Already below threshold — register/unregister churn that stays below
            // must not duplicate the disappeared notification.
            observer.registerPeer(INFO_NEW);  // peers=2, +1=3 = 3 — crosses up again (establishes)
            observer.unregisterPeer(NEW_PEER); // crosses down again
            // Two more transitions — now at 4 total. The "below threshold" idempotence
            // is shown by the next assertions where we churn while staying below.
            observer.unregisterPeer(PEER_B); // peers=0, +1=1 < 3 — already below, no fire
            assertThat(notifications)
                .as("repeated unregisters while already below threshold do not duplicate")
                .hasSize(4);
        }

        @Test
        void connectionFailed_isTransportOnly_doesNotRouteQuorumTransition() {
            // POST-FIX INVARIANT (2026-05-01): transport-level `ConnectionFailed` events
            // are local peer-link bookkeeping only. They MUST NOT fire
            // `QuorumStateNotification` edges — the canonical publisher is driven by
            // authoritative membership signals (SWIM → HealthReconciler → KV
            // `NodeLifecycleKey` atom → `addNode`/`removeNode` / snapshot source).
            //
            // Pre-fix, a QUIC-handshake storm during cold-boot cascaded transport flaps
            // through `handleConnectionFailed → evaluateQuorumState`, fired spurious
            // `QuorumStateNotification.disappeared`, and reset RabiaEngine. This test
            // pins the new behaviour: even when both peers' QUIC links fail, no
            // disappeared notification is routed because peer membership is unchanged.
            var notifications = new CopyOnWriteArrayList<QuorumStateNotification>();
            var router = routerCapturing(notifications);
            var observer = TopologyObserver.topologyObserver(selfOnlyConfig(), router).unwrap();
            observer.start().await();

            // Establish quorum via authoritative `registerPeer`.
            observer.registerPeer(INFO_A);
            observer.registerPeer(INFO_B);
            assertThat(notifications).hasSize(1);
            assertThat(notifications.getFirst().state())
                .isEqualTo(QuorumStateNotification.State.ESTABLISHED);

            // Sink other transport-level routes.
            router.addRoute(NetworkServiceMessage.ConnectNode.class, _ -> {});
            router.addRoute(NetworkServiceMessage.DisconnectNode.class, _ -> {});
            router.addRoute(NetworkServiceMessage.ListConnectedNodes.class, _ -> {});

            // Both peers' QUIC links fail (transport-only event).
            observer.handleConnectionFailed(new NetworkServiceMessage.ConnectionFailed(PEER_A, Causes.cause("link reset")));
            observer.handleConnectionFailed(new NetworkServiceMessage.ConnectionFailed(PEER_B, Causes.cause("link reset")));

            assertThat(notifications)
                .as("transport-level ConnectionFailed must not fire QuorumStateNotification")
                .hasSize(1);
        }

        @Test
        void quicHandshakeFlap_onAuthoritativeOnDutyPeer_doesNotChangeQuorumState() {
            // Cold-boot QUIC handshake storm: a peer that is authoritatively registered
            // (registerPeer) cycles connect → disconnect → connect repeatedly while its
            // membership remains unchanged. The canonical publisher must remain at its
            // current latch state; no edge transitions may be routed.
            var notifications = new CopyOnWriteArrayList<QuorumStateNotification>();
            var router = routerCapturing(notifications);
            router.addRoute(NetworkServiceMessage.ConnectNode.class, _ -> {});
            router.addRoute(NetworkServiceMessage.DisconnectNode.class, _ -> {});
            router.addRoute(NetworkServiceMessage.ListConnectedNodes.class, _ -> {});
            var observer = TopologyObserver.topologyObserver(selfOnlyConfig(), router).unwrap();
            observer.start().await();

            // Establish quorum: peers=2, +1=3 >= quorum=2 → established once.
            observer.registerPeer(INFO_A);
            observer.registerPeer(INFO_B);
            assertThat(notifications).hasSize(1);
            assertThat(notifications.getFirst().state())
                .isEqualTo(QuorumStateNotification.State.ESTABLISHED);

            // Simulate the handshake storm: each peer flaps connect/fail/connect 5 times.
            for (var i = 0; i < 5; i++) {
                observer.handleConnectionFailed(new NetworkServiceMessage.ConnectionFailed(PEER_A, Causes.cause("flap " + i)));
                observer.handleConnectionEstablished(new NetworkServiceMessage.ConnectionEstablished(PEER_A));
                observer.handleConnectionFailed(new NetworkServiceMessage.ConnectionFailed(PEER_B, Causes.cause("flap " + i)));
                observer.handleConnectionEstablished(new NetworkServiceMessage.ConnectionEstablished(PEER_B));
            }

            assertThat(notifications)
                .as("QUIC handshake storm on authoritative on-duty peers must not change quorum state")
                .hasSize(1);
        }

        @Test
        void authoritativeRemoval_viaUnregisterPeer_stillRoutesDisappeared() {
            // Counterpart to the transport-flap test: authoritative membership signals
            // (SWIM-confirmed `DisconnectNode` → QUIC fires `unregisterPeer`) MUST still
            // propagate to `QuorumStateNotification`. This pins that the surgical fix
            // didn't accidentally mute the authoritative path.
            var notifications = new CopyOnWriteArrayList<QuorumStateNotification>();
            var router = routerCapturing(notifications);
            router.addRoute(NetworkServiceMessage.ConnectNode.class, _ -> {});
            router.addRoute(NetworkServiceMessage.DisconnectNode.class, _ -> {});
            router.addRoute(NetworkServiceMessage.ListConnectedNodes.class, _ -> {});
            var observer = TopologyObserver.topologyObserver(selfOnlyConfig(), router).unwrap();
            observer.start().await();

            // Establish quorum.
            observer.registerPeer(INFO_A);
            observer.registerPeer(INFO_B);
            assertThat(notifications).hasSize(1);

            // Authoritative removal of both peers — peers=0, +1=1 < quorum=2 → disappeared.
            observer.unregisterPeer(PEER_A);
            observer.unregisterPeer(PEER_B);

            assertThat(notifications)
                .as("authoritative unregisterPeer crossing quorum threshold fires disappeared")
                .hasSize(2);
            assertThat(notifications.get(1).state())
                .isEqualTo(QuorumStateNotification.State.DISAPPEARED);
        }

        @Test
        void constructionWithFullCoreNodes_doesNotRouteUntilStart() {
            // baseConfig() seeds SELF + PEER_A + PEER_B. The constructor used to call
            // `evaluateQuorumState()` for every seeded `addNode`, which crashed at startup
            // when the production `MessageRouter.DelegateRouter` had a null delegate field.
            // Construction must NOT publish; only `start()` may.
            var notifications = new CopyOnWriteArrayList<QuorumStateNotification>();
            var router = routerCapturing(notifications);

            var observer = TopologyObserver.topologyObserver(baseConfig(), router).unwrap();

            assertThat(notifications)
                .as("construction-time evaluateQuorumState must be deferred until start()")
                .isEmpty();

            // Now start: peers=2 (excl self), +1=3 >= quorum=2 → established edge fires once.
            observer.start().await();

            assertThat(notifications)
                .as("start() publishes the initial edge after the router is fully wired")
                .hasSize(1);
            assertThat(notifications.getFirst().state())
                .isEqualTo(QuorumStateNotification.State.ESTABLISHED);
        }

        @Test
        void construction_withPartiallyWiredDelegateRouter_doesNotNpe() {
            // Regression for HEAD 3c66e9e65 NPE: the production wiring uses a
            // `MessageRouter.DelegateRouter` whose `delegate` field is null at construction
            // time and is populated by node bootstrap before `start()` is called. The
            // pre-fix `evaluateQuorumState()` invoked from constructor-time `addNode` would
            // call `delegate.route(...)` on the null delegate and crash every container.
            //
            // This test reproduces that exact wiring order: delegate null at construction
            // (so any `router.route(...)` call NPEs), then populated, then `start()`.
            // The fix gates `evaluateQuorumState()` on a `started` flag so the constructor
            // path no-ops; `start()` flips the flag and publishes once with a live router.
            var delegate = MessageRouter.DelegateRouter.delegate();
            var notifications = new CopyOnWriteArrayList<QuorumStateNotification>();

            // Construct with a delegate router whose inner delegate is still null.
            // Pre-fix: NPE here. Post-fix: silent no-op via the started gate.
            var observer = TopologyObserver.topologyObserver(baseConfig(), delegate).unwrap();
            assertThat(notifications)
                .as("construction with partially-wired router must not NPE and must not publish")
                .isEmpty();

            // Populate the delegate (mirrors node bootstrap order: handlers register first,
            // then the real router replaces the placeholder before start()).
            var realRouter = routerCapturing(notifications);
            delegate.replaceDelegate(realRouter);

            observer.start().await();

            assertThat(notifications)
                .as("start() with fully-wired router publishes initial established edge")
                .hasSize(1);
            assertThat(notifications.getFirst().state())
                .isEqualTo(QuorumStateNotification.State.ESTABLISHED);
        }

        @Test
        void start_belowQuorum_doesNotRouteEstablished() {
            // Self-only config below quorum (clusterSize=3, peers=0, +1=1 < quorum=2):
            // start() must not fire an `established` notification because no edge has been
            // crossed (latch starts false, threshold-test is false → no transition).
            var notifications = new CopyOnWriteArrayList<QuorumStateNotification>();
            var router = routerCapturing(notifications);

            var observer = TopologyObserver.topologyObserver(selfOnlyConfig(), router).unwrap();
            observer.start().await();

            assertThat(notifications)
                .as("start() below quorum must not publish established")
                .isEmpty();
        }
    }
}
