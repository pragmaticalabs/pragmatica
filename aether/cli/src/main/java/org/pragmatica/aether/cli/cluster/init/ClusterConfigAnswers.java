// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster.init;

import java.nio.file.Path;
import java.util.List;

import org.pragmatica.aether.config.cluster.CloudProviderName;
import org.pragmatica.aether.config.cluster.FirewallRule;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.lang.Option;


public record ClusterConfigAnswers(String clusterName,
                                   String clusterVersion,
                                   SourceType target,
                                   Option<CloudAnswers> cloud,
                                   Option<SshAnswers> ssh,
                                   CoreWorkerSplit topology,
                                   Option<DatabaseAnswers> database,
                                   FirewallPreset firewallPreset,
                                   Option<String> adminCidr,
                                   Option<String> internalCidr,
                                   List<FirewallRule> customFirewallRules,
                                   TlsAnswers tls,
                                   SecretAnswers secret) {
    public ClusterConfigAnswers {
        customFirewallRules = customFirewallRules == null
                              ? List.of()
                              : List.copyOf(customFirewallRules);
    }

    public record CloudAnswers(CloudProviderName provider,
                               String region,
                               String instanceType,
                               String credentialEnvVar) {}

    public record SshAnswers(List<String> hosts, String user, Path keyPath, int port) {
        public SshAnswers {
            hosts = List.copyOf(hosts);
        }
    }

    public record DatabaseAnswers(String host, int port, String name, String user, PasswordSource password) {
        public sealed interface PasswordSource {
            record FromEnv(String envVar) implements PasswordSource {}

            record Plaintext(String value) implements PasswordSource {}
        }
    }

    public sealed interface TlsAnswers {
        record AutoGenerate() implements TlsAnswers {}

        record Manual(String certPathEnvVar, String keyPathEnvVar) implements TlsAnswers {}

        record Skipped() implements TlsAnswers {}
    }

    public sealed interface SecretAnswers {
        record AutoGenerate() implements SecretAnswers {}

        record FromEnv(String envVar) implements SecretAnswers {}

        record Skipped() implements SecretAnswers {}
    }
}
