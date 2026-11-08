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
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.upload.MultipartDecoder.Event;
import org.pragmatica.http.routing.UploadSpec;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// The streaming multipart decoder (U2): the same events whatever the chunking, file content passed through as slices,
/// and each framing error or limit refused with its typed cause.
class MultipartDecoderTest {
    private static final String BOUNDARY = "b0und4ry";
    private static final String TYPE = "multipart/form-data; boundary=" + BOUNDARY;
    private static final UploadSpec UNLIMITED = UploadSpec.uploadSpec(true, 0, 0);

    /// What a body decoded to: events with file content gathered per file, in body order.
    private record Decoded(List<String> events, Optional<Cause> refusal) {}

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    /// A file's content that holds a near-delimiter (`\r\n--b0und4r` followed by a byte that ends no delimiter) and every
    /// byte value, so a decoder that cuts content at a partial match, or treats any byte as text, changes it.
    private static byte[] trickyContent() {
        var out = new ByteArrayOutputStream();

        out.writeBytes(ascii("head\r\n--" + BOUNDARY.substring(0, BOUNDARY.length() - 1) + "X tail\r\n-\r\n--"));
        for (var value = 0; value < 256; value++) {
            out.write(value);
        }
        out.writeBytes(ascii("\r\n"));

        return out.toByteArray();
    }

    private static byte[] body(byte[] fileContent) {
        var out = new ByteArrayOutputStream();

        out.writeBytes(ascii("preamble to skip\r\n--" + BOUNDARY + "\r\n"
                             + "Content-Disposition: form-data; name=\"comment\"\r\n\r\n"
                             + "hello world\r\n--" + BOUNDARY + "\r\n"
                             + "Content-Disposition: form-data; name=\"file\"; filename=\"a.bin\"\r\n"
                             + "Content-Type: application/octet-stream\r\n\r\n"));
        out.writeBytes(fileContent);
        out.writeBytes(ascii("\r\n--" + BOUNDARY + "\r\n"
                             + "Content-Disposition: form-data; name=\"empty\"; filename=\"\"\r\n\r\n"
                             + "\r\n--" + BOUNDARY + "--\r\nepilogue"));

        return out.toByteArray();
    }

    /// Decodes `body` pushed in chunks of `chunkSize`, gathering file content until each [Event.FileEnd].
    private static Decoded decode(byte[] body, int chunkSize, UploadSpec spec, String type) {
        var decoder = MultipartDecoder.multipartDecoder(type, spec).unwrap();
        var events = new ArrayList<String>();
        var content = new ByteArrayOutputStream();

        for (var offset = 0; offset < body.length; offset += chunkSize) {
            var chunk = ByteBuffer.wrap(Arrays.copyOfRange(body, offset, Math.min(body.length, offset + chunkSize)));
            var pushed = decoder.push(chunk);

            if (pushed.isFailure()) {
                return new Decoded(events, pushed.fold(Option::some, _ -> Option.<Cause>none()).toOptional());
            }
            pushed.onSuccess(list -> list.forEach(event -> record(event, events, content)));
        }

        return new Decoded(events, decoder.finish().fold(Option::some, _ -> Option.<Cause>none()).toOptional());
    }

    private static void record(Event event, List<String> events, ByteArrayOutputStream content) {
        switch (event) {
            case Event.FileStart start -> events.add("start:" + start.field() + ":" + start.fileName().or("<none>") + ":"
                                                     + start.contentType());
            case Event.FileData data -> content.writeBytes(copy(data.bytes()));
            case Event.FileEnd _ -> {
                events.add("end:" + Arrays.hashCode(content.toByteArray()) + ":" + content.size());
                content.reset();
            }
            case Event.Field field -> events.add("field:" + field.name() + "=" + field.value());
        }
    }

    private static byte[] copy(ByteBuffer bytes) {
        var copy = new byte[bytes.remaining()];

        bytes.duplicate().get(copy);

        return copy;
    }

    private static List<String> expectedEvents(byte[] fileContent) {
        return List.of("field:comment=hello world",
                       "start:file:a.bin:application/octet-stream",
                       "end:" + Arrays.hashCode(fileContent) + ":" + fileContent.length,
                       "start:empty:<none>:application/octet-stream",
                       "end:" + Arrays.hashCode(new byte[0]) + ":0");
    }

    @Test
    void sameEvents_whateverTheChunking_fromOneByteToTheWholeBody() {
        var content = trickyContent();
        var body = body(content);

        for (var chunkSize = 1; chunkSize <= body.length; chunkSize++) {
            var decoded = decode(body, chunkSize, UNLIMITED, TYPE);

            assertThat(decoded.refusal()).as("chunk size %d", chunkSize).isEmpty();
            assertThat(decoded.events()).as("chunk size %d", chunkSize).containsExactlyElementsOf(expectedEvents(content));
        }
    }

    /// RFC 7578 parameters: a quoted value keeps an escaped quote and a `;` it encloses, and parameter names match in any
    /// case.
    @Test
    void quotedParameters_unescape_keepEnclosedSeparators_andNamesIgnoreCase() {
        var body = ascii("--" + BOUNDARY + "\r\nContent-Disposition: form-data; NAME=\"f\"; FileName=\"a\\\"b;c.bin\"\r\n\r\n"
                         + "xyz\r\n--" + BOUNDARY + "--\r\n");

        var decoded = decode(body, body.length, UNLIMITED, TYPE);

        assertThat(decoded.refusal()).isEmpty();
        assertThat(decoded.events()).first().isEqualTo("start:f:a\"b;c.bin:application/octet-stream");
    }

    @Test
    void fileContent_comesOutAsSlicesOfThePushedChunk_notCopies() {
        var decoder = MultipartDecoder.multipartDecoder(TYPE, UNLIMITED).unwrap();
        var chunk = ByteBuffer.wrap(ascii("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"f\"; filename=\"x\"\r\n\r\n"
                                          + "0123456789abcdef"));

        var events = decoder.push(chunk).unwrap();
        var data = events.stream().filter(Event.FileData.class::isInstance).map(Event.FileData.class::cast).findFirst();

        assertThat(data).isPresent();
        assertThat(data.get().bytes().hasArray() && data.get().bytes().array() == chunk.array())
            .as("the content shares the chunk's memory: nothing is gathered").isTrue();
    }

    @Test
    void noBoundary_isRefusedMalformed() {
        var decoder = MultipartDecoder.multipartDecoder("multipart/form-data", UNLIMITED);

        assertThat(decoder.fold(Option::some, _ -> Option.<Cause>none()).toOptional()).contains(UploadIngestError.Malformed.NO_BOUNDARY);
    }

    @Test
    void bodyEndingBeforeItsClosingDelimiter_isRefusedNotClosed() {
        var body = Arrays.copyOf(body(trickyContent()), 200);

        assertThat(decode(body, 7, UNLIMITED, TYPE).refusal()).contains(UploadIngestError.Malformed.NOT_CLOSED);
    }

    @Test
    void partWithoutDisposition_isRefusedNoDisposition() {
        var body = ascii("--" + BOUNDARY + "\r\nContent-Type: text/plain\r\n\r\nx\r\n--" + BOUNDARY + "--\r\n");

        assertThat(decode(body, 5, UNLIMITED, TYPE).refusal()).contains(UploadIngestError.Malformed.NO_DISPOSITION);
    }

    @Test
    void nestedMultipartPart_isRefusedNested() {
        var body = ascii("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"n\"\r\n"
                         + "Content-Type: multipart/mixed; boundary=inner\r\n\r\nx\r\n--" + BOUNDARY + "--\r\n");

        assertThat(decode(body, 5, UNLIMITED, TYPE).refusal()).contains(UploadIngestError.Malformed.NESTED_MULTIPART);
    }

    @Test
    void headerBlockOverItsBound_isRefusedHeadersTooLarge() {
        var body = ascii("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"n\"\r\nX-Pad: "
                         + "p".repeat(MultipartDecoder.MAX_HEADER_BYTES) + "\r\n\r\nx\r\n--" + BOUNDARY + "--\r\n");

        assertThat(decode(body, 1000, UNLIMITED, TYPE).refusal()).contains(UploadIngestError.Malformed.HEADERS_TOO_LARGE);
    }

    @Test
    void delimiterFollowedByNeitherAPartNorTheEnd_isRefusedBadDelimiter() {
        var body = ascii("--" + BOUNDARY + "XY\r\n\r\n");

        assertThat(decode(body, 3, UNLIMITED, TYPE).refusal()).contains(UploadIngestError.Malformed.BAD_DELIMITER);
    }

    @Test
    void routeLimits_areRefusedLimitExceeded_atThePartThatCrossesThem() {
        var content = trickyContent();
        var body = body(content);

        assertThat(decode(body, 64, UploadSpec.uploadSpec(true, 0, 0, 1, 0, 0, 0), TYPE).refusal())
            .contains(UploadIngestError.LimitExceeded.limitExceeded("max_files", 1));
        assertThat(decode(body, 64, UploadSpec.uploadSpec(true, 0, 0, 0, content.length - 1, 0, 0), TYPE).refusal())
            .contains(UploadIngestError.LimitExceeded.limitExceeded("max_file_size", content.length - 1));
        assertThat(decode(body, 64, UploadSpec.uploadSpec(true, 0, 0, 0, content.length, 0, 0), TYPE).refusal())
            .as("a file exactly at max_file_size is accepted").isEmpty();
        assertThat(decode(body, 64, UploadSpec.uploadSpec(true, 0, 0, 0, 0, 0, 5), TYPE).refusal())
            .contains(UploadIngestError.LimitExceeded.limitExceeded("max_field_size", 5));

        var twoFields = ascii("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\n1\r\n--" + BOUNDARY
                              + "\r\nContent-Disposition: form-data; name=\"b\"\r\n\r\n2\r\n--" + BOUNDARY + "--\r\n");

        assertThat(decode(twoFields, 9, UploadSpec.uploadSpec(true, 0, 0, 0, 0, 1, 0), TYPE).refusal())
            .contains(UploadIngestError.LimitExceeded.limitExceeded("max_fields", 1));
    }

    @Test
    void fieldValuesOverTheUndeclaredTotal_areRefused_soFieldsStayBounded() {
        var big = "v".repeat((int) MultipartDecoder.DEFAULT_FIELDS_TOTAL + 1);
        var body = ascii("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\n" + big + "\r\n--" + BOUNDARY
                         + "--\r\n");

        assertThat(decode(body, 64 * 1024, UNLIMITED, TYPE).refusal())
            .contains(UploadIngestError.LimitExceeded.limitExceeded("the fields total", MultipartDecoder.DEFAULT_FIELDS_TOTAL));
    }

    @Test
    void aRefusal_isFinal_andAnsweredAgainByLaterCalls() {
        var decoder = MultipartDecoder.multipartDecoder(TYPE, UNLIMITED).unwrap();

        assertThat(decoder.push(ByteBuffer.wrap(ascii("--" + BOUNDARY + "XY"))).isFailure()).isTrue();
        assertThat(decoder.push(ByteBuffer.wrap(ascii("anything"))).isFailure()).isTrue();
        assertThat(decoder.finish().fold(Option::some, _ -> Option.<Cause>none()).toOptional())
            .contains(UploadIngestError.Malformed.BAD_DELIMITER);
    }

    private static final String DISPOSITION_A = "Content-Disposition: form-data; name=\"a\"";

    private static byte[] singleField(String headers, String value) {
        return ascii("--" + BOUNDARY + "\r\n" + headers + "\r\n\r\n" + value + "\r\n--" + BOUNDARY + "--\r\n");
    }

    @Test
    void boundary_ofExactlySeventyCharacters_isAccepted_andEmptyOrOverlongIsNot() {
        var seventy = "multipart/form-data; boundary=" + "b".repeat(70);

        assertThat(MultipartDecoder.multipartDecoder(seventy, UNLIMITED).isSuccess()).isTrue();
        assertThat(MultipartDecoder.multipartDecoder(seventy + "b", UNLIMITED).isFailure()).isTrue();
        assertThat(MultipartDecoder.multipartDecoder("multipart/form-data; boundary=", UNLIMITED).isFailure()).isTrue();
        assertThat(MultipartDecoder.multipartDecoder("multipart/form-data; boundary=\"\"", UNLIMITED).isFailure()).isTrue();
    }

    @Test
    void isFormData_acceptsOnlyFormData_whateverTheCaseAndParameters() {
        assertThat(MultipartDecoder.isFormData("Multipart/Form-Data ; boundary=x")).isTrue();
        assertThat(MultipartDecoder.isFormData("multipart/mixed; boundary=x")).isFalse();
        assertThat(MultipartDecoder.isFormData("multipart/form-data-x; boundary=x")).isFalse();
    }

    @Test
    void fieldsTotalOfExactlyTheUndeclaredBound_isAccepted_oneByteMoreIsNot() {
        var atBound = "v".repeat((int) MultipartDecoder.DEFAULT_FIELDS_TOTAL);

        assertThat(decode(singleField(DISPOSITION_A, atBound), 64 * 1024, UNLIMITED, TYPE).refusal()).isEmpty();
        assertThat(decode(singleField(DISPOSITION_A, atBound + "v"), 64 * 1024, UNLIMITED, TYPE).refusal()).isNotEmpty();
    }

    @Test
    void routeDeclaringMaxFieldSize_isNotHeldToTheUndeclaredTotal() {
        var value = "v".repeat(600 * 1024);
        var body = ascii("--" + BOUNDARY + "\r\n" + DISPOSITION_A + "\r\n\r\n" + value + "\r\n--" + BOUNDARY + "\r\n"
                         + "Content-Disposition: form-data; name=\"b\"\r\n\r\n" + value + "\r\n--" + BOUNDARY + "--\r\n");
        var spec = UploadSpec.uploadSpec(true, 0, 0, 0, 0, 0, 700 * 1024);

        assertThat(decode(body, 64 * 1024, spec, TYPE).refusal())
            .as("1.2 MiB of fields under a declared 700 KiB per field and the default part cap").isEmpty();
    }

    @Test
    void secondField_doesNotCarryTheFirstValue() {
        var body = ascii("--" + BOUNDARY + "\r\n" + DISPOSITION_A + "\r\n\r\n1\r\n--" + BOUNDARY
                         + "\r\nContent-Disposition: form-data; name=\"b\"\r\n\r\n2\r\n--" + BOUNDARY + "--\r\n");

        assertThat(decode(body, 7, UNLIMITED, TYPE).events()).containsExactly("field:a=1", "field:b=2");
    }

    @Test
    void fieldValue_isDecodedAsUtf8_acrossChunks() {
        var value = "h\u00e9llo \u20ac \u4e16\u754c";
        var body = ("--" + BOUNDARY + "\r\n" + DISPOSITION_A + "\r\n\r\n" + value + "\r\n--" + BOUNDARY + "--\r\n")
            .getBytes(StandardCharsets.UTF_8);

        assertThat(decode(body, 3, UNLIMITED, TYPE).events()).containsExactly("field:a=" + value);
    }

    @Test
    void delimiterFollowedByOneDashAndAnotherByte_isBadDelimiter_eitherOrder() {
        var dashFirst = ascii("--" + BOUNDARY + "-xzz");
        var dashSecond = ascii("--" + BOUNDARY + "x-zz");

        assertThat(decode(dashFirst, 4, UNLIMITED, TYPE).refusal()).contains(UploadIngestError.Malformed.BAD_DELIMITER);
        assertThat(decode(dashSecond, 4, UNLIMITED, TYPE).refusal()).contains(UploadIngestError.Malformed.BAD_DELIMITER);
    }

    @Test
    void headerBlockOfExactlyTheBound_isAccepted() {
        var prefix = DISPOSITION_A + "\r\nX-Pad: ";
        var pad = "p".repeat(MultipartDecoder.MAX_HEADER_BYTES - prefix.length() - 4);
        var body = singleField(prefix + pad, "x");

        assertThat(decode(body, 1000, UNLIMITED, TYPE).refusal()).isEmpty();
    }

    @Test
    void partWithNoHeaderAtAll_isRefusedNoDisposition() {
        var body = ascii("--" + BOUNDARY + "\r\n\r\nx\r\n--" + BOUNDARY + "--\r\n");

        assertThat(decode(body, 4, UNLIMITED, TYPE).refusal()).contains(UploadIngestError.Malformed.NO_DISPOSITION);
    }

    @Test
    void headerLineWithEmptyName_isRefusedBadHeaders() {
        var body = singleField(DISPOSITION_A + "\r\n: v", "x");

        assertThat(decode(body, 4, UNLIMITED, TYPE).refusal()).contains(UploadIngestError.Malformed.BAD_HEADERS);
    }

    @Test
    void dispositionThatIsNotFormData_isRefusedNoDisposition() {
        var body = singleField("Content-Disposition: attachment; name=\"a\"", "x");

        assertThat(decode(body, 4, UNLIMITED, TYPE).refusal()).contains(UploadIngestError.Malformed.NO_DISPOSITION);
    }

    @Test
    void anyNestedMultipartType_isRefusedNested_notOnlyMixed() {
        var body = singleField(DISPOSITION_A + "\r\nContent-Type: multipart/alternative; boundary=inner", "x");

        assertThat(decode(body, 4, UNLIMITED, TYPE).refusal()).contains(UploadIngestError.Malformed.NESTED_MULTIPART);
    }

    @Test
    void textPlainPartWithoutFilename_isAField_notAFile() {
        var body = singleField(DISPOSITION_A + "\r\nContent-Type: text/plain; charset=utf-8", "hello");

        assertThat(decode(body, 4, UNLIMITED, TYPE).events()).containsExactly("field:a=hello");
    }

    @Test
    void fieldBytesAcrossChunks_areSummed_againstMaxFieldSize() {
        var body = singleField(DISPOSITION_A, "abcdef");

        assertThat(decode(body, 3, UploadSpec.uploadSpec(true, 0, 0, 0, 0, 0, 5), TYPE).refusal())
            .contains(UploadIngestError.LimitExceeded.limitExceeded("max_field_size", 5));
    }
}
