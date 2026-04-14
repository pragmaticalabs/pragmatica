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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;


/// Verifies parser support for `[source.X.node_config]` and template inheritance
/// of node_config descendants.
class NodeConfigParseInheritTest {

    private static final String BASE = """
            config_version = "1.0.0"

            [cluster]
            name = "test"
            version = "1.0.0"

            """;

    @Test
    void parser_extractsInlineNodeConfigSubtree() {
        var toml = BASE + """
                [source.dev]
                type = "docker"

                [source.dev.node_config.app-http]
                timeout = "30s"

                [source.dev.node_config.logging]
                level = "DEBUG"

                [source.dev.core]
                count = 1
                """;

        var profile = parseSource(toml, "dev");
        var nodeConfig = profile.nodeConfig().unwrap();

        assertThat(nodeConfig.getString("app-http", "timeout").unwrap()).isEqualTo("30s");
        assertThat(nodeConfig.getString("logging", "level").unwrap()).isEqualTo("DEBUG");
        // Existing source fields remain unaffected
        assertThat(profile.type()).isEqualTo(SourceType.DOCKER);
    }

    @Test
    void parser_returnsEmptyWhenNoNodeConfig() {
        var toml = BASE + """
                [source.dev]
                type = "docker"

                [source.dev.core]
                count = 1
                """;

        var profile = parseSource(toml, "dev");

        assertThat(profile.nodeConfig().isEmpty()).isTrue();
    }

    @Test
    void inheritance_propagatesTemplateNodeConfigDescendants() {
        var toml = BASE + """
                [template.prod-defaults]

                [template.prod-defaults.node_config.app-http]
                timeout = "60s"

                [template.prod-defaults.node_config.logging]
                level = "WARN"

                [source.eu-1]
                type = "cloud"
                inherit = "prod-defaults"

                [source.eu-1.core]
                count = 3
                """;

        var profile = parseSource(toml, "eu-1");
        var nodeConfig = profile.nodeConfig().unwrap();

        assertThat(nodeConfig.getString("app-http", "timeout").unwrap()).isEqualTo("60s");
        assertThat(nodeConfig.getString("logging", "level").unwrap()).isEqualTo("WARN");
    }

    @Test
    void inheritance_sourceOverridesTemplateAtDescendantLevel() {
        var toml = BASE + """
                [template.prod-defaults]

                [template.prod-defaults.node_config.logging]
                level = "WARN"

                [source.eu-1]
                type = "cloud"
                inherit = "prod-defaults"

                [source.eu-1.node_config.logging]
                level = "INFO"

                [source.eu-1.core]
                count = 3
                """;

        var profile = parseSource(toml, "eu-1");
        var nodeConfig = profile.nodeConfig().unwrap();

        assertThat(nodeConfig.getString("logging", "level").unwrap()).isEqualTo("INFO");
    }

    @Test
    void inheritance_chainedTemplatesPropagateDescendants() {
        var toml = BASE + """
                [template.base]

                [template.base.node_config.cluster]
                tls = true

                [template.prod]
                inherit = "base"

                [template.prod.node_config.app-http]
                timeout = "60s"

                [source.eu-1]
                type = "cloud"
                inherit = "prod"

                [source.eu-1.core]
                count = 3
                """;

        var profile = parseSource(toml, "eu-1");
        var nodeConfig = profile.nodeConfig().unwrap();

        assertThat(nodeConfig.getBoolean("cluster", "tls").unwrap()).isTrue();
        assertThat(nodeConfig.getString("app-http", "timeout").unwrap()).isEqualTo("60s");
    }

    @Test
    void inheritance_doesNotPropagateNonNodeConfigDescendants() {
        // Sanity: only sections that match the inherited template's prefix get propagated.
        // A template that defines `template.X.foo.bar` should propagate to `source.Y.foo.bar`,
        // but never invent new top-level fields outside the inheritance contract.
        var toml = BASE + """
                [template.prod]

                [template.prod.node_config.app-http]
                timeout = "60s"

                [source.eu-1]
                type = "cloud"
                inherit = "prod"

                [source.eu-1.core]
                count = 3
                """;

        var profile = parseSource(toml, "eu-1");

        assertThat(profile.nodeConfig().unwrap().hasSection("app-http")).isTrue();
        // Source-level fields not present in template stay default
        assertThat(profile.region().isEmpty()).isTrue();
    }

    private static SourceProfile parseSource(String toml, String name) {
        var config = ClusterBootstrapConfigParser.parse(toml)
                                                  .onFailure(cause -> fail("parse failed: " + cause.message()))
                                                  .unwrap();
        return config.sources().get(name);
    }
}
