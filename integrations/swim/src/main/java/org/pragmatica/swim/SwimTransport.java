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
package org.pragmatica.swim;

import java.net.InetSocketAddress;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// Transport abstraction for sending and receiving SWIM protocol messages over UDP.
public interface SwimTransport {
    /// Send a message to the given target address.
    Promise<Unit> send(InetSocketAddress target, SwimMessage message);
    /// Start listening for incoming messages on the given port.
    Promise<Unit> start(int port, SwimMessageHandler handler);

    /// Test-only: toggle silent-death fault injection on the SWIM UDP plane.
    /// When enabled, outbound sends are dropped and inbound datagrams are discarded
    /// before dispatch. Default no-op; overridden by transports that support it.
    @Contract
    default void blackhole(boolean enabled) {}

    /// Stop the transport and release resources.
    Promise<Unit> stop();

    /// Handler for incoming SWIM messages.
    interface SwimMessageHandler {
        /// Called when a message is received from the given sender address.
        @Contract
        void onMessage(InetSocketAddress sender, SwimMessage message);
    }
}
