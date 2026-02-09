package org.pragmatica.aether.forge.api;

import org.pragmatica.aether.forge.ForgeCluster;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Promise;

import static org.pragmatica.http.routing.Route.in;

/// Routes for cluster size management.
/// Uses a desired-state model: buttons change target cluster size,
/// and CDM auto-heal handles provisioning/deprovisioning.
public sealed interface ClusterSizeRoutes {
    record ClusterSizeResponse(boolean success, int newSize, int currentNodes) {}

    static RouteSource clusterSizeRoutes(ForgeCluster cluster) {
        return in("/api/cluster/resize")
        .serve(resizeUpRoute(cluster),
               resizeDownRoute(cluster));
    }

    private static Route<ClusterSizeResponse> resizeUpRoute(ForgeCluster cluster) {
        return Route.<ClusterSizeResponse> post("/up")
                    .toJson(_ -> resizeUp(cluster));
    }

    private static Route<ClusterSizeResponse> resizeDownRoute(ForgeCluster cluster) {
        return Route.<ClusterSizeResponse> post("/down")
                    .toJson(_ -> resizeDown(cluster));
    }

    private static Promise<ClusterSizeResponse> resizeUp(ForgeCluster cluster) {
        var newSize = cluster.effectiveClusterSize() + 1;
        cluster.setClusterSize(newSize);
        return Promise.success(new ClusterSizeResponse(true, newSize, cluster.nodeCount()));
    }

    private static Promise<ClusterSizeResponse> resizeDown(ForgeCluster cluster) {
        var currentSize = cluster.effectiveClusterSize();
        if (currentSize <= 3) {
            return Promise.success(new ClusterSizeResponse(false, currentSize, cluster.nodeCount()));
        }
        var newSize = currentSize - 1;
        cluster.setClusterSize(newSize);
        return Promise.success(new ClusterSizeResponse(true, newSize, cluster.nodeCount()));
    }

    record unused() implements ClusterSizeRoutes {}
}
