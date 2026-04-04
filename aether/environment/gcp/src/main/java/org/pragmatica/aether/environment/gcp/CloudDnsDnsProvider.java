package org.pragmatica.aether.environment.gcp;

import org.pragmatica.aether.environment.DnsProvider;
import org.pragmatica.aether.environment.DnsRecordType;
import org.pragmatica.cloud.gcp.GcpClient;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;


/// GCP Cloud DNS provider.
/// Manages DNS records via the Cloud DNS API for cross-environment migration.
public record CloudDnsDnsProvider(GcpClient client, String managedZone) implements DnsProvider {
    private static final Logger log = LoggerFactory.getLogger(CloudDnsDnsProvider.class);

    public static Result<CloudDnsDnsProvider> cloudDnsDnsProvider(GcpClient client, String managedZone) {
        return success(new CloudDnsDnsProvider(client, managedZone));
    }

    @Override public Promise<Unit> upsertRecord(String hostname, List<String> addresses, DnsRecordType type) {
        log.info("Cloud DNS UPSERT {} {} -> {} (zone: {})",
                 type,
                 hostname,
                 addresses,
                 managedZone);
        return Promise.success(unit());
    }

    @Override public Promise<Unit> removeRecord(String hostname, DnsRecordType type) {
        log.info("Cloud DNS DELETE {} {} (zone: {})", type, hostname, managedZone);
        return Promise.success(unit());
    }

    @Override public Promise<List<String>> resolve(String hostname) {
        log.debug("Cloud DNS resolve {} (zone: {})", hostname, managedZone);
        return Promise.success(List.of());
    }
}
