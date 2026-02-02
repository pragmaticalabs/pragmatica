package org.pragmatica.aether.forge.api;

import org.pragmatica.aether.forge.ForgeCluster;
import org.pragmatica.aether.forge.ForgeMetrics;
import org.pragmatica.aether.forge.LoadGenerator;
import org.pragmatica.aether.forge.api.ChaosRoutes.EventLogEntry;
import org.pragmatica.aether.forge.api.ForgeApiResponses.ForgeEvent;
import org.pragmatica.aether.forge.api.SimulatorRoutes.InventoryState;
import org.pragmatica.aether.forge.load.ConfigurableLoadRunner;
import org.pragmatica.aether.forge.simulator.ChaosController;
import org.pragmatica.aether.forge.simulator.SimulatorConfig;
import org.pragmatica.http.routing.RequestRouter;

import java.util.Deque;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Combines all Forge API route sources into a single RequestRouter.
 */
public final class ForgeRouter {
    private ForgeRouter() {}

    /**
     * Create a RequestRouter combining all Forge API routes.
     *
     * @param cluster         the ForgeCluster for node management
     * @param loadGenerator   the LoadGenerator for rate control
     * @param loadRunner      the ConfigurableLoadRunner for load testing
     * @param chaosController the ChaosController for chaos injection
     * @param configSupplier  supplier for SimulatorConfig
     * @param inventoryState  state for inventory simulation
     * @param metrics         the ForgeMetrics instance
     * @param events          the event log deque
     * @param startTime       server start time in milliseconds
     * @param eventLogger     callback to log events for the dashboard
     * @return RequestRouter containing all Forge API routes
     */
    public static RequestRouter forgeRouter(ForgeCluster cluster,
                                            LoadGenerator loadGenerator,
                                            ConfigurableLoadRunner loadRunner,
                                            ChaosController chaosController,
                                            Supplier<SimulatorConfig> configSupplier,
                                            InventoryState inventoryState,
                                            ForgeMetrics metrics,
                                            Deque<ForgeEvent> events,
                                            long startTime,
                                            Consumer<EventLogEntry> eventLogger) {
        return RequestRouter.with(StatusRoutes.statusRoutes(cluster, loadGenerator, metrics, events, startTime),
                                  ChaosRoutes.chaosRoutes(cluster, chaosController, events, inventoryState, eventLogger),
                                  LoadRoutes.loadRoutes(loadGenerator, loadRunner),
                                  SimulatorRoutes.simulatorRoutes(loadGenerator,
                                                                  configSupplier,
                                                                  inventoryState,
                                                                  eventLogger),
                                  DeploymentRoutes.deploymentRoutes(cluster, eventLogger),
                                  PanelRoutes.panelRoutes());
    }
}
