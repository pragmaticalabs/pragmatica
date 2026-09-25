// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.AetherConfig;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.ConfigLoader;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.DefaultNodeConfig;
import org.pragmatica.aether.config.cluster.NodeConfigComposer;
import org.pragmatica.aether.config.cluster.NodeUserDataRenderer;
import org.pragmatica.aether.config.cluster.ReplacementNodeConfigComposer;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlWriter;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.net.tcp.TlsConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// #1519 — every shipped node config must pass #1390's durable-control gate
/// ([AetherNode#consensusDirectory]), which refuses to boot without an absolute `cluster.consensus_path`
/// or an explicit artifacts storage path. Before #1519 none of them did, and 5 of 5 cloud nodes aborted.
///
/// Each template is loaded the way `Main` loads `--config=`: a TOML-file [ConfigurationProvider] for
/// `cluster.consensus_path`, and `ConfigLoader`'s `[storage]` map (empty when absent or unloadable) for the
/// artifacts fallback. System properties and `AETHER_*` env are left out so the result does not depend on
/// the machine running the test.
///
/// Every case carries its own control: the SAME template with its `consensus_path` line removed must be
/// refused with #1390's message. That proves the line is what satisfies the gate — not an artifacts
/// section the loader synthesized — and that the #1390 requirement is still in force.
class ShippedTemplateControlStorageTest {
    private static final String REQUIRED = "Durable control storage requires cluster.consensus_path";
    private static final Pattern CONSENSUS_PATH_LINE = Pattern.compile("(?m)^\\s*consensus_path\\s*=.*$");
    private static final String CLOUD_BOOTSTRAP = """
            config_version = "1.0.0"

            [cluster]
            name = "prod-cluster"
            version = "1.0.0"

            [source.eu-1]
            type = "cloud"
            provider = "hetzner"
            region = "eu-central"

            [source.eu-1.core]
            count = 5
            """;

    @TempDir
    Path tempDir;

    record Template(String name, String toml, String expectedPath) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Template> shippedTemplates() {
        return Stream.of(new Template("image aether/docker/aether-node/aether.toml",
                                      read(Path.of("..", "docker", "aether-node", "aether.toml")),
                                      "/data/aether-control"),
                         new Template("scaling-test aether/docker/scaling-test/aether.toml",
                                      read(Path.of("..", "docker", "scaling-test", "aether.toml")),
                                      "/data/aether-control"),
                         new Template("cloud source composed config (bootstrap + CTM auto-heal)",
                                      TomlWriter.toToml(composedCloud()),
                                      NodeUserDataRenderer.CONSENSUS_PATH),
                         new Template("ssh source composed config",
                                      TomlWriter.toToml(composedSourceDefaults(SourceType.SSH)),
                                      NodeUserDataRenderer.CONSENSUS_PATH));
    }

    @TestFactory
    Stream<DynamicTest> consensusDirectory_resolvesAbsolutePersistentPath_forEveryShippedTemplate() {
        return shippedTemplates().map(template -> DynamicTest.dynamicTest(template.name(),
                                                                          () -> assertResolves(template)));
    }

    @TestFactory
    Stream<DynamicTest> consensusDirectory_refusesBoot_whenTheTemplatesConsensusPathLineIsRemoved() {
        return shippedTemplates().map(template -> DynamicTest.dynamicTest(template.name(),
                                                                          () -> assertRefusedWithoutLine(template)));
    }

    private void assertResolves(Template template) {
        AetherNode.consensusDirectory(nodeConfigFrom(write(template.toml())))
                  .onFailure(cause -> fail(template.name() + " is refused by the #1390 gate: " + cause.message()))
                  .onSuccess(path -> assertThat(path).as(template.name())
                                                     .isAbsolute()
                                                     .isEqualTo(Path.of(template.expectedPath())));
    }

    private void assertRefusedWithoutLine(Template template) {
        var stripped = CONSENSUS_PATH_LINE.matcher(template.toml()).replaceAll("");

        assertThat(stripped).as("control: the template must carry a consensus_path line to remove")
                            .isNotEqualTo(template.toml());
        AetherNode.consensusDirectory(nodeConfigFrom(write(stripped)))
                  .onSuccess(path -> fail(template.name() + " still boots without consensus_path (resolved " + path
                                          + "), so the line is not what satisfies the gate"))
                  .onFailure(cause -> assertThat(cause.message()).contains(REQUIRED));
    }

    private static TomlDocument composedCloud() {
        var config = ClusterBootstrapConfigParser.parse(CLOUD_BOOTSTRAP).unwrap();

        return ReplacementNodeConfigComposer.compose(config, config.sources().get("eu-1"), Option.some("secret"))
                                            .unwrap();
    }

    private static TomlDocument composedSourceDefaults(SourceType type) {
        return Result.all(DefaultNodeConfig.globalDefault(), DefaultNodeConfig.sourceTypeDefault(type))
                     .map((global, typeDefault) -> NodeConfigComposer.compose(global,
                                                                              typeDefault,
                                                                              Option.none(),
                                                                              TomlDocument.EMPTY))
                     .unwrap();
    }

    private AetherNodeConfig nodeConfigFrom(Path configFile) {
        var provider = ConfigurationProvider.builder().withTomlFile(configFile).build();
        var self = NodeId.nodeId("shipped-template-node").unwrap();
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", 1).unwrap());

        return AetherNodeConfig.builder()
                               .self(self).coreNodes(List.of(selfInfo)).managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
                               .sliceConfig(SliceConfig.sliceConfig()).artifactRepo(DHTConfig.FULL).coreMax(1)
                               .appHttp(AppHttpConfig.appHttpConfig()).tls(Option.none()).quicTls(TlsConfig.selfSignedMutual())
                               .certificateProvider(Option.none())
                               .configProvider(Option.some(provider))
                               .environment(Option.none())
                               .managementHttpProtocol(HttpProtocol.H1)
                               .storageConfig(storageAsMainResolvesIt(configFile))
                               .build();
    }

    /// Mirrors `Main.resolveStorage` over `Main.loadConfigFile`: an unloadable config contributes no storage.
    private static Map<String, StorageConfig> storageAsMainResolvesIt(Path configFile) {
        return ConfigLoader.load(configFile)
                           .option()
                           .map(AetherConfig::storage)
                           .filter(storage -> !storage.isEmpty())
                           .or(Map.of());
    }

    private Path write(String toml) {
        try {
            return Files.writeString(Files.createTempFile(tempDir, "aether", ".toml"), toml);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("shipped template not found at " + path.toAbsolutePath(), e);
        }
    }
}
