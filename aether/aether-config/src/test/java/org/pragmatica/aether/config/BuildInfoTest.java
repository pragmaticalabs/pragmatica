// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


class BuildInfoTest {
    @Test void current_returnsSentinelsWhenRunningFromTestClasspath() {
        // From a test classpath, BuildInfo.class loads from `target/classes` (a directory,
        // not a jar). The loader's File.isFile() check returns false → fallback path fires.
        // This is the expected, by-design behaviour: the production wiring (jar manifest)
        // only kicks in when running from a packaged jar.
        var info = BuildInfo.current();
        assertThat(info.version()).isEqualTo("dev");
        assertThat(info.buildDate()).isEqualTo("unknown");
        assertThat(info.displayString()).isEqualTo("dev (built unknown)");
    }

    @Test void staticAccessors_matchInstanceFields() {
        assertThat(BuildInfo.version()).isEqualTo(BuildInfo.current().version());
        assertThat(BuildInfo.buildDate()).isEqualTo(BuildInfo.current().buildDate());
    }
}
