package org.pragmatica.http.routing;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.option;


/// The structural reading of a multipart/form-data request that Netty's decoder does not give: the boundary taken
/// from a Content-Type in any letter case, whether the body ends with its close delimiter, and which filename-less
/// parts declared a Content-Type. Package-private: [MultipartParser] is its only user.
///
/// Netty's decoder accepts a body without a close delimiter (it returns the parts before the cut and drops the
/// rest), falls back to urlencoded decoding when the boundary parameter is absent or the media type is not
/// lowercase, and decodes every filename-less part as a text attribute, whatever its Content-Type. This scan only
/// reads part headers and delimiter lines; Netty still decodes the bodies.
sealed interface MultipartScan {
    record unused() implements MultipartScan {}

    String MULTIPART_FORM_DATA = "multipart/form-data";

    /// The boundary and optional charset of a multipart/form-data Content-Type, read case-insensitively.
    record Framing(String boundary, Option<String> charset) {
        static Framing framing(String boundary, Option<String> charset) {
            return new Framing(boundary, charset);
        }

        /// The Content-Type handed to Netty: media type and parameter names in lowercase (Netty matches them
        /// case-sensitively), the boundary quoted so any RFC 2046 boundary character survives.
        String normalizedContentType() {
            return MULTIPART_FORM_DATA + "; boundary=\"" + boundary + "\"" + charset.map(cs -> "; charset=" + cs)
                                                                                    .or("");
        }
    }

    /// What the scan found. `fieldTypes` holds, per field name and in body order, one entry for each filename-less
    /// part: its Content-Type when it declared one other than `text/plain`, empty otherwise. Thread-confined working
    /// data of one parse, consumed by [#nextFieldType(String)].
    record Parts(boolean closed, Map<String, Deque<Option<String>>> fieldTypes) {
        static Parts parts(boolean closed, Map<String, Deque<Option<String>>> fieldTypes) {
            return new Parts(closed, fieldTypes);
        }

        /// The declared Content-Type of the next filename-less part named `name`, in body order.
        Option<String> nextFieldType(String name) {
            return option(fieldTypes.get(name)).flatMap(queue -> option(queue.pollFirst()))
                         .flatMap(type -> type);
        }
    }

    static String mediaType(String contentType) {
        var semicolon = contentType.indexOf(';');

        return (semicolon < 0
                ? contentType
                : contentType.substring(0, semicolon)).trim()
                                                      .toLowerCase(Locale.ROOT);
    }

    static Option<Framing> framing(String contentType) {
        var parameters = parameters(contentType);

        return option(parameters.get("boundary")).filter(boundary -> !boundary.isEmpty())
                     .map(boundary -> Framing.framing(boundary,
                                                      option(parameters.get("charset"))));
    }

    @SuppressWarnings("JBCT-PAT-01")  // Byte-level scan of delimiter lines and part headers
    static Parts scan(byte[] body, String boundary) {
        var delimiter = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        var fieldTypes = new HashMap<String, Deque<Option<String>>>();
        var position = indexOf(body, delimiter, 0);

        while (position >= 0) {
            var after = position + delimiter.length;

            if (!atLineStart(body, position)) {
                position = indexOf(body, delimiter, position + 1);
                continue;
            }

            if (startsWith(body, after, "--")) {
                return Parts.parts(true, fieldTypes);
            }

            var headersStart = delimiterLineEnd(body, after);

            if (headersStart < 0) {
                position = indexOf(body, delimiter, position + 1);
                continue;
            }

            var headersEnd = headerBlockEnd(body, headersStart);

            if (headersEnd < 0) {
                return Parts.parts(false, fieldTypes);
            }

            recordPart(new String(body, headersStart, headersEnd - headersStart, StandardCharsets.UTF_8), fieldTypes);
            position = indexOf(body, delimiter, headersEnd);
        }

        return Parts.parts(false, fieldTypes);
    }

    /// One part's header block. A part with a filename is a file to Netty already and is not recorded; a nested
    /// `multipart/*` part becomes files inside Netty too.
    private static void recordPart(String headerBlock, Map<String, Deque<Option<String>>> fieldTypes) {
        var headers = partHeaders(headerBlock);
        var contentType = option(headers.get("content-type"));

        option(headers.get("content-disposition")).map(MultipartScan::parameters)
              .filter(params -> !params.containsKey("filename") && !params.containsKey("filename*"))
              .flatMap(params -> option(params.get("name")))
              .onPresent(fieldName -> recordField(fieldTypes, fieldName, contentType));
    }

    /// Lowercase header names to values; the first occurrence of a name wins.
    private static Map<String, String> partHeaders(String headerBlock) {
        var headers = new HashMap<String, String>();

        for (var rawLine : headerBlock.split("\n")) {
            var line = rawLine.strip();
            var colon = line.indexOf(':');

            if (colon > 0) {
                headers.putIfAbsent(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                                    line.substring(colon + 1).trim());
            }
        }

        return headers;
    }

    private static void recordField(Map<String, Deque<Option<String>>> fieldTypes,
                                    String fieldName,
                                    Option<String> contentType) {
        if (contentType.map(MultipartScan::mediaType).filter(type -> type.startsWith("multipart/")).isPresent()) {
            return;
        }

        fieldTypes.computeIfAbsent(cleanName(fieldName),
                                   _ -> new ArrayDeque<>())
                  .addLast(contentType.filter(type -> !mediaType(type).equals("text/plain")));
    }

    /// Netty's own cleaning of a disposition parameter (`HttpPostMultipartRequestDecoder.cleanString`), so a name read
    /// here matches the name Netty gives the attribute.
    private static String cleanName(String raw) {
        var cleaned = new StringBuilder(raw.length());

        for (var ch : raw.toCharArray()) {
            switch (ch) {
                case ':', ',', '=', ';', '\t' -> cleaned.append(' ');
                case '"' -> {}
                default -> cleaned.append(ch);
            }
        }

        return cleaned.toString()
                      .trim();
    }

    /// `type; key=value; key="quoted;value"` → lowercase keys to unquoted values. The leading token is skipped.
    private static Map<String, String> parameters(String headerValue) {
        var parameters = new HashMap<String, String>();
        var segments = splitOutsideQuotes(headerValue);

        for (var segment : segments.subList(1, segments.size())) {
            var equals = segment.indexOf('=');

            if (equals > 0) {
                parameters.putIfAbsent(segment.substring(0, equals).trim().toLowerCase(Locale.ROOT),
                                       unquote(segment.substring(equals + 1).trim()));
            }
        }

        return parameters;
    }

    private static List<String> splitOutsideQuotes(String value) {
        var segments = new ArrayList<String>();
        var current = new StringBuilder();
        var quoted = false;

        for (var ch : value.toCharArray()) {
            if (ch == '"') {
                quoted = !quoted;
            }

            if (ch == ';' && !quoted) {
                segments.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }

        segments.add(current.toString());

        return segments;
    }

    private static String unquote(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
               ? value.substring(1, value.length() - 1)
               : value;
    }

    private static boolean atLineStart(byte[] body, int position) {
        return position == 0 || body[position - 1] == '\n';
    }

    private static boolean startsWith(byte[] body, int position, String prefix) {
        if (position + prefix.length() > body.length) {
            return false;
        }

        for (int i = 0; i < prefix.length(); i++) {
            if (body[position + i] != prefix.charAt(i)) {
                return false;
            }
        }

        return true;
    }

    /// The index after the delimiter line's line break, allowing RFC 2046 transport padding; -1 if what follows the
    /// boundary is not whitespace up to a line break (so it is not a delimiter).
    private static int delimiterLineEnd(byte[] body, int position) {
        for (int i = position; i < body.length; i++) {
            if (body[i] == '\n') {
                return i + 1;
            }

            if (body[i] != ' ' && body[i] != '\t' && body[i] != '\r') {
                return -1;
            }
        }

        return -1;
    }

    /// The index where the empty line ending the header block starts; -1 if the block never ends.
    private static int headerBlockEnd(byte[] body, int start) {
        var lineStart = start;

        for (int i = start; i < body.length; i++) {
            if (body[i] != '\n') {
                continue;
            }

            if (i == lineStart || (i == lineStart + 1 && body[lineStart] == '\r')) {
                return lineStart;
            }

            lineStart = i + 1;
        }

        return -1;
    }

    private static int indexOf(byte[] body, byte[] pattern, int from) {
        var last = body.length - pattern.length;

        for (int i = Math.max(from, 0); i <= last; i++) {
            if (matchesAt(body, pattern, i)) {
                return i;
            }
        }

        return -1;
    }

    private static boolean matchesAt(byte[] body, byte[] pattern, int position) {
        for (int j = 0; j < pattern.length; j++) {
            if (body[position + j] != pattern[j]) {
                return false;
            }
        }

        return true;
    }
}
