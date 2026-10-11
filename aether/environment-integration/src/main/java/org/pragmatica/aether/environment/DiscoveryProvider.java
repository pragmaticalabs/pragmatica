// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment;

import java.util.List;
import java.util.function.Consumer;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


public interface DiscoveryProvider {
    Promise<List<PeerInfo>> discoverPeers();
    Promise<Unit> watchPeers(Consumer<List<PeerInfo>> onChange);
    Promise<Unit> stopWatching();
    Promise<Unit> registerSelf(PeerInfo self);
    Promise<Unit> deregisterSelf();
}
