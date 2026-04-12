package org.pragmatica.aether.environment;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.pragmatica.aether.environment.NoopFloatingIpProvider.noopFloatingIpProvider;


class NoopFloatingIpProviderTest {
    private final NoopFloatingIpProvider provider = noopFloatingIpProvider();

    @Nested
    class HappyPath {
        @Test
        void attach_anyInput_succeeds() {
            provider.attach("10.0.0.1", "node-1").await()
                    .onFailureRun(Assertions::fail);
        }

        @Test
        void verify_anyInput_returnsOwnedByLocalhost() {
            provider.verify("10.0.0.1").await()
                    .onFailureRun(Assertions::fail)
                    .onSuccess(ownership -> {
                        assertTrue(ownership.ownedByAccount());
                        assertEquals("localhost", ownership.currentAttachment());
                    });
        }

        @Test
        void compatibleZones_anyInput_returnsLocalZone() {
            provider.compatibleZones("10.0.0.1").await()
                    .onFailureRun(Assertions::fail)
                    .onSuccess(zones -> {
                        assertEquals(1, zones.size());
                        assertTrue(zones.contains("local"));
                    });
        }
    }
}
