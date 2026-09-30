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
package org.pragmatica.dht;

import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Per-read options for [DHTClient#get(byte[], ReadOptions)].
///
/// `absentGrace` bounds how long a read that already holds R empty answers waits for the remaining
/// original R-set replica(s) before it reports ABSENT. The DEFAULT is zero, which is exactly the
/// quorum rule (absent as soon as R replicas answer empty), so a caller that does not opt in sees no
/// change. A positive grace lets a late replica that holds the value win, and so resurrects a value
/// removed at W quorum from a replica that missed the remove (the DHT keeps no tombstones): opt in only
/// for write-once data.
///
/// @param absentGrace maximum extra wait after R empty answers; zero or negative disables the grace
public record ReadOptions(TimeSpan absentGrace) {
    /// No grace: today's absent-on-R-empties.
    public static final ReadOptions DEFAULT = new ReadOptions(timeSpan(0).nanos());

    /// Read options with the given absent grace window.
    public static ReadOptions absentGrace(TimeSpan grace) {
        return new ReadOptions(grace);
    }

    /// Whether a positive grace window was requested.
    public boolean hasAbsentGrace() {
        return absentGrace.nanos() > 0;
    }
}
