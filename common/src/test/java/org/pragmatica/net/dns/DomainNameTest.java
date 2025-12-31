package org.pragmatica.net.dns;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.net.dns.DomainName.domainName;

class DomainNameTest {

    @Test
    void domainName_creates_instance_with_provided_name() {
        var domain = domainName("example.com");

        assertThat(domain.name()).isEqualTo("example.com");
    }

    @Test
    void domainName_preserves_case() {
        var domain = domainName("Example.COM");

        assertThat(domain.name()).isEqualTo("Example.COM");
    }

    @Test
    void domainName_equality_based_on_name() {
        var domain1 = domainName("example.com");
        var domain2 = domainName("example.com");
        var domain3 = domainName("other.com");

        assertThat(domain1).isEqualTo(domain2);
        assertThat(domain1).isNotEqualTo(domain3);
    }

    @Test
    void domainName_hashCode_consistent_with_equality() {
        var domain1 = domainName("example.com");
        var domain2 = domainName("example.com");

        assertThat(domain1.hashCode()).isEqualTo(domain2.hashCode());
    }
}
