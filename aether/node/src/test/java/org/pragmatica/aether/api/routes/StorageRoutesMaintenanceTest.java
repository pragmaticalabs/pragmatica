// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.api.routes;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.StorageFactory.StorageSetup;
import org.pragmatica.lang.Option;
import org.pragmatica.storage.DemotionManager;
import org.pragmatica.storage.DemotionManager.DemotionStats;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.PromotionManager;
import org.pragmatica.storage.PromotionManager.PromotionStats;
import org.pragmatica.storage.StorageGarbageCollector;
import org.pragmatica.storage.StorageGarbageCollector.GCStats;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.storage.StorageReadinessGate;
import org.pragmatica.storage.SnapshotManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.pragmatica.aether.api.routes.StorageRoutes.storageRoutes;

/// `GET /api/v1/storage/{name}` carries the instance's background maintenance as this node ran it: demotion, promotion
/// and GC, each with whether it is running and its cumulative counters. Every figure is distinct, so a field wired to
/// the wrong counter cannot pass.
class StorageRoutesMaintenanceTest {
    @Test
    void instanceDetail_reportsThisNodesDemotionPromotionAndGarbageCollection() {
        var detail = detailOf(setup("artifacts", promotion(true, new PromotionStats(7, 7168, 2, 5, 1_700_000_000_005L))));

        assertThat(detail.maintenance().demotion()).isEqualTo(new StorageRoutes.DemotionDetail(true, 11, 4096, 1_700_000_000_011L));
        assertThat(detail.maintenance().promotion()).isEqualTo(new StorageRoutes.PromotionDetail(true,
                                                                                                  true,
                                                                                                  7,
                                                                                                  7168,
                                                                                                  2,
                                                                                                  5,
                                                                                                  1_700_000_000_005L));
        assertThat(detail.maintenance().garbageCollection()).isEqualTo(new StorageRoutes.GarbageCollectionDetail(false,
                                                                                                                  13,
                                                                                                                  1_700_000_000_013L));
    }

    @Test
    void instanceDetail_reportsPromotionDisabled_forAnInstanceWithTheInertManager() {
        var detail = detailOf(setup("streams", PromotionManager.NOOP));

        assertThat(detail.maintenance().promotion().enabled()).as("streams (or promotion = false) never promotes").isFalse();
        assertThat(detail.maintenance().promotion().active()).isFalse();
    }

    private static PromotionManager promotion(boolean active, PromotionStats stats) {
        var promotion = mock(PromotionManager.class);

        when(promotion.isActive()).thenReturn(active);
        when(promotion.stats()).thenReturn(stats);

        return promotion;
    }

    private static StorageRoutes.StorageDetailResponse detailOf(StorageSetup setup) {
        var node = (AetherNode) Proxy.newProxyInstance(AetherNode.class.getClassLoader(),
                                                       new Class[]{AetherNode.class},
                                                       (_, method, _) -> storageSetups(method.getName(), setup));

        return storageRoutes(() -> node).instanceDetail(setup.name())
                                        .onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()))
                                        .unwrap();
    }

    private static Object storageSetups(String method, StorageSetup setup) {
        if ("storageSetups".equals(method)) {
            return Map.of(setup.name(), setup);
        }

        throw new UnsupportedOperationException("Not implemented in test proxy: " + method);
    }

    private static StorageSetup setup(String name, PromotionManager promotion) {
        var instance = StorageInstance.storageInstance(name, List.of(MemoryTier.memoryTier(1024L * 1024L)));
        var demotion = mock(DemotionManager.class);
        var gc = mock(StorageGarbageCollector.class);

        when(demotion.isActive()).thenReturn(true);
        when(demotion.stats()).thenReturn(new DemotionStats(11, 4096, 1_700_000_000_011L));
        when(gc.isActive()).thenReturn(false);
        when(gc.stats()).thenReturn(new GCStats(13, 1_700_000_000_013L));

        return StorageSetup.storageSetup(name,
                                         instance,
                                         mock(SnapshotManager.class),
                                         StorageReadinessGate.storageReadinessGate(),
                                         MetadataStore.inMemoryMetadataStore(name),
                                         demotion,
                                         gc,
                                         Option.empty(),
                                         promotion);
    }
}
