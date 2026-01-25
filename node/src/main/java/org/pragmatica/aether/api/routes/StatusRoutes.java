package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.server.RequestContext;
import org.pragmatica.http.server.ResponseWriter;

import java.util.LinkedHashSet;
import java.util.function.Supplier;

/**
 * Routes for cluster status: health, status, nodes, events.
 */
public final class StatusRoutes implements RouteHandler {
    private final Supplier<AetherNode> nodeSupplier;

    private StatusRoutes(Supplier<AetherNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static StatusRoutes statusRoutes(Supplier<AetherNode> nodeSupplier) {
        return new StatusRoutes(nodeSupplier);
    }

    @Override
    public boolean handle(RequestContext ctx, ResponseWriter response) {
        if (ctx.method() != HttpMethod.GET) {
            return false;
        }
        return switch (ctx.path()) {
            case "/api/status" -> {
                response.ok(buildStatusResponse());
                yield true;
            }
            case "/api/nodes" -> {
                response.ok(buildNodesResponse());
                yield true;
            }
            case "/api/health" -> {
                response.ok(buildHealthResponse());
                yield true;
            }
            case "/api/events" -> {
                response.ok("[]");
                yield true;
            }
            case "/api/panel/chaos" -> {
                response.okText("");
                yield true;
            }
            default -> false;
        };
    }

    private String buildStatusResponse() {
        var node = nodeSupplier.get();
        var sb = new StringBuilder();
        sb.append("{");
        // Uptime
        var uptimeSeconds = node.uptimeSeconds();
        sb.append("\"uptimeSeconds\":")
          .append(uptimeSeconds)
          .append(",");
        // Cluster info
        sb.append("\"cluster\":{");
        var allMetrics = node.metricsCollector()
                             .allMetrics();
        var connectedNodes = allMetrics.keySet();
        var leaderId = node.leader()
                           .map(NodeId::id)
                           .or("none");
        sb.append("\"nodeCount\":")
          .append(connectedNodes.size())
          .append(",");
        sb.append("\"leaderId\":\"")
          .append(leaderId)
          .append("\",");
        sb.append("\"nodes\":[");
        boolean first = true;
        for (NodeId nodeId : connectedNodes) {
            if (!first) sb.append(",");
            var isLeader = node.leader()
                               .map(l -> l.equals(nodeId))
                               .or(false);
            sb.append("{\"id\":\"")
              .append(nodeId.id())
              .append("\",\"isLeader\":")
              .append(isLeader)
              .append("}");
            first = false;
        }
        sb.append("]},");
        // Slice count
        var sliceCount = node.sliceStore()
                             .loaded()
                             .size();
        sb.append("\"sliceCount\":")
          .append(sliceCount)
          .append(",");
        // Metrics summary
        var derived = node.snapshotCollector()
                          .derivedMetrics();
        sb.append("\"metrics\":{");
        sb.append("\"requestsPerSecond\":")
          .append(derived.requestRate())
          .append(",");
        sb.append("\"successRate\":")
          .append(100.0 - derived.errorRate() * 100.0)
          .append(",");
        sb.append("\"avgLatencyMs\":")
          .append(derived.latencyP50());
        sb.append("},");
        // Node info
        sb.append("\"nodeId\":\"")
          .append(node.self()
                      .id())
          .append("\",");
        sb.append("\"status\":\"running\",");
        sb.append("\"isLeader\":")
          .append(node.isLeader())
          .append(",");
        sb.append("\"leader\":\"")
          .append(leaderId)
          .append("\"");
        sb.append("}");
        return sb.toString();
    }

    private String buildNodesResponse() {
        var node = nodeSupplier.get();
        var metrics = node.metricsCollector()
                          .allMetrics();
        var nodeIds = new LinkedHashSet<String>();
        // Always include self
        nodeIds.add(node.self()
                        .id());
        // Add nodes from initial topology
        node.initialTopology()
            .forEach(nid -> nodeIds.add(nid.id()));
        // Add nodes from metrics
        for (NodeId nodeId : metrics.keySet()) {
            nodeIds.add(nodeId.id());
        }
        // Add nodes from KV-Store slice entries
        node.kvStore()
            .snapshot()
            .keySet()
            .stream()
            .filter(key -> key instanceof AetherKey.SliceNodeKey)
            .map(key -> ((AetherKey.SliceNodeKey) key).nodeId()
                                   .id())
            .forEach(nodeIds::add);
        var sb = new StringBuilder();
        sb.append("{\"nodes\":[");
        boolean first = true;
        for (String id : nodeIds) {
            if (!first) sb.append(",");
            sb.append("\"")
              .append(id)
              .append("\"");
            first = false;
        }
        sb.append("]}");
        return sb.toString();
    }

    private String buildHealthResponse() {
        var node = nodeSupplier.get();
        var metrics = node.metricsCollector()
                          .allMetrics();
        var metricsNodeCount = metrics.size();
        var connectedNodeCount = node.connectedNodeCount();
        var sliceCount = node.sliceStore()
                             .loaded()
                             .size();
        var ready = node.isReady();
        var totalNodes = connectedNodeCount + 1;
        var hasQuorum = totalNodes >= 2;
        var status = !ready || !hasQuorum
                     ? "unhealthy"
                     : sliceCount == 0
                       ? "degraded"
                       : "healthy";
        var sb = new StringBuilder();
        sb.append("{");
        sb.append("\"status\":\"")
          .append(status)
          .append("\",");
        sb.append("\"ready\":")
          .append(ready)
          .append(",");
        sb.append("\"quorum\":")
          .append(hasQuorum)
          .append(",");
        sb.append("\"nodeCount\":")
          .append(totalNodes)
          .append(",");
        sb.append("\"connectedPeers\":")
          .append(connectedNodeCount)
          .append(",");
        sb.append("\"metricsNodeCount\":")
          .append(metricsNodeCount)
          .append(",");
        sb.append("\"sliceCount\":")
          .append(sliceCount);
        sb.append("}");
        return sb.toString();
    }
}
