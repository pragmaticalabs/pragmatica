// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.management;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.terra.management.ManagementEndpoint.Reply;


/// Spring Boot's `info` endpoint (#3651): `build` from `META-INF/build-info.properties` (the file Spring Boot's
/// `build-info` goal writes; its `build.*` keys without the prefix) and the `info.*` settings when
/// `management.info.env.enabled` is true. Not sensitive.
final class InfoEndpoint {
    private static final String BUILD_INFO = "META-INF/build-info.properties";

    private InfoEndpoint() {}

    static ManagementEndpoint infoEndpoint(Map<String, Object> build, Map<String, Object> info) {
        var body = new LinkedHashMap<String, Object>(info);

        if (!build.isEmpty()) {
            body.put("build", build);
        }

        var answer = Map.copyOf(body);

        return ManagementEndpoint.managementEndpoint("info",
                                                     false,
                                                     Option.empty(),
                                                     call -> Promise.success(call.selectors().isEmpty()
                                                                             ? Reply.json(200, answer)
                                                                             : Reply.empty(404)));
    }

    /// The `build.*` entries of `META-INF/build-info.properties` on `loader`, without the prefix; empty when absent.
    static Result<Map<String, Object>> buildInfo(ClassLoader loader) {
        return Result.lift(Causes::fromThrowable, () -> readBuildInfo(loader));
    }

    @Contract
    private static Map<String, Object> readBuildInfo(ClassLoader loader) throws IOException {
        var build = new TreeMap<String, Object>();

        try (var input = loader.getResourceAsStream(BUILD_INFO)) {
            if (input == null) {
                return build;
            }

            var properties = new Properties();

            properties.load(input);
            properties.stringPropertyNames()
                      .stream()
                      .filter(key -> key.startsWith("build."))
                      .forEach(key -> build.put(key.substring(6), properties.getProperty(key)));
        }

        return build;
    }
}
