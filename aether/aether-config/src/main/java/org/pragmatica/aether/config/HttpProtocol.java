package org.pragmatica.aether.config;

import org.pragmatica.lang.Option;


/// HTTP protocol mode for the application HTTP server.
///
/// Controls which transport protocols are active:
/// - H1: HTTP/1.1 only (default, backward compatible)
/// - H3: HTTP/3 over QUIC only (requires TLS)
/// - BOTH: dual-stack HTTP/1.1 + HTTP/3 (requires TLS)
public enum HttpProtocol {
    H1,
    H3,
    BOTH;
    public static Option<HttpProtocol> httpProtocol(String value) {
        return Option.option(value).map(String::trim)
                            .map(String::toLowerCase)
                            .flatMap(HttpProtocol::fromNormalized);
    }
    public boolean includesH1() {
        return this == H1 || this == BOTH;
    }
    public boolean includesH3() {
        return this == H3 || this == BOTH;
    }
    public boolean requiresTls() {
        return this == H3 || this == BOTH;
    }
    private static Option<HttpProtocol> fromNormalized(String normalized) {
        return switch (normalized){
            case "h1", "http1", "http/1.1" -> Option.some(H1);
            case "h3", "http3", "http/3" -> Option.some(H3);
            case "both", "dual" -> Option.some(BOTH);
            default -> Option.empty();
        };
    }
}
