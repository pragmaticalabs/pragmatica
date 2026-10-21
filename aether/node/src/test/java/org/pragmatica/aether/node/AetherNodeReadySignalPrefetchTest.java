// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.DemotionManager;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.PrefetchHint;
import org.pragmatica.storage.PrefetchManager;
import org.pragmatica.storage.PrefetchStats;
import org.pragmatica.storage.PromotionManager;
import org.pragmatica.storage.SnapshotManager;
import org.pragmatica.storage.StorageGarbageCollector;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.storage.StorageReadinessGate;

import static org.assertj.core.api.Assertions.assertThat;

/// What the node's ready signal does for cross-node prefetching: lifecycle first, then the managers wake -- and not at
/// all once the node has begun draining, because the ready signal can fire again after that.
class AetherNodeReadySignalPrefetchTest {
    private final List<String> events = new CopyOnWriteArrayList<>();

    private final class RecordingManager implements PrefetchManager {
        @Override
        public void recordAccess(BlockId blockId) {}

        @Override
        public List<PrefetchHint> collectHints() {
            return List.of();
        }

        @Override
        public void processHints(List<PrefetchHint> hints) {}

        @Override
        public PrefetchStats stats() {
            return PrefetchStats.empty();
        }

        @Override
        public Result<Unit> activate() {
            events.add("wake");

            return Result.unitResult();
        }

        @Override
        public Result<Unit> deactivate() {
            events.add("sleep");

            return Result.unitResult();
        }

        @Override
        public boolean isActive() {
            return false;
        }
    }

    private Map<String, StorageFactory.StorageSetup> setups() {
        return Map.of("artifacts",
                      new StorageFactory.StorageSetup("artifacts",
                                                      Mockito.mock(StorageInstance.class),
                                                      Mockito.mock(SnapshotManager.class),
                                                      Mockito.mock(StorageReadinessGate.class),
                                                      Mockito.mock(MetadataStore.class),
                                                      Mockito.mock(DemotionManager.class),
                                                      Mockito.mock(StorageGarbageCollector.class),
                                                      Option.empty(),
                                                      Option.empty(),
                                                      PromotionManager.NOOP,
                                                      new RecordingManager()));
    }

    @Test
    void signalReadyAndWakePrefetch_signalsReadyFirst_thenWakesTheManagers() {
        AetherNode.signalReadyAndWakePrefetch(() -> events.add("ready"), setups(), () -> false);

        assertThat(events).containsExactly("ready", "wake");
    }

    @Test
    void signalReadyAndWakePrefetch_onADrainingNode_signalsReadyButWakesNothing() {
        AetherNode.signalReadyAndWakePrefetch(() -> events.add("ready"), setups(), () -> true);

        assertThat(events).containsExactly("ready");
    }
}
