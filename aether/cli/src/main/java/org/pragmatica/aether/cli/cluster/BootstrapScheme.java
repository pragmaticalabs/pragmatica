// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.SourceType;


/// The scheme bootstrap speaks to a node's management port (#2089).
///
/// `operations.tls.auto_generate` (default true) makes a node serve HTTPS, because the node's user-data carries the TLS material. A
/// DOCKER node is created by `docker run` with no such material and serves plain HTTP, so a bootstrap that polled `https://` got TLS
/// bytes into an HTTP listener (`path=/bad-request` in the node log) and never saw a healthy node, with the cluster up. The scheme is
/// therefore plain HTTP when every source is DOCKER, whatever the TLS setting says; any other mix keeps the configured scheme.
sealed interface BootstrapScheme {
    record unused() implements BootstrapScheme {}

    static String of(ClusterBootstrapConfig config) {
        return config.operations()
                     .tls()
                     .autoGenerate() && !allDocker(config)
               ? "https"
               : "http";
    }

    private static boolean allDocker(ClusterBootstrapConfig config) {
        return ! config.sources()
                       .isEmpty() && config.sources()
                                           .values()
                                           .stream()
                                           .allMatch(source -> source.type() == SourceType.DOCKER);
    }
}
