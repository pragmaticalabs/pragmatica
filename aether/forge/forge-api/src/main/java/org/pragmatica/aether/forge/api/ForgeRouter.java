// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge.api;

import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.ember.EmberCluster.EventLogEntry;
import org.pragmatica.aether.forge.ForgeMetrics;
import org.pragmatica.aether.forge.api.ForgeApiResponses.ForgeEvent;
import org.pragmatica.aether.forge.api.SimulatorRoutes.InventoryState;
import org.pragmatica.aether.forge.load.ConfigurableLoadRunner;
import org.pragmatica.aether.forge.simulator.ChaosController;
import org.pragmatica.aether.forge.simulator.SimulatorConfig;
import org.pragmatica.http.routing.RequestRouter;

import java.util.Deque;
import java.util.function.Consumer;
import java.util.function.Supplier;


/// Combines all Forge API route sources into a single RequestRouter.
public final class ForgeRouter {
    private ForgeRouter() {}

    public static RequestRouter forgeRouter(EmberCluster cluster,
                                            ConfigurableLoadRunner loadRunner,
                                            ChaosController chaosController,
                                            Supplier<SimulatorConfig> configSupplier,
                                            InventoryState inventoryState,
                                            ForgeMetrics metrics,
                                            Deque<ForgeEvent> events,
                                            long startTime,
                                            Consumer<EventLogEntry> eventLogger) {
        return RequestRouter.with(StatusRoutes.statusRoutes(cluster, metrics, events, startTime, loadRunner),
                                  TopologyRoutes.topologyRoutes(cluster),
                                  ChaosRoutes.chaosRoutes(cluster, chaosController, events, inventoryState, eventLogger),
                                  LoadRoutes.loadRoutes(loadRunner),
                                  SimulatorRoutes.simulatorRoutes(configSupplier, inventoryState, eventLogger),
                                  DeploymentRoutes.deploymentRoutes(cluster, eventLogger),
                                  AlertProxyRoutes.alertProxyRoutes(cluster),
                                  ObservabilityProxyRoutes.observabilityProxyRoutes(cluster),
                                  MetricsProxyRoutes.metricsProxyRoutes(cluster));
    }
}
