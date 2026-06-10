// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.pragmatica.lang.io.StreamOps;

import java.util.Properties;


public record BuildInfo(String buildTimestamp, String buildVersion) {
    private static final BuildInfo INSTANCE = loadBuildInfo();

    public static BuildInfo buildInfo() {
        return INSTANCE;
    }

    private static BuildInfo loadBuildInfo() {
        var props = new Properties();

        StreamOps.openResource(BuildInfo.class.getClassLoader(), "build-info.properties").onSuccess(is -> {
            try {
                props.load(is);
            } catch (Exception ignored) {}
        });

        return new BuildInfo(props.getProperty("build.timestamp", "unknown"),
                             props.getProperty("build.version", "unknown"));
    }
}
