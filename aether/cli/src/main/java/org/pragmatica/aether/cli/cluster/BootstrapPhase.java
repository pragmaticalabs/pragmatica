// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

public enum BootstrapPhase {
    VALIDATE,
    UPLOAD_SSH_KEYS,
    CREATE_FIREWALL,
    PROVISION,
    COLLECT_ADDRESSES,
    DEPLOY_RUNTIME,
    CLUSTER_FORMATION,
    POST_BOOTSTRAP
}
