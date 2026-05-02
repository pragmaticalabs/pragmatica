// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster.init;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.cli.Prompt;
import org.pragmatica.aether.cli.cluster.init.ClusterConfigAnswers.SecretAnswers;
import org.pragmatica.aether.cli.cluster.init.ClusterConfigAnswers.TlsAnswers;
import org.pragmatica.aether.config.cluster.CloudProviderName;
import org.pragmatica.aether.config.cluster.SourceType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class ClusterConfigWizardTest {

    private static ClusterConfigWizard wizardFor(String input) {
        var in = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        var out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        return new ClusterConfigWizard(new Prompt(in, out));
    }

    @Nested
    class HappyPath {

        @Test
        void run_dockerMinimal_collectsAnswers() {
            // Steps: cluster name -> deployment target (default DOCKER) -> topology (3) ->
            //        database (no) -> firewall/security skipped (Docker) -> review (yes)
            var input = "my-cluster\n" +    // cluster name
                        "\n" +              // deployment target: default = DOCKER
                        "3\n" +             // total node count
                        "n\n" +             // configure database? no
                        "\n";               // generate config? default yes
            var wizard = wizardFor(input);

            wizard.run()
                  .onFailure(c -> fail("Expected success but got " + c.message()))
                  .onSuccess(answers -> {
                      assertThat(answers.clusterName()).isEqualTo("my-cluster");
                      assertThat(answers.target()).isEqualTo(SourceType.DOCKER);
                      assertThat(answers.topology().core()).isEqualTo(3);
                      assertThat(answers.topology().worker()).isEqualTo(0);
                      assertThat(answers.database().isEmpty()).isTrue();
                      assertThat(answers.tls()).isInstanceOf(TlsAnswers.Skipped.class);
                      assertThat(answers.secret()).isInstanceOf(SecretAnswers.Skipped.class);
                  });
        }

        @Test
        void run_cloudFullHetzner_collectsAllAnswers() {
            // Steps: cluster name -> deployment target (CLOUD=1) ->
            //        cloud provider (default HETZNER) -> region (default hel1) ->
            //        instance type (default cx21) -> credential env (default HCLOUD_TOKEN) ->
            //        topology (3) -> database (no) -> firewall preset (default STANDARD) ->
            //        TLS (default auto-gen) -> secret (default auto-gen) -> review (yes)
            var input = "prod-eu\n" +       // cluster name
                        "1\n" +             // deployment target: CLOUD
                        "\n" +              // cloud provider: default HETZNER
                        "\n" +              // region: default hel1
                        "\n" +              // instance type: default cx21
                        "\n" +              // credential env var: default HCLOUD_TOKEN
                        "3\n" +             // total node count
                        "n\n" +             // configure database? no
                        "\n" +              // firewall preset: default STANDARD
                        "\n" +              // TLS configuration: default auto-generate
                        "\n" +              // cluster secret: default auto-generate
                        "\n";               // generate config? default yes
            var wizard = wizardFor(input);

            wizard.run()
                  .onFailure(c -> fail("Expected success but got " + c.message()))
                  .onSuccess(answers -> {
                      assertThat(answers.clusterName()).isEqualTo("prod-eu");
                      assertThat(answers.target()).isEqualTo(SourceType.CLOUD);
                      assertThat(answers.cloud().isPresent()).isTrue();
                      answers.cloud().onPresent(cloud -> {
                          assertThat(cloud.provider()).isEqualTo(CloudProviderName.HETZNER);
                          assertThat(cloud.region()).isEqualTo("hel1");
                          assertThat(cloud.instanceType()).isEqualTo("cx21");
                          assertThat(cloud.credentialEnvVar()).isEqualTo("HCLOUD_TOKEN");
                      });
                      assertThat(answers.topology().core()).isEqualTo(3);
                      assertThat(answers.firewallPreset()).isEqualTo(FirewallPreset.STANDARD);
                      assertThat(answers.tls()).isInstanceOf(TlsAnswers.AutoGenerate.class);
                      assertThat(answers.secret()).isInstanceOf(SecretAnswers.AutoGenerate.class);
                  });
        }
    }

    @Nested
    class AbortAndBack {

        @Test
        void run_abortAtFirstStep_returnsAborted() {
            var wizard = wizardFor("abort\n");

            wizard.run()
                  .onSuccess(a -> fail("Expected failure but got " + a))
                  .onFailure(cause -> assertThat(cause).isInstanceOf(ClusterInitError.Aborted.class));
        }

        @Test
        void run_backAtFirstStep_returnsAborted() {
            var wizard = wizardFor("back\n");

            wizard.run()
                  .onSuccess(a -> fail("Expected failure but got " + a))
                  .onFailure(cause -> assertThat(cause).isInstanceOf(ClusterInitError.Aborted.class));
        }
    }
}
