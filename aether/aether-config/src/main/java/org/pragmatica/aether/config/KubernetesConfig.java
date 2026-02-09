package org.pragmatica.aether.config;
/// Kubernetes-specific configuration.
///
/// @param namespace    Kubernetes namespace
/// @param serviceType  Service type (ClusterIP, LoadBalancer, NodePort)
/// @param storageClass Storage class for persistent volumes (empty = default)
public record KubernetesConfig(String namespace,
                               String serviceType,
                               String storageClass) {
    public static final String DEFAULT_NAMESPACE = "aether";
    public static final String DEFAULT_SERVICE_TYPE = "ClusterIP";

    /// Factory method following JBCT naming convention.
    public static KubernetesConfig kubernetesConfig(String namespace, String serviceType, String storageClass) {
        return new KubernetesConfig(namespace, serviceType, storageClass);
    }

    public static KubernetesConfig defaultConfig() {
        return kubernetesConfig(DEFAULT_NAMESPACE, DEFAULT_SERVICE_TYPE, "");
    }

    public KubernetesConfig withNamespace(String namespace) {
        return kubernetesConfig(namespace, serviceType, storageClass);
    }

    public KubernetesConfig withServiceType(String serviceType) {
        return kubernetesConfig(namespace, serviceType, storageClass);
    }

    public KubernetesConfig withStorageClass(String storageClass) {
        return kubernetesConfig(namespace, serviceType, storageClass);
    }

    public boolean hasStorageClass() {
        return !storageClass.isBlank();
    }
}
