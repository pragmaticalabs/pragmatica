package org.pragmatica.aether.config.cluster;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.Arrays;

/// Supported deployment provider types.
public enum DeploymentType {
    HETZNER("hetzner"),
    AWS("aws"),
    GCP("gcp"),
    AZURE("azure"),
    KUBERNETES("kubernetes"),
    ON_PREMISES("on-premises"),
    EMBEDDED("embedded"),
    DOCKER("docker");
    private static final Cause INVALID_TYPE = Causes.cause("Invalid deployment type");
    private final String value;
    DeploymentType(String value) {
        this.value = value;
    }
    public String value() {
        return value;
    }
    /// Parse a deployment type from its string representation.
    public static Result<DeploymentType> deploymentType(String raw) {
        return Arrays.stream(values()).filter(dt -> dt.value.equals(raw))
                            .findFirst()
                            .map(Result::success)
                            .orElseGet(INVALID_TYPE::result);
    }
}
