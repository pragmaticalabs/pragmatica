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

import java.util.ArrayDeque;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;


/// The order in which the responses of one HTTP/1.1 connection are written (RFC 9112 section 9.3.2: in the order of the
/// requests, whichever handler finishes first).
///
/// A slot is reserved when a request is answered by a writer, on the event loop and so in request order. A finished
/// response is written only when every earlier slot has been written; until then it is held. What a response does after it
/// is written, such as closing the connection for `Connection: close`, is part of its write and so also waits its turn: a
/// later close never overtakes an earlier response. The responses held are bounded by the unanswered requests the codec
/// accepts (its pipeline depth, 128).
///
/// Per connection; completed from any thread.
final class ResponseSequence {
    private static final AttributeKey<ResponseSequence> SEQUENCE = AttributeKey.valueOf("responseSequence");

    private final ArrayDeque<Slot> pending = new ArrayDeque<>();

    static ResponseSequence of(Channel channel) {
        var created = new ResponseSequence();

        return Option.option(channel.attr(SEQUENCE).setIfAbsent(created)).or(created);
    }

    /// The place of the next response, taken in request order.
    synchronized Slot reserve() {
        var slot = new Slot();

        pending.addLast(slot);

        return slot;
    }

    /// Hands over the slot's write; it runs as soon as every earlier slot has run, in order, on the calling thread.
    @Contract
    synchronized void complete(Slot slot, Runnable write) {
        slot.write = write;
        runReady();
    }

    private void runReady() {
        while (!pending.isEmpty() && pending.peekFirst().write != null) {
            pending.pollFirst().write.run();
        }
    }

    static final class Slot {
        private Runnable write;
    }
}
