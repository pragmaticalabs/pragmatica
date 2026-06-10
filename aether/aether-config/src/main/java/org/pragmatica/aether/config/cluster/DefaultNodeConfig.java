// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.StreamOps;


public interface DefaultNodeConfig {
    String GLOBAL_DEFAULT_RESOURCE = "defaults/aether-default.toml";

    static Result<TomlDocument> globalDefault() {
        return loadResource(GLOBAL_DEFAULT_RESOURCE);
    }

    static Result<TomlDocument> sourceTypeDefault(SourceType type) {
        return loadResource("defaults/aether-" + type.value() + ".toml");
    }

    private static Result<TomlDocument> loadResource(String path) {
        return StreamOps.readResource(DefaultNodeConfig.class.getClassLoader(),
                                      path)
                        .flatMap(TomlParser::parse);
    }
}
