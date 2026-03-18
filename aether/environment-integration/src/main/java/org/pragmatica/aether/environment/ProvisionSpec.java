package org.pragmatica.aether.environment;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.Map;

import static org.pragmatica.lang.Result.success;

/// Detailed provisioning specification for compute instances.
/// Extends the basic InstanceType-based provisioning with pool, size, tags, and optional image/userData.
public record ProvisionSpec(InstanceType instanceType,
                            String instanceSize,
                            String pool,
                            Map<String, String> tags,
                            Option<String> imageId,
                            Option<String> userData) {
    public static Result<ProvisionSpec> provisionSpec(InstanceType instanceType,
                                                       String instanceSize,
                                                       String pool,
                                                       Map<String, String> tags) {
        return success(new ProvisionSpec(instanceType, instanceSize, pool,
                                         Map.copyOf(tags), Option.empty(), Option.empty()));
    }

    public ProvisionSpec withImage(String imageId) {
        return new ProvisionSpec(instanceType, instanceSize, pool, tags, Option.some(imageId), userData);
    }

    public ProvisionSpec withUserData(String userData) {
        return new ProvisionSpec(instanceType, instanceSize, pool, tags, imageId, Option.some(userData));
    }
}
