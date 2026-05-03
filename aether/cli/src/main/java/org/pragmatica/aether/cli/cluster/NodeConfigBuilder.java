// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapContext;
import org.pragmatica.aether.config.cluster.DefaultNodeConfig;
import org.pragmatica.aether.config.cluster.NodeConfigComposer;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"}) sealed interface NodeConfigBuilder {
    record unused() implements NodeConfigBuilder{}

    static Result<TomlDocument> compose(BootstrapContext ctx,
                                        SourceProfile source,
                                        int nodeIndex,
                                        Option<String> dockerGid,
                                        Option<String> clusterSecret) {
        var overlay = BootstrapOverlayGenerator.overlay(ctx.config(),
                                                        source,
                                                        nodeIndex,
                                                        ctx.apiKey(),
                                                        dockerGid,
                                                        clusterSecret);
        return Result.all(DefaultNodeConfig.globalDefault(),
                          DefaultNodeConfig.sourceTypeDefault(source.type()))
        .map((global, typeDefault) -> NodeConfigComposer.compose(global,
                                                                 typeDefault,
                                                                 source.nodeConfig(),
                                                                 overlay));
    }
}
