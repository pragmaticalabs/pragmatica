// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.reconciler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.ReconcilerConfig;
import org.pragmatica.aether.config.ReconcilerRulesConfig;
import org.pragmatica.aether.config.RuleSpec;
import org.pragmatica.aether.deployment.audit.CommandLifecycleEvent;
import org.pragmatica.aether.deployment.audit.CommandLifecycleEvent.CommandReceived;
import org.pragmatica.aether.deployment.cluster.LifecycleWriter;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmConfig;
import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.swim.SwimHealth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


/// Unit tests for `LifecycleReconcilerRecord` — leader lifecycle, phase gating, and the
/// audit-only-vs-enforcing dispatch semantics.
class LifecycleReconcilerRecordTest {
    private AtomicReference<ClusterPhase> phaseRef;
    private KVStore<AetherKey, AetherValue> kvStore;
    private AtomicReference<Map<NodeId, SwimHealth>> swimHealthRef;
    private AtomicReference<Set<NodeId>> activeSyncHoldsRef;
    private List<LifecycleCommand> dispatchedCommands;
    private List<String> dispatchedSources;
    private List<CommandLifecycleEvent> auditEvents;
    private AtomicLong clockMs;
    private MembershipFsmConfig fsmConfig;
    private AtomicReference<Option<MembershipView>> generationSnapshotRef;

    @BeforeEach
    void setUp() {
        phaseRef = new AtomicReference<>(ClusterPhase.NORMAL);
        var router = MessageRouter.DelegateRouter.delegate();
        router.quiesce();
        kvStore = new KVStore<>(router, null, null);
        swimHealthRef = new AtomicReference<>(Map.of());
        activeSyncHoldsRef = new AtomicReference<>(Set.of());
        dispatchedCommands = new ArrayList<>();
        dispatchedSources = new ArrayList<>();
        auditEvents = new ArrayList<>();
        clockMs = new AtomicLong(0L);
        fsmConfig = MembershipFsmConfig.defaultMembershipFsmConfig();
        generationSnapshotRef = new AtomicReference<>(Option.none());
    }

    @Nested
    class LeaderGating {
        @Test
        void activate_thenDeactivate_togglesActiveState() {
            var reconciler = buildReconciler(ReconcilerConfig.defaults());
            assertFalse(reconciler.active());
            reconciler.activate();
            assertTrue(reconciler.active());
            reconciler.deactivate();
            assertFalse(reconciler.active());
        }

        @Test
        void activate_isIdempotent() {
            var reconciler = buildReconciler(ReconcilerConfig.defaults());
            reconciler.activate();
            reconciler.activate();
            assertTrue(reconciler.active());
            reconciler.deactivate();
        }

        @Test
        void deactivate_whileInactive_isNoOp() {
            var reconciler = buildReconciler(ReconcilerConfig.defaults());
            reconciler.deactivate();
            assertFalse(reconciler.active());
        }
    }

    @Nested
    class PhaseGating {
        @Test
        void reconcile_skipsWhenPhaseIsColdBoot() {
            phaseRef.set(ClusterPhase.COLD_BOOT);
            seedDecommissionableJoiner();

            var reconciler = (LifecycleReconcilerRecord) buildReconciler(enforcingConfig());
            reconciler.activate();
            invokeReconcileDirectly(reconciler);

            assertEquals(0, dispatchedCommands.size());
            assertEquals(0, auditEvents.size());
            reconciler.deactivate();
        }

        @Test
        void reconcile_skipsWhenPhaseIsRecovering() {
            phaseRef.set(ClusterPhase.RECOVERING);
            seedDecommissionableJoiner();

            var reconciler = (LifecycleReconcilerRecord) buildReconciler(enforcingConfig());
            reconciler.activate();
            invokeReconcileDirectly(reconciler);

            assertEquals(0, dispatchedCommands.size());
            reconciler.deactivate();
        }

        @Test
        void reconcile_runsWhenPhaseIsNormal() {
            phaseRef.set(ClusterPhase.NORMAL);
            seedDecommissionableJoiner();

            var reconciler = (LifecycleReconcilerRecord) buildReconciler(enforcingConfig());
            reconciler.activate();
            invokeReconcileDirectly(reconciler);

            assertEquals(1, dispatchedCommands.size());
            assertEquals(CommandLifecycleEvent.SOURCE_RECONCILER, dispatchedSources.get(0));
            reconciler.deactivate();
        }
    }

    @Nested
    class DispatchSemantics {
        @Test
        void auditOnly_publishesCommandReceivedAndSkipsApplyCommand() {
            phaseRef.set(ClusterPhase.NORMAL);
            seedDecommissionableJoiner();

            var reconciler = (LifecycleReconcilerRecord) buildReconciler(dryRunConfig());
            reconciler.activate();
            invokeReconcileDirectly(reconciler);

            assertEquals(0, dispatchedCommands.size(), "dry-run must not call applyCommand");
            assertEquals(1, auditEvents.size(), "dry-run must publish CommandReceived audit event");
            var received = (CommandReceived) auditEvents.get(0);
            assertEquals(CommandLifecycleEvent.SOURCE_RECONCILER, received.source());
            assertEquals("ForceDecommission", received.commandType());
            reconciler.deactivate();
        }

        @Test
        void enforcing_dispatchesViaApplyCommandWithReconcilerSource() {
            phaseRef.set(ClusterPhase.NORMAL);
            seedDecommissionableJoiner();

            var reconciler = (LifecycleReconcilerRecord) buildReconciler(enforcingConfig());
            reconciler.activate();
            invokeReconcileDirectly(reconciler);

            assertEquals(1, dispatchedCommands.size());
            assertEquals(CommandLifecycleEvent.SOURCE_RECONCILER, dispatchedSources.get(0));
            reconciler.deactivate();
        }

        @Test
        void recentDecisions_capturesEveryRuleFire() {
            phaseRef.set(ClusterPhase.NORMAL);
            seedDecommissionableJoiner();

            var reconciler = (LifecycleReconcilerRecord) buildReconciler(dryRunConfig());
            reconciler.activate();
            invokeReconcileDirectly(reconciler);

            var decisions = reconciler.recentDecisions();
            assertEquals(1, decisions.size());
            var decision = decisions.get(0);
            assertEquals("JoiningTimeout", decision.ruleName());
            assertEquals("node-2", decision.peer());
            assertFalse(decision.enforced());
            assertNotNull(reconciler.lastTickAt().fold(() -> null, x -> x));
            assertNotNull(reconciler.lastActionAt().fold(() -> null, x -> x));
            reconciler.deactivate();
        }
    }

    @Nested
    class NormalPhaseWarmup {
        @Test
        void firstTickAfterNormalEntry_skipsRulesEvenIfBudgetExceeded() {
            phaseRef.set(ClusterPhase.NORMAL);
            seedDecommissionableJoiner();

            var reconciler = (LifecycleReconcilerRecord) buildReconciler(enforcingConfigWithWarmup(60_000L));
            reconciler.activate();
            invokeReconcileDirectly(reconciler);

            // Warmup window has just opened (60s budget unmet) — even with all rule
            // budgets satisfied, the reconciler must not fire on the first NORMAL tick.
            assertEquals(0, dispatchedCommands.size(), "warmup must suppress enforce");
            assertEquals(0, auditEvents.size(), "warmup must also suppress audit-only emit");
            reconciler.deactivate();
        }

        @Test
        void tickPastWarmup_firesNormally() {
            phaseRef.set(ClusterPhase.NORMAL);
            seedDecommissionableJoiner();
            var initialNowMs = clockMs.get();

            var reconciler = (LifecycleReconcilerRecord) buildReconciler(enforcingConfigWithWarmup(60_000L));
            reconciler.activate();
            invokeReconcileDirectly(reconciler);
            assertEquals(0, dispatchedCommands.size(), "first tick is in warmup");

            // Advance clock past warmup deadline. Rules now fire on subsequent tick.
            clockMs.set(initialNowMs + 61_000L);
            invokeReconcileDirectly(reconciler);

            assertEquals(1, dispatchedCommands.size(), "post-warmup tick must fire");
            reconciler.deactivate();
        }

        @Test
        void phaseTransitionAwayFromNormal_clearsWarmupAndSwimSince() {
            phaseRef.set(ClusterPhase.NORMAL);
            seedDecommissionableJoiner();
            var initialNowMs = clockMs.get();

            var reconciler = (LifecycleReconcilerRecord) buildReconciler(enforcingConfigWithWarmup(60_000L));
            reconciler.activate();
            invokeReconcileDirectly(reconciler); // first NORMAL tick — warmup starts
            clockMs.set(initialNowMs + 30_000L);
            invokeReconcileDirectly(reconciler); // still warmup
            assertEquals(0, dispatchedCommands.size());

            // Phase wobbles to RECOVERING then back. Warmup must restart.
            phaseRef.set(ClusterPhase.RECOVERING);
            invokeReconcileDirectly(reconciler);
            phaseRef.set(ClusterPhase.NORMAL);
            clockMs.set(initialNowMs + 35_000L);
            invokeReconcileDirectly(reconciler); // fresh NORMAL entry — warmup restarted
            clockMs.set(initialNowMs + 35_000L + 30_000L);
            invokeReconcileDirectly(reconciler); // 30s after fresh entry → still warmup
            assertEquals(0, dispatchedCommands.size(), "warmup must restart on each NORMAL entry");

            clockMs.set(initialNowMs + 35_000L + 61_000L);
            invokeReconcileDirectly(reconciler);
            assertEquals(1, dispatchedCommands.size(), "rule fires once new warmup elapses");
            reconciler.deactivate();
        }
    }

    private static ReconcilerConfig enforcingConfigWithWarmup(long warmupMs) {
        var enforce = RuleSpec.enforcing();
        var rules = new ReconcilerRulesConfig(enforce, enforce, enforce, enforce, enforce, enforce, enforce);
        return new ReconcilerConfig(true,
                                    TimeSpan.timeSpan(10).seconds(),
                                    TimeSpan.timeSpan(warmupMs).millis(),
                                    rules,
                                    50);
    }

    private void seedDecommissionableJoiner() {
        var node = NodeId.nodeId("node-2").unwrap();
        var lifecycleEntries = new HashMap<NodeId, NodeLifecycleValue>();
        lifecycleEntries.put(node, NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.JOINING, 0L));
        kvStore.process(new KVCommand.Put<>(NodeLifecycleKey.nodeLifecycleKey(node),
                                             lifecycleEntries.get(node)));
        // SWIM Faulty signals "container demonstrably gone" — triggers JoiningTimeout.
        swimHealthRef.set(Map.of(node, SwimHealth.FAULTY));
        // Advance clock past JOIN_DEADLINE × 1.5
        clockMs.set((long) (fsmConfig.joinDeadline().millis() * 1.5) + 5_000L);
    }

    private static ReconcilerConfig enforcingConfig() {
        var enforce = RuleSpec.enforcing();
        var rules = new ReconcilerRulesConfig(enforce, enforce, enforce, enforce, enforce, enforce, enforce);
        // Warmup=0 so single-tick tests exercise rule firing immediately on NORMAL entry.
        // Warmup semantics covered separately by `NormalPhaseWarmupTest`.
        return new ReconcilerConfig(true, TimeSpan.timeSpan(10).seconds(), TimeSpan.timeSpan(0).millis(), rules, 50);
    }

    /// Phase 4 dry-run shape — every rule enabled, every rule audit-only. Kept for tests
    /// that exercise the audit-only dispatch path. Warmup=0 — see `enforcingConfig`.
    private static ReconcilerConfig dryRunConfig() {
        return new ReconcilerConfig(true,
                                    TimeSpan.timeSpan(10).seconds(),
                                    TimeSpan.timeSpan(0).millis(),
                                    ReconcilerRulesConfig.dryRunDefaults(),
                                    50);
    }

    private LifecycleReconciler buildReconciler(ReconcilerConfig config) {
        LifecycleWriter writer = new CapturingLifecycleWriter();
        StreamPublisher<CommandLifecycleEvent> auditPublisher = event -> {
            auditEvents.add(event);
            return Promise.unitPromise();
        };
        return LifecycleReconciler.lifecycleReconciler(phaseRef::get,
                                                       kvStore,
                                                       generationSnapshotRef::get,
                                                       swimHealthRef::get,
                                                       activeSyncHoldsRef::get,
                                                       writer,
                                                       auditPublisher,
                                                       fsmConfig,
                                                       config,
                                                       clockMs::get);
    }

    /// Bypass the scheduler — invoke `doReconcile` synchronously via the public tick that
    /// the scheduler would normally drive. The test reflectively reaches the package-private
    /// hook by calling the public `activate()` then directly invoking a fresh tick through
    /// `LifecycleReconcilerRecord`'s reconcile method.
    private static void invokeReconcileDirectly(LifecycleReconcilerRecord reconciler) {
        try {
            var method = LifecycleReconcilerRecord.class.getDeclaredMethod("reconcile");
            method.setAccessible(true);
            method.invoke(reconciler);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to invoke reconcile()", e);
        }
    }

    /// Lifecycle writer stub that captures commands + sources without performing any KV
    /// writes. Returns a successful `Unit` promise for every call.
    private final class CapturingLifecycleWriter implements LifecycleWriter {
        private final AtomicInteger callCount = new AtomicInteger(0);

        @Override public Promise<Unit> requestDrain(NodeId target) {
            return Causes.cause("not used in this test").promise();
        }

        @Override public Promise<Unit> requestDecommission(NodeId target) {
            return Causes.cause("not used in this test").promise();
        }

        @Override public Promise<Unit> requestActivate(NodeId target) {
            return Causes.cause("not used in this test").promise();
        }

        @Override public Promise<Unit> requestFailedDrain(NodeId target) {
            return Causes.cause("not used in this test").promise();
        }

        @Override public Promise<Unit> applyCommand(LifecycleCommand command) {
            return applyCommand(command, CommandLifecycleEvent.SOURCE_UNKNOWN);
        }

        @Override public Promise<Unit> applyCommand(LifecycleCommand command, String source) {
            callCount.incrementAndGet();
            dispatchedCommands.add(command);
            dispatchedSources.add(source);
            return Promise.unitPromise();
        }
    }
}
