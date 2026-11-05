// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.controller;

import io.netty.buffer.ByteBuf;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.config.RollbackConfig;
import org.pragmatica.aether.controller.RollbackManagerGuardedRecordTest.HeldClusterNode;
import org.pragmatica.aether.controller.RollbackManagerOverridePreservationTest.AlwaysLeaderManager;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.PreviousVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.PreviousVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/// #2613 item 3: a refusal re-derives at most three times per chain, then the leader reports the lagging record as an
/// operator warning, and the warning is closed by a recovery once the record names its target's version again.
@Timeout(60)
class RollbackManagerRecordReconcileTest {
    private static final NodeId SELF = NodeId.nodeId("node-1").unwrap();
    private static final ArtifactBase BASE = Artifact.artifact("org.test:my-slice:1.0.0").unwrap().base();
    private static final SliceTargetKey TARGET_KEY = SliceTargetKey.sliceTargetKey(BASE);
    private static final Version X = Version.version("0.9.0").unwrap();
    private static final Version A = Version.version("1.0.0").unwrap();
    private static final Version B = Version.version("2.0.0").unwrap();

    private final List<OperatorWarning> warnings = new CopyOnWriteArrayList<>();
    private KVStore<AetherKey, AetherValue> store;
    private HeldClusterNode held;
    private SliceTargetValue targetA;
    private RollbackManager manager;

    @BeforeEach
    void setUp() {
        store = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        held = new HeldClusterNode(SELF, store);
        targetA = SliceTargetValue.sliceTargetValue(A, 3);

        RollbackManagerOverridePreservationTest.seedCommittedLeader(store, SELF, 1);
        commit(TARGET_KEY, targetA);
        commit(PreviousVersionKey.previousVersionKey(BASE), PreviousVersionValue.previousVersionValue(BASE, X, A));
        manager = RollbackManager.rollbackManager(SELF,
                                                  () -> RollbackConfig.rollbackConfig(),
                                                  held,
                                                  store,
                                                  new AlwaysLeaderManager(SELF),
                                                  _ -> {},
                                                  OperatorWarningSink.handingOffTo(warnings::add));
        manager.activate();
    }

    @Test
    void onSliceTargetPut_raisesExhaustedOnce_thenRecovers_whenEveryWriteInTheChainIsRefused() throws InterruptedException {
        var targetB = commit(TARGET_KEY, targetA.withVersion(B));

        manager.onSliceTargetPut(put(targetB, targetA));
        assertThat(held.captured()).as("arming").hasSize(1);

        var latest = contendAndApply(targetB, 0);

        held.awaitCaptured(2);
        latest = contendAndApply(latest, 1);
        held.awaitCaptured(3);
        latest = contendAndApply(latest, 2);
        held.settle();

        assertThat(held.captured()).as("three attempts, no fourth").hasSize(3);
        awaitWarnings(1);
        assertThat(warnings).singleElement().satisfies(warning -> {
            assertThat(warning.code()).isEqualTo(OperatorWarningCode.ROLLBACK_RECORD_RECONCILE_EXHAUSTED);
            assertThat(warning.subject()).isEqualTo(BASE.asString());
        });

        manager.onSliceTargetPut(put(latest, latest.withInstances(latest.targetInstances() + 1)));
        held.awaitCaptured(4);
        held.applyToStore(3);
        awaitWarnings(2);

        assertThat(warnings.get(1).code()).isEqualTo(OperatorWarningCode.ROLLBACK_RECORD_RECONCILED);
        assertThat(warnings.get(1).subject()).isEqualTo(BASE.asString());
    }

    /// Control for the test above: one refusal followed by an accepted retry reports nothing, so the warning is the
    /// exhaustion and not the refusal.
    @Test
    void onSliceTargetPut_raisesNothing_whenTheRetryAfterARefusalIsAccepted() throws InterruptedException {
        var targetB = commit(TARGET_KEY, targetA.withVersion(B));

        manager.onSliceTargetPut(put(targetB, targetA));
        contendAndApply(targetB, 0);
        held.awaitCaptured(2);
        held.applyToStore(1);
        held.settle();
        Thread.sleep(300);

        assertThat(warnings).isEmpty();
        assertThat(store.getTyped(PreviousVersionKey.previousVersionKey(BASE), PreviousVersionValue.class)
                        .map(PreviousVersionValue::previousVersion)).isEqualTo(Option.some(A));
    }

    /// A scale change commits (the target moves, the version does not), then the held write at `index` is applied and refused.
    private SliceTargetValue contendAndApply(SliceTargetValue current, int index) {
        var scaled = commit(TARGET_KEY, current.withInstances(current.targetInstances() + 1));

        held.applyToStore(index);

        return scaled;
    }

    private void awaitWarnings(int count) throws InterruptedException {
        var deadline = System.nanoTime() + 5_000_000_000L;

        while (warnings.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(warnings).as("operator warnings after waiting for %s", count).hasSizeGreaterThanOrEqualTo(count);
    }

    private <V extends AetherValue> V commit(AetherKey key, V value) {
        store.process(store.createBatch(List.<KVCommand<AetherKey>>of(new KVCommand.Put<AetherKey, AetherValue>(key, value))));

        return value;
    }

    private static ValuePut<SliceTargetKey, SliceTargetValue> put(SliceTargetValue value, SliceTargetValue replaced) {
        return new ValuePut<>(new KVCommand.Put<>(TARGET_KEY, value), Option.some(replaced));
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
