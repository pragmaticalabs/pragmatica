package org.pragmatica.net.redis;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.net.redis.RespValue.ArrayReply;
import org.pragmatica.net.redis.RespValue.BulkString;
import org.pragmatica.net.redis.RespValue.ErrorReply;
import org.pragmatica.net.redis.RespValue.IntegerReply;
import org.pragmatica.net.redis.RespValue.NullReply;
import org.pragmatica.net.redis.RespValue.SimpleString;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;

import static org.assertj.core.api.Assertions.assertThat;

/// Byte-fixture tests of [RespCodec]: one complete reply is consumed, or nothing is and "incomplete" is
/// reported.
class RespCodecTest {
    static ByteBuf buf(String wire) {
        return Unpooled.copiedBuffer(wire, StandardCharsets.ISO_8859_1);
    }

    /// Stable text form of a reply, so that records holding byte[] compare by content.
    static String show(RespValue value) {
        return switch (value) {
            case SimpleString s -> "+" + s.text();
            case ErrorReply e -> "-" + e.text();
            case IntegerReply i -> ":" + i.value();
            case BulkString b -> "$" + new String(b.bytes(), StandardCharsets.ISO_8859_1);
            case NullReply _ -> "null";
            case ArrayReply a -> a.items()
                                  .stream()
                                  .map(RespCodecTest::show)
                                  .collect(Collectors.joining(",", "[", "]"));
        };
    }

    static String decoded(String wire) {
        return RespCodec.decode(buf(wire))
                        .unwrap()
                        .map(RespCodecTest::show)
                        .or("incomplete");
    }

    @Test
    void simpleString() {
        assertThat(decoded("+OK\r\n")).isEqualTo("+OK");
    }

    @Test
    void errorReply() {
        assertThat(decoded("-ERR unknown command\r\n")).isEqualTo("-ERR unknown command");
    }

    @Test
    void integers() {
        assertThat(decoded(":1000\r\n")).isEqualTo(":1000");
        assertThat(decoded(":-7\r\n")).isEqualTo(":-7");
    }

    @Test
    void bulkAndEmptyBulk() {
        assertThat(decoded("$5\r\nhello\r\n")).isEqualTo("$hello");
        assertThat(decoded("$0\r\n\r\n")).isEqualTo("$");
    }

    @Test
    void bulkBodyMayContainCrlf() {
        assertThat(decoded("$4\r\na\r\nb\r\n")).isEqualTo("$a\r\nb");
    }

    @Test
    void nullBulkAndNullArray() {
        assertThat(decoded("$-1\r\n")).isEqualTo("null");
        assertThat(decoded("*-1\r\n")).isEqualTo("null");
    }

    @Test
    void emptyArray() {
        assertThat(decoded("*0\r\n")).isEqualTo("[]");
    }

    @Test
    void nestedArraysWithNullElement() {
        assertThat(decoded("*3\r\n*2\r\n:1\r\n$1\r\na\r\n$-1\r\n*1\r\n*0\r\n")).isEqualTo("[[:1,$a],null,[[]]]");
    }

    @Test
    void consumesExactlyOneReply() {
        var in = buf("+A\r\n+B\r\n");

        assertThat(show(RespCodec.decode(in)
                                 .unwrap()
                                 .or(NullReply.NULL_REPLY))).isEqualTo("+A");
        assertThat(in.readableBytes()).isEqualTo(4);
    }

    private static final String NESTED = "*3\r\n*2\r\n:1\r\n$3\r\nabc\r\n$-1\r\n+OK\r\n";

    @Test
    void crlfSplitBetweenCrAndLfConsumesNothing() {
        var in = buf("+OK\r");

        assertThat(RespCodec.decode(in)
                            .unwrap()
                            .isEmpty()).isTrue();
        assertThat(in.readerIndex()).isZero();
        in.writeByte('\n');
        assertThat(show(RespCodec.decode(in)
                                 .unwrap()
                                 .or(NullReply.NULL_REPLY))).isEqualTo("+OK");
    }

    @Test
    void everyPrefixIsIncompleteAndLeavesTheBufferUntouched() {
        for (int cut = 0; cut < NESTED.length(); cut++) {
            var in = buf(NESTED.substring(0, cut));

            assertThat(RespCodec.decode(in)
                                .unwrap()
                                .isEmpty()).as("prefix of %d bytes", cut)
                                           .isTrue();
            assertThat(in.readerIndex()).as("prefix of %d bytes", cut)
                                        .isZero();
        }

        assertThat(decoded(NESTED)).isEqualTo("[[:1,$abc],null,+OK]");
    }

    @Test
    void declaredLengthIsNotTrustedBeforeBytesArrive() {
        assertThat(decoded("$536870912\r\nabc")).isEqualTo("incomplete");
        assertThat(decoded("*16777216\r\n:1\r\n")).isEqualTo("incomplete");
    }

    private String violation(String wire) {
        return RespCodec.decode(buf(wire))
                        .fold(cause -> cause.getClass()
                                            .getSimpleName(),
                              _ -> "no violation");
    }

    @Test
    void unknownTypeByte() {
        assertThat(violation("?x\r\n")).isEqualTo("ProtocolViolation");
    }

    @Test
    void malformedLength() {
        assertThat(violation("$abc\r\n")).isEqualTo("ProtocolViolation");
        assertThat(violation(":\r\n")).isEqualTo("ProtocolViolation");
    }

    @Test
    void bareLfAndBulkCrWithoutLfAreViolations() {
        assertThat(violation("+OK\n")).isEqualTo("ProtocolViolation");
        assertThat(violation("$3\r\nabc\rX")).isEqualTo("ProtocolViolation");
    }

    @Test
    void lengthOutsideBounds() {
        assertThat(violation("$-2\r\n")).isEqualTo("ProtocolViolation");
        assertThat(violation("$536870913\r\n")).isEqualTo("ProtocolViolation");
        assertThat(violation("*16777217\r\n")).isEqualTo("ProtocolViolation");
    }

    @Test
    void bulkWithoutTrailingCrlf() {
        assertThat(violation("$3\r\nabcXY")).isEqualTo("ProtocolViolation");
    }

    @Test
    void nestingBeyondLimit() {
        assertThat(violation("*1\r\n".repeat(RespCodec.MAX_DEPTH + 1) + ":1\r\n")).isEqualTo("ProtocolViolation");
        assertThat(decoded("*1\r\n".repeat(RespCodec.MAX_DEPTH) + ":1\r\n")).startsWith("[");
    }

    @Test
    void violationInsideAnArrayElementFailsTheWholeReply() {
        assertThat(violation("*2\r\n:1\r\n?bad\r\n")).isEqualTo("ProtocolViolation");
        assertThat(violation("*2\r\n:1\r\n$2\r\nabXX")).isEqualTo("ProtocolViolation");
    }

    @Test
    void unterminatedLineBeyondLimit() {
        assertThat(violation("+" + "a".repeat(RespCodec.MAX_LINE + 2))).isEqualTo("ProtocolViolation");
    }

    @Test
    void commandIsAnArrayOfBulkStrings() {
        var out = RespCodec.encode(ByteBufAllocator.DEFAULT,
                                   List.of("SET".getBytes(), "k".getBytes(), "va\r\nl".getBytes()));

        assertThat(out.toString(StandardCharsets.ISO_8859_1)).isEqualTo("*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$5\r\nva\r\nl\r\n");
    }

    @Test
    void emptyArgumentIsEncodedAsEmptyBulk() {
        var out = RespCodec.encode(ByteBufAllocator.DEFAULT, List.of("GET".getBytes(), new byte[0]));

        assertThat(out.toString(StandardCharsets.ISO_8859_1)).isEqualTo("*2\r\n$3\r\nGET\r\n$0\r\n\r\n");
    }
}
