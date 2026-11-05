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

import java.util.function.BiConsumer;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Promise;


/// Test [DHTNetwork] that hands every message to a sink and reports it accepted ([WriteOutcome.Sent]): the answer
/// the removed outcome default gave for a sink-only network (#2583 P1).
public sealed interface AcceptingNetwork {
    static DHTNetwork accepting(BiConsumer<NodeId, ProtocolMessage> sink) {
        return (target, message) -> accept(sink, target, message);
    }

    private static Promise<WriteOutcome> accept(BiConsumer<NodeId, ProtocolMessage> sink,
                                                NodeId target,
                                                ProtocolMessage message) {
        sink.accept(target, message);

        return Promise.success(new WriteOutcome.Sent(target));
    }

    record unused() implements AcceptingNetwork {}
}
