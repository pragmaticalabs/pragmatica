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
package org.pragmatica.http.server;

import org.pragmatica.http.HttpRequest;


/// Decides what to do with a request from its HEADERS alone, before any body byte is read, on HTTP/1.1 and HTTP/3
/// alike. The request it receives carries an empty body and no trailers.
///
/// This is where authentication happens ahead of the body: a classifier that answers [Disposition#reject] for an
/// unauthenticated request means its body is never read. It runs on the connection's event loop, so it must not block.
@FunctionalInterface
public interface RequestClassifier {
    Disposition classify(HttpRequest head);

    /// Aggregate every request up to `maxBytes` under the listener's general bounds.
    static RequestClassifier aggregateAll(long maxBytes) {
        return _ -> Disposition.aggregate(maxBytes);
    }
}
