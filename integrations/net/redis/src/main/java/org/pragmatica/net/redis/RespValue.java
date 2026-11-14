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

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.net.redis.RedisError.UnexpectedReply.unexpectedReply;


/// One decoded RESP2 reply. RESP2 null bulk (`$-1`) and null array (`*-1`) are both [NullReply].
///
/// The typed accessors turn a reply into the value a command promises, failing with
/// [RedisError.UnexpectedReply] when the server sent another type; they never throw.
public sealed interface RespValue {
    /// `+text`
    record SimpleString(String text) implements RespValue {
        public static SimpleString simpleString(String text) {
            return new SimpleString(text);
        }
    }

    /// `-PREFIX detail`: a server error, delivered as a value so pipelined replies stay aligned.
    record ErrorReply(String text) implements RespValue {
        public static ErrorReply errorReply(String text) {
            return new ErrorReply(text);
        }
    }

    /// `:42`
    record IntegerReply(long value) implements RespValue {
        public static IntegerReply integerReply(long value) {
            return new IntegerReply(value);
        }
    }

    /// `$n` followed by n bytes. The array is owned by the reply: callers must not mutate it.
    record BulkString(byte[] bytes) implements RespValue {
        public static BulkString bulkString(byte[] bytes) {
            return new BulkString(bytes);
        }
    }

    /// `*n` followed by n replies.
    record ArrayReply(List<RespValue> items) implements RespValue {
        public static ArrayReply arrayReply(List<RespValue> items) {
            return new ArrayReply(List.copyOf(items));
        }
    }

    /// `$-1` or `*-1`.
    enum NullReply implements RespValue {
        NULL_REPLY
    }

    /// Server error reply split at its first space into `prefix` and `detail`, or none when this is
    /// not an error reply.
    default Option<RedisError.ServerError> asServerError() {
        return this instanceof ErrorReply error
               ? some(serverError(error.text()))
               : none();
    }

    /// `+OK`-style acknowledgement.
    default Result<Unit> asOk() {
        return this instanceof SimpleString simple && "OK".equals(simple.text())
               ? success(unit())
               : unexpectedReply("+OK", describe()).result();
    }

    /// `+PONG` or the echoed argument of PING.
    default Result<Unit> asPong() {
        return this instanceof SimpleString simple && "PONG".equals(simple.text())
               ? success(unit())
               : unexpectedReply("+PONG", describe()).result();
    }

    default Result<Long> asLong() {
        return this instanceof IntegerReply integer
               ? success(integer.value())
               : unexpectedReply("integer", describe()).result();
    }

    /// `:1` as true, `:0` as false.
    default Result<Boolean> asFlag() {
        return asLong().map(value -> value == 1L);
    }

    /// Bulk string as bytes; the null reply is [Option#none()].
    default Result<Option<byte[]>> asOptionalBytes() {
        return switch (this) {
            case BulkString bulk -> success(some(bulk.bytes()));
            case NullReply _ -> success(none());
            default -> unexpectedReply("bulk string or null", describe()).result();
        };
    }

    /// `+OK` as true, the null reply as false (SET with NX/XX that was not applied).
    default Result<Boolean> asAppliedFlag() {
        return switch (this) {
            case NullReply _ -> success(false);
            default -> asOk().map(_ -> true);
        };
    }

    /// Array of optional bulk strings (MGET).
    default Result<List<Option<byte[]>>> asOptionalBytesList() {
        return this instanceof ArrayReply array
               ? Result.allOf(array.items().stream().map(RespValue::asOptionalBytes).toList())
               : unexpectedReply("array", describe()).result();
    }

    /// Short type name used in failure messages; never includes payload bytes.
    default String describe() {
        return switch (this) {
            case SimpleString _ -> "simple string";
            case ErrorReply _ -> "error";
            case IntegerReply _ -> "integer";
            case BulkString _ -> "bulk string";
            case ArrayReply _ -> "array";
            case NullReply _ -> "null";
        };
    }

    private static RedisError.ServerError serverError(String text) {
        var space = text.indexOf(' ');

        return space < 0
               ? RedisError.ServerError.serverError(text, "")
               : RedisError.ServerError.serverError(text.substring(0, space), text.substring(space + 1));
    }

    /// UTF-8 text of a bulk string (test and diagnostics convenience).
    static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
