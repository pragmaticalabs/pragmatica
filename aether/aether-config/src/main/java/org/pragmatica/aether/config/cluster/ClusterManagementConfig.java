package org.pragmatica.aether.config.cluster;

public record ClusterManagementConfig(DeploymentSpec deployment, ClusterSpec cluster) {
    public static ClusterManagementConfig clusterManagementConfig(DeploymentSpec deployment, ClusterSpec cluster) {
        return new ClusterManagementConfig(deployment, cluster);
    }
}
