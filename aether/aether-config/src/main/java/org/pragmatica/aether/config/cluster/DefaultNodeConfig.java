/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package org.pragmatica.aether.config.cluster;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;


/// Loader for shipped node config defaults (Layers 1 and 2) from the classpath.
///
/// Layer 1 ("global default") is `defaults/aether-default.toml` — applies to all node sources.
/// Layer 2 ("per-source-type default") is `defaults/aether-{type}.toml` — overlays Layer 1 with
/// settings specific to each [SourceType].
///
/// Both files are shipped as classpath resources inside this module's JAR. A missing default
/// file is a packaging error, not a user error — `defaultLoadFailure` is returned in that case.
public interface DefaultNodeConfig {
    String GLOBAL_DEFAULT_RESOURCE = "defaults/aether-default.toml";

    static Result<TomlDocument> globalDefault() {
        return loadResource(GLOBAL_DEFAULT_RESOURCE);
    }

    static Result<TomlDocument> sourceTypeDefault(SourceType type) {
        return loadResource("defaults/aether-" + type.value() + ".toml");
    }

    private static Result<TomlDocument> loadResource(String path) {
        return readResource(path).flatMap(TomlParser::parse);
    }

    private static Result<String> readResource(String path) {
        try (var in = DefaultNodeConfig.class.getClassLoader().getResourceAsStream(path)) {
            return Option.option(in).toResult(missingDefault(path))
                                .flatMap(stream -> readAll(stream, path));
        } catch (IOException e) {
            return readFailure(path, e).result();
        }
    }

    private static Result<String> readAll(InputStream in, String path) {
        try {
            return Result.success(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return readFailure(path, e).result();
        }
    }

    private static Cause missingDefault(String path) {
        return Causes.cause("Missing default node config resource: " + path);
    }

    private static Cause readFailure(String path, IOException e) {
        return Causes.cause("Failed to read default node config '" + path + "': " + e.getMessage());
    }
}
