package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.endpoint.WorkerEndpointEntry;
import org.pragmatica.aether.endpoint.WorkerEndpointRegistry;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

/// Routes for worker pool management: list workers, health summary, and endpoints.
public final class WorkerRoutes implements RouteSource {
    private final Supplier<WorkerEndpointRegistry> registrySupplier;

    private WorkerRoutes(Supplier<WorkerEndpointRegistry> registrySupplier) {
        this.registrySupplier = registrySupplier;
    }

    public static WorkerRoutes workerRoutes(Supplier<WorkerEndpointRegistry> registrySupplier) {
        return new WorkerRoutes(registrySupplier);
    }

    record WorkerEntry(String nodeId) {
        static WorkerEntry workerEntry(String nodeId) {
            return new WorkerEntry(nodeId);
        }
    }

    record WorkerHealthSummary(int totalWorkers, int totalGroups, boolean empty) {
        static WorkerHealthSummary workerHealthSummary(int totalWorkers, int totalGroups, boolean empty) {
            return new WorkerHealthSummary(totalWorkers, totalGroups, empty);
        }
    }

    record EndpointEntry(String artifact, String methodName, String workerNodeId, int instanceNumber) {
        static EndpointEntry endpointEntry(String artifact,
                                           String methodName,
                                           String workerNodeId,
                                           int instanceNumber) {
            return new EndpointEntry(artifact, methodName, workerNodeId, instanceNumber);
        }
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(Route.<List<WorkerEntry>> get("/api/workers")
                              .toJson(this::listWorkers),
                         Route.<WorkerHealthSummary> get("/api/workers/health")
                              .toJson(this::buildHealthSummary),
                         Route.<List<EndpointEntry>> get("/api/workers/endpoints")
                              .toJson(this::listEndpoints));
    }

    private List<WorkerEntry> listWorkers() {
        return registrySupplier.get()
                               .allWorkerNodeIds()
                               .stream()
                               .map(WorkerRoutes::toWorkerEntry)
                               .toList();
    }

    private static WorkerEntry toWorkerEntry(NodeId nodeId) {
        return WorkerEntry.workerEntry(nodeId.id());
    }

    private WorkerHealthSummary buildHealthSummary() {
        var registry = registrySupplier.get();
        return WorkerHealthSummary.workerHealthSummary(registry.workerCount(), registry.groupCount(), registry.isEmpty());
    }

    private List<EndpointEntry> listEndpoints() {
        return registrySupplier.get()
                               .allEndpoints()
                               .stream()
                               .map(WorkerRoutes::toEndpointEntry)
                               .toList();
    }

    private static EndpointEntry toEndpointEntry(WorkerEndpointEntry entry) {
        return EndpointEntry.endpointEntry(entry.artifact()
                                                .asString(),
                                           entry.methodName()
                                                .name(),
                                           entry.workerNodeId()
                                                .id(),
                                           entry.instanceNumber());
    }
}
