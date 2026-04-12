package org.pragmatica.aether.config.cluster;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.Arrays;


/// Networking configuration types. §6.1
public enum NetworkingType {
    MANUAL("manual");
    private static final Cause INVALID_TYPE = Causes.cause("Invalid networking type: must be 'manual'");
    private final String value;
    NetworkingType(String value) {
        this.value = value;
    }
    public String value() {
        return value;
    }
    public static Result<NetworkingType> networkingType(String raw) {
        return Arrays.stream(values()).filter(nt -> nt.value.equals(raw))
                            .findFirst()
                            .map(Result::success)
                            .orElseGet(INVALID_TYPE::result);
    }
}
