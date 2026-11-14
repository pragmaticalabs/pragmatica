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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.net.redis.RedisError.InvalidArgument.invalidArgument;


/// The command surface shared by a single [RedisConnection] and a pooled [RedisClient].
///
/// Guarantees, per operation, and what earns each:
///
///  - **Reply to command pairing**: each command's promise is resolved with the reply that the server
///    produced for THAT command. Earned by FIFO correlation: replies on one connection arrive in the
///    order the commands were written, the frame is encoded BEFORE the command is queued (a frame the codec
///    refuses fails with [RedisError.InvalidArgument] and never enters the queue), and the write and the
///    queue entry happen in one event-loop task.
///  - **Order of commands**: commands issued from one thread to one [RedisConnection] are executed by the
///    server in issue order (same mechanism). Through a [RedisClient] there is NO order between commands:
///    each may take a different connection.
///  - **Delivery**: a command that fails with [RedisError.ConnectionLost] or [RedisError.Timeout] has an
///    UNKNOWN outcome (the server may have executed it). Nothing here retries, so a non-idempotent command
///    (INCR, SET NX, EVAL) is never executed twice by the client.
///  - **Atomicity**: each command is atomic on the server, as the server defines it. A sequence of
///    commands is not: use [#eval] for a multi-step atomic operation.
///  - **Failure channel**: every failure, including a server `-ERR` reply, is a [RedisError] in the
///    promise; nothing throws.
public interface RedisCommands {
    /// Write one raw command and resolve with its reply. An error reply fails the promise with
    /// [RedisError.ServerError]. The first element is the verb.
    Promise<RespValue> execute(List<byte[]> command);

    /// `PING`: succeeds on `+PONG`.
    default Promise<Unit> ping() {
        return expect(command("PING"), RespValue::asPong);
    }

    /// `GET`: none when the key does not exist.
    default Promise<Option<byte[]>> get(String key) {
        return expect(command("GET", utf8(key)), RespValue::asOptionalBytes);
    }

    /// `SET key value [EX|PX ttl] [NX]`: true when the value was stored, false when `NX` found the key
    /// present.
    default Promise<Boolean> set(String key, byte[] value, SetOptions options) {
        return expect(command("SET",
                              Stream.concat(Stream.of(utf8(key),
                                                      value),
                                            options.arguments().stream())
                                    .toArray(byte[][]::new)),
                      RespValue::asAppliedFlag);
    }

    default Promise<Boolean> set(String key, byte[] value) {
        return set(key, value, SetOptions.setOptions());
    }

    /// `DEL`: the number of keys that existed and were removed.
    default Promise<Long> del(String first, String... more) {
        return expect(command("DEL", keys(first, more)), RespValue::asLong);
    }

    /// `EXPIRE`: true when the key exists and now has the time to live.
    default Promise<Boolean> expire(String key, long seconds) {
        return expect(command("EXPIRE", utf8(key), utf8(Long.toString(seconds))), RespValue::asFlag);
    }

    /// `PEXPIRE`: as [#expire] with a millisecond time to live.
    default Promise<Boolean> pexpire(String key, long millis) {
        return expect(command("PEXPIRE", utf8(key), utf8(Long.toString(millis))), RespValue::asFlag);
    }

    /// `INCR`: the new value. Not idempotent: a lost connection leaves the increment unknown.
    default Promise<Long> incr(String key) {
        return expect(command("INCR", utf8(key)), RespValue::asLong);
    }

    /// `MGET`: one entry per key, in key order, none for a missing key.
    default Promise<List<Option<byte[]>>> mget(String first, String... more) {
        return expect(command("MGET", keys(first, more)), RespValue::asOptionalBytesList);
    }

    /// `EVAL script numkeys key... arg...`: the raw reply, whose type the script decides. Executed
    /// atomically by the server; not idempotent in general.
    default Promise<RespValue> eval(String script, List<String> keys, List<byte[]> args) {
        return script.isBlank()
               ? invalidArgument("EVAL script is blank").promise()
               : execute(evalCommand(script, keys, args));
    }

    /// Interpret the reply of a command with `interpret`.
    private <T> Promise<T> expect(List<byte[]> command, Fn1<Result<T>, RespValue> interpret) {
        return execute(command).flatMap(reply -> interpret.apply(reply)
                                                          .async());
    }

    private static List<byte[]> evalCommand(String script, List<String> keys, List<byte[]> args) {
        var parts = new ArrayList<byte[]>();

        parts.add(utf8(script));
        parts.add(utf8(Integer.toString(keys.size())));
        keys.forEach(key -> parts.add(utf8(key)));
        parts.addAll(args);

        return command("EVAL", parts.toArray(byte[][]::new));
    }

    private static byte[][] keys(String first, String[] more) {
        return Stream.concat(Stream.of(first),
                             Stream.of(more))
                     .map(RedisCommands::utf8)
                     .toArray(byte[][]::new);
    }

    /// Verb followed by its arguments, as the parts of one command.
    static List<byte[]> command(String verb, byte[]... arguments) {
        return Stream.concat(Stream.of(utf8(verb)),
                             Stream.of(arguments))
                     .toList();
    }

    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
