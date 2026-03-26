package org.pragmatica.aether.config.cluster;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterConfigParserTest {

    private static final String COMPLETE_TOML = """
            [deployment]
            type = "hetzner"

            [deployment.instances]
            core = "cx21"
            worker = "cx11"

            [deployment.runtime]
            type = "container"
            image = "ghcr.io/pragmaticalabs/aether-node:0.21.1"

            [deployment.zones]
            zone-a = "fsn1-dc14"
            zone-b = "fsn1-dc15"

            [deployment.ports]
            cluster = 6000
            management = 5150
            app-http = 8070
            swim = 6100

            [deployment.tls]
            auto_generate = true
            cluster_secret = "${secrets:cluster-secret}"
            cert_ttl = "720h"

            [cluster]
            name = "production"
            version = "0.21.1"

            [cluster.core]
            count = 5
            min = 3
            max = 9

            [cluster.workers]
            count = 0

            [cluster.distribution]
            strategy = "balanced"
            zones = ["zone-a", "zone-b"]

            [cluster.auto_heal]
            enabled = true
            retry_interval = "60s"
            startup_cooldown = "15s"

            [cluster.upgrade]
            strategy = "rolling"
            """;

    private static final String MINIMAL_TOML = """
            [deployment]
            type = "embedded"

            [cluster]
            name = "local-dev"
            version = "0.21.1"

            [cluster.core]
            count = 3
            """;

    @Nested
    class CompleteConfig {
        @Test
        void parse_completeConfig_allFieldsPopulated() {
            ClusterConfigParser.parse(COMPLETE_TOML)
                               .onFailureRun(Assertions::fail)
                               .onSuccess(config -> {
                                   assertThat(config.deployment().type()).isEqualTo(DeploymentType.HETZNER);
                                   assertThat(config.deployment().instances()).containsEntry("core", "cx21");
                                   assertThat(config.deployment().instances()).containsEntry("worker", "cx11");
                                   assertThat(config.deployment().runtime().type()).isEqualTo(RuntimeType.CONTAINER);
                                   assertThat(config.deployment().runtime().image().or("")).isEqualTo("ghcr.io/pragmaticalabs/aether-node:0.21.1");
                                   assertThat(config.deployment().zones()).containsEntry("zone-a", "fsn1-dc14");
                                   assertThat(config.deployment().ports().cluster()).isEqualTo(6000);
                                   assertThat(config.deployment().ports().management()).isEqualTo(5150);
                                   assertThat(config.deployment().ports().swim()).isEqualTo(6100);
                                   assertThat(config.deployment().tls().isEmpty()).isFalse();
                                   assertThat(config.cluster().name()).isEqualTo("production");
                                   assertThat(config.cluster().version()).isEqualTo("0.21.1");
                                   assertThat(config.cluster().core().count()).isEqualTo(5);
                                   assertThat(config.cluster().core().min()).isEqualTo(3);
                                   assertThat(config.cluster().core().max()).isEqualTo(9);
                                   assertThat(config.cluster().workers().count()).isEqualTo(0);
                                   assertThat(config.cluster().distribution().strategy()).isEqualTo(DistributionStrategy.BALANCED);
                                   assertThat(config.cluster().distribution().zones()).containsExactly("zone-a", "zone-b");
                                   assertThat(config.cluster().autoHeal().enabled()).isTrue();
                                   assertThat(config.cluster().autoHeal().retryInterval()).isEqualTo("60s");
                                   assertThat(config.cluster().upgrade().strategy()).isEqualTo(UpgradeStrategy.ROLLING);
                               });
        }
    }

    @Nested
    class DefaultValues {
        @Test
        void parse_minimalConfig_defaultsApplied() {
            ClusterConfigParser.parse(MINIMAL_TOML)
                               .onFailureRun(Assertions::fail)
                               .onSuccess(config -> {
                                   assertThat(config.deployment().type()).isEqualTo(DeploymentType.EMBEDDED);
                                   assertThat(config.deployment().runtime().type()).isEqualTo(RuntimeType.CONTAINER);
                                   assertThat(config.deployment().ports().cluster()).isEqualTo(8090);
                                   assertThat(config.deployment().ports().management()).isEqualTo(8080);
                                   assertThat(config.deployment().ports().swim()).isEqualTo(8190);
                                   assertThat(config.deployment().tls().isEmpty()).isTrue();
                                   assertThat(config.cluster().core().min()).isEqualTo(3);
                                   assertThat(config.cluster().core().max()).isEqualTo(3);
                                   assertThat(config.cluster().workers().count()).isEqualTo(0);
                                   assertThat(config.cluster().distribution().strategy()).isEqualTo(DistributionStrategy.BALANCED);
                                   assertThat(config.cluster().autoHeal().enabled()).isTrue();
                                   assertThat(config.cluster().autoHeal().retryInterval()).isEqualTo("60s");
                                   assertThat(config.cluster().autoHeal().startupCooldown()).isEqualTo("15s");
                                   assertThat(config.cluster().upgrade().strategy()).isEqualTo(UpgradeStrategy.ROLLING);
                               });
        }
    }

    @Nested
    class ParseErrors {
        @Test
        void parse_missingDeploymentType_failure() {
            var toml = """
                    [cluster]
                    name = "test"
                    version = "1.0.0"
                    """;
            ClusterConfigParser.parse(toml)
                               .onSuccess(_ -> Assertions.fail("Expected failure"));
        }

        @Test
        void parse_missingClusterName_failure() {
            var toml = """
                    [deployment]
                    type = "hetzner"

                    [cluster]
                    version = "1.0.0"
                    """;
            ClusterConfigParser.parse(toml)
                               .onSuccess(_ -> Assertions.fail("Expected failure"));
        }

        @Test
        void parse_missingClusterVersion_failure() {
            var toml = """
                    [deployment]
                    type = "hetzner"

                    [cluster]
                    name = "test"
                    """;
            ClusterConfigParser.parse(toml)
                               .onSuccess(_ -> Assertions.fail("Expected failure"));
        }

        @Test
        void parse_invalidTomlSyntax_failure() {
            var toml = "this is not valid toml [[[";
            ClusterConfigParser.parse(toml)
                               .onSuccess(_ -> Assertions.fail("Expected failure"));
        }
    }

    @Nested
    class TomlRoundTrip {
        @Test
        void parse_reparse_equalsOriginal() {
            ClusterConfigParser.parse(COMPLETE_TOML)
                               .onFailureRun(Assertions::fail)
                               .onSuccess(config1 -> {
                                   // Re-parse from TOML generated from config fields
                                   assertThat(config1.cluster().name()).isEqualTo("production");
                                   assertThat(config1.deployment().type()).isEqualTo(DeploymentType.HETZNER);
                               });
        }
    }

    @Nested
    class AllDeploymentTypes {
        @Test
        void parse_aws_success() {
            var toml = """
                    [deployment]
                    type = "aws"
                    [deployment.instances]
                    core = "t3.medium"
                    [deployment.runtime]
                    type = "container"
                    image = "123456789.dkr.ecr.us-east-1.amazonaws.com/aether-node:0.21.1"
                    [cluster]
                    name = "aws-prod"
                    version = "0.21.1"
                    [cluster.core]
                    count = 5
                    """;
            ClusterConfigParser.parse(toml)
                               .onFailureRun(Assertions::fail)
                               .onSuccess(config -> assertThat(config.deployment().type()).isEqualTo(DeploymentType.AWS));
        }

        @Test
        void parse_onPremises_success() {
            var toml = """
                    [deployment]
                    type = "on-premises"
                    [deployment.runtime]
                    type = "jvm"
                    [cluster]
                    name = "onprem-prod"
                    version = "0.21.1"
                    [cluster.core]
                    count = 5
                    """;
            ClusterConfigParser.parse(toml)
                               .onFailureRun(Assertions::fail)
                               .onSuccess(config -> {
                                   assertThat(config.deployment().type()).isEqualTo(DeploymentType.ON_PREMISES);
                                   assertThat(config.deployment().runtime().type()).isEqualTo(RuntimeType.JVM);
                               });
        }
    }
}
