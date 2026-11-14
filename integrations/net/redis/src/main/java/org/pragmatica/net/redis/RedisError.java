/*
 *  Copyright (c) 2022-2025 Sergiy Yevtushenko.
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
package org.pragmatica.net.redis;

import java.util.Set;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.utils.Causes;


/// Redis client failures.
///
/// Classification answers one question: can the same command succeed if it is issued again?
/// Transport failures and a server that is busy loading are transient; a malformed frame, a refused
/// credential, a closed client and a server verdict on the command itself are terminal. A transient
/// classification is NOT permission to retry blindly: [ConnectionLost] and [Timeout] leave the
/// outcome of the command unknown (it may have executed), so only the caller can decide whether
/// re-issuing it is safe. The client itself never retries.
public sealed interface RedisError extends Cause {
    /// The connection could not be established, or the handshake (AUTH, SELECT) was not completed
    /// before the first command. No user command of that connection was sent.
    @SuppressWarnings("JBCT-CAUSE-04")
    record ConnectionFailed(Cause origin, String message) implements RedisError, Cause.Wrapped {
        static final Fn1<ConnectionFailed, Cause> FACTORY = Causes.forOneValue("Redis connection failed",
                                                                               ConnectionFailed::new);

        public static ConnectionFailed connectionFailed(Cause origin) {
            return FACTORY.apply(origin);
        }

        @Override
        public boolean isTransient() {
            return true;
        }
    }

    /// The connection ended while this command was in flight. The command may or may not have been
    /// executed by the server; the client does not retry it.
    @SuppressWarnings("JBCT-CAUSE-04")
    record ConnectionLost(Cause origin, String message) implements RedisError, Cause.Wrapped {
        static final Fn1<ConnectionLost, Cause> FACTORY = Causes.forOneValue("Redis connection lost with the command in flight",
                                                                             ConnectionLost::new);

        public static ConnectionLost connectionLost(Cause origin) {
            return FACTORY.apply(origin);
        }

        @Override
        public boolean isTransient() {
            return true;
        }
    }

    /// No reply within the command timeout. The connection is closed (FIFO correlation cannot skip
    /// a reply), and the command may or may not have been executed.
    record Timeout(long millis, String message) implements RedisError, Cause.Transient {
        static final Fn1<Timeout, Long> FACTORY = Causes.forOneValue("Redis command timed out after %s ms", Timeout::new);

        public static Timeout timeout(long millis) {
            return FACTORY.apply(millis);
        }
    }

    /// The pool has no connection that can take another command: every connection is at its in-flight
    /// limit. The command was not sent.
    record PoolSaturated(int maxInFlight, String message) implements RedisError, Cause.Transient {
        static final Fn1<PoolSaturated, Integer> FACTORY = Causes.forOneValue("Redis pool saturated: every connection has %s commands in flight",
                                                                              PoolSaturated::new);

        public static PoolSaturated poolSaturated(int maxInFlight) {
            return FACTORY.apply(maxInFlight);
        }
    }

    /// The server answered with an error reply (`-PREFIX detail`). Transient only for the prefixes
    /// the server documents as passing conditions; the rest are verdicts on the command.
    record ServerError(String prefix, String detail, String message) implements RedisError {
        private static final Set<String> TRANSIENT_PREFIXES = Set.of("LOADING",
                                                                     "BUSY",
                                                                     "TRYAGAIN",
                                                                     "CLUSTERDOWN",
                                                                     "MASTERDOWN");

        static final Fn2<ServerError, String, String> FACTORY = Causes.forTwoValues("Redis error %s: %s",
                                                                                    ServerError::new);

        public static ServerError serverError(String prefix, String detail) {
            return FACTORY.apply(prefix, detail);
        }

        @Override
        public boolean isTransient() {
            return TRANSIENT_PREFIXES.contains(prefix);
        }
    }

    /// AUTH was refused by the server. Terminal: the same credential is refused again.
    record AuthFailed(String detail, String message) implements RedisError, Cause.Terminal {
        static final Fn1<AuthFailed, String> FACTORY = Causes.forOneValue("Redis AUTH refused: %s", AuthFailed::new);

        public static AuthFailed authFailed(String detail) {
            return FACTORY.apply(detail);
        }
    }

    /// The server sent bytes that are not RESP2, or a frame beyond the decoder limits. The
    /// connection is closed: the stream position is lost.
    record ProtocolViolation(String detail, String message) implements RedisError, Cause.Terminal {
        static final Fn1<ProtocolViolation, String> FACTORY = Causes.forOneValue("Redis protocol violation: %s",
                                                                                 ProtocolViolation::new);

        public static ProtocolViolation protocolViolation(String detail) {
            return FACTORY.apply(detail);
        }
    }

    /// A well-formed reply of a type the command does not produce (for example an array for INCR).
    record UnexpectedReply(String expected, String actual, String message) implements RedisError, Cause.Terminal {
        static final Fn2<UnexpectedReply, String, String> FACTORY = Causes.forTwoValues("Unexpected Redis reply: expected %s, got %s",
                                                                                        UnexpectedReply::new);

        public static UnexpectedReply unexpectedReply(String expected, String actual) {
            return FACTORY.apply(expected, actual);
        }
    }

    /// The command was rejected before it was sent (empty key list, empty script).
    record InvalidArgument(String detail, String message) implements RedisError, Cause.Terminal {
        static final Fn1<InvalidArgument, String> FACTORY = Causes.forOneValue("Invalid Redis command argument: %s",
                                                                               InvalidArgument::new);

        public static InvalidArgument invalidArgument(String detail) {
            return FACTORY.apply(detail);
        }
    }

    /// The TLS context could not be built: configuration, not a server verdict.
    record TlsSetupFailed(String detail, String message) implements RedisError, Cause.Terminal {
        static final Fn1<TlsSetupFailed, String> FACTORY = Causes.forOneValue("Redis TLS setup failed: %s",
                                                                              TlsSetupFailed::new);

        public static TlsSetupFailed tlsSetupFailed(String detail) {
            return FACTORY.apply(detail);
        }
    }

    /// Fixed-text failures.
    enum General implements RedisError {
        /// The client or connection was closed by the caller; the command was not sent, or was in
        /// flight when the caller closed it.
        CLOSED("Redis client is closed") {
            @Override
            public boolean isTerminal() {
                return true;
            }
        };
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }
}
