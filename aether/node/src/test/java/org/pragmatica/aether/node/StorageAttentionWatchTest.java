// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.pragmatica.aether.node.StorageFactory.StorageSetup;
import org.pragmatica.lang.Option;
import org.pragmatica.storage.DemotionManager;
import org.pragmatica.storage.SnapshotManager;
import org.pragmatica.storage.StorageCondition;
import org.pragmatica.storage.StorageCondition.Kind;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// A0b: every condition a storage instance holds that needs an operator becomes ONE operator event when it appears and
/// ONE recovery when it goes. The hand-off sink delivers on its own thread, so a settle wait follows each look.
///
/// Degrade paths pinned here, with the search space `log.warn`/`log.error` in `integrations/storage/src/main`,
/// `aether/aether-storage/src/main` and the storage classes of `aether/node/src/main`:
/// disk tier unavailable (boot, end to end below), memory tier clamped (`StorageFactoryBootDegradeTest`, then the same
/// boot-condition route), demotion refused, snapshot write failing and snapshot fell back (`StorageConditionsTest` for
/// the managers; the transition and the one-event rule below, for the route every condition takes).
class StorageAttentionWatchTest {
    @TempDir
    Path tempDir;

    private final List<OperatorWarning> delivered = new CopyOnWriteArrayList<>();
    private final OperatorWarningSink sink = OperatorWarningSink.handingOffTo(delivered::add);

    /// Mutation: `storageAttentionWatch` does not observe on construction -- what boot left standing is not reported.
    @Test
    void storageAttentionWatch_bootDegrade_raisesOneEventPerInstance_endToEnd() {
        var setups = StorageFactory.createAll(Map.of(), "node-1", Option.none(), Option.none(), HermeticStorage.synthesisDefaultsIn(tempDir))
                                   .onFailure(cause -> fail(cause.message()))
                                   .unwrap();

        var watch = StorageAttentionWatch.storageAttentionWatch(setups, sink);
        awaitEvents(2);
        watch.observe();
        watch.observe();
        settle();

        assertThat(delivered).extracting(OperatorWarning::code).containsOnly(OperatorWarningCode.STORAGE_DEGRADED);
        assertThat(delivered).extracting(OperatorWarning::subject).containsExactlyInAnyOrder("artifacts/disk-tier-unavailable",
                                                                                              "content/disk-tier-unavailable");
        assertThat(delivered.getFirst().message()).contains("no local disk tier");
    }

    /// Mutation: `observe` compares nothing against what was raised (raises on every look).
    @Test
    void observe_aConditionThatStays_isRaisedOnce_andRecoveredOnceWhenItGoes() {
        var held = new CopyOnWriteArrayList<StorageCondition>();
        var watch = StorageAttentionWatch.storageAttentionWatch(Map.of("artifacts", setupHolding(held)), sink);

        held.add(StorageCondition.storageCondition(Kind.DEMOTION_REFUSED, "durable tier full"));
        watch.observe();
        watch.observe();
        awaitEvents(1);
        settle();
        assertThat(delivered).extracting(OperatorWarning::code).containsExactly(OperatorWarningCode.STORAGE_DEGRADED);
        assertThat(delivered.getFirst().subject()).isEqualTo("artifacts/demotion-refused");

        held.clear();
        watch.observe();
        watch.observe();
        awaitEvents(2);
        settle();
        assertThat(delivered).extracting(OperatorWarning::code)
                             .containsExactly(OperatorWarningCode.STORAGE_DEGRADED, OperatorWarningCode.STORAGE_RECOVERED);
        assertThat(delivered.getLast().subject()).as("the recovery closes the same subject").isEqualTo("artifacts/demotion-refused");
    }

    /// Mutation: the subject drops the condition kind -- two conditions of one instance close each other.
    @Test
    void observe_twoConditionsOfOneInstance_haveTheirOwnSubjects() {
        var held = new CopyOnWriteArrayList<StorageCondition>();
        var watch = StorageAttentionWatch.storageAttentionWatch(Map.of("artifacts", setupHolding(held)), sink);

        held.add(StorageCondition.storageCondition(Kind.DEMOTION_REFUSED, "a"));
        held.add(StorageCondition.storageCondition(Kind.SNAPSHOT_WRITE_FAILING, "b"));
        watch.observe();
        awaitEvents(2);
        held.removeIf(condition -> condition.kind() == Kind.DEMOTION_REFUSED);
        watch.observe();
        awaitEvents(3);
        settle();

        assertThat(delivered).extracting(OperatorWarning::subject)
                             .containsExactlyInAnyOrder("artifacts/demotion-refused", "artifacts/snapshot-write-failing", "artifacts/demotion-refused");
        assertThat(delivered).filteredOn(warning -> warning.code() == OperatorWarningCode.STORAGE_RECOVERED)
                             .extracting(OperatorWarning::subject)
                             .containsExactly("artifacts/demotion-refused");
    }

    /// Mutation: `StorageSetup#conditions` leaves out the demotion manager's conditions.
    @Test
    void conditions_includeWhatTheManagersHold() {
        var held = new CopyOnWriteArrayList<StorageCondition>();

        held.add(StorageCondition.storageCondition(Kind.DEMOTION_REFUSED, "full"));

        assertThat(setupHolding(held).conditions()).extracting(StorageCondition::kind).containsExactly(Kind.DEMOTION_REFUSED);
    }

    /// Mutation: `StorageSetup#conditions` leaves out the snapshot manager's conditions.
    @Test
    void conditions_includeWhatTheSnapshotManagerHolds_andWhatBootLeftStanding() {
        var base = healthySetups().get("artifacts");
        var snapshots = Mockito.mock(SnapshotManager.class);
        var boot = StorageCondition.storageCondition(Kind.MEMORY_TIER_CLAMPED, "cut");

        Mockito.when(snapshots.conditions()).thenReturn(List.of(StorageCondition.storageCondition(Kind.SNAPSHOT_WRITE_FAILING, "disk")));

        var setup = new StorageSetup(base.name(), base.instance(), snapshots, base.readinessGate(), base.metadataStore(), base.demotionManager(),
                                     base.garbageCollector(), base.dhtMarkerCheck(), Option.empty(), base.promotionManager(), base.prefetchManager(),
                                     List.of(boot));

        assertThat(setup.conditions()).extracting(StorageCondition::kind).containsExactly(Kind.MEMORY_TIER_CLAMPED, Kind.SNAPSHOT_WRITE_FAILING);
    }

    /// Mutation: `signalReadyActivatingStorage` stops calling `signalConsensusSynced` -- `writeReady` stays false forever.
    @Test
    void signalReadyActivatingStorage_makesEveryGateWriteReady() {
        var setups = healthySetups();
        var gate = setups.get("artifacts").readinessGate();

        assertThat(gate.isWriteReady()).isFalse();

        AetherNode.signalReadyActivatingStorage(() -> {}, setups, () -> false);

        assertThat(gate.isWriteReady()).isTrue();
        assertThat(setups.get("content").readinessGate().isWriteReady()).isTrue();
        setups.values().forEach(setup -> setup.instance().shutdown());
    }

    /// Mutation: the draining guard is dropped from the activation path -- a draining node reports write-ready.
    @Test
    void signalReadyActivatingStorage_onADrainingNode_leavesTheGateAlone() {
        var setups = healthySetups();

        AetherNode.signalReadyActivatingStorage(() -> {}, setups, (BooleanSupplier) () -> true);

        assertThat(setups.get("artifacts").readinessGate().isWriteReady()).isFalse();
        setups.values().forEach(setup -> setup.instance().shutdown());
    }

    private Map<String, StorageSetup> healthySetups() {
        return StorageFactory.createAll(Map.of(), "node-1", Option.none(), Option.none(), HermeticStorage.synthesisDefaultsIn(tempDir))
                             .onFailure(cause -> fail(cause.message()))
                             .unwrap();
    }

    /// A real setup whose demotion manager reports whatever `held` holds.
    private StorageSetup setupHolding(List<StorageCondition> held) {
        var base = healthySetups().get("artifacts");
        var demotion = Mockito.mock(DemotionManager.class);

        Mockito.when(demotion.conditions()).thenAnswer(_ -> List.copyOf(held));

        return new StorageSetup(base.name(),
                                base.instance(),
                                base.snapshotManager(),
                                base.readinessGate(),
                                base.metadataStore(),
                                demotion,
                                base.garbageCollector(),
                                base.dhtMarkerCheck(),
                                Option.empty(),
                                base.promotionManager(),
                                base.prefetchManager(),
                                List.of());
    }

    private void awaitEvents(int count) {
        var deadline = System.currentTimeMillis() + 5_000;

        while (delivered.size() < count && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(delivered).hasSizeGreaterThanOrEqualTo(count);
    }

    /// A raise that must NOT have happened gives no event to wait for: a short pause lets a wrong one arrive.
    private static void settle() {
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
