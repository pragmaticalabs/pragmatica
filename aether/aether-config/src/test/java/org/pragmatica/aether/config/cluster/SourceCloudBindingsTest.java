package org.pragmatica.aether.config.cluster;

import org.pragmatica.config.toml.TomlDocument;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;
import static org.assertj.core.api.Assertions.assertThat;


class SourceCloudBindingsTest {
    @Test
    void replacement_selectsTargetAccountAndResourcesInsteadOfLeadersAccount() {
        var protectedConfig = TomlDocument.EMPTY.with("cloud.credentials", "api_token", "leader-account")
                                                .with("cloud.sources.east", "provider", "hetzner")
                                                .with("cloud.sources.east.credentials", "api_token", "east-account")
                                                .with("cloud.sources.east.compute", "ssh_key_ids", "11")
                                                .with("cloud.sources.west", "provider", "hetzner")
                                                .with("cloud.sources.west.credentials", "api_token", "west-account")
                                                .with("cloud.sources.west.compute", "ssh_key_ids", "22");
        var composed = TomlDocument.EMPTY.with("cloud.credentials", "api_token", "${env:WEST_TOKEN}").with("cloud.compute",
                                                                                                           "server_type",
                                                                                                           "large");
        var result = SourceCloudBindings.resolveOverlay(composed,
                                                        protectedConfig,
                                                        sourceNameOrDefault("west"),
                                                        NodeRole.CORE).unwrap();

        assertThat(result.getString("cloud.credentials", "api_token").or("")).isEqualTo("west-account");
        assertThat(result.getString("cloud.compute", "ssh_key_ids").or("")).isEqualTo("22");
        assertThat(result.getString("cloud.compute", "server_type").or("")).isEqualTo("large");
        assertThat(result.getString("cloud.sources.east.credentials", "api_token").or("")).isEqualTo("east-account");
    }

    @Test
    void missingSourceBinding_doesNotBorrowLeaderCredentials() {
        var leader = TomlDocument.EMPTY.with("cloud.credentials", "api_token", "leader-account");

        assertThat(SourceCloudBindings.resolveOverlay(TomlDocument.EMPTY,
                                                      leader,
                                                      sourceNameOrDefault("west"),
                                                      NodeRole.CORE).isFailure()).isTrue();
    }

    @Test
    void worker_receivesOnlyItsSelectedAccount() {
        var protectedConfig = TomlDocument.EMPTY.with("cloud.sources.east", "provider", "hetzner")
                                                .with("cloud.sources.east.credentials", "api_token", "east-account")
                                                .with("cloud.sources.west", "provider", "hetzner")
                                                .with("cloud.sources.west.credentials", "api_token", "west-account");
        var result = SourceCloudBindings.resolveOverlay(TomlDocument.EMPTY,
                                                        protectedConfig,
                                                        sourceNameOrDefault("west"),
                                                        NodeRole.WORKER).unwrap();

        assertThat(result.getString("cloud.credentials", "api_token").or("")).isEqualTo("west-account");
        assertThat(result.sections().keySet()).noneMatch(section -> section.startsWith(SourceCloudBindings.PREFIX));
    }
}
