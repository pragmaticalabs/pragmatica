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
import org.pragmatica.aether.config.cluster.FirewallRule;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.lang.Option;

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
                        "5\n" +             // total node count
                        "n\n" +             // configure database? no
                        "\n";               // generate config? default yes
            var wizard = wizardFor(input);

            wizard.run()
                  .onFailure(c -> fail("Expected success but got " + c.message()))
                  .onSuccess(answers -> {
                      assertThat(answers.clusterName()).isEqualTo("my-cluster");
                      assertThat(answers.target()).isEqualTo(SourceType.DOCKER);
                      assertThat(answers.topology().core()).isEqualTo(5);
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
                        "hel1\n" +          // region: no default — must be typed
                        "cpx32\n" +         // instance type: no default — must be typed
                        "\n" +              // credential env var: default HCLOUD_TOKEN
                        "~/.ssh/id_ed25519.pub\n" + // SSH public key: required for cloud
                        "5\n" +             // total node count
                        "n\n" +             // configure database? no
                        "\n" +              // firewall preset: default STANDARD
                        "203.0.113.0/24\n" + // admin CIDR: STANDARD needs one too
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
                          assertThat(cloud.sshPublicKeyPath()).isEqualTo("~/.ssh/id_ed25519.pub");
                      });
                      assertThat(answers.adminCidr()).isEqualTo(Option.some("203.0.113.0/24"));
                      assertThat(answers.topology().core()).isEqualTo(5);
                      assertThat(answers.firewallPreset()).isEqualTo(FirewallPreset.STANDARD);
                      assertThat(answers.tls()).isInstanceOf(TlsAnswers.AutoGenerate.class);
                      assertThat(answers.secret()).isInstanceOf(SecretAnswers.AutoGenerate.class);
                  });
        }
    }

    /// The wizard shipped `cx21` as Hetzner's instance-type default until 2026-09-10, and Hetzner
    /// has DELETED that server type — so pressing Enter through this prompt produced a config that
    /// could not provision in any region. The defect is latent rather than observed: the 422
    /// failures of that day were a separate, still-undiagnosed incident whose config carried
    /// `cpx11`, passed explicitly. Providers retire instance types, so the wizard ships no default
    /// and requires an answer.
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
                        "hel1\n" +          // region
                        "\n" +              // instance type: EMPTY -> rejected, re-prompts
                        "cpx32\n" +         // instance type: the actual answer
                        "\n" +              // credential env var: default HCLOUD_TOKEN
                        "~/.ssh/id_ed25519.pub\n" + // SSH public key
                        "5\n" +             // total node count
                        "n\n" +             // configure database? no
                        "\n" +              // firewall preset: default STANDARD
                        "203.0.113.0/24\n" + // admin CIDR
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
            var input = "prod-eu\n1\n\nhel1\n   \ncpx32\n\n~/.ssh/id_ed25519.pub\n5\nn\n\n203.0.113.0/24\n\n\n\n";
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

    /// The region default was removed for a DIFFERENT reason than the instance type, and the
    /// distinction is the point: instance types rot and fail loud at the provider API, whereas a
    /// defaulted region SUCCEEDS and silently puts the cluster's data in a jurisdiction nobody
    /// chose. `hel1` was the Hetzner default.
    @Nested
    class RegionHasNoDefault {

        /// Positional proof, same shape as the instance-type pin: with the empty answer refused,
        /// the next typed line becomes the region and the instance-type prompt still gets its own
        /// answer. Restore the default and `hel1` is taken from the fallback while `nbg1` slides
        /// into the instance-type slot — both assertions flip.
        @Test
        void run_cloudEmptyRegion_reprompts_andAcceptsTheNextAnswer() {
            var input = "prod-eu\n1\n\n" +   // name, CLOUD, default provider
                        "\n" +                 // region: EMPTY -> rejected, re-prompts
                        "nbg1\n" +             // region: the actual answer
                        "cpx32\n\n~/.ssh/id_ed25519.pub\n5\nn\n\n203.0.113.0/24\n\n\n\n";
            var wizard = wizardFor(input);

            wizard.run()
                  .onFailure(c -> fail("Expected success but got " + c.message()))
                  .onSuccess(answers -> answers.cloud().onPresent(cloud -> {
                      assertThat(cloud.region()).isEqualTo("nbg1");
                      assertThat(cloud.instanceType()).isEqualTo("cpx32");
                  }));
        }

        /// The refusal argues from RESIDENCY, not from catalogue rot — the reasoning an operator
        /// needs in order to know the answer matters.
        @Test
        void message_namesTheFlagAndTheResidencyReason() {
            var message = new ClusterInitError.RegionRequired("hetzner").message();

            assertThat(message).contains("--region")
                               .contains("hetzner")
                               .contains("jurisdiction");
        }
    }

    /// `--admin-cidr` / the wizard's admin prompt were honoured for RESTRICTIVE only, so STANDARD —
    /// the DEFAULT preset — emitted no port-22 and no management-port rule and produced a config
    /// whose bootstrap cannot reach healthy nodes.
    @Nested
    class StandardPresetCollectsAdminCidr {

        @Test
        void run_cloudStandardPreset_collectsAdminCidr_andEmitsAdminScopedRules() {
            var input = "prod-eu\n1\n\nhel1\ncpx32\n\n~/.ssh/id_ed25519.pub\n5\nn\n" +
                        "\n" +                  // firewall preset: default STANDARD
                        "203.0.113.0/24\n" +    // admin CIDR — STANDARD must ask
                        "\n\n\n";
            var wizard = wizardFor(input);

            wizard.run()
                  .onFailure(c -> fail("Expected success but got " + c.message()))
                  .onSuccess(ClusterConfigWizardTest::assertStandardPresetCarriesAdminRules);
        }

        /// An empty answer re-prompts rather than falling through to "no admin rules".
        @Test
        void run_cloudStandardEmptyAdminCidr_reprompts() {
            var input = "prod-eu\n1\n\nhel1\ncpx32\n\n~/.ssh/id_ed25519.pub\n5\nn\n\n" +
                        "\n" +                  // admin CIDR: EMPTY -> rejected
                        "198.51.100.0/24\n" +   // the actual answer
                        "\n\n\n";
            var wizard = wizardFor(input);

            wizard.run()
                  .onFailure(c -> fail("Expected success but got " + c.message()))
                  .onSuccess(a -> assertThat(a.adminCidr()).isEqualTo(Option.some("198.51.100.0/24")));
        }
    }

    /// The generated cloud config used to carry no SSH reference at all, while
    /// `SshKeyResolver.resolveOrFailIfCloud` refuses any cloud cluster without one — `init` printed
    /// "Next: run bootstrap" and bootstrap rejected the file it had just written.
    @Nested
    class CloudCollectsSshPublicKey {

        @Test
        void run_cloudEmptySshPublicKey_reprompts_andAcceptsTheNextAnswer() {
            var input = "prod-eu\n1\n\nhel1\ncpx32\n\n" +
                        "\n" +                          // SSH public key: EMPTY -> rejected
                        "/tmp/example_key.pub\n" +      // the actual answer
                        "5\nn\n\n203.0.113.0/24\n\n\n\n";
            var wizard = wizardFor(input);

            wizard.run()
                  .onFailure(c -> fail("Expected success but got " + c.message()))
                  .onSuccess(a -> a.cloud()
                                   .onPresent(cloud -> assertThat(cloud.sshPublicKeyPath()).isEqualTo("/tmp/example_key.pub")));
        }

        @Test
        void message_namesTheFlagAndWhyItIsNotInferred() {
            var message = new ClusterInitError.SshPublicKeyRequired().message();

            assertThat(message).contains("--ssh-public-key")
                               .contains("bootstrap");
        }
    }

    private static void assertStandardPresetCarriesAdminRules(ClusterConfigAnswers answers) {
        assertThat(answers.firewallPreset()).isEqualTo(FirewallPreset.STANDARD);
        assertThat(answers.adminCidr()).isEqualTo(Option.some("203.0.113.0/24"));

        var rules = FirewallPresets.rulesFor(answers.firewallPreset(), answers.adminCidr(), "10.0.0.0/8");
        var ports = rules.stream().map(FirewallRule::port).toList();

        // 22 = bootstrap SSH, 8080 = management API polled by the Phase 7 readiness gate.
        assertThat(ports).contains(22, 8080);
    }

    /// Removing the defaults made every cloud prompt REQUIRED, and a required prompt re-asks on an
    /// empty answer. At EOF each re-ask reads "" again, so before `Prompt.isInputExhausted` these
    /// recursed without bound: `aether cluster init < truncated-file` died with a
    /// `StackOverflowError`. A regression introduced by this change, pinned here.
    @Nested
    class ExhaustedInputAborts {

        @Test
        void run_inputEndsAtRequiredRegion_failsCleanly_insteadOfRecursing() {
            var wizard = wizardFor("prod-eu\n1\n\n");

            wizard.run()
                  .onSuccess(a -> fail("Expected failure but got " + a))
                  .onFailure(cause -> assertThat(cause).isInstanceOf(ClusterInitError.InputExhausted.class));
        }

        @Test
        void run_inputEndsAtRequiredInstanceType_failsCleanly_insteadOfRecursing() {
            var wizard = wizardFor("prod-eu\n1\n\nhel1\n");

            wizard.run()
                  .onSuccess(a -> fail("Expected failure but got " + a))
                  .onFailure(cause -> assertThat(cause).isInstanceOf(ClusterInitError.InputExhausted.class));
        }

        /// A docker target reaches the review step without passing a single REQUIRED prompt, so
        /// nothing before it can notice exhausted input and `confirm` answers its own default —
        /// which SILENTLY GENERATED a full config and exited SUCCESS, contradicting the contract
        /// this class exists to enforce. The gap the first EOF fix left.
        @Test
        void run_dockerInputEndsBeforeReview_failsCleanly_insteadOfGeneratingFromDefaults() {
            var wizard = wizardFor("c\n4\n5\n");

            wizard.run()
                  .onSuccess(a -> fail("Expected failure but generated a config from defaults: " + a))
                  .onFailure(cause -> assertThat(cause).isInstanceOf(ClusterInitError.InputExhausted.class));
        }

        /// Forge with input ending EARLIER — and deliberately labelled for what it pins, which is
        /// NOT the review-step guard. Measured: removing that guard reddens the docker test above
        /// and leaves this one green, because this input runs out at a `guardedPrompt` and is caught
        /// by the pre-existing check. It is kept as coverage that forge also refuses, not as a
        /// second pin on the same line.
        @Test
        void run_forgeInputEndsAtAnEarlierPrompt_failsCleanly() {
            var wizard = wizardFor("c\n4\n");

            wizard.run()
                  .onSuccess(a -> fail("Expected failure but generated a config from defaults: " + a))
                  .onFailure(cause -> assertThat(cause).isInstanceOf(ClusterInitError.InputExhausted.class));
        }

        /// Calibration for the two above: a docker run whose input is COMPLETE must still succeed.
        /// Without this, the EOF guard could be refusing every docker run and the tests above would
        /// not notice.
        @Test
        void run_dockerCompleteInput_stillSucceeds() {
            var wizard = wizardFor("my-cluster\n\n5\nn\n\n");

            wizard.run()
                  .onFailure(c -> fail("Expected success but got " + c.message()))
                  .onSuccess(a -> assertThat(a.clusterName()).isEqualTo("my-cluster"));
        }

        /// The message must send the operator somewhere useful, not just say "input ended".
        @Test
        void message_pointsAtTheNonInteractiveRoute() {
            var message = ClusterInitError.InputExhausted.INSTANCE.message();

            assertThat(message).contains("--non-interactive")
                               .contains("--region")
                               .contains("--ssh-public-key");
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
