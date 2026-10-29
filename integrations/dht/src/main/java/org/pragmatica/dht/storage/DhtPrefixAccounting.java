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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.lang.Contract;

/// The per-prefix byte accounting of a DHT engine (#2258, spec 3.2 item 7): the registered table of prefixes, the cap of the
/// bounded one and the listener its transitions go to.
public final class DhtPrefixAccounting {
    /// The prefix of the streams instance's blocks: the only bounded one.
    public static final String STREAM_SEGMENTS = "stream-segments";
    /// The prefix of the artifact repository's blocks.
    public static final String ARTIFACTS = "artifacts";
    /// The bucket of every key that matches no registered prefix.
    public static final String OTHER = "other";
    /// A prefix with no cap.
    public static final long UNBOUNDED = Long.MAX_VALUE;

    private final long streamSegmentsMaxBytes;
    private final List<String> registered = new CopyOnWriteArrayList<>(List.of(STREAM_SEGMENTS, ARTIFACTS));
    private final AtomicReference<DhtCapacityListener> listener = new AtomicReference<>(DhtCapacityListener.NONE);

    private DhtPrefixAccounting(long streamSegmentsMaxBytes) {
        this.streamSegmentsMaxBytes = streamSegmentsMaxBytes;
    }

    /// Accounting with no cap on any prefix.
    public static DhtPrefixAccounting dhtPrefixAccounting() {
        return new DhtPrefixAccounting(UNBOUNDED);
    }

    /// Accounting that caps the live bytes of [#STREAM_SEGMENTS] at `streamSegmentsMaxBytes`.
    public static DhtPrefixAccounting dhtPrefixAccounting(long streamSegmentsMaxBytes) {
        return new DhtPrefixAccounting(streamSegmentsMaxBytes);
    }

    /// Adds `prefix` to the registered table; a prefix already registered stays as it is.
    @Contract
    public void register(String prefix) {
        if (!registered.contains(prefix)) {
            registered.add(prefix);
        }
    }

    /// Where the transitions of the bounded prefix go from now on.
    @Contract
    public void listener(DhtCapacityListener listener) {
        this.listener.set(listener);
    }

    /// The registered prefixes and [#OTHER]: the labels of the gauges, bounded by the table.
    public List<String> prefixes() {
        var all = new ArrayList<>(registered);

        all.add(OTHER);

        return List.copyOf(all);
    }
}
