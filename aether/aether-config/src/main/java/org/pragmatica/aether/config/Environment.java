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


public enum Environment {
    LOCAL("local", 3, "256m", false),
    DOCKER("docker", 5, "512m", true),
    KUBERNETES("kubernetes", 5, "1g", true);
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
        return switch (value.toLowerCase()){
            case "local" -> success(LOCAL);
            case "docker" -> success(DOCKER);
            case "kubernetes", "k8s" -> success(KUBERNETES);
            default -> UNKNOWN_ENVIRONMENT.apply(value).result();
        };
    }
}
