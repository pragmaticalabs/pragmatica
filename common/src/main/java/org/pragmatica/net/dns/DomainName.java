package org.pragmatica.net.dns;
/**
 * Domain name to resolve.
 */
public record DomainName(String name) {
    public static DomainName domainName(String domainName) {
        return new DomainName(domainName);
    }
}
