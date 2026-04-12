package org.pragmatica.aether.config.cluster;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.Arrays;


/// Node source types. §5.1.1
public enum SourceType {
    CLOUD("cloud"),
    SSH("ssh"),
    FORGE("forge"),
    DOCKER("docker");
    private static final Cause INVALID_TYPE = Causes.cause("Invalid source type: must be 'cloud', 'ssh', 'forge', or 'docker'");
    private final String value;
    SourceType(String value) {
        this.value = value;
    }
    public String value() {
        return value;
    }
    public static Result<SourceType> sourceType(String raw) {
        return Arrays.stream(values()).filter(st -> st.value.equals(raw))
                            .findFirst()
                            .map(Result::success)
                            .orElseGet(INVALID_TYPE::result);
    }
}
