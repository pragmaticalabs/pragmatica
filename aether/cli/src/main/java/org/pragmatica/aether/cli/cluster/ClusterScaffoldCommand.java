// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.concurrent.Callable;

import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.config.cluster.ClusterIdentity;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;


/// Emits deployment-manifest templates with `aether.cluster` and `aether.node-id` labels set
/// correctly. Operators get a working starting point that's correct-by-construction —
/// removes the "forgot to set the cluster label" class of misconfiguration that leaves
/// CTM container reaping unable to distinguish two clusters sharing infrastructure.
///
/// Currently supports docker-compose. Kubernetes / Hetzner Terraform are tracked as
/// follow-ups in `aether/docs/specs/cluster-label-scoping-spec.md` item 6.
@Command(name = "scaffold", description = "Emit a deployment-manifest template with cluster-scoped labels pre-set")
@SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01"})
class ClusterScaffoldCommand implements Callable<Integer> {
    @Option(names = "--name", required = true, description = "Cluster name (regex: ^[a-z]([a-z0-9-]{0,61}[a-z0-9])?$)")
    private String name;

    @Option(names = "--template", required = true, description = "Output template: docker-compose")
    private String format;

    @Option(names = "--nodes", defaultValue = "5", description = "Number of compose-fixed nodes (default 5)")
    private int nodes;

    @Option(names = "--image", defaultValue = "aether-node:local", description = "Container image to use (default aether-node:local)")
    private String image;

    @Option(names = "--mgmt-port-base", defaultValue = "5150", description = "Host port base for management API (default 5150)")
    private int mgmtPortBase;

    @Option(names = "--app-port-base", defaultValue = "8070", description = "Host port base for application HTTP (default 8070)")
    private int appPortBase;

    @Option(names = "--cluster-port", defaultValue = "6000", description = "QUIC cluster transport port (default 6000)")
    private int clusterPort;

    @CommandLine.ParentCommand
    private ClusterCommand parent;

    @Override
    public Integer call() {
        return validateName().flatMap(this::renderValidated)
                           .fold(ClusterScaffoldCommand::onFailure, ClusterScaffoldCommand::onSuccess);
    }

    private Result<ClusterName> validateName() {
        return ClusterIdentity.clusterIdentity(name, "1.0.0").map(ClusterIdentity::name);
    }

    private Result<String> renderValidated(ClusterName clusterName) {
        return validateFormat().flatMap(_ -> render(clusterName));
    }

    private Result<String> validateFormat() {
        return switch (format) {
            case "docker-compose" -> Result.success(format);
            default -> new ScaffoldError.UnsupportedFormat(format).result();
        };
    }

    private Result<String> render(ClusterName clusterName) {
        if (nodes < 3) {
            return new ScaffoldError.InvalidNodeCount(nodes).result();
        }

        return Result.success(DockerComposeTemplate.render(clusterName,
                                                           nodes,
                                                           image,
                                                           mgmtPortBase,
                                                           appPortBase,
                                                           clusterPort));
    }

    private static int onSuccess(String rendered) {
        System.out.print(rendered);

        return ExitCode.SUCCESS;
    }

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());

        return ExitCode.ERROR;
    }

    sealed interface ScaffoldError extends Cause {
        record UnsupportedFormat(String format) implements ScaffoldError {
            @Override
            public String message() {
                return "Unsupported --template '" + format + "' (supported: docker-compose)";
            }
        }

        record InvalidNodeCount(int nodes) implements ScaffoldError {
            @Override
            public String message() {
                return "Invalid --nodes " + nodes + " (must be >= 3)";
            }
        }
    }
}
