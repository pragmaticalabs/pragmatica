// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.testkit.releaseidentity;

import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.aether.testkit.trackedresource.Tracked;
import org.pragmatica.aether.testkit.trackedresource.TrackedResource;
import org.pragmatica.lang.Promise;


/// A slice whose ONLY dependency is one closeable resource — the smallest artifact that can show
/// whether a slice unload closes what it provisioned (#892).
///
/// It declares no configuration section and no second resource on purpose: if the resource is not
/// closed at unload, the release path is the only thing that can have failed, so a red test names
/// the defect instead of implicating config or provisioning.
///
/// This package, and only this package, is packaged into the fixture's slice jar — which is why
/// [TrackedResource] lives elsewhere. See its header.
@Slice
public interface ReleaseProbe {
    Promise<String> describe(String probe);

    static ReleaseProbe releaseProbe(@Tracked TrackedResource resource) {
        record releaseProbe(TrackedResource resource) implements ReleaseProbe {
            @Override
            public Promise<String> describe(String probe) {
                return Promise.success(probe + "=" + resource.config());
            }
        }

        return new releaseProbe(resource);
    }
}
