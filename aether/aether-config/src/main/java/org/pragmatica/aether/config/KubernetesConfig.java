package org.pragmatica.aether.config;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;

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
    public static Result<KubernetesConfig> kubernetesConfig(String namespace, String serviceType, String storageClass) {
        return success(new KubernetesConfig(namespace, serviceType, storageClass));
    }

    /// Default Kubernetes configuration.
    public static KubernetesConfig kubernetesConfig() {
        return kubernetesConfig(DEFAULT_NAMESPACE, DEFAULT_SERVICE_TYPE, "").unwrap();
    }

    public KubernetesConfig withNamespace(String namespace) {
        return kubernetesConfig(namespace, serviceType, storageClass).unwrap();
    }

    public KubernetesConfig withServiceType(String serviceType) {
        return kubernetesConfig(namespace, serviceType, storageClass).unwrap();
    }

    public KubernetesConfig withStorageClass(String storageClass) {
        return kubernetesConfig(namespace, serviceType, storageClass).unwrap();
    }

    public boolean hasStorageClass() {
        return ! storageClass.isBlank();
    }
}
