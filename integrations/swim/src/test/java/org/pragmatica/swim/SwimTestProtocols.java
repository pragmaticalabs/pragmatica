/*
 *  Copyright (c) 2020-2026 Sergiy Yevtushenko.
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
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Result;

/// The one place a test builds a [SwimProtocol] (#3568, the class of #3560): the executor is chosen at construction,
/// so no entry point needs a waiting wrapper in the test that calls it.
///
/// [#swimProtocol] builds the protocol on a quiescing executor ([SerialExecutor#quiescingSerialExecutor]): every call
/// into the protocol from outside a task, an entry point, `onMessage`, a listener path such as `BootTokens.admit`
/// reaching `onRetired`, or a test seam, returns only after the executor has drained, follow-ups included, even while
/// the probe tick is the drainer. A test may assert on the next line, for every entry point, present and future.
/// [#swimProtocolWithHeldExecutor] is the only opt-out: the production executor, for a test that holds the executor on
/// purpose and so needs a bare call to return while its write is still queued. `SwimDeliveryGuardTest` fails the build
/// when a test file other than this one constructs a protocol.
///
/// The mirrored factory shapes are the production overloads; a test that starts a protocol calls `start()` on the
/// result, which cannot bypass the executor chosen here.
public final class SwimTestProtocols {
    private SwimTestProtocols() {}

    public static Result<SwimProtocol> swimProtocol(SwimConfig config,
                                                    SwimTransport transport,
                                                    SwimMembershipListener listener,
                                                    NodeId selfId,
                                                    InetSocketAddress selfAddress) {
        return swimProtocol(config, transport, listener, selfId, selfAddress, () -> true);
    }

    public static Result<SwimProtocol> swimProtocol(SwimConfig config,
                                                    SwimTransport transport,
                                                    SwimMembershipListener listener,
                                                    NodeId selfId,
                                                    InetSocketAddress selfAddress,
                                                    BooleanSupplier isBooting) {
        return swimProtocol(config, transport, listener, selfId, selfAddress, isBooting, id -> false);
    }

    public static Result<SwimProtocol> swimProtocol(SwimConfig config,
                                                    SwimTransport transport,
                                                    SwimMembershipListener listener,
                                                    NodeId selfId,
                                                    InetSocketAddress selfAddress,
                                                    BooleanSupplier isBooting,
                                                    Predicate<NodeId> transportConnected) {
        return Result.success(new SwimProtocol(config,
                                               transport,
                                               listener,
                                               selfId,
                                               selfAddress,
                                               isBooting,
                                               transportConnected,
                                               SerialExecutor.quiescingSerialExecutor()));
    }

    /// The opt-out: the production (non-waiting) executor, for tests that hold the executor on purpose.
    static Result<SwimProtocol> swimProtocolWithHeldExecutor(SwimConfig config,
                                                             SwimTransport transport,
                                                             SwimMembershipListener listener,
                                                             NodeId selfId,
                                                             InetSocketAddress selfAddress,
                                                             BooleanSupplier isBooting,
                                                             Predicate<NodeId> transportConnected) {
        return Result.success(new SwimProtocol(config,
                                               transport,
                                               listener,
                                               selfId,
                                               selfAddress,
                                               isBooting,
                                               transportConnected,
                                               SerialExecutor.serialExecutor()));
    }
}
