package org.pragmatica.http.server.impl;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.pragmatica.http.server.HttpServerError;
import org.pragmatica.http.server.TlsConfig;
import org.pragmatica.lang.Result;

/**
 * Factory for creating Netty SSL contexts from TLS configuration.
 */
public final class TlsContextFactory {

    private TlsContextFactory() {}

    /**
     * Create SSL context from TLS configuration.
     *
     * @param config TLS configuration
     * @return SSL context or error
     */
    public static Result<SslContext> create(TlsConfig config) {
        return switch (config) {
            case TlsConfig.SelfSigned() -> createSelfSigned();
            case TlsConfig.FromFiles fromFiles -> createFromFiles(fromFiles);
        };
    }

    private static Result<SslContext> createSelfSigned() {
        try {
            var ssc = new SelfSignedCertificate();
            var sslContext = SslContextBuilder
                    .forServer(ssc.certificate(), ssc.privateKey())
                    .build();
            return Result.success(sslContext);
        } catch (Exception e) {
            return new HttpServerError.SelfSignedGenerationFailed(e).result();
        }
    }

    private static Result<SslContext> createFromFiles(TlsConfig.FromFiles config) {
        try {
            var password = config.keyPassword().fold(() -> null, pwd -> pwd);
            var builder = SslContextBuilder.forServer(
                    config.certificatePath().toFile(),
                    config.privateKeyPath().toFile(),
                    password
            );
            return Result.success(builder.build());
        } catch (Exception e) {
            return new HttpServerError.CertificateLoadFailed(config.certificatePath(), e).result();
        }
    }
}
