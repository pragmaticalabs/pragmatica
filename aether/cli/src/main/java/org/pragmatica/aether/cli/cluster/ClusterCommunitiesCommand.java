// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.List;
import java.util.concurrent.Callable;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;

import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_COMMUNITIES;
import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_COMMUNITY_GET;
import static org.pragmatica.lang.Option.option;


/// #1652: `aether cluster communities [<id>]` — every community, or one, with its lifecycle state,
/// target size, roster and the leader's live-member count.
@Command(name = "communities", description = "Show worker communities: state (FORMING/ACTIVE/DEGRADED/DISSOLVED), target size, members and live members")
@SuppressWarnings("JBCT-RET-01")
class ClusterCommunitiesCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private ClusterCommand parent;

    @Mixin
    ClusterTargetMixin clusterTarget = new ClusterTargetMixin();

    @Parameters(index = "0", arity = "0..1", description = "Community id (omit to list every community)")
    private String communityId;

    @Override
    public Integer call() {
        return clusterTarget.applyOverrides()
                            .flatMap(_ -> fetch(option(communityId)))
                            .fold(ClusterCommunitiesCommand::onFailure, this::onSuccess);
    }

    private static Result<String> fetch(Option<String> communityId) {
        return communityId.map(id -> ClusterHttpClient.fetch(CLUSTER_COMMUNITY_GET,
                                                             List.of(id)))
                          .or(() -> ClusterHttpClient.fetch(CLUSTER_COMMUNITIES));
    }

    private int onSuccess(String json) {
        return OutputFormatter.printQuery(json, parent.outputOptions());
    }

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());

        return ExitCode.ERROR;
    }
}
