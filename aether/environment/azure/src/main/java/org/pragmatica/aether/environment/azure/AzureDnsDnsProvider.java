// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment.azure;

import org.pragmatica.aether.environment.DnsProvider;
import org.pragmatica.aether.environment.DnsRecordType;
import org.pragmatica.cloud.azure.AzureClient;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;


public record AzureDnsDnsProvider(AzureClient client, String resourceGroup, String dnsZoneName) implements DnsProvider {
    private static final Logger log = LoggerFactory.getLogger(AzureDnsDnsProvider.class);

    public static Result<AzureDnsDnsProvider> azureDnsDnsProvider(AzureClient client,
                                                                  String resourceGroup,
                                                                  String dnsZoneName) {
        return success(new AzureDnsDnsProvider(client, resourceGroup, dnsZoneName));
    }

    @Override
    public Promise<Unit> upsertRecord(String hostname, List<String> addresses, DnsRecordType type) {
        log.info("Azure DNS UPSERT {} {} -> {} (rg: {}, zone: {})",
                 type,
                 hostname,
                 addresses,
                 resourceGroup,
                 dnsZoneName);

        return Promise.success(unit());
    }

    @Override
    public Promise<Unit> removeRecord(String hostname, DnsRecordType type) {
        log.info("Azure DNS DELETE {} {} (rg: {}, zone: {})", type, hostname, resourceGroup, dnsZoneName);

        return Promise.success(unit());
    }

    @Override
    public Promise<List<String>> resolve(String hostname) {
        log.debug("Azure DNS resolve {} (rg: {}, zone: {})", hostname, resourceGroup, dnsZoneName);

        return Promise.success(List.of());
    }
}
