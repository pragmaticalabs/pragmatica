package org.pragmatica.aether.config.cluster;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;


class ClusterBootstrapConfigParserTest {

    @Nested
    class HappyPath {

        @Test
        void parse_minimalForge_parsesCorrectly() {
            var toml = """
                config_version = "1.0.0"

                [cluster]
                name = "dev-local"
                version = "1.0.0"

                [source.local]
                type = "forge"

                [source.local.core]
                count = 3
                """;

            ClusterBootstrapConfigParser.parse(toml)
                .onFailure(cause -> Assertions.fail(cause.message()))
                .onSuccess(config -> {
                    assertThat(config.configVersion()).isEqualTo("1.0.0");
                    assertThat(config.cluster().name()).isEqualTo("dev-local");
                    assertThat(config.cluster().version()).isEqualTo("1.0.0");
                    assertThat(config.sources()).containsKey("local");

                    var source = config.sources().get("local");
                    assertThat(source.type()).isEqualTo(SourceType.FORGE);
                    assertThat(source.loadBalancer()).isEqualTo(LoadBalancerMode.ELECTED);
                    assertThat(source.roles()).containsKey(NodeRole.CORE);
                    assertThat(source.roles().get(NodeRole.CORE).count()).isEqualTo(Option.some(3));
                    assertThat(source.roles().get(NodeRole.CORE).runtimeRef()).isEqualTo("ember");
                });
        }

        @Test
        void parse_singleCloud_parsesAllFields() {
            var toml = """
                config_version = "1.0.0"

                [cluster]
                name = "production"
                version = "1.0.0"

                [source.hetzner-eu]
                type = "cloud"
                provider = "hetzner"
                credentials = "${secrets:hetzner-api-key}"
                region = "eu-central"
                zone = "fsn1-dc14"
                load_balancer = "external"
                load_balancer_ips = ["10.0.0.1", "10.0.0.2"]
                load_balancer_endpoint = "lb.example.com"

                [source.hetzner-eu.core]
                count = 5
                instance_type = "cx41"
                runtime = "prod-container"

                [source.hetzner-eu.worker]
                count = 3
                instance_type = "cx31"
                runtime = "prod-container"

                [runtime.prod-container]
                type = "container"
                image = "ghcr.io/pragmaticalabs/aether-node:1.0.0"
                jvm_args = "-Xmx2g"
                """;

            ClusterBootstrapConfigParser.parse(toml)
                .onFailure(cause -> Assertions.fail(cause.message()))
                .onSuccess(config -> {
                    assertThat(config.cluster().name()).isEqualTo("production");

                    var source = config.sources().get("hetzner-eu");
                    assertThat(source.type()).isEqualTo(SourceType.CLOUD);
                    assertThat(source.provider()).isEqualTo(Option.some(CloudProviderName.HETZNER));
                    assertThat(source.credentials()).isEqualTo(Option.some("${secrets:hetzner-api-key}"));
                    assertThat(source.region()).isEqualTo(Option.some("eu-central"));
                    assertThat(source.zone()).isEqualTo(Option.some("fsn1-dc14"));
                    assertThat(source.loadBalancer()).isEqualTo(LoadBalancerMode.EXTERNAL);
                    assertThat(source.loadBalancerIps()).containsExactly("10.0.0.1", "10.0.0.2");
                    assertThat(source.loadBalancerEndpoint()).isEqualTo(Option.some("lb.example.com"));
                    assertThat(source.roles()).containsKey(NodeRole.CORE);
                    assertThat(source.roles()).containsKey(NodeRole.WORKER);
                    assertThat(source.roles().get(NodeRole.CORE).instanceType()).isEqualTo(Option.some("cx41"));
                    assertThat(source.roles().get(NodeRole.CORE).runtimeRef()).isEqualTo("prod-container");
                    assertThat(source.roles().get(NodeRole.WORKER).count()).isEqualTo(Option.some(3));

                    var runtime = config.runtimes().get("prod-container");
                    assertThat(runtime.type()).isEqualTo(RuntimeType.CONTAINER);
                    assertThat(runtime.image()).isEqualTo(Option.some("ghcr.io/pragmaticalabs/aether-node:1.0.0"));
                    assertThat(runtime.jvmArgs()).isEqualTo(Option.some("-Xmx2g"));
                });
        }

        @Test
        void parse_sshSource_parsesHostsAndSshConfig() {
            var toml = """
                config_version = "1.0.0"

                [cluster]
                name = "staging"
                version = "1.0.0"

                [source.bare-metal]
                type = "ssh"
                user = "deploy"
                key = "~/.ssh/id_ed25519"
                ssh_port = 2222

                [source.bare-metal.core]
                hosts = ["10.0.1.1", "10.0.1.2", "10.0.1.3"]
                runtime = "jvm-runtime"

                [runtime.jvm-runtime]
                type = "jvm"
                jvm_args = "-Xmx4g"
                """;

            ClusterBootstrapConfigParser.parse(toml)
                .onFailure(cause -> Assertions.fail(cause.message()))
                .onSuccess(config -> {
                    var source = config.sources().get("bare-metal");
                    assertThat(source.type()).isEqualTo(SourceType.SSH);
                    assertThat(source.user()).isEqualTo(Option.some("deploy"));
                    assertThat(source.key()).isEqualTo(Option.some("~/.ssh/id_ed25519"));
                    assertThat(source.sshPort()).isEqualTo(Option.some(2222));
                    assertThat(source.loadBalancer()).isEqualTo(LoadBalancerMode.NONE);

                    var coreRole = source.roles().get(NodeRole.CORE);
                    assertThat(coreRole.hosts().or(List.of())).containsExactly("10.0.1.1", "10.0.1.2", "10.0.1.3");
                    assertThat(coreRole.runtimeRef()).isEqualTo("jvm-runtime");
                });
        }

        @Test
        void parse_dockerSource_parsesCorrectly() {
            var toml = """
                config_version = "1.0.0"

                [cluster]
                name = "test-local"
                version = "1.0.0"

                [source.docker-local]
                type = "docker"

                [source.docker-local.core]
                count = 3
                """;

            ClusterBootstrapConfigParser.parse(toml)
                .onFailure(cause -> Assertions.fail(cause.message()))
                .onSuccess(config -> {
                    var source = config.sources().get("docker-local");
                    assertThat(source.type()).isEqualTo(SourceType.DOCKER);
                    assertThat(source.loadBalancer()).isEqualTo(LoadBalancerMode.NONE);
                    assertThat(source.roles().get(NodeRole.CORE).runtimeRef()).isEqualTo("docker");
                });
        }

        @Test
        void parse_multipleRuntimes_parsesAll() {
            var toml = """
                config_version = "1.0.0"

                [cluster]
                name = "multi-rt"
                version = "1.0.0"

                [source.local]
                type = "forge"

                [source.local.core]
                count = 3

                [runtime.ember]
                type = "ember"

                [runtime.container-prod]
                type = "container"
                image = "ghcr.io/pragmaticalabs/aether-node:1.0.0"

                [runtime.jvm-dev]
                type = "jvm"
                jvm_args = "-Xmx1g -ea"
                """;

            ClusterBootstrapConfigParser.parse(toml)
                .onFailure(cause -> Assertions.fail(cause.message()))
                .onSuccess(config -> {
                    assertThat(config.runtimes()).hasSize(3);
                    assertThat(config.runtimes()).containsKey("ember");
                    assertThat(config.runtimes()).containsKey("container-prod");
                    assertThat(config.runtimes()).containsKey("jvm-dev");
                    assertThat(config.runtimes().get("ember").type()).isEqualTo(RuntimeType.EMBER);
                    assertThat(config.runtimes().get("container-prod").type()).isEqualTo(RuntimeType.CONTAINER);
                    assertThat(config.runtimes().get("jvm-dev").jvmArgs()).isEqualTo(Option.some("-Xmx1g -ea"));
                });
        }

        @Test
        void parse_databaseDotNotation_parsesCorrectly() {
            var toml = """
                config_version = "1.0.0"

                [cluster]
                name = "db-test"
                version = "1.0.0"

                [source.cloud-eu]
                type = "cloud"
                provider = "hetzner"
                databases.default = "postgres://db1.example.com:5432/aether"
                databases.analytics = "postgres://db2.example.com:5432/analytics"

                [source.cloud-eu.core]
                count = 3
                instance_type = "cx41"
                """;

            ClusterBootstrapConfigParser.parse(toml)
                .onFailure(cause -> Assertions.fail(cause.message()))
                .onSuccess(config -> {
                    var source = config.sources().get("cloud-eu");
                    assertThat(source.databases()).hasSize(2);
                    assertThat(source.databases().get("default")).isEqualTo("postgres://db1.example.com:5432/aether");
                    assertThat(source.databases().get("analytics")).isEqualTo("postgres://db2.example.com:5432/analytics");
                });
        }

        @Test
        void parse_firewallRules_parsesInlineTables() {
            var toml = """
                config_version = "1.0.0"

                [cluster]
                name = "fw-test"
                version = "1.0.0"

                [source.prod]
                type = "cloud"
                provider = "hetzner"

                [source.prod.core]
                count = 3
                instance_type = "cx41"

                [source.prod.firewall]
                allow_ingress = [
                    { port = 8070, protocol = "tcp", source_cidr = "0.0.0.0/0", description = "app HTTP" },
                    { port = 8090, protocol = "tcp", source_cidr = "10.0.0.0/8" },
                ]
                """;

            ClusterBootstrapConfigParser.parse(toml)
                .onFailure(cause -> Assertions.fail(cause.message()))
                .onSuccess(config -> {
                    var rules = config.sources().get("prod").firewallRules();
                    assertThat(rules).hasSize(2);
                    assertThat(rules.get(0).port()).isEqualTo(8070);
                    assertThat(rules.get(0).protocol()).isEqualTo("tcp");
                    assertThat(rules.get(0).sourceCidr()).isEqualTo("0.0.0.0/0");
                    assertThat(rules.get(0).description()).isEqualTo(Option.some("app HTTP"));
                    assertThat(rules.get(1).port()).isEqualTo(8090);
                    assertThat(rules.get(1).sourceCidr()).isEqualTo("10.0.0.0/8");
                    assertThat(rules.get(1).description()).isEqualTo(Option.none());
                });
        }

        @Test
        void parse_operationsDefaults_appliedCorrectly() {
            var toml = """
                config_version = "1.0.0"

                [cluster]
                name = "defaults-test"
                version = "1.0.0"

                [source.local]
                type = "forge"

                [source.local.core]
                count = 3
                """;

            ClusterBootstrapConfigParser.parse(toml)
                .onFailure(cause -> Assertions.fail(cause.message()))
                .onSuccess(config -> {
                    var ops = config.operations();
                    assertThat(ops.autoHeal().enabled()).isTrue();
                    assertThat(ops.autoHeal().retryInterval()).isEqualTo("60s");
                    assertThat(ops.tls().autoGenerate()).isTrue();
                    assertThat(ops.tls().certTtl()).isEqualTo("720h");
                    assertThat(ops.timeouts().healthCheck()).isEqualTo("300s");
                    assertThat(ops.timeouts().quorumFormation()).isEqualTo("600s");
                    assertThat(ops.ports().cluster()).isEqualTo(8090);
                    assertThat(ops.ports().management()).isEqualTo(8080);
                    assertThat(ops.ports().appHttp()).isEqualTo(8070);
                    assertThat(ops.ports().swim()).isEqualTo(8190);
                });
        }
    }

    @Nested
    class RuntimeDefaults {

        @Test
        void parse_forgeDefaultsEmber_runtimeNotSpecified() {
            var toml = """
                config_version = "1.0.0"

                [cluster]
                name = "forge-rt"
                version = "1.0.0"

                [source.local]
                type = "forge"

                [source.local.core]
                count = 3
                """;

            ClusterBootstrapConfigParser.parse(toml)
                .onFailure(cause -> Assertions.fail(cause.message()))
                .onSuccess(config -> {
                    var coreRole = config.sources().get("local").roles().get(NodeRole.CORE);
                    assertThat(coreRole.runtimeRef()).isEqualTo("ember");
                });
        }

        @Test
        void parse_dockerDefaultsDocker_runtimeNotSpecified() {
            var toml = """
                config_version = "1.0.0"

                [cluster]
                name = "docker-rt"
                version = "1.0.0"

                [source.local]
                type = "docker"

                [source.local.core]
                count = 3
                """;

            ClusterBootstrapConfigParser.parse(toml)
                .onFailure(cause -> Assertions.fail(cause.message()))
                .onSuccess(config -> {
                    var coreRole = config.sources().get("local").roles().get(NodeRole.CORE);
                    assertThat(coreRole.runtimeRef()).isEqualTo("docker");
                });
        }
    }

    @Nested
    class ErrorCases {

        @Test
        void parse_missingConfigVersion_returnsError() {
            var toml = """
                [cluster]
                name = "test"
                version = "1.0.0"
                """;

            ClusterBootstrapConfigParser.parse(toml)
                .onSuccess(_ -> Assertions.fail("Expected failure for missing config_version"));
        }

        @Test
        void parse_wrongConfigVersion_returnsError() {
            var toml = """
                config_version = "2.0.0"

                [cluster]
                name = "test"
                version = "1.0.0"
                """;

            ClusterBootstrapConfigParser.parse(toml)
                .onSuccess(_ -> Assertions.fail("Expected failure for wrong config_version"))
                .onFailure(cause -> assertThat(cause.message()).contains("Unsupported config_version"));
        }

        @Test
        void parse_missingClusterName_returnsError() {
            var toml = """
                config_version = "1.0.0"

                [cluster]
                version = "1.0.0"
                """;

            ClusterBootstrapConfigParser.parse(toml)
                .onSuccess(_ -> Assertions.fail("Expected failure for missing cluster.name"))
                .onFailure(cause -> assertThat(cause.message()).contains("cluster.name"));
        }
    }
}
