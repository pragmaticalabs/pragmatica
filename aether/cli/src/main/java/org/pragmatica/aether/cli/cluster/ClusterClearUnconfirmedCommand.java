// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.List;
import java.util.LinkedHashMap;
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
/// The cluster refuses (nothing cleared) for a node that shows life, for a node with no mark, and, unless `--force`, while the provider still lists the
/// node's instance or cannot be asked. Exit codes: 0 cleared, 3 no such mark, 1 any other refusal or failure, 64 (`ExitCode.USAGE`, as `cluster bootstrap`
/// and `cluster destroy` use for their own argument checks) for a missing or blank `--instance`.
@Command(name = "clear-unconfirmed", description = "Clear the unconfirmed-termination warning of a retired node after you verified and terminated its instance by hand")
@SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01"})
class ClusterClearUnconfirmedCommand implements Callable<Integer> {
    private static final int HTTP_NOT_FOUND = 404;
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();
    /// The request seam (node id, JSON request body) -> response body; tests replace it, the default is the management route.
    static BiFunction<String, String, Result<String>> requester = ClusterClearUnconfirmedCommand::request;

    @Parameters(index = "0", description = "Retired node ID whose mark to clear")
    private String nodeId;

    @Option(names = "--instance", description = "The provider instance id you verified and terminated, or 'unknown'")
    private String instance;

    @Option(names = "--force", description = "Clear even if the provider still lists the instance or cannot be asked (recorded in the audit event as forced=true)")
    private boolean force;

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
                            .flatMap(_ -> body(instance.trim(),
                                               force))
                            .flatMap(body -> requester.apply(nodeId, body))
                            .fold(this::onFailure, this::onCleared);
    }

    /// The request body the route reads as `ClearRequest(instance, force)`; the keys are the route's contract.
    static Result<String> body(String instance, boolean force) {
        var fields = new LinkedHashMap<String, Object>();

        fields.put("instance", instance);
        fields.put("force", force);

        return MAPPER.writeAsString(fields);
    }

    private static Result<String> request(String node, String body) {
        return ClusterHttpClient.post(NODE_UNCONFIRMED_CLEAR, List.of(node), body);
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
