// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.deadsurface;

import java.util.List;

import org.pragmatica.aether.api.DynamicConfigManager;
import org.pragmatica.aether.deployment.config.ConfigNotificationManager;
import org.pragmatica.aether.deployment.config.DefaultConfigChangeFeed;
import org.pragmatica.aether.deployment.node.NodeDeploymentManager;
import org.pragmatica.aether.slice.ConfigChangeFeed;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /// #774: the `Watched`/`Live` feed is fed by the applier (`DynamicConfigManager.onApplied(feed::publish)` in
    /// `AetherNode`) and released by the slice unload (`SliceStore.unloadEntry` -> `ConfigChangeFeed.releaseAll`).
    /// A negative control rides along: `holdsFeedLock` is a test seam with no production caller, so a scan that
    /// cannot tell a dead method from a live one would pass the two assertions above for the wrong reason.
    @Test
    void configFeedIsPublishedToAndReleasedThroughProductionCode() throws Exception {
        assertCorpusIsComplete();

        var reachability = BytecodeReachability.scan(PRODUCTION_ROOTS);

        assertTrue(reachability.isReachable(MethodRef.of(DefaultConfigChangeFeed.class.getDeclaredMethod("publish", String.class))),
                   "#774: DefaultConfigChangeFeed.publish must be registered as a DynamicConfigManager applied-listener "
                   + "by AetherNode, or Watched/Live never see a change");
        assertTrue(reachability.isReachable(MethodRef.of(ConfigChangeFeed.class.getDeclaredMethod("releaseAll", String.class))),
                   "#774: ConfigChangeFeed.releaseAll must be called by SliceStore.unloadEntry, or an unloaded slice's "
                   + "subscriptions outlive it");
        assertTrue(reachability.isReachable(MethodRef.of(DefaultConfigChangeFeed.class.getDeclaredMethod("shutdown"))),
                   "#774: DefaultConfigChangeFeed.shutdown must be called by the node's stop sequence, or a stopped node "
                   + "leaves delivery threads running against torn-down resources");
        assertFalse(reachability.isReachable(MethodRef.of(DefaultConfigChangeFeed.class.getDeclaredMethod("holdsFeedLock"))),
                    "negative control: a test-only seam has no production caller; if this is 'reachable' the scan proves nothing");
    }
}
