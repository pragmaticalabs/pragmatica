package org.pragmatica.http.routing;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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

            result.onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
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

            result.onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
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

            result.onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
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

            result.onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
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

            result.onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
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
            result.onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"));
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

            result.onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
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
