package org.pragmatica.aether.environment;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;


/// SPI for DNS record management during cross-environment migration.
/// Implementations handle cloud-specific DNS APIs (Route53, Cloud DNS, Azure DNS).
public interface DnsProvider {
    Promise<Unit> upsertRecord(String hostname, List<String> addresses, DnsRecordType type);
    Promise<Unit> removeRecord(String hostname, DnsRecordType type);
    Promise<List<String>> resolve(String hostname);
}
