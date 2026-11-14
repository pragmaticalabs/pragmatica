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

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.net.redis.RespValue.ArrayReply;
import org.pragmatica.net.redis.RespValue.BulkString;
import org.pragmatica.net.redis.RespValue.ErrorReply;
import org.pragmatica.net.redis.RespValue.IntegerReply;
import org.pragmatica.net.redis.RespValue.NullReply;
import org.pragmatica.net.redis.RespValue.SimpleString;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.net.redis.RedisError.ProtocolViolation.protocolViolation;


/// RESP2 wire codec: pure functions over a [ByteBuf], no channel, no state.
///
/// Decoding guarantees: [#decode] consumes exactly one complete reply, or consumes nothing and
/// reports "incomplete" (`Success(None)`) so the caller can retry when more bytes arrive. Because a
/// reply split anywhere (inside a length line, between CR and LF, inside a bulk body, between array
/// elements) is retried from its first byte, the cost of one reply is quadratic in the number of
/// arrivals it spans for an array and constant per arrival for a bulk string (the length is checked
/// against the readable bytes before anything is copied). Memory is bounded by the limits below: a
/// frame beyond them, an unknown type byte, a malformed length or a missing CRLF is a
/// [RedisError.ProtocolViolation] and the stream position is lost.
final class RespCodec {
    /// Longest header, simple-string or error line accepted, in bytes.
    static final int MAX_LINE = 64 * 1024;
    /// Redis' own proto-max-bulk-len default.
    static final long MAX_BULK = 512L * 1024 * 1024;
    static final long MAX_ARRAY = 16L * 1024 * 1024;
    /// Nesting limit: an adversarial `*1\r\n*1\r\n...` must not exhaust the stack.
    static final int MAX_DEPTH = 32;
    private static final int INITIAL_CAPACITY_CAP = 1024;
    private static final byte CR = '\r';
    private static final byte LF = '\n';
    private static final Cause BAD_NUMBER = protocolViolation("malformed integer");

    private static final Cause BAD_LINE = protocolViolation("line not terminated by CRLF within " + MAX_LINE + " bytes");

    private RespCodec() {}

    /// Decode one reply from the readable bytes of `in`. `Success(None)` means more bytes are needed
    /// and `in` is left exactly as it was.
    static Result<Option<RespValue>> decode(ByteBuf in) {
        var mark = in.readerIndex();
        var parsed = parse(in, 0);

        if (!isComplete(parsed)) {
            in.readerIndex(mark);
        }

        return parsed;
    }

    /// Encode a command as an array of bulk strings: `*N\r\n` then `$len\r\n<bytes>\r\n` per part.
    static ByteBuf encode(ByteBufAllocator allocator, List<byte[]> parts) {
        var out = allocator.buffer();

        ByteBufUtil.writeAscii(out, "*" + parts.size() + "\r\n");
        parts.forEach(part -> writeBulk(out, part));

        return out;
    }

    private static void writeBulk(ByteBuf out, byte[] part) {
        ByteBufUtil.writeAscii(out, "$" + part.length + "\r\n");
        out.writeBytes(part);
        out.writeByte(CR).writeByte(LF);
    }

    private static boolean isComplete(Result<Option<RespValue>> parsed) {
        return parsed.fold(_ -> true, Option::isPresent);
    }

    /// A reply that is a value: neither "incomplete" nor a violation. An array stops at the first element that is not.
    private static boolean isValue(Result<Option<RespValue>> parsed) {
        return parsed.fold(_ -> false, Option::isPresent);
    }

    private static Result<Option<RespValue>> incomplete() {
        return success(none());
    }

    private static Result<Option<RespValue>> done(RespValue value) {
        return success(some(value));
    }

    private static Result<Option<RespValue>> parse(ByteBuf in, int depth) {
        return in.isReadable()
               ? parseTyped(in, in.readByte(), depth)
               : incomplete();
    }

    private static Result<Option<RespValue>> parseTyped(ByteBuf in, byte type, int depth) {
        return switch (type) {
            case '+' -> line(in).map(RespCodec::simple);
            case '-' -> line(in).map(RespCodec::error);
            case ':' -> number(in).map(RespCodec::integer);
            case '$' -> number(in).flatMap(length -> bulk(in, length));
            case '*' -> number(in).flatMap(count -> array(in, count, depth));
            default -> protocolViolation("unknown reply type byte " + type).result();
        };
    }

    private static Option<RespValue> simple(Option<String> line) {
        return line.<RespValue> map(SimpleString::simpleString);
    }

    private static Option<RespValue> error(Option<String> line) {
        return line.<RespValue> map(ErrorReply::errorReply);
    }

    private static Option<RespValue> integer(Option<Long> number) {
        return number.<RespValue> map(IntegerReply::integerReply);
    }

    /// One CRLF-terminated line without the terminator; none when the LF has not arrived yet (which
    /// includes a CR that is the last readable byte).
    private static Result<Option<String>> line(ByteBuf in) {
        var from = in.readerIndex();
        var window = Math.min(in.readableBytes(), MAX_LINE + 1);
        var lf = in.indexOf(from, from + window, LF);

        if (lf < 0) {
            return window > MAX_LINE
                   ? BAD_LINE.result()
                   : success(none());
        }

        return lf > from && in.getByte(lf - 1) == CR
               ? success(some(takeLine(in, lf - from - 1)))
               : BAD_LINE.result();
    }

    private static String takeLine(ByteBuf in, int length) {
        var text = in.toString(in.readerIndex(), length, StandardCharsets.UTF_8);

        in.skipBytes(length + 2);

        return text;
    }

    private static Result<Option<Long>> number(ByteBuf in) {
        return line(in).flatMap(RespCodec::toNumber);
    }

    private static Result<Option<Long>> toNumber(Option<String> line) {
        return line.fold(() -> success(none()),
                         text -> Number.parseLong(text)
                                       .option()
                                       .toResult(BAD_NUMBER)
                                       .map(Option::some));
    }

    private static Result<Option<RespValue>> bulk(ByteBuf in, Option<Long> length) {
        return length.fold(RespCodec::incomplete, size -> bulkOfSize(in, size));
    }

    private static Result<Option<RespValue>> bulkOfSize(ByteBuf in, long size) {
        if (size == -1) {
            return done(NullReply.NULL_REPLY);
        }

        if (size < 0 || size > MAX_BULK) {
            return protocolViolation("bulk length " + size + " outside 0.." + MAX_BULK).result();
        }
        // size + CRLF must be readable before anything is copied: a huge declared length costs nothing
        // until the bytes are really there.
        return in.readableBytes() < size + 2
               ? incomplete()
               : takeBulk(in, (int) size);
    }

    private static Result<Option<RespValue>> takeBulk(ByteBuf in, int size) {
        var bytes = new byte[size];

        in.readBytes(bytes);

        return in.readByte() == CR && in.readByte() == LF
               ? done(BulkString.bulkString(bytes))
               : protocolViolation("bulk string not terminated by CRLF").result();
    }

    private static Result<Option<RespValue>> array(ByteBuf in, Option<Long> count, int depth) {
        return count.fold(RespCodec::incomplete, size -> arrayOfSize(in, size, depth));
    }

    private static Result<Option<RespValue>> arrayOfSize(ByteBuf in, long size, int depth) {
        if (size == -1) {
            return done(NullReply.NULL_REPLY);
        }

        if (size < 0 || size > MAX_ARRAY) {
            return protocolViolation("array length " + size + " outside 0.." + MAX_ARRAY).result();
        }

        return depth >= MAX_DEPTH
               ? protocolViolation("arrays nested deeper than " + MAX_DEPTH).result()
               : takeElements(in, size, depth);
    }

    /// Declared size only caps the pre-allocation: the elements must actually arrive.
    private static Result<Option<RespValue>> takeElements(ByteBuf in, long size, int depth) {
        var items = new ArrayList<RespValue>((int) Math.min(size, INITIAL_CAPACITY_CAP));

        for (long i = 0; i < size; i++) {
            var element = parse(in, depth + 1);

            element.onSuccess(parsed -> parsed.onPresent(items::add));
            if (!isValue(element)) {
                return element;
            }
        }

        return done(ArrayReply.arrayReply(items));
    }
}
