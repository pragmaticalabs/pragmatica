package org.pragmatica.aether.deployment.migration;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


/// Request to migrate a cluster across cloud environments.
///
/// Specifies the target provider, zone/region, migration strategy, and DNS hostname to update.
public record MigrationRequest(String targetProvider,
                                String targetZone,
                                MigrationStrategy strategy,
                                Option<String> dnsHostname) {
    public static Result<MigrationRequest> migrationRequest(String targetProvider,
                                                             String targetZone,
                                                             MigrationStrategy strategy,
                                                             String dnsHostname) {
        return success(new MigrationRequest(targetProvider,
                                             targetZone,
                                             strategy,
                                             option(dnsHostname).filter(s -> !s.isEmpty())));
    }
}
