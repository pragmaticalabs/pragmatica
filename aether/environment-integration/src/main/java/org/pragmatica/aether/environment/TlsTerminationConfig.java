package org.pragmatica.aether.environment;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;

/// TLS termination configuration for load balancers.
public record TlsTerminationConfig( String certificateId,
                                    Option<String> privateKeyPath,
                                    boolean redirectHttp) {
    public static Result<TlsTerminationConfig> tlsTerminationConfig(String certificateId) {
        return success(new TlsTerminationConfig(certificateId, Option.empty(), true));
    }
}
