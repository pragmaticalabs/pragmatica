package org.pragmatica.http.routing;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpError;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Result;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class MultipartParserTest {
    private static final String BOUNDARY = "----TestBoundary12345";
    private static final String CONTENT_TYPE = "multipart/form-data; boundary=" + BOUNDARY;

    @Nested
    class SingleFileUpload {
        @Test
        void parse_singleFile_extractedCorrectly() {
            var body = multipartBody(
                filePart("file", "test.txt", "text/plain", "Hello, World!")
            );

            var result = MultipartParser.parse(body, CONTENT_TYPE, "/upload");

            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(mp -> {
                      assertThat(mp.files()).hasSize(1);
                      assertThat(mp.fields()).isEmpty();
                      var file = mp.files().getFirst();
                      assertThat(file.fieldName()).isEqualTo("file");
                      assertThat(file.filename()).isEqualTo("test.txt");
                      assertThat(file.contentType()).isEqualTo("text/plain");
                      assertThat(new String(file.content(), StandardCharsets.UTF_8)).isEqualTo("Hello, World!");
                      assertThat(file.size()).isEqualTo(13);
                  });
        }
    }

    @Nested
    class MultipleFiles {
        @Test
        void parse_multipleFiles_allPresent() {
            var body = multipartBody(
                filePart("file1", "a.txt", "text/plain", "AAA"),
                filePart("file2", "b.png", "image/png", "PNG_DATA")
            );

            var result = MultipartParser.parse(body, CONTENT_TYPE, "/upload");

            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(mp -> {
                      assertThat(mp.files()).hasSize(2);
                      assertThat(mp.file("file1").isPresent()).isTrue();
                      assertThat(mp.file("file2").isPresent()).isTrue();
                      assertThat(mp.file("file1").unwrap().filename()).isEqualTo("a.txt");
                      assertThat(mp.file("file2").unwrap().filename()).isEqualTo("b.png");
                      assertThat(mp.file("file2").unwrap().contentType()).isEqualTo("image/png");
                  });
        }
    }

    @Nested
    class FieldsAndFiles {
        @Test
        void parse_fieldsAndFiles_bothAccessible() {
            var body = multipartBody(
                fieldPart("name", "John"),
                fieldPart("email", "john@example.com"),
                filePart("avatar", "photo.jpg", "image/jpeg", "JPEG_BINARY")
            );

            var result = MultipartParser.parse(body, CONTENT_TYPE, "/profile");

            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(mp -> {
                      assertThat(mp.hasFields()).isTrue();
                      assertThat(mp.hasFiles()).isTrue();
                      assertThat(mp.field("name").unwrap()).isEqualTo("John");
                      assertThat(mp.field("email").unwrap()).isEqualTo("john@example.com");
                      assertThat(mp.files()).hasSize(1);
                      assertThat(mp.file("avatar").unwrap().filename()).isEqualTo("photo.jpg");
                  });
        }
    }

    @Nested
    class BinaryContent {
        @Test
        void parse_binaryData_preservedExactly() {
            var binaryData = new byte[]{0x00, 0x01, (byte) 0xFF, (byte) 0xFE, 0x7F, (byte) 0x80};
            var body = multipartBodyWithBinary("data", "binary.bin", "application/octet-stream", binaryData);

            var result = MultipartParser.parse(body, CONTENT_TYPE, "/upload");

            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(mp -> {
                      assertThat(mp.files()).hasSize(1);
                      assertThat(mp.files().getFirst().content()).isEqualTo(binaryData);
                  });
        }
    }

    @Nested
    class ContentTypeDetection {
        @Test
        void parse_pdfContentType_detected() {
            var body = multipartBody(
                filePart("doc", "report.pdf", "application/pdf", "PDF_CONTENT")
            );

            var result = MultipartParser.parse(body, CONTENT_TYPE, "/upload");

            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(mp -> assertThat(mp.file("doc").unwrap().contentType()).isEqualTo("application/pdf"));
        }

        @Test
        void isMultipart_multipartContentType_returnsTrue() {
            assertThat(MultipartParser.isMultipart(CONTENT_TYPE)).isTrue();
        }

        @Test
        void isMultipart_jsonContentType_returnsFalse() {
            assertThat(MultipartParser.isMultipart("application/json")).isFalse();
        }

        @Test
        void isMultipart_nullContentType_returnsFalse() {
            assertThat(MultipartParser.isMultipart((String) null)).isFalse();
        }
    }

    @Nested
    class ErrorCases {
        @Test
        void parse_nonMultipartContentType_returnsError() {
            var result = MultipartParser.parse("body".getBytes(), "application/json", "/upload");

            assertThat(result.isFailure()).isTrue();
            result.onSuccess(_ -> fail("Expected failure"));
        }

        @Test
        void parse_nullContentType_returnsError() {
            var result = MultipartParser.parse("body".getBytes(), (String) null, "/upload");

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void parse_missingContentTypeInHeaders_returnsError() {
            var result = MultipartParser.parse("body".getBytes(), Map.of(), "/upload");

            assertThat(result.isFailure()).isTrue();
        }
    }

    @Nested
    class HeaderMapOverload {
        @Test
        void parse_withHeaderMap_extractsContentType() {
            var body = multipartBody(
                fieldPart("key", "value")
            );
            var headers = Map.of("content-type", List.of(CONTENT_TYPE));

            var result = MultipartParser.parse(body, headers, "/upload");

            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(mp -> assertThat(mp.field("key").unwrap()).isEqualTo("value"));
        }
    }

    @Nested
    class NonMultipartRegression {
        @Test
        void parse_regularJsonBody_failsGracefully() {
            var jsonBody = "{\"key\":\"value\"}".getBytes(StandardCharsets.UTF_8);
            var result = MultipartParser.parse(jsonBody, "application/json", "/api");

            assertThat(result.isFailure()).isTrue();
        }
    }

    /// I9/A5: a structurally broken body fails typed instead of yielding the parts before the break.
    @Nested
    class MalformedBodies {
        @Test
        void parse_missingCloseDelimiter_fails() {
            var body = (fieldPart("k", "v") + filePart("file", "cut.bin", "application/octet-stream", "PARTIAL"))
                .getBytes(StandardCharsets.UTF_8);

            var result = MultipartParser.parse(body, CONTENT_TYPE, "/upload");

            assertThat(failureOf(result)).isEqualTo(MultipartError.General.MISSING_CLOSE_DELIMITER);
        }

        @Test
        void parse_truncatedMidFile_fails() {
            var full = multipartBody(filePart("file", "a.bin", "application/octet-stream", "0123456789".repeat(100)));
            var truncated = Arrays.copyOf(full, full.length / 2);

            assertThat(MultipartParser.parse(truncated, CONTENT_TYPE, "/upload").isFailure()).isTrue();
        }

        @Test
        void parse_garbage_fails() {
            var result = MultipartParser.parse("this is not multipart at all".getBytes(StandardCharsets.UTF_8),
                                               CONTENT_TYPE,
                                               "/upload");

            assertThat(failureOf(result)).isEqualTo(MultipartError.General.MISSING_CLOSE_DELIMITER);
        }

        @Test
        void parse_wrongBoundary_fails() {
            var body = multipartBody(fieldPart("k", "v"));

            var result = MultipartParser.parse(body, "multipart/form-data; boundary=SomethingElse", "/upload");

            assertThat(failureOf(result)).isEqualTo(MultipartError.General.MISSING_CLOSE_DELIMITER);
        }

        @Test
        void parse_missingBoundaryParameter_fails() {
            var body = multipartBody(fieldPart("k", "v"));

            var result = MultipartParser.parse(body, "multipart/form-data", "/upload");

            assertThat(failureOf(result)).isEqualTo(MultipartError.General.MISSING_BOUNDARY);
        }

        @Test
        void parse_lfOnlyDelimiters_stillParse() {
            var body = ("--" + BOUNDARY + "\nContent-Disposition: form-data; name=\"k\"\n\nv\n--" + BOUNDARY + "--\n")
                .getBytes(StandardCharsets.UTF_8);

            var result = MultipartParser.parse(body, CONTENT_TYPE, "/upload");

            result.onFailure(cause -> fail(cause.message()))
                  .onSuccess(mp -> assertThat(mp.field("k").unwrap()).isEqualTo("v"));
        }

        @Test
        void parse_quotedBoundary_stillParses() {
            var body = multipartBody(fieldPart("k", "v"));

            var result = MultipartParser.parse(body, "multipart/form-data; boundary=\"" + BOUNDARY + "\"", "/upload");

            result.onFailure(cause -> fail(cause.message()))
                  .onSuccess(mp -> assertThat(mp.field("k").unwrap()).isEqualTo("v"));
        }
    }

    /// I8: media types are case-insensitive (RFC 2045); Netty is handed a normalised type.
    @Nested
    class ContentTypeCase {
        @Test
        void parse_mixedCaseMediaTypeAndParameter_parsesAsMultipart() {
            var body = multipartBody(fieldPart("k", "v"), filePart("file", "a.txt", "text/plain", "AAA"));

            var result = MultipartParser.parse(body, "Multipart/Form-Data; Boundary=" + BOUNDARY, "/upload");

            result.onFailure(cause -> fail(cause.message()))
                  .onSuccess(mp -> {
                      assertThat(mp.field("k").unwrap()).isEqualTo("v");
                      assertThat(mp.files()).hasSize(1);
                  });
        }
    }

    /// I6: the part limit is explicit, configurable, defaults to Netty's 128, and fails typed past it.
    @Nested
    class PartLimit {
        @Test
        void parse_atDefaultLimit_succeeds() {
            assertThat(MultipartParser.parse(fields(MultipartParser.DEFAULT_MAX_PARTS), CONTENT_TYPE, "/upload")
                                      .isSuccess()).isTrue();
        }

        @Test
        void parse_overDefaultLimit_failsTooManyParts() {
            var result = MultipartParser.parse(fields(MultipartParser.DEFAULT_MAX_PARTS + 1), CONTENT_TYPE, "/upload");

            assertThat(failureOf(result)).isInstanceOf(MultipartError.TooManyParts.class);
            assertThat(((MultipartError) failureOf(result)).message()).isEqualTo("Multipart body has more than 128 parts");
        }

        @Test
        void parse_overConfiguredLimit_failsTooManyParts() {
            var result = MultipartParser.parse(fields(4), CONTENT_TYPE, "/upload", 3);

            assertThat(failureOf(result)).isEqualTo(new MultipartError.TooManyParts(3, "Multipart body has more than 3 parts"));
        }

        private static byte[] fields(int count) {
            var parts = new String[count];

            for (int i = 0; i < count; i++) {
                parts[i] = fieldPart("f" + i, "v" + i);
            }

            return multipartBody(parts);
        }
    }

    /// I13: a filename-less part with a non-text Content-Type is a file, its bytes intact; text/plain stays a field.
    @Nested
    class FilenameLessParts {
        @Test
        void parse_partWithContentTypeButNoFilename_isAFile() {
            var binary = new byte[]{0x00, (byte) 0xFF, (byte) 0xC3, 0x28, 0x7F};
            var header = ("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"blob\"\r\n"
                          + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
            var footer = ("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8);
            var body = concat(header, binary, footer);

            var result = MultipartParser.parse(body, CONTENT_TYPE, "/upload");

            result.onFailure(cause -> fail(cause.message()))
                  .onSuccess(mp -> {
                      assertThat(mp.field("blob").isEmpty()).as("not decoded as text").isTrue();
                      var file = mp.file("blob").unwrap();
                      assertThat(file.contentType()).isEqualTo("application/octet-stream");
                      assertThat(file.optionalFilename().isEmpty()).isTrue();
                      assertThat(file.content()).isEqualTo(binary);
                  });
        }

        @Test
        void parse_textPlainPartWithoutFilename_staysAField() {
            var body = multipartBody("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"note\"\r\n"
                                     + "Content-Type: text/plain; charset=UTF-8\r\n\r\nhello\r\n");

            var result = MultipartParser.parse(body, CONTENT_TYPE, "/upload");

            result.onFailure(cause -> fail(cause.message()))
                  .onSuccess(mp -> {
                      assertThat(mp.field("note").unwrap()).isEqualTo("hello");
                      assertThat(mp.hasFiles()).isFalse();
                  });
        }
    }

    /// I10/A13: a repeated field name keeps every value; field(name) is the first.
    @Nested
    class DuplicateFields {
        @Test
        void parse_repeatedFieldName_keepsAllValuesInOrder() {
            var body = multipartBody(fieldPart("tag", "a"), fieldPart("tag", "b"));

            var result = MultipartParser.parse(body, CONTENT_TYPE, "/upload");

            result.onFailure(cause -> fail(cause.message()))
                  .onSuccess(mp -> {
                      assertThat(mp.fieldValues("tag")).containsExactly("a", "b");
                      assertThat(mp.field("tag").unwrap()).isEqualTo("a");
                      assertThat(mp.fields()).containsEntry("tag", "a");
                      assertThat(mp.fieldValues("absent")).isEmpty();
                  });
        }
    }

    /// I7/A6: every multipart failure is a client-status HttpError whose client text carries no parser internals.
    @Nested
    class ErrorStatus {
        @Test
        void notMultipart_is415() {
            var result = MultipartParser.parse("{}".getBytes(StandardCharsets.UTF_8), "application/json", "/upload");

            assertThat(((MultipartError) failureOf(result)).httpStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        }

        @Test
        void malformedBody_is400() {
            var result = MultipartParser.parse("garbage".getBytes(StandardCharsets.UTF_8), CONTENT_TYPE, "/upload");

            assertThat(((MultipartError) failureOf(result)).httpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void partWithoutDisposition_is400_withoutExceptionText() {
            var body = multipartBody("--" + BOUNDARY + "\r\nContent-Type: text/plain\r\n\r\nno disposition\r\n");

            var result = MultipartParser.parse(body, CONTENT_TYPE, "/upload");
            var error = (MultipartError) failureOf(result);

            assertThat(error).isInstanceOf(MultipartError.ParseFailed.class);
            assertThat(error.httpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(HttpError.clientMessage(error)).isEqualTo("Multipart body could not be parsed");
        }
    }

    private static Object failureOf(Result<?> result) {
        return result.fold(cause -> cause, value -> "succeeded: " + value);
    }

    private static byte[] concat(byte[]... chunks) {
        var out = new ByteArrayOutputStream();

        for (var chunk : chunks) {
            out.writeBytes(chunk);
        }

        return out.toByteArray();
    }

    // ================== Test Helpers ==================

    private static String filePart(String fieldName, String filename, String contentType, String content) {
        return "--" + BOUNDARY + "\r\n" +
               "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + filename + "\"\r\n" +
               "Content-Type: " + contentType + "\r\n" +
               "\r\n" +
               content + "\r\n";
    }

    private static String fieldPart(String name, String value) {
        return "--" + BOUNDARY + "\r\n" +
               "Content-Disposition: form-data; name=\"" + name + "\"\r\n" +
               "\r\n" +
               value + "\r\n";
    }

    private static byte[] multipartBody(String... parts) {
        var sb = new StringBuilder();
        for (var part : parts) {
            sb.append(part);
        }
        sb.append("--").append(BOUNDARY).append("--\r\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] multipartBodyWithBinary(String fieldName, String filename,
                                                   String contentType, byte[] binaryData) {
        var header = ("--" + BOUNDARY + "\r\n" +
                      "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + filename + "\"\r\n" +
                      "Content-Type: " + contentType + "\r\n" +
                      "\r\n").getBytes(StandardCharsets.UTF_8);
        var footer = ("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8);

        var result = new byte[header.length + binaryData.length + footer.length];
        System.arraycopy(header, 0, result, 0, header.length);
        System.arraycopy(binaryData, 0, result, header.length, binaryData.length);
        System.arraycopy(footer, 0, result, header.length + binaryData.length, footer.length);
        return result;
    }
}
