// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.BiFunction;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import static org.pragmatica.aether.management.route.ManagementRoute.NODE_UNCONFIRMED_CLEAR;


/// #2062: clears ONE unconfirmed-termination mark, by node id, after the operator verified at the provider and terminated the instance by hand.
/// The cluster refuses (nothing cleared) for a node that shows life and for a node with no mark. Exit codes: 0 cleared, 3 no such mark, 1 any other
/// refusal or failure, 64 missing/blank `--instance`.
@Command(name = "clear-unconfirmed", description = "Clear the unconfirmed-termination warning of a retired node after you verified and terminated its instance by hand")
@SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01"})
class ClusterClearUnconfirmedCommand implements Callable<Integer> {
    private static final int HTTP_NOT_FOUND = 404;
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();
    /// The request seam (node id, instance) -> response body; tests replace it, the default is the management route.
    static BiFunction<String, String, Result<String>> requester = ClusterClearUnconfirmedCommand::request;

    @Parameters(index = "0", description = "Retired node ID whose mark to clear")
    private String nodeId;

    @Option(names = "--instance", required = true, description = "The provider instance id you verified and terminated, or 'unknown'")
    private String instance;

    @CommandLine.ParentCommand
    private ClusterCommand parent;

    @Mixin
    ClusterTargetMixin clusterTarget = new ClusterTargetMixin();

    @Override
    public Integer call() {
        if (instance == null || instance.isBlank()) {
            System.err.println("Error: --instance needs the provider instance id you terminated, or 'unknown'");

            return ExitCode.USAGE;
        }

        return clusterTarget.applyOverrides()
                            .flatMap(_ -> requester.apply(nodeId,
                                                          instance.trim()))
                            .fold(this::onFailure, this::onCleared);
    }

    private static Result<String> request(String node, String instance) {
        return MAPPER.writeAsString(Map.of("instance", instance)).flatMap(body -> ClusterHttpClient.post(NODE_UNCONFIRMED_CLEAR,
                                                                                                         List.of(node),
                                                                                                         body));
    }

    private int onCleared(String body) {
        System.out.printf("Cleared the unconfirmed-termination mark of node %s (instance attested: %s)%n",
                          nodeId,
                          instance.trim());

        return ExitCode.SUCCESS;
    }

    private int onFailure(Cause cause) {
        var notFound = cause instanceof ClusterHttpClient.HttpError.ApiError api && api.statusCode() == HTTP_NOT_FOUND;

        return notFound
               ? OutputFormatter.printNotFound(cause.message(), parent.outputOptions())
               : OutputFormatter.printError(cause, parent.outputOptions());
    }
}
