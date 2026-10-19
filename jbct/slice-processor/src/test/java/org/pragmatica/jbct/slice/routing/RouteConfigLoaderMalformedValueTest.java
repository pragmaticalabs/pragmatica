// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.jbct.slice.routing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #1455: `[errors] default = "500x"` used to read as absent and take 500; the load refuses it by name.
class RouteConfigLoaderMalformedValueTest {
    @TempDir
    Path tempDir;

    private Path routes(String errorsDefault) throws IOException {
        return Files.writeString(tempDir.resolve("routes.toml"), """
            prefix = "/api/v1"

            [security]
            default = "authenticated"
            override_policy = "strengthen_only"

            [routes]
            getUser = "GET /{id}"

            [errors]
            default = %s
            """.formatted(errorsDefault));
    }

    @Test
    void load_malformedErrorsDefault_refusesNamingKeyAndValue() throws IOException {
        RouteConfigLoader.load(routes("\"500x\""))
                         .onSuccess(config -> fail("errors.default = \"500x\" must refuse the load, loaded " + config))
                         .onFailure(cause -> assertThat(cause.message()).contains("errors.default")
                                                                        .contains("500x"));
    }

    @Test
    void load_wellFormedErrorsDefault_loads() throws IOException {
        assertThat(RouteConfigLoader.load(routes("503")).isSuccess()).isTrue();
    }
}
