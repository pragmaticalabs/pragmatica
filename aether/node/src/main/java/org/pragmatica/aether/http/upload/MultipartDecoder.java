// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.upload;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.pragmatica.http.routing.UploadSpec;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.aether.http.upload.UploadIngestError.LimitExceeded.limitExceeded;

/// A push-style `multipart/form-data` decoder (RFC 7578, framing per RFC 2046 §5.1.1) for a body that arrives in chunks
/// split anywhere: inside a delimiter, inside a part's header block, inside the two bytes after a delimiter (U2).
///
/// [#push] turns one chunk into [Event]s: a file part's content comes out as slices of that chunk ([Event.FileData]),
/// valid only as long as the chunk is; the few bytes that might begin a delimiter are held back, copied, until the next
/// chunk tells. A text field's value is buffered here and comes out whole ([Event.Field]). Nothing else is kept between
/// chunks, so the decoder holds at most one part header block, one field value and a delimiter's length.
///
/// Classification follows the aggregated path's: a part whose Content-Type is `multipart/*` is nested and refused (400);
/// a part with a `filename` parameter, or with a Content-Type other than `text/plain`, is a file; every other part is a
/// field. Limits, each refused 413 at the byte or part that crosses it: the route's `max_files`, `max_file_size`,
/// `max_fields`, `max_field_size`; the part count cap of the aggregated path (128, or `max_files + max_fields` when
/// larger); and, when the route does not declare `max_field_size`, 1 MiB of field values in all [G: the bound].
///
/// Not thread-safe: the sink pushes one chunk at a time, after the previous one was handled. A refusal is final: every
/// later call answers it again.
final class MultipartDecoder {
    /// The fields total of one body when the route declares no `max_field_size` [G].
    static final long DEFAULT_FIELDS_TOTAL = 1024 * 1024;
    /// The largest part header block read [G: the bound].
    static final int MAX_HEADER_BYTES = 8 * 1024;
    /// The aggregated path's part cap (`MultipartParser.DEFAULT_MAX_PARTS`), kept equal by value: that parser goes away.
    static final long DEFAULT_MAX_PARTS = 128;
    private static final byte[] CRLF = {'\r', '\n'};
    private static final int CRLF_BITS = 0x0D0A;
    private static final int HEADER_END_BITS = 0x0D0A0D0A;
    private static final byte[] NO_BYTES = new byte[0];

    /// What one chunk decodes to, in body order.
    sealed interface Event {
        /// A file part begins; its content follows as [FileData] until [FileEnd].
        record FileStart(String field, Option<String> fileName, String contentType) implements Event {}

        /// Content of the open file part: a slice of the pushed chunk, or a copy of the bytes held back from the one before.
        record FileData(ByteBuffer bytes) implements Event {}

        /// The open file part ended at its delimiter.
        record FileEnd() implements Event {}

        /// A whole text field.
        record Field(String name, String value) implements Event {}
    }

    private enum State {
        PREAMBLE,
        AFTER_DELIMITER,
        HEADERS,
        BODY,
        EPILOGUE
    }

    private enum Kind {
        FILE,
        FIELD
    }

    private final byte[] delimiter;
    private final UploadSpec spec;
    private final long partCap;
    private State state = State.PREAMBLE;
    private Option<UploadIngestError> refusal = Option.none();
    private byte[] carry = CRLF.clone();
    private final ByteArrayOutputStream headerBlock = new ByteArrayOutputStream();
    private final ByteArrayOutputStream fieldValue = new ByteArrayOutputStream();
    private Kind kind = Kind.FIELD;
    private String partName = "";
    private int headerTail;
    private long partBytes;
    private long parts;
    private long files;
    private long fields;
    private long fieldsTotal;

    private MultipartDecoder(byte[] delimiter, UploadSpec spec) {
        this.delimiter = delimiter;
        this.spec = spec;
        this.partCap = Math.max(DEFAULT_MAX_PARTS, spec.maxFiles() + spec.maxFields());
    }

    /// A decoder for a body of `contentType`, under the part limits of `spec`; refused when the type carries no usable
    /// boundary (1 to 70 characters, RFC 2046 §5.1.1).
    ///
    /// The first delimiter of a body may open it without a preceding CRLF, so the decoder starts as if one had arrived.
    static Result<MultipartDecoder> multipartDecoder(String contentType, UploadSpec spec) {
        return HeaderParameters.parameter(contentType, "boundary")
                               .filter(boundary -> !boundary.isEmpty() && boundary.length() <= 70)
                               .map(boundary -> new MultipartDecoder(("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1),
                                                                     spec))
                               .toResult(UploadIngestError.Malformed.NO_BOUNDARY);
    }

    /// Whether `contentType` is `multipart/form-data`, whatever its case and parameters.
    static boolean isFormData(String contentType) {
        return HeaderParameters.mediaType(contentType)
                               .equals("multipart/form-data");
    }

    /// The events `chunk` completes, or the refusal it reached. The returned [Event.FileData] slices share `chunk`'s
    /// content and are valid only while it is.
    Result<List<Event>> push(ByteBuffer chunk) {
        var events = new ArrayList<Event>();
        var input = new Input(carry, chunk);

        carry = NO_BYTES;

        var position = 0;

        while (refusal.isEmpty() && position < input.length()) {
            position = step(input, position, events);
        }

        return refusal.fold(() -> Result.success(List.copyOf(events)),
                            UploadIngestError::result);
    }

    /// The body ended: refused unless its closing delimiter was read.
    Result<Unit> finish() {
        return refusal.fold(() -> state == State.EPILOGUE
                                  ? Result.unitResult()
                                  : refuse(UploadIngestError.Malformed.NOT_CLOSED).result(),
                            UploadIngestError::result);
    }

    /// Advances over `input` from `position` by one state's worth and answers the next position.
    private int step(Input input, int position, List<Event> events) {
        return switch (state) {
            case PREAMBLE, BODY -> scanContent(input, position, events);
            case AFTER_DELIMITER -> afterDelimiter(input, position);
            case HEADERS -> readHeaders(input, position, events);
            case EPILOGUE -> input.length();
        };
    }

    /// Content up to the next delimiter: emitted (in a part) or skipped (the preamble). Without a delimiter in sight, the
    /// longest tail that could still begin one is held back for the next chunk.
    private int scanContent(Input input, int position, List<Event> events) {
        var found = input.indexOf(delimiter, position);

        if (found >= 0) {
            content(input, position, found, events);
            endPart(events);

            return found + delimiter.length;
        }

        var held = input.delimiterPrefixAtEnd(delimiter, position);
        var end = input.length() - held;

        content(input, position, end, events);
        carry = input.copy(end, input.length());

        return input.length();
    }

    private void content(Input input, int from, int to, List<Event> events) {
        if (state == State.BODY && to > from) {
            if (kind == Kind.FILE) {
                fileContent(input, from, to, events);
            } else {
                fieldContent(input, from, to);
            }
        }
    }

    private void fileContent(Input input, int from, int to, List<Event> events) {
        partBytes += to - from;

        if (spec.maxFileBytes() > 0 && partBytes > spec.maxFileBytes()) {
            refuse(limitExceeded("max_file_size", spec.maxFileBytes()));

            return;
        }

        input.slices(from, to)
             .forEach(slice -> events.add(new Event.FileData(slice)));
    }

    private void fieldContent(Input input, int from, int to) {
        partBytes += to - from;
        fieldsTotal += to - from;

        if (spec.maxFieldBytes() > 0 && partBytes > spec.maxFieldBytes()) {
            refuse(limitExceeded("max_field_size", spec.maxFieldBytes()));

            return;
        }

        if (spec.maxFieldBytes() == 0 && fieldsTotal > DEFAULT_FIELDS_TOTAL) {
            refuse(limitExceeded("the fields total", DEFAULT_FIELDS_TOTAL));

            return;
        }

        fieldValue.writeBytes(input.copy(from, to));
    }

    private void endPart(List<Event> events) {
        if (state == State.BODY) {
            events.add(kind == Kind.FILE
                       ? new Event.FileEnd()
                       : new Event.Field(partName, fieldValue.toString(StandardCharsets.UTF_8)));
            fieldValue.reset();
        }

        state = State.AFTER_DELIMITER;
    }

    /// The two bytes after a delimiter: `--` closes the body, CRLF opens a part; anything else is malformed.
    private int afterDelimiter(Input input, int position) {
        if (input.length() - position < 2) {
            carry = input.copy(position, input.length());

            return input.length();
        }

        var first = input.at(position);
        var second = input.at(position + 1);

        if (first == '-' && second == '-') {
            state = State.EPILOGUE;
        } else if (first == '\r' && second == '\n') {
            state = State.HEADERS;
            headerBlock.reset();
            headerTail = 0;
        } else {
            refuse(UploadIngestError.Malformed.BAD_DELIMITER);
        }

        return position + 2;
    }

    /// The part's header block, byte by byte (it is small and bounded), up to the empty line that ends it.
    private int readHeaders(Input input, int position, List<Event> events) {
        var next = position;

        while (next < input.length() && state == State.HEADERS && refusal.isEmpty()) {
            var octet = input.at(next++);

            headerBlock.write(octet);
            headerTail = (headerTail << 8) | (octet & 0xFF);
            headerByte(events);
        }

        return next;
    }

    private void headerByte(List<Event> events) {
        if (headerBlock.size() > MAX_HEADER_BYTES) {
            refuse(UploadIngestError.Malformed.HEADERS_TOO_LARGE);
        } else if (headerBlockEnded()) {
            PartHeaders.partHeaders(headerBlock.toString(StandardCharsets.UTF_8))
                       .onFailure(cause -> refuse(UploadIngestError.ingestError(cause)))
                       .onSuccess(headers -> startPart(headers, events));
        }
    }

    /// The block ends at an empty line: CRLF CRLF, or a lone CRLF when the part has no header at all. Read from the last
    /// four bytes, kept as they arrive, so the check does not copy the block.
    private boolean headerBlockEnded() {
        return headerBlock.size() == 2
               ? (headerTail & 0xFFFF) == CRLF_BITS
               : headerBlock.size() >= 4 && headerTail == HEADER_END_BITS;
    }

    /// A nested part is refused before the part count is checked, as on the aggregated path: a nested part at the cap is
    /// answered 400, not 413.
    private void startPart(PartHeaders headers, List<Event> events) {
        if (headers.isNested()) {
            refuse(UploadIngestError.Malformed.NESTED_MULTIPART);
        } else if (++parts > partCap) {
            refuse(limitExceeded("the part count", partCap));
        } else if (headers.isFile()) {
            startFile(headers, events);
        } else {
            startField(headers);
        }
    }

    private void startFile(PartHeaders headers, List<Event> events) {
        if (spec.maxFiles() > 0 && ++files > spec.maxFiles()) {
            refuse(limitExceeded("max_files", spec.maxFiles()));

            return;
        }

        open(Kind.FILE, headers.name());
        events.add(new Event.FileStart(headers.name(),
                                       headers.fileName()
                                              .filter(fileName -> !fileName.isEmpty()),
                                       headers.contentType()));
    }

    private void startField(PartHeaders headers) {
        if (spec.maxFields() > 0 && ++fields > spec.maxFields()) {
            refuse(limitExceeded("max_fields", spec.maxFields()));

            return;
        }

        open(Kind.FIELD, headers.name());
    }

    private void open(Kind partKind, String name) {
        kind = partKind;
        partName = name;
        partBytes = 0;
        state = State.BODY;
    }

    private UploadIngestError refuse(UploadIngestError cause) {
        refusal = refusal.orElse(() -> Option.some(cause));

        return cause;
    }

    /// The bytes held back from the previous chunk followed by the pushed one, indexed as one sequence without copying the
    /// chunk.
    private record Input(byte[] held, ByteBuffer chunk) {
        int length() {
            return held.length + chunk.remaining();
        }

        byte at(int index) {
            return index < held.length
                   ? held[index]
                   : chunk.get(chunk.position() + index - held.length);
        }

        /// The first index at or after `from` where `pattern` occurs whole, or -1.
        int indexOf(byte[] pattern, int from) {
            var last = length() - pattern.length;

            for (var start = from; start <= last; start++) {
                if (matchesAt(pattern, start, pattern.length)) {
                    return start;
                }
            }

            return -1;
        }

        /// The length of the longest tail, starting at or after `from`, that is a proper prefix of `pattern`.
        int delimiterPrefixAtEnd(byte[] pattern, int from) {
            var longest = Math.min(pattern.length - 1, length() - from);

            for (var size = longest; size > 0; size--) {
                if (matchesAt(pattern, length() - size, size)) {
                    return size;
                }
            }

            return 0;
        }

        private boolean matchesAt(byte[] pattern, int start, int count) {
            for (var offset = 0; offset < count; offset++) {
                if (at(start + offset) != pattern[offset]) {
                    return false;
                }
            }

            return true;
        }

        /// `[from, to)` as buffers: a copy of the held part (it is not the caller's), a slice of the chunk for the rest.
        List<ByteBuffer> slices(int from, int to) {
            var result = new ArrayList<ByteBuffer>(2);

            if (from < held.length) {
                result.add(ByteBuffer.wrap(Arrays.copyOfRange(held, from, Math.min(to, held.length))));
            }

            if (to > held.length) {
                var start = Math.max(from, held.length) - held.length;

                result.add(chunk.slice(chunk.position() + start, to - held.length - start));
            }

            return result;
        }

        byte[] copy(int from, int to) {
            var bytes = new byte[to - from];

            for (var index = from; index < to; index++) {
                bytes[index - from] = at(index);
            }

            return bytes;
        }
    }

    /// One part's header fields, by lower-cased name, and what they say about the part.
    record PartHeaders(String name, Option<String> fileName, Option<String> declaredType) {
        private static final String DEFAULT_FILE_TYPE = "application/octet-stream";

        static Result<PartHeaders> partHeaders(String block) {
            return fields(block).flatMap(PartHeaders::fromFields);
        }

        private static Result<Map<String, String>> fields(String block) {
            var fields = new HashMap<String, String>();

            for (var line : block.split("\r\n")) {
                if (line.isEmpty()) {
                    continue;
                }

                var colon = line.indexOf(':');

                if (colon <= 0) {
                    return UploadIngestError.Malformed.BAD_HEADERS.result();
                }

                fields.put(line.substring(0, colon)
                               .strip()
                               .toLowerCase(Locale.ROOT),
                           line.substring(colon + 1)
                               .strip());
            }

            return Result.success(fields);
        }

        private static Result<PartHeaders> fromFields(Map<String, String> fields) {
            var disposition = Option.option(fields.get("content-disposition"));
            var name = disposition.filter(value -> HeaderParameters.mediaType(value)
                                                                   .equals("form-data"))
                                  .flatMap(value -> HeaderParameters.parameter(value, "name"));

            return name.map(fieldName -> new PartHeaders(fieldName,
                                                         disposition.flatMap(value -> HeaderParameters.parameter(value,
                                                                                                                 "filename")),
                                                         Option.option(fields.get("content-type"))))
                       .toResult(UploadIngestError.Malformed.NO_DISPOSITION);
        }

        boolean isNested() {
            return declaredType.map(HeaderParameters::mediaType)
                               .filter(type -> type.startsWith("multipart/"))
                               .isPresent();
        }

        boolean isFile() {
            return fileName.isPresent() || declaredType.map(HeaderParameters::mediaType)
                                                       .filter(type -> !type.equals("text/plain"))
                                                       .isPresent();
        }

        /// The type the client declared for a file, or `application/octet-stream`.
        String contentType() {
            return declaredType.or(DEFAULT_FILE_TYPE);
        }
    }
}
