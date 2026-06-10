// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment.azure;

import org.pragmatica.aether.environment.CloudCertificateProviderError;
import org.pragmatica.cloud.azure.AzureClient;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.net.tcp.security.CertificateBundle;
import org.pragmatica.net.tcp.security.CertificateProvider;
import org.pragmatica.net.tcp.security.GossipKey;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Result.success;


public final class AzureCertificateProvider implements CertificateProvider {
    private final byte[] caCertPem;
    private final byte[] caKeyPem;
    private final GossipKey currentGossipKey;

    private AzureCertificateProvider(byte[] caCertPem, byte[] caKeyPem, GossipKey currentGossipKey) {
        this.caCertPem = caCertPem;
        this.caKeyPem = caKeyPem;
        this.currentGossipKey = currentGossipKey;
    }

    public static Result<AzureCertificateProvider> azureCertificateProvider(AzureClient client, String secretPrefix) {
        return splitVaultAndPrefix(secretPrefix).flatMap(vp -> fetchAndBuild(client, vp.vaultName(), vp.prefix()).await());
    }

    private static Promise<AzureCertificateProvider> fetchAndBuild(AzureClient client,
                                                                   String vaultName,
                                                                   String prefix) {
        return Promise.all(fetchSecret(client, vaultName, prefix + "-ca-cert"),
                           fetchSecret(client, vaultName, prefix + "-ca-key"),
                           fetchSecret(client, vaultName, prefix + "-gossip-key"),
                           fetchSecret(client, vaultName, prefix + "-gossip-key-id")).map(AzureCertificateProvider::assembleProvider);
    }

    private static Promise<String> fetchSecret(AzureClient client, String vaultName, String secretName) {
        return client.getSecret(vaultName, secretName)
                     .mapError(cause -> CloudCertificateProviderError.certificateFetchFailed(vaultName
                                                                                            + "/" + secretName,
                                                                                             new RuntimeException(cause.message())));
    }

    private static AzureCertificateProvider assembleProvider(String caCert,
                                                             String caKey,
                                                             String gossipKeyHex,
                                                             String gossipKeyIdStr) {
        var caCertBytes = caCert.getBytes(StandardCharsets.UTF_8);
        var caKeyBytes = caKey.getBytes(StandardCharsets.UTF_8);
        var gossipKey = parseGossipKey(gossipKeyHex, gossipKeyIdStr);

        return new AzureCertificateProvider(caCertBytes, caKeyBytes, gossipKey);
    }

    private static GossipKey parseGossipKey(String gossipKeyHex, String gossipKeyIdStr) {
        var keyBytes = hexToBytes(gossipKeyHex.trim());
        var keyId = Integer.parseInt(gossipKeyIdStr.trim());

        return GossipKey.gossipKey(keyBytes, keyId, Instant.now());
    }

    @Override
    public Result<CertificateBundle> issueCertificate(String nodeId, String hostname) {
        return success(CertificateBundle.certificateBundle(caCertPem,
                                                           caKeyPem,
                                                           caCertPem,
                                                           Instant.now().plusSeconds(7 * 24 * 3600)));
    }

    @Override
    public Result<CertificateBundle> caCertificate() {
        return success(CertificateBundle.certificateBundle(caCertPem,
                                                           new byte[0],
                                                           caCertPem,
                                                           Instant.now().plusSeconds(365 * 24 * 3600)));
    }

    @Override
    public Result<GossipKey> currentGossipKey() {
        return success(currentGossipKey);
    }

    @Override
    public Option<GossipKey> previousGossipKey() {
        return none();
    }

    private static Result<VaultAndPrefix> splitVaultAndPrefix(String path) {
        var slashIndex = path.indexOf('/');

        if (slashIndex <= 0 || slashIndex >= path.length() - 1) {
            return CloudCertificateProviderError.invalidCertificateMaterial("Secret prefix must be in format: vaultName/secretPrefix, got: " + path).result();
        }

        return success(new VaultAndPrefix(path.substring(0, slashIndex), path.substring(slashIndex + 1)));
    }

    private static byte[] hexToBytes(String hex) {
        var len = hex.length();
        var data = new byte[len / 2];

        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte)((Character.digit(hex.charAt(i), 16) << 4) + Character.digit(hex.charAt(i + 1), 16));
        }

        return data;
    }

    private record VaultAndPrefix(String vaultName, String prefix) {}
}
