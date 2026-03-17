package org.pragmatica.email.http;

import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class HttpEmailSenderTest {
    @Test
    void serviceLoader_discoversAllFourVendorMappings() {
        var mappings = ServiceLoader.load(VendorMapping.class).stream().toList();

        assertThat(mappings).hasSize(4);
    }

    @Test
    void serviceLoader_discoversSpecificVendors() {
        var vendorIds = ServiceLoader.load(VendorMapping.class)
                                     .stream()
                                     .map(ServiceLoader.Provider::get)
                                     .map(VendorMapping::vendorId)
                                     .toList();

        assertThat(vendorIds).containsExactlyInAnyOrder("sendgrid", "mailgun", "postmark", "resend");
    }
}
