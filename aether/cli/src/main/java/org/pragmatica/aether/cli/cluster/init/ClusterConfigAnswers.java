// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster.init;

import org.pragmatica.aether.config.cluster.CloudProviderName;
import org.pragmatica.aether.config.cluster.FirewallRule;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.lang.Option;

import java.nio.file.Path;
import java.util.List;

/// All inputs collected by the wizard, ready to be rendered as a cluster-config.toml.
///
/// Many fields are `Option<>` because they apply only to specific deployment targets:
/// - `cloud` populated only when target is CLOUD
/// - `ssh` populated only when target is SSH
/// - `database` is always optional (operator may skip)
/// - `adminCidr`/`internalCidr` populated only for FirewallPreset.RESTRICTIVE
/// - `customFirewallRules` populated only for FirewallPreset.CUSTOM
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
        customFirewallRules = customFirewallRules == null ? List.of() : List.copyOf(customFirewallRules);
    }

    /// Cloud-target-specific answers.
    public record CloudAnswers(CloudProviderName provider,
                                String region,
                                String instanceType,
                                String credentialEnvVar) {}

    /// SSH-target-specific answers.
    public record SshAnswers(List<String> hosts,
                              String user,
                              Path keyPath,
                              int port) {
        public SshAnswers {
            hosts = List.copyOf(hosts);
        }
    }

    /// Database connection answers (optional).
    public record DatabaseAnswers(String host,
                                   int port,
                                   String name,
                                   String user,
                                   PasswordSource password) {
        public sealed interface PasswordSource {
            record FromEnv(String envVar) implements PasswordSource {}
            record Plaintext(String value) implements PasswordSource {}
        }
    }

    /// TLS configuration choice.
    public sealed interface TlsAnswers {
        /// Auto-generate self-signed cert at bootstrap.
        record AutoGenerate() implements TlsAnswers {}
        /// Read cert + key from environment variables (paths to PEM files).
        record Manual(String certPathEnvVar, String keyPathEnvVar) implements TlsAnswers {}
        /// Skipped entirely — Docker/Forge targets.
        record Skipped() implements TlsAnswers {}
    }

    /// Cluster secret source.
    public sealed interface SecretAnswers {
        /// Auto-generate at bootstrap.
        record AutoGenerate() implements SecretAnswers {}
        /// Read from an environment variable.
        record FromEnv(String envVar) implements SecretAnswers {}
        /// Skipped — Docker/Forge targets.
        record Skipped() implements SecretAnswers {}
    }
}
