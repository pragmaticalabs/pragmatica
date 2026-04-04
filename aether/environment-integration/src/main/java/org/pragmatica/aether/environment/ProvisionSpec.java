package org.pragmatica.aether.environment;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.Map;

import static org.pragmatica.lang.Result.success;


/// Detailed provisioning specification for compute instances.
/// Extends the basic InstanceType-based provisioning with pool, size, tags, and optional image/userData/placement.
public record ProvisionSpec(InstanceType instanceType,
                            String instanceSize,
                            String pool,
                            Map<String, String> tags,
                            Option<String> imageId,
                            Option<String> userData,
                            Option<PlacementHint> placement) {
    public static Result<ProvisionSpec> provisionSpec(InstanceType instanceType,
                                                      String instanceSize,
                                                      String pool,
                                                      Map<String, String> tags) {
        return success(new ProvisionSpec(instanceType,
                                         instanceSize,
                                         pool,
                                         Map.copyOf(tags),
                                         Option.empty(),
                                         Option.empty(),
                                         Option.empty()));
    }

    @SuppressWarnings("JBCT-VO-02") public ProvisionSpec withImage(String imageId) {
        return new ProvisionSpec(instanceType, instanceSize, pool, tags, Option.some(imageId), userData, placement);
    }

    @SuppressWarnings("JBCT-VO-02") public ProvisionSpec withUserData(String userData) {
        return new ProvisionSpec(instanceType, instanceSize, pool, tags, imageId, Option.some(userData), placement);
    }

    @SuppressWarnings("JBCT-VO-02") public ProvisionSpec withPlacement(PlacementHint placement) {
        return new ProvisionSpec(instanceType, instanceSize, pool, tags, imageId, userData, Option.some(placement));
    }
}
