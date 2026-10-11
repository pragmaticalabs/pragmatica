// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


public record KubernetesConfig(String namespace, String serviceType, String storageClass) {
    public static final String DEFAULT_NAMESPACE = "aether";
    public static final String DEFAULT_SERVICE_TYPE = "ClusterIP";

    public static Result<KubernetesConfig> kubernetesConfig(String namespace, String serviceType, String storageClass) {
        return success(new KubernetesConfig(namespace, serviceType, storageClass));
    }

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
