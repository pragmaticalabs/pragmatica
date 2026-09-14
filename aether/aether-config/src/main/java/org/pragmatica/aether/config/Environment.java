// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


/// Per-environment defaults. `defaultNodes` is the CONSENSUS tier size (the quorum basis), bounded by
/// [ConfigValidator]'s minimum of 5 and maximum of 9.
///
/// Owner ruling 2026-09-12 sets the production default at 7: a 3-node cluster tolerates ZERO failures
/// during maintenance (a rolling restart leaves 2 of 3, and any further fault loses quorum), 5 is the
/// smallest size where a planned operation still leaves margin, and 7 buys a second concurrent fault
/// during maintenance. DOCKER and KUBERNETES are deployment targets and take that default.
///
/// LOCAL is 5 rather than 7 ON PURPOSE and must not be "corrected" to match the others: it exists for
/// developer ergonomics, where availability is not a goal and seven JVMs on a laptop is a real cost.
/// 5 is the smallest value that still satisfies the supported minimum. `StartupConfig#DEFAULT_CLUSTER_SIZE`
/// and `EmberConfig#DEFAULT_NODES` track THIS value, not the production default, for the same reason.
public enum Environment {
    LOCAL("local", 5, "256m", false),
    DOCKER("docker", 7, "512m", true),
    KUBERNETES("kubernetes", 7, "1g", true);
    private static final Fn1<Cause, String> UNKNOWN_ENVIRONMENT = Causes.forOneValue("Unknown environment: %s. Valid: local, docker, kubernetes");
    private final String name;
    private final int defaultNodes;
    private final String defaultHeap;
    private final boolean defaultTls;
    Environment(String name, int defaultNodes, String defaultHeap, boolean defaultTls) {
        this.name = name;
        this.defaultNodes = defaultNodes;
        this.defaultHeap = defaultHeap;
        this.defaultTls = defaultTls;
    }
    public String displayName() {
        return name;
    }
    public int defaultNodes() {
        return defaultNodes;
    }
    public String defaultHeap() {
        return defaultHeap;
    }
    public boolean defaultTls() {
        return defaultTls;
    }
    public static Result<Environment> environment(String value) {
        return option(value).map(String::trim)
                     .filter(s -> !s.isEmpty())
                     .fold(() -> success(DOCKER),
                           Environment::fromNormalized);
    }
    private static Result<Environment> fromNormalized(String value) {
        return switch (value.toLowerCase()) {
            case "local" -> success(LOCAL);
            case "docker" -> success(DOCKER);
            case "kubernetes", "k8s" -> success(KUBERNETES);
            default -> UNKNOWN_ENVIRONMENT.apply(value).result();
        };
    }
}
