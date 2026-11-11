// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.concurrent.Callable;
import java.util.stream.StreamSupport;

import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import tools.jackson.databind.JsonNode;

import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_NODE_IDS_RESERVE;


/// Reserve `--count` node ids `<source>-<role>-<n>` for one (source, role) (#1715). The core leader advances the committed per-(source, role)
/// high-water and answers only once the advance is committed, so ids it returns are never issued again, even if the caller dies before it
/// provisions anything. The server validates source, role and count; this command only spells the request.
@Command(name = "reserve", description = "Reserve node ids <source>-<role>-<n> for a source and role")
class ClusterNodeIdsReserveCommand implements Callable<Integer> {
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();
    private static final String DEFAULT_ROLE = "core";

    @Option(names = "--source", required = true, description = "Source name")
    private String sourceName;

    @Option(names = "--role", description = "Node role: core, worker or spot (default: core)")
    private String roleName = DEFAULT_ROLE;

    @Option(names = "--count", required = true, description = "Number of ids to reserve")
    private int count;

    @CommandLine.ParentCommand
    private ClusterNodeIdsCommand parent;

    @Mixin
    ClusterTargetMixin clusterTarget = new ClusterTargetMixin();

    @Contract
    @Override
    public Integer call() {
        return clusterTarget.applyOverrides()
                            .flatMap(_ -> ClusterHttpClient.post(CLUSTER_NODE_IDS_RESERVE,
                                                                 buildReserveJson(sourceName, roleName, count)))
                            .fold(this::onFailure, this::onSuccess);
    }

    /// Field names MUST match `ManagementApiResponses.ReserveNodeIdsRequest`; the CLI cannot depend on `aether/node`, so
    /// `ClusterNodeIdsReserveCommandTest` and `NodeIdRoutesTest` pin the two spellings.
    static String buildReserveJson(String source, String role, int count) {
        return "{\"source\":" + MAPPER.writeAsString(source)
                                      .or("\"\"")
             + ",\"role\":" + MAPPER.writeAsString(role)
                                    .or("\"\"")
             + ",\"count\":" + count
             + "}";
    }

    static String successMessage(String json) {
        return MAPPER.readTree(json)
                     .map(ClusterNodeIdsReserveCommand::idsLine)
                     .or("Node ids reserved.");
    }

    private static String idsLine(JsonNode response) {
        return "Reserved: " + String.join(", ",
                                          StreamSupport.stream(response.path("ids")
                                                                       .spliterator(),
                                                               false)
                                                       .map(JsonNode::asText)
                                                       .toList());
    }

    private int onSuccess(String json) {
        return OutputFormatter.printAction(json, parent.outputOptions(), successMessage(json));
    }

    private int onFailure(Cause cause) {
        return OutputFormatter.printError(cause, parent.outputOptions());
    }
}
