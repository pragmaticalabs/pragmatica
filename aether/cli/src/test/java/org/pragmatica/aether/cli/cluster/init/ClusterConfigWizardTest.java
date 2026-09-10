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
            //        instance type (REQUIRED, no default) -> credential env (default HCLOUD_TOKEN) ->
            //        topology (3) -> database (no) -> firewall preset (default STANDARD) ->
            //        TLS (default auto-gen) -> secret (default auto-gen) -> review (yes)
            var input = "prod-eu\n" +       // cluster name
                        "1\n" +             // deployment target: CLOUD
                        "\n" +              // cloud provider: default HETZNER
                        "\n" +              // region: default hel1
                        "cpx32\n" +         // instance type: no default — must be typed
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
                          assertThat(cloud.instanceType()).isEqualTo("cpx32");
                          assertThat(cloud.credentialEnvVar()).isEqualTo("HCLOUD_TOKEN");
                      });
                      assertThat(answers.topology().core()).isEqualTo(3);
                      assertThat(answers.firewallPreset()).isEqualTo(FirewallPreset.STANDARD);
                      assertThat(answers.tls()).isInstanceOf(TlsAnswers.AutoGenerate.class);
                      assertThat(answers.secret()).isInstanceOf(SecretAnswers.AutoGenerate.class);
                  });
        }
    }

    /// The wizard shipped `cx21` as Hetzner's instance-type default until 2026-09-10. Hetzner had
    /// DELETED that server type, so every operator who pressed Enter through this prompt got a
    /// config that could not provision in any region — three bootstrap runs died on
    /// `422 (invalid_input): unsupported location for server type`. Providers retire instance types,
    /// so the wizard now ships no default and requires an answer.
    @Nested
    class InstanceTypeHasNoDefault {

        /// Pressing Enter must NOT be accepted. The proof is positional: with the prompt refusing
        /// the empty answer, the NEXT line typed is consumed as the instance type and the credential
        /// prompt still gets its own default. Were a default restored, the empty line would be
        /// accepted as that default and `cpx32` would slide into the credential-env prompt — so both
        /// assertions below flip.
        @Test
        void run_cloudEmptyInstanceType_reprompts_andAcceptsTheNextAnswer() {
            var input = "prod-eu\n" +       // cluster name
                        "1\n" +             // deployment target: CLOUD
                        "\n" +              // cloud provider: default HETZNER
                        "\n" +              // region: default hel1
                        "\n" +              // instance type: EMPTY -> rejected, re-prompts
                        "cpx32\n" +         // instance type: the actual answer
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
                  .onSuccess(answers -> answers.cloud().onPresent(cloud -> {
                      assertThat(cloud.instanceType()).isEqualTo("cpx32");
                      assertThat(cloud.credentialEnvVar()).isEqualTo("HCLOUD_TOKEN");
                  }));
        }

        /// A blank-but-not-empty answer is refused for the same reason — `"   "` would otherwise be
        /// written into `instance_type` and reach the provider verbatim.
        @Test
        void run_cloudBlankInstanceType_reprompts_andAcceptsTheNextAnswer() {
            var input = "prod-eu\n1\n\n\n   \ncpx32\n\n3\nn\n\n\n\n\n";
            var wizard = wizardFor(input);

            wizard.run()
                  .onFailure(c -> fail("Expected success but got " + c.message()))
                  .onSuccess(answers -> answers.cloud()
                                               .onPresent(cloud -> assertThat(cloud.instanceType()).isEqualTo("cpx32")));
        }

        /// The refusal names the flag an operator would reach for and the provider whose catalogue
        /// governs the value. Both the wizard and `--non-interactive` raise this same cause.
        @Test
        void message_namesTheFlagAndTheProviderCatalogue() {
            var message = new ClusterInitError.InstanceTypeRequired("hetzner").message();

            assertThat(message).contains("--instance-type")
                               .contains("hetzner")
                               .contains("catalogue");
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
