/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.dht.storage;

import org.pragmatica.lang.Contract;


/// Fence-free [OwnerEpochGate] for non-cluster DHT paths and tests: nothing is ever stale and no
/// high-water is recorded (#345 piece 1c). A stateless singleton — see [OwnerEpochGate#noOp].
enum NoOpOwnerEpochGate implements OwnerEpochGate {
    INSTANCE;
    @Override
    public boolean isStale(byte[] key, long epochTerm, long epochCounter) {
        return false;
    }
    @Contract
    @Override
    public void advance(byte[] key, long epochTerm, long epochCounter) {
    // No ownership plane to fence against — nothing to record.
    }
    @Override
    public boolean epochOrderingEnabled() {
        return false;
    }
}
