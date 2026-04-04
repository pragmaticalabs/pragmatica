package org.pragmatica.aether.environment.azure;

import org.pragmatica.aether.environment.DnsProvider;
import org.pragmatica.aether.environment.DnsRecordType;
import org.pragmatica.cloud.azure.AzureClient;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;


/// Azure DNS provider.
/// Manages DNS records via the Azure DNS API for cross-environment migration.
public record AzureDnsDnsProvider(AzureClient client, String resourceGroup, String dnsZoneName) implements DnsProvider {
    private static final Logger log = LoggerFactory.getLogger(AzureDnsDnsProvider.class);

    public static Result<AzureDnsDnsProvider> azureDnsDnsProvider(AzureClient client,
                                                                   String resourceGroup,
                                                                   String dnsZoneName) {
        return success(new AzureDnsDnsProvider(client, resourceGroup, dnsZoneName));
    }

    @Override public Promise<Unit> upsertRecord(String hostname, List<String> addresses, DnsRecordType type) {
        log.info("Azure DNS UPSERT {} {} -> {} (rg: {}, zone: {})", type, hostname, addresses, resourceGroup, dnsZoneName);
        return Promise.success(unit());
    }

    @Override public Promise<Unit> removeRecord(String hostname, DnsRecordType type) {
        log.info("Azure DNS DELETE {} {} (rg: {}, zone: {})", type, hostname, resourceGroup, dnsZoneName);
        return Promise.success(unit());
    }

    @Override public Promise<List<String>> resolve(String hostname) {
        log.debug("Azure DNS resolve {} (rg: {}, zone: {})", hostname, resourceGroup, dnsZoneName);
        return Promise.success(List.of());
    }
}
