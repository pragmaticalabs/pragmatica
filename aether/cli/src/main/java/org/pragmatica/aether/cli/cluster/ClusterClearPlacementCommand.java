// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.LinkedHashMap;
import java.util.List;
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

import static org.pragmatica.aether.management.route.ManagementRoute.COMMUNITY_PLACEMENT_CLEAR;


/// #241: clears ONE parked community placement operation, by community id, after the operator looked at the nodes the `community-placement-parked`
/// warning names. The clear closes the operation and nothing else: the previous worker and the replacement are left as they are, and the next pass
/// recomputes the community from what is there. The cluster refuses (nothing cleared) for a community with no operation, for an operation that is
/// not parked, and on a node that is not the leader. Exit codes: 0 cleared, 3 no such operation, 1 any other refusal or failure, 64 (`ExitCode.USAGE`,
/// as `cluster clear-unconfirmed` uses for its own argument check) for a missing or blank `--reason`.
@Command(name = "clear-placement", description = "Clear a parked community placement operation after you checked the nodes it names")
@SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01"})
class ClusterClearPlacementCommand implements Callable<Integer> {
    private static final int HTTP_NOT_FOUND = 404;
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();
    /// The request seam (community id, JSON request body) -> response body; tests replace it, the default is the management route.
    static BiFunction<String, String, Result<String>> requester = ClusterClearPlacementCommand::request;

    @Parameters(index = "0", description = "Community ID whose parked placement operation to clear")
    private String community;

    @Option(names = "--reason", description = "What you checked on the nodes before clearing; recorded in the audit event")
    private String reason;

    @CommandLine.ParentCommand
    private ClusterCommand parent;

    @Mixin
    ClusterTargetMixin clusterTarget = new ClusterTargetMixin();

    @Override
    public Integer call() {
        if (reason == null || reason.isBlank()) {
            System.err.println("Error: --reason needs what you checked on the nodes before clearing");

            return ExitCode.USAGE;
        }

        return clusterTarget.applyOverrides()
                            .flatMap(_ -> body(reason.trim()))
                            .flatMap(body -> requester.apply(community, body))
                            .fold(this::onFailure, this::onCleared);
    }

    /// The request body the route reads as `ClearRequest(reason)`; the key is the route's contract.
    static Result<String> body(String reason) {
        var fields = new LinkedHashMap<String, Object>();

        fields.put("reason", reason);

        return MAPPER.writeAsString(fields);
    }

    private static Result<String> request(String community, String body) {
        return ClusterHttpClient.post(COMMUNITY_PLACEMENT_CLEAR, List.of(community), body);
    }

    private int onCleared(String body) {
        System.out.printf("Cleared the parked placement operation of community %s (reason: %s)%n", community, reason.trim());

        return ExitCode.SUCCESS;
    }

    private int onFailure(Cause cause) {
        var notFound = cause instanceof ClusterHttpClient.HttpError.ApiError api && api.statusCode() == HTTP_NOT_FOUND;

        return notFound
               ? OutputFormatter.printNotFound(cause.message(), parent.outputOptions())
               : OutputFormatter.printError(cause, parent.outputOptions());
    }
}
