// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/// The CLI keeps its state under `<user.home>/.aether`, resolved once at class load. A test that
/// resolves that to the real home writes into the developer's (or CI worker's) own ledgers and registry:
/// a verifier's probe wrote `~/.aether/clusters/x/apply-state.json`, and the real `~/.aether/clusters`
/// already holds hundreds of leftover test directories. The module's surefire run therefore forks with
/// `-Duser.home=target/test-home`; this test fails if any state location the CLI resolves is the real one.
class CliTestHomeIsolationTest {
    private static Path realHome() {
        return Path.of(System.getenv("HOME")).toAbsolutePath().normalize();
    }

    private static Path normalized(Path path) {
        return path.toAbsolutePath().normalize();
    }

    @Test
    void userHome_isNotTheRealHome() {
        assertThat(normalized(Path.of(System.getProperty("user.home")))).isNotEqualTo(realHome());
        assertThat(normalized(Path.of(System.getProperty("user.home"))).startsWith(realHome().resolve(".aether"))).isFalse();
    }

    @Test
    void everyResolvedStateLocation_isUnderTheTestHome() throws Exception {
        var testHome = normalized(Path.of(System.getProperty("user.home")));

        assertThat(normalized(BootstrapStatePersistence.AETHER_DIR).startsWith(testHome)).isTrue();
        assertThat(normalized(staticPath(ApplyState.class, "AETHER_DIR")).startsWith(testHome)).isTrue();
        assertThat(normalized(staticPath(ClusterRegistry.class, "DEFAULT_REGISTRY_PATH")).startsWith(testHome)).isTrue();
    }

    @Test
    void testHome_isUnderTheBuildDirectory() {
        assertThat(normalized(Path.of(System.getProperty("user.home"))).toString()).contains("target");
    }

    private static Path staticPath(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);

        field.setAccessible(true);

        return (Path) field.get(null);
    }
}
