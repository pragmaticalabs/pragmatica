// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

public record PortMapping(int cluster, int management, int appHttp, int swim) {
    public static PortMapping portMapping(int cluster, int management, int appHttp, int swim) {
        return new PortMapping(cluster, management, appHttp, swim);
    }

    public static PortMapping defaultPortMapping() {
        return new PortMapping(8090, 8080, 8070, 8190);
    }
}
