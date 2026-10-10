// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// #909: a sibling `aether.toml` that exists and does not load must refuse the Forge start. It used to be dropped through `Result::option`, so
/// Forge ran with security NONE for a user who had asked for `jwt`.
class ForgeAppConfigTest {
    private static final String VALID = "[cluster]\nenvironment = \"docker\"\nnodes = 3\n\n[app-http]\nenabled = true\nsecurity_mode = \"api_key\"\n";
    private static final String JWT_WITHOUT_JWKS = "[cluster]\nenvironment = \"docker\"\nnodes = 3\n\n[app-http]\nenabled = true\nsecurity_mode = \"jwt\"\n";

    private static Option<Path> forgeConfigIn(Path dir) {
        return Option.some(dir.resolve("forge.toml"));
    }

    @Test
    void noForgeConfig_orNoSiblingFile_isNoConfiguration_notAFailure(@TempDir Path dir) {
        assertThat(ForgeAppConfig.load(Option.none()).map(Option::isEmpty).or(false)).isTrue();
        assertThat(ForgeAppConfig.load(forgeConfigIn(dir)).map(Option::isEmpty).or(false)).as("no aether.toml next to the forge config").isTrue();
    }

    @Test
    void aValidSibling_loads(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("aether.toml"), VALID);

        assertThat(ForgeAppConfig.load(forgeConfigIn(dir)).map(Option::isPresent).or(false)).isTrue();
    }

    @Test
    void aSiblingThatFailsValidation_isARefusal_namingTheFileAndTheCause(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("aether.toml"), JWT_WITHOUT_JWKS);

        var result = ForgeAppConfig.load(forgeConfigIn(dir));

        assertThat(result.isFailure()).as("fail closed: not dropped to NONE").isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("refusing to start").contains("aether.toml").contains("jwks_url"));
    }

    @Test
    void aSiblingThatDoesNotParse_isARefusal(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("aether.toml"), "[cluster\nnodes = \n");

        assertThat(ForgeAppConfig.load(forgeConfigIn(dir)).isFailure()).isTrue();
    }

    /// The refusal is wired: Forge exits with 1 on an unloadable sibling, and does not on a valid or absent one.
    @Test
    void loadOrRefuse_exitsWithOne_onlyForAnUnloadableSibling(@TempDir Path dir) throws Exception {
        var exits = new java.util.ArrayList<Integer>();

        assertThat(ForgeAppConfig.loadOrRefuse(forgeConfigIn(dir), exits::add).isEmpty()).isTrue();
        Files.writeString(dir.resolve("aether.toml"), VALID);
        assertThat(ForgeAppConfig.loadOrRefuse(forgeConfigIn(dir), exits::add).isPresent()).isTrue();
        assertThat(exits).as("CONTROL: absent and valid siblings never exit").isEmpty();

        Files.writeString(dir.resolve("aether.toml"), JWT_WITHOUT_JWKS);
        ForgeAppConfig.loadOrRefuse(forgeConfigIn(dir), exits::add);

        assertThat(exits).containsExactly(1);
    }
}
