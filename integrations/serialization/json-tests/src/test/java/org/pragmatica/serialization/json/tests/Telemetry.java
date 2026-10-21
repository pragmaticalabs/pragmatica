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
package org.pragmatica.serialization.json.tests;

import org.pragmatica.serialization.json.Json;

import java.util.List;

/// SYNTHETIC payload: none of the examples carries doubles. Exists only to exercise double parsing.
public interface Telemetry {
    record Sample(String metric, long timestamp, double value, double rate, double ratio) {}

    @Json
    record Series(String source, List<Sample> samples) {}
}
