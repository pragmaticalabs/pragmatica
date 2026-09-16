// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deadsurface;

import java.util.List;

import org.pragmatica.aether.api.DynamicConfigManager;
import org.pragmatica.aether.deployment.config.ConfigNotificationManager;
import org.pragmatica.aether.deployment.node.NodeDeploymentManager;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;


/// #381: the runtime config-change PUSH to slices (`notifyConfigUpdate` on the generated factory)
/// existed end to end except for its trigger — `ConfigNotificationManager.notifyChange` had no caller,
/// so only the ACTIVATE-time notification ever fired. These assertions pin the chain that now carries
/// a committed `ConfigKey` change to the registered slices, one production call site each:
/// `AetherNode.collectRouteEntries` -> `DynamicConfigManager.onApplied(nodeDeploymentManager::onConfigChanged)`
/// -> `NodeDeploymentState.Active.handleConfigChanged` -> `ConfigNotificationManager.notifyChange`.
/// Structural on purpose: a boot test with a deployed `@ConfigUpdate` slice is the only runtime
/// observation, and none exists; deleting any one of the three call sites reddens the matching line.
class ConfigChangePushLivenessTest {
    private static final List<java.nio.file.Path> PRODUCTION_ROOTS = ReactorRoots.productionRoots();

    private static void assertCorpusIsComplete() {
        var missing = ReactorRoots.missingProductionOutput();

        assertTrue(missing.isEmpty(),
                   "Corpus incomplete: " + missing + " — run a full reactor build before trusting this gate");
    }

    @Test
    void configChangeReachesNotifyChangeThroughProductionCode() throws Exception {
        assertCorpusIsComplete();

        var reachability = BytecodeReachability.scan(PRODUCTION_ROOTS);

        assertTrue(reachability.isReachable(MethodRef.of(ConfigNotificationManager.class.getDeclaredMethod("notifyChange",
                                                                                                           String.class,
                                                                                                           org.pragmatica.lang.Functions.Fn1.class))),
                   "#381: ConfigNotificationManager.notifyChange must be called by production code "
                   + "(NodeDeploymentState.Active on a ConfigChanged event); with no caller the slice "
                   + "notifyConfigUpdate callbacks never fire after activation");
        assertTrue(reachability.isReachable(MethodRef.of(NodeDeploymentManager.class.getDeclaredMethod("onConfigChanged",
                                                                                                       String.class))),
                   "#381: NodeDeploymentManager.onConfigChanged must be the DynamicConfigManager listener "
                   + "registered by AetherNode.collectRouteEntries");
        assertTrue(reachability.isReachable(MethodRef.of(DynamicConfigManager.class.getDeclaredMethod("onApplied",
                                                                                                      java.util.function.Consumer.class))),
                   "#381: DynamicConfigManager.onApplied must be registered by production code "
                   + "(AetherNode.collectRouteEntries), or the KV change never leaves the overlay provider");
    }
}
