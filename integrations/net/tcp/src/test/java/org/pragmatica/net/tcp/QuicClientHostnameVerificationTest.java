package org.pragmatica.net.tcp;

import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.handler.codec.quic.QuicSslContext;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins the owner decision of 2026-10-01: QUIC client contexts do NOT match the server name against the certificate.
/// Netty 4.2.11 (#16426) defaults QUIC clients to "HTTPS"; cluster nodes dial by IP and carry no IP SAN, so with the
/// default every cluster handshake fails with CERTIFICATE_VERIFY_FAILED. Removing the opt-out in
/// `QuicSslContextFactory.withoutHostnameVerification` turns both tests red.
class QuicClientHostnameVerificationTest {
    @Test
    void createClient_engineDialedByIp_hasNoEndpointIdentification() {
        assertNoEndpointIdentification(QuicSslContextFactory.createClient(TlsConfig.insecureClient()));
    }

    @Test
    void createInsecureClient_engineDialedByIp_hasNoEndpointIdentification() {
        assertNoEndpointIdentification(QuicSslContextFactory.createInsecureClient());
    }

    private static void assertNoEndpointIdentification(Result<QuicSslContext> result) {
        var context = result.fold(cause -> {
            throw new AssertionError("context build failed: " + cause.message());
        }, c -> c);
        var engine = context.newEngine(UnpooledByteBufAllocator.DEFAULT, "127.0.0.1", 6000);

        assertThat(engine.getSSLParameters().getEndpointIdentificationAlgorithm()).isNull();
    }
}
