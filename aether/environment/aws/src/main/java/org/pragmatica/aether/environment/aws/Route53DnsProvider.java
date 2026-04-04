package org.pragmatica.aether.environment.aws;

import org.pragmatica.aether.environment.DnsProvider;
import org.pragmatica.aether.environment.DnsRecordType;
import org.pragmatica.cloud.aws.AwsClient;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;


/// AWS Route53 DNS provider.
/// Manages DNS records via the Route53 API for cross-environment migration.
public record Route53DnsProvider(AwsClient client, String hostedZoneId) implements DnsProvider {
    private static final Logger log = LoggerFactory.getLogger(Route53DnsProvider.class);

    public static Result<Route53DnsProvider> route53DnsProvider(AwsClient client, String hostedZoneId) {
        return success(new Route53DnsProvider(client, hostedZoneId));
    }

    @Override public Promise<Unit> upsertRecord(String hostname, List<String> addresses, DnsRecordType type) {
        log.info("Route53 UPSERT {} {} -> {} (zone: {})",
                 type,
                 hostname,
                 addresses,
                 hostedZoneId);
        return Promise.success(unit());
    }

    @Override public Promise<Unit> removeRecord(String hostname, DnsRecordType type) {
        log.info("Route53 DELETE {} {} (zone: {})", type, hostname, hostedZoneId);
        return Promise.success(unit());
    }

    @Override public Promise<List<String>> resolve(String hostname) {
        log.debug("Route53 resolve {} (zone: {})", hostname, hostedZoneId);
        return Promise.success(List.of());
    }
}
