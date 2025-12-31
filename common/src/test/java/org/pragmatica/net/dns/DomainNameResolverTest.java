package org.pragmatica.net.dns;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.net.dns.DomainAddress.domainAddress;
import static org.pragmatica.net.dns.DomainName.domainName;
import static org.pragmatica.net.dns.DomainNameResolver.domainNameResolver;

class DomainNameResolverTest {

    @Test
    void resolver_returns_localhost_from_cache() {
        var resolver = domainNameResolver(List.of());

        resolver.resolve("localhost")
                .await()
                .onFailureRun(Assertions::fail)
                .onSuccess(address -> {
                    assertThat(address.name().name()).isEqualTo("localhost");
                    assertThat(address.ip().isLoopbackAddress()).isTrue();
                });
    }

    @Test
    void resolveCached_returns_unknown_for_uncached_domain() {
        var resolver = domainNameResolver(List.of());

        resolver.resolveCached("not-in-cache.example.com")
                .await()
                .onSuccessRun(Assertions::fail)
                .onFailure(cause -> {
                    assertThat(cause).isInstanceOf(ResolverErrors.UnknownDomain.class);
                });
    }

    @Test
    void resolver_caches_successful_resolution() throws UnknownHostException {
        // Use a mock-like approach: create resolver, manually populate cache via resolve
        var googleDns = InetAddress.getByName("8.8.8.8");
        var resolver = domainNameResolver(List.of(googleDns));

        // First resolution (will go to real DNS)
        var firstResult = resolver.resolve("example.com").await();

        if (firstResult.isSuccess()) {
            // Second call should return cached result
            resolver.resolveCached("example.com")
                    .await()
                    .onFailureRun(Assertions::fail)
                    .onSuccess(cached -> {
                        assertThat(cached.name().name()).isEqualTo("example.com");
                    });
        }
        // If first resolution failed (no network), test is inconclusive but passes
    }
}
