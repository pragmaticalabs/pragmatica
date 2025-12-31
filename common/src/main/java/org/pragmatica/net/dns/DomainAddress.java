package org.pragmatica.net.dns;

import java.net.InetAddress;
import java.time.Duration;

/**
 * Resolved domain address with TTL.
 */
public interface DomainAddress {
    DomainName name();

    InetAddress ip();

    Duration ttl();

    static DomainAddress domainAddress(DomainName name, InetAddress ip, Duration ttl) {
        record domainAddress(DomainName name, InetAddress ip, Duration ttl) implements DomainAddress {}
        return new domainAddress(name, ip, ttl);
    }

    default DomainAddress withDomain(DomainName domainName) {
        return domainAddress(domainName, ip(), ttl());
    }
}
