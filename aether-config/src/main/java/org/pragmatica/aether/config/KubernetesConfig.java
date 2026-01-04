package org.pragmatica.aether.config;
/**
 * Kubernetes-specific configuration.
 *
 * @param namespace    Kubernetes namespace
 * @param serviceType  Service type (ClusterIP, LoadBalancer, NodePort)
 * @param storageClass Storage class for persistent volumes (empty = default)
 */
public record KubernetesConfig(
 String namespace,
 String serviceType,
 String storageClass) {
    public static final String DEFAULT_NAMESPACE = "aether";
    public static final String DEFAULT_SERVICE_TYPE = "ClusterIP";

    public static KubernetesConfig defaults() {
        return new KubernetesConfig(DEFAULT_NAMESPACE, DEFAULT_SERVICE_TYPE, "");
    }

    public KubernetesConfig withNamespace(String namespace) {
        return new KubernetesConfig(namespace, serviceType, storageClass);
    }

    public KubernetesConfig withServiceType(String serviceType) {
        return new KubernetesConfig(namespace, serviceType, storageClass);
    }

    public KubernetesConfig withStorageClass(String storageClass) {
        return new KubernetesConfig(namespace, serviceType, storageClass);
    }

    public boolean hasStorageClass() {
        return storageClass != null && !storageClass.isBlank();
    }
}
