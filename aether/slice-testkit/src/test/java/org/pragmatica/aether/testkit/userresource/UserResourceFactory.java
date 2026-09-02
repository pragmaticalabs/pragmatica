// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.testkit.userresource;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.lang.Promise;

/// A user-supplied [ResourceFactory] for a user-supplied resource type (#773).
///
/// Its `META-INF/services` descriptor is written ONLY into the throw-away slice jar, never onto the
/// test classpath, so the node-boot `ServiceLoader` scan (which runs against the TCCL) cannot see
/// it — which is precisely the production shape this fixture reproduces.
///
/// The config type is [String] deliberately: it is a platform class, so it resolves through the
/// slice loader's parent and no second class has to be packaged to make config loading work.
public final class UserResourceFactory implements ResourceFactory<UserResource, String> {
    @Override
    public Class<UserResource> resourceType() {
        return UserResource.class;
    }

    @Override
    public Class<String> configType() {
        return String.class;
    }

    @Override
    public Promise<UserResource> provision(String config) {
        return Promise.success(new UserResource(config));
    }
}
