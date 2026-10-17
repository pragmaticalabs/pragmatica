// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.testkit.deployedconfig;

import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;


/// A slice whose ONLY dependency is a configuration section — the artifact #889 says cannot be
/// created in a deployed cluster.
///
/// It has no resource dependencies on purpose. If creation fails, the config chain is the only
/// thing that can have failed, so a red test names the defect instead of implicating provisioning.
@Slice
public interface EndpointProbe {
    Promise<String> describe(String probe);

    static EndpointProbe endpointProbe(@EndpointSettings EndpointConfig config) {
        record endpointProbe(EndpointConfig config) implements EndpointProbe {
            @Override
            public Promise<String> describe(String probe) {
                return Promise.success(probe + "=" + config.render());
            }
        }

        return new endpointProbe(config);
    }
}
