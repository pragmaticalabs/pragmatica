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
