// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


public record ProvisionSpec(InstanceType instanceType,
                            String instanceSize,
                            String pool,
                            ProvisionContext context,
                            Option<String> imageId,
                            Option<String> userData,
                            Option<PlacementHint> placement) {
    public static Result<ProvisionSpec> provisionSpec(InstanceType instanceType,
                                                      String instanceSize,
                                                      String pool,
                                                      ProvisionContext context) {
        return success(new ProvisionSpec(instanceType,
                                         instanceSize,
                                         pool,
                                         context,
                                         Option.empty(),
                                         Option.empty(),
                                         Option.empty()));
    }

    public ProvisionSpec withImage(String imageId) {
        return new ProvisionSpec(instanceType, instanceSize, pool, context, Option.some(imageId), userData, placement);
    }

    public ProvisionSpec withUserData(String userData) {
        return new ProvisionSpec(instanceType, instanceSize, pool, context, imageId, Option.some(userData), placement);
    }

    public ProvisionSpec withPlacement(PlacementHint placement) {
        return new ProvisionSpec(instanceType, instanceSize, pool, context, imageId, userData, Option.some(placement));
    }
}
