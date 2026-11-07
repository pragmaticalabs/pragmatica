// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.upload;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.upload.MultipartDecoder.Event;
import org.pragmatica.http.routing.UploadSpec;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// The push decoder reads the same body the same way wherever the chunks split it (inside a delimiter, a header block,
/// the two bytes after a delimiter), never reads a chunk after its `push` returned, and refuses each malformed framing and
/// each crossed limit with its typed cause.
class MultipartDecoderTest {
    private static final String BOUNDARY = "decoder-boundary";
    private static final String TYPE = "multipart/form-data; boundary=" + BOUNDARY;
    private static final UploadSpec NO_LIMITS = UploadSpec.uploadSpec(true, 100L * 1024 * 1024, 0);

    /// What a decoded body says, comparable by value: a file with its content as ISO-8859-1 text, or a field.
    sealed interface Item {
        record File(String field, Option<String> fileName, String type, String content) implements Item {}

        record Field(String name, String value) implements Item {}
    }

    // ---- split anywhere

    /// Kills: a held-back delimiter prefix emitted as content, a carry dropped between chunks, or the two bytes after a
    /// delimiter read from a chunk that ended before them.
    @Test
    void push_everySingleSplitPoint_yieldsTheSameItems() {
        var body = fullBody();
        var expected = fullBodyItems();

        assertThat(items(decode(TYPE, NO_LIMITS, body))).as("control: the whole body in one chunk").isEqualTo(expected);
        IntStream.rangeClosed(0, body.length)
                 .forEach(split -> assertThat(items(decode(TYPE, NO_LIMITS, body, split))).as("split at %d", split)
                                                                                         .isEqualTo(expected));
    }

    /// Kills the same carry mutants as above where two cuts fall inside one delimiter or one header block.
    @Test
    void push_everyPairOfSplitPoints_yieldsTheSameItems() {
        var body = MultipartBody.multipartBody(BOUNDARY)
                                .field("a", "1")
                                .file("f", "x.bin", "application/octet-stream", latin("ab\r\n--decoder-bo\r\ncd"))
                                .close()
                                .bytes();
        var expected = List.<Item>of(new Item.Field("a", "1"),
                                     new Item.File("f", Option.some("x.bin"), "application/octet-stream", "ab\r\n--decoder-bo\r\ncd"));

        for (var first = 0; first <= body.length; first++) {
            for (var second = first; second <= body.length; second++) {
                assertThat(items(decode(TYPE, NO_LIMITS, body, first, second))).as("splits at %d and %d", first, second)
                                                                                .isEqualTo(expected);
            }
        }
    }

    /// Kills a decoder that only works when the delimiter lands whole in one chunk.
    @Test
    void push_oneByteChunks_yieldsTheSameItems() {
        var body = fullBody();

        assertThat(items(decode(TYPE, NO_LIMITS, body, IntStream.range(1, body.length).toArray()))).isEqualTo(fullBodyItems());
    }

    /// Kills a decoder that emits the tail that might begin a delimiter before the next chunk tells: the first push
    /// yields only the bytes before `\r\n--decoder-bou`, and the second yields the held tail as content.
    @Test
    void push_delimiterPrefixAtChunkEnd_isHeldBackThenEmittedAsContent() {
        var content = "abc\r\n--decoder-bouXYZ";
        var body = MultipartBody.multipartBody(BOUNDARY)
                                .file("f", "t.bin", "application/octet-stream", latin(content))
                                .close()
                                .bytes();
        var cut = indexOf(body, latin("XYZ"));
        var decoder = MultipartDecoder.multipartDecoder(TYPE, NO_LIMITS).unwrap();
        var events = new Collector();

        decoder.push(ByteBuffer.wrap(Arrays.copyOfRange(body, 0, cut))).onSuccess(events::accept);
        assertThat(events.data()).as("the possible delimiter prefix is held back").isEqualTo("abc");
        decoder.push(ByteBuffer.wrap(Arrays.copyOfRange(body, cut, body.length))).onSuccess(events::accept);
        assertThat(events.data()).as("the held tail turned out to be content").isEqualTo("abc\r\n--decoder-bouXYZ");
        assertThat(decoder.finish().isSuccess()).isTrue();
    }

    // ---- framing

    /// Kills a decoder that reads the preamble as content or anything after the closing delimiter as another part.
    @Test
    void push_preambleAndEpilogue_areIgnored() {
        var body = MultipartBody.multipartBody(BOUNDARY)
                                .raw("This is the preamble.\r\n-- not a delimiter\r\n")
                                .field("kept", "yes")
                                .close()
                                .raw("\r\n--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"ghost\"\r\n\r\nboo\r\n--" + BOUNDARY + "--\r\n")
                                .bytes();

        assertThat(items(decode(TYPE, NO_LIMITS, body))).containsExactly(new Item.Field("kept", "yes"));
    }

    /// Kills a boundary read up to the first `;` or space of a quoted value.
    @Test
    void multipartDecoder_quotedBoundary_isReadWhole() {
        var body = MultipartBody.multipartBody("b;q 1").field("x", "y").close().bytes();

        assertThat(items(decode("multipart/form-data; boundary=\"b;q 1\"; charset=utf-8", NO_LIMITS, body))).containsExactly(new Item.Field("x",
                                                                                                                                             "y"));
    }

    /// Kills a decoder that misses an empty file part or reports `filename=""` as a name.
    @Test
    void push_emptyFileAndEmptyFilename_yieldAnEmptyFileWithNoName() {
        var body = MultipartBody.multipartBody(BOUNDARY)
                                .part("Content-Disposition: form-data; name=\"blank\"; filename=\"\"\r\n", new byte[0])
                                .close()
                                .bytes();

        assertThat(items(decode(TYPE, NO_LIMITS, body))).containsExactly(new Item.File("blank", Option.none(), "application/octet-stream", ""));
    }

    /// Kills a classification other than the aggregated path's: a non-text type without a filename is a file, a
    /// `text/plain` part without one is a field.
    @Test
    void push_partTypes_classifyAsTheAggregatedPathDoes() {
        var body = MultipartBody.multipartBody(BOUNDARY)
                                .part("Content-Disposition: form-data; name=\"json\"\r\nContent-Type: application/json\r\n", latin("{}"))
                                .part("Content-Disposition: form-data; name=\"note\"\r\nContent-Type: text/plain; charset=utf-8\r\n", latin("hi"))
                                .close()
                                .bytes();

        assertThat(items(decode(TYPE, NO_LIMITS, body))).containsExactly(new Item.File("json", Option.none(), "application/json", "{}"),
                                                                         new Item.Field("note", "hi"));
    }

    /// Kills content that loses or rewrites CRLFs and dashes that do not form the delimiter.
    @Test
    void push_crlfInsideFileData_isPreserved() {
        var content = "\r\n\r\n--\r\n--decoder-boundar\r\n-\r";
        var body = MultipartBody.multipartBody(BOUNDARY).file("f", "c.txt", "text/plain", latin(content)).close().bytes();

        assertThat(items(decode(TYPE, NO_LIMITS, body))).containsExactly(new Item.File("f", Option.some("c.txt"), "text/plain", content));
    }

    // ---- refusals

    /// Kills "EOF treated as success": a body without its closing delimiter is refused, whether it ended after a
    /// delimiter or inside a part, and the refusal is final.
    @Test
    void finish_withoutClosingDelimiter_isRefusedNotClosed() {
        var afterDelimiter = MultipartBody.multipartBody(BOUNDARY).field("a", "1").raw("--" + BOUNDARY).bytes();
        var insidePart = MultipartBody.multipartBody(BOUNDARY).raw("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"f\"; filename=\"f\"\r\n\r\nhalf").bytes();
        var decoder = MultipartDecoder.multipartDecoder(TYPE, NO_LIMITS).unwrap();

        assertThat(causeOf(decode(TYPE, NO_LIMITS, afterDelimiter))).isEqualTo(UploadIngestError.Malformed.NOT_CLOSED);
        assertThat(causeOf(decode(TYPE, NO_LIMITS, insidePart))).isEqualTo(UploadIngestError.Malformed.NOT_CLOSED);
        assertThat(decoder.finish().isFailure()).as("an empty body is not a closed one").isTrue();
        assertThat(causeOf(decoder.push(ByteBuffer.wrap(latin("--" + BOUNDARY + "--"))))).as("the refusal is final")
                                                                                           .isEqualTo(UploadIngestError.Malformed.NOT_CLOSED);
    }

    /// Kills a delimiter accepted when followed by neither `--` nor CRLF.
    @Test
    void push_delimiterFollowedByOtherBytes_isRefusedBadDelimiter() {
        var body = latin("--" + BOUNDARY + "XX\r\n");

        assertThat(causeOf(decode(TYPE, NO_LIMITS, body))).isEqualTo(UploadIngestError.Malformed.BAD_DELIMITER);
    }

    /// Kills header-block checks that let a malformed, nameless, nested or oversized part through.
    @Test
    void push_badPartHeaders_areRefusedEachWithItsCause() {
        assertThat(causeOf(decode(TYPE, NO_LIMITS, headersPart("no colon here\r\n")))).isEqualTo(UploadIngestError.Malformed.BAD_HEADERS);
        assertThat(causeOf(decode(TYPE, NO_LIMITS, headersPart("Content-Type: text/plain\r\n")))).isEqualTo(UploadIngestError.Malformed.NO_DISPOSITION);
        assertThat(causeOf(decode(TYPE, NO_LIMITS, headersPart("Content-Disposition: attachment; name=\"a\"\r\n")))).isEqualTo(UploadIngestError.Malformed.NO_DISPOSITION);
        assertThat(causeOf(decode(TYPE,
                                  NO_LIMITS,
                                  headersPart("Content-Disposition: form-data; name=\"n\"\r\nContent-Type: Multipart/Mixed; boundary=x\r\n")))).isEqualTo(UploadIngestError.Malformed.NESTED_MULTIPART);
        assertThat(causeOf(decode(TYPE,
                                  NO_LIMITS,
                                  headersPart("X-Big: " + "h".repeat(MultipartDecoder.MAX_HEADER_BYTES) + "\r\n")))).isEqualTo(UploadIngestError.Malformed.HEADERS_TOO_LARGE);
    }

    /// Kills a decoder built from a type with no usable boundary (absent, empty, longer than 70 characters).
    @Test
    void multipartDecoder_withoutUsableBoundary_isRefusedNoBoundary() {
        assertThat(causeOf(MultipartDecoder.multipartDecoder("multipart/form-data", NO_LIMITS))).isEqualTo(UploadIngestError.Malformed.NO_BOUNDARY);
        assertThat(causeOf(MultipartDecoder.multipartDecoder("multipart/form-data; boundary=\"\"", NO_LIMITS))).isEqualTo(UploadIngestError.Malformed.NO_BOUNDARY);
        assertThat(causeOf(MultipartDecoder.multipartDecoder("multipart/form-data; boundary=" + "b".repeat(71), NO_LIMITS))).isEqualTo(UploadIngestError.Malformed.NO_BOUNDARY);
        assertThat(MultipartDecoder.multipartDecoder("multipart/form-data; boundary=" + "b".repeat(70), NO_LIMITS).isSuccess()).as("control: 70 is allowed")
                                                                                                                         .isTrue();
    }

    @Test
    void isFormData_matchesTheMediaTypeOnly_whateverItsCase() {
        assertThat(MultipartDecoder.isFormData("Multipart/Form-Data; boundary=x")).isTrue();
        assertThat(MultipartDecoder.isFormData("multipart/mixed; boundary=x")).isFalse();
        assertThat(MultipartDecoder.isFormData("application/octet-stream")).isFalse();
    }

    // ---- limits

    /// Kills each limit check removed: every one is refused 413 with the route key it crossed.
    @Test
    void push_eachDeclaredLimitCrossed_isRefusedWithThatLimit() {
        var twoFiles = MultipartBody.multipartBody(BOUNDARY).file("a", "a", "x/y", latin("1")).file("b", "b", "x/y", latin("2")).close().bytes();
        var bigFile = MultipartBody.multipartBody(BOUNDARY).file("a", "a", "x/y", latin("12345")).close().bytes();
        var twoFields = MultipartBody.multipartBody(BOUNDARY).field("a", "1").field("b", "2").close().bytes();
        var bigField = MultipartBody.multipartBody(BOUNDARY).field("a", "1234").close().bytes();

        assertThat(causeOf(decode(TYPE, limits(1, 0, 0, 0), twoFiles))).isEqualTo(UploadIngestError.LimitExceeded.limitExceeded("max_files", 1));
        assertThat(causeOf(decode(TYPE, limits(0, 4, 0, 0), bigFile))).isEqualTo(UploadIngestError.LimitExceeded.limitExceeded("max_file_size", 4));
        assertThat(causeOf(decode(TYPE, limits(0, 0, 1, 0), twoFields))).isEqualTo(UploadIngestError.LimitExceeded.limitExceeded("max_fields", 1));
        assertThat(causeOf(decode(TYPE, limits(0, 0, 0, 3), bigField))).isEqualTo(UploadIngestError.LimitExceeded.limitExceeded("max_field_size", 3));
        assertThat(items(decode(TYPE, limits(2, 5, 2, 4), twoFiles))).as("control: at the limits, accepted").hasSize(2);
        assertThat(UploadIngestError.LimitExceeded.limitExceeded("max_files", 1).status().code()).isEqualTo(413);
    }

    /// Kills the built-in bounds removed: 1 MiB of field values when `max_field_size` is undeclared, and the part cap.
    @Test
    void push_builtInBoundsCrossed_areRefused() {
        var oversized = MultipartBody.multipartBody(BOUNDARY)
                                     .field("a", "x".repeat((int) MultipartDecoder.DEFAULT_FIELDS_TOTAL / 2))
                                     .field("b", "y".repeat((int) MultipartDecoder.DEFAULT_FIELDS_TOTAL / 2 + 1))
                                     .close()
                                     .bytes();
        var manyParts = MultipartBody.multipartBody(BOUNDARY);

        IntStream.rangeClosed(0, (int) MultipartDecoder.DEFAULT_MAX_PARTS).forEach(index -> manyParts.field("f" + index, "v"));

        assertThat(causeOf(decode(TYPE, NO_LIMITS, oversized))).isEqualTo(UploadIngestError.LimitExceeded.limitExceeded("the fields total",
                                                                                                                       MultipartDecoder.DEFAULT_FIELDS_TOTAL));
        assertThat(causeOf(decode(TYPE, NO_LIMITS, manyParts.close().bytes()))).isEqualTo(UploadIngestError.LimitExceeded.limitExceeded("the part count",
                                                                                                                                       MultipartDecoder.DEFAULT_MAX_PARTS));
    }

    // ---- fixtures

    private static byte[] fullBody() {
        return MultipartBody.multipartBody(BOUNDARY)
                            .raw("preamble\r\n")
                            .field("title", "héllo")
                            .file("doc", "a b;c.txt", "text/plain", latin("line1\r\nline2\r\n--\r\n--decoder-boundar\r\nend"))
                            .file("empty", "e.bin", "application/octet-stream", new byte[0])
                            .field("tag", "x")
                            .field("tag", "")
                            .close()
                            .raw("\r\nepilogue")
                            .bytes();
    }

    private static List<Item> fullBodyItems() {
        return List.of(new Item.Field("title", "héllo"),
                       new Item.File("doc", Option.some("a b;c.txt"), "text/plain", "line1\r\nline2\r\n--\r\n--decoder-boundar\r\nend"),
                       new Item.File("empty", Option.some("e.bin"), "application/octet-stream", ""),
                       new Item.Field("tag", "x"),
                       new Item.Field("tag", ""));
    }

    private static byte[] headersPart(String headers) {
        return MultipartBody.multipartBody(BOUNDARY).part(headers, latin("v")).close().bytes();
    }

    private static UploadSpec limits(long maxFiles, long maxFileSize, long maxFields, long maxFieldSize) {
        return UploadSpec.uploadSpec(true, 100L * 1024 * 1024, 0, maxFiles, maxFileSize, maxFields, maxFieldSize);
    }

    /// Decodes `body` cut at `splits` (ascending; equal or edge cuts make empty chunks). Each chunk is a fresh array
    /// overwritten right after its push, so a decoder that kept a reference to it reads garbage on the next push.
    private static Result<List<Item>> decode(String type, UploadSpec spec, byte[] body, int... splits) {
        var decoder = MultipartDecoder.multipartDecoder(type, spec).unwrap();
        var collector = new Collector();
        var cuts = IntStream.concat(Arrays.stream(splits), IntStream.of(body.length)).toArray();
        var from = 0;

        for (var to : cuts) {
            var chunk = Arrays.copyOfRange(body, from, to);
            var pushed = decoder.push(ByteBuffer.wrap(chunk));

            if (pushed.isFailure()) {
                return pushed.map(_ -> List.<Item>of());
            }
            pushed.onSuccess(collector::accept);
            Arrays.fill(chunk, (byte) '#');
            from = to;
        }

        return decoder.finish().map(_ -> collector.items());
    }

    private static List<Item> items(Result<List<Item>> result) {
        return result.fold(cause -> fail("refused: " + cause.message()), items -> items);
    }

    private static Cause causeOf(Result<?> result) {
        return result.fold(cause -> cause, value -> fail("expected a refusal, got " + value));
    }

    private static byte[] latin(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        return IntStream.rangeClosed(0, haystack.length - needle.length)
                        .filter(start -> Arrays.equals(haystack, start, start + needle.length, needle, 0, needle.length))
                        .findFirst()
                        .orElseThrow();
    }

    /// Turns events into items, copying each file slice at once (it is valid only while its chunk is).
    private static final class Collector {
        private final List<Item> items = new ArrayList<>();
        private final ByteArrayOutputStream content = new ByteArrayOutputStream();
        private final ByteArrayOutputStream allData = new ByteArrayOutputStream();
        private Option<Event.FileStart> open = Option.none();

        void accept(List<Event> events) {
            events.forEach(this::accept);
        }

        void accept(Event event) {
            switch (event) {
                case Event.FileStart start -> start(start);
                case Event.FileData data -> data(data.bytes());
                case Event.FileEnd _ -> end();
                case Event.Field field -> items.add(new Item.Field(field.name(), field.value()));
            }
        }

        private void start(Event.FileStart start) {
            open = Option.some(start);
            content.reset();
        }

        private void data(ByteBuffer bytes) {
            var copy = new byte[bytes.remaining()];

            bytes.duplicate().get(copy);
            content.writeBytes(copy);
            allData.writeBytes(copy);
        }

        private void end() {
            assertThat(open.isPresent()).as("a file ended that had started").isTrue();
            var start = open.unwrap();

            items.add(new Item.File(start.field(), start.fileName(), start.contentType(), content.toString(StandardCharsets.ISO_8859_1)));
            open = Option.none();
        }

        List<Item> items() {
            return List.copyOf(items);
        }

        String data() {
            return allData.toString(StandardCharsets.ISO_8859_1);
        }
    }
}
