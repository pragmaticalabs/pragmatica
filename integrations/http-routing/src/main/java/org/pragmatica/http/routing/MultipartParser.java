package org.pragmatica.http.routing;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpConstants;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;


/// Parses multipart/form-data requests using Netty's built-in decoder.
///
/// Extracts form fields and file uploads into a {@link MultipartRequest}.
/// Resources are cleaned up after parsing completes.
///
/// A body is accepted only when it is structurally complete: the Content-Type names multipart/form-data (in any
/// letter case) with a boundary, and the body ends with its close delimiter. Anything else fails typed (see
/// [MultipartError]) instead of yielding the parts before a cut. A filename-less part that declares a Content-Type
/// other than `text/plain` (a Blob sent without a name) is a file with an empty filename, not a text field;
/// `text/plain` stays a field, as RFC 7578 §4.4 makes it the default type of a field.
public sealed interface MultipartParser {
    record unused() implements MultipartParser {}

    /// The default part limit: Netty's own default, which applied implicitly before it was made explicit here.
    int DEFAULT_MAX_PARTS = 128;
    /// Netty's default for bytes buffered while a part is undecided; passed explicitly only because the decoder
    /// constructor that takes a part limit also takes this.
    int MAX_BUFFERED_BYTES = 1024;

    /// Check whether the given Content-Type header indicates multipart/form-data.
    static boolean isMultipart(Option<String> contentType) {
        return contentType.map(MultipartScan::mediaType)
                          .filter(MultipartScan.MULTIPART_FORM_DATA::equals)
                          .isPresent();
    }

    /// Check whether the given Content-Type header indicates multipart/form-data.
    static boolean isMultipart(String contentType) {
        return isMultipart(Option.option(contentType));
    }

    /// Parse a multipart request from raw body bytes and Content-Type header, with the default part limit.
    ///
    /// @param body        the raw request body
    /// @param contentType the Content-Type header value (must include boundary)
    /// @param uri         the request URI (used by Netty decoder)
    /// @return parsed multipart request or error
    static Result<MultipartRequest> parse(byte[] body, String contentType, String uri) {
        return parse(body, contentType, uri, DEFAULT_MAX_PARTS);
    }

    /// Parse a multipart request from raw body bytes and Content-Type header.
    ///
    /// @param body        the raw request body
    /// @param contentType the Content-Type header value (must include boundary)
    /// @param uri         the request URI (used by Netty decoder)
    /// @param maxParts    the most parts (fields and files together) the body may carry; more fails with
    ///                    [MultipartError.TooManyParts]
    /// @return parsed multipart request or error
    static Result<MultipartRequest> parse(byte[] body, String contentType, String uri, int maxParts) {
        return Option.option(contentType)
                     .toResult(MultipartError.General.MISSING_CONTENT_TYPE)
                     .filter(MultipartError.General.NOT_MULTIPART, MultipartParser::isMultipart)
                     .flatMap(ct -> MultipartScan.framing(ct)
                                                 .toResult(MultipartError.General.MISSING_BOUNDARY))
                     .flatMap(framing -> parseFramed(body, framing, uri, maxParts));
    }

    /// Parse multipart from request headers map and body.
    ///
    /// @param body    the raw request body
    /// @param headers request headers (multi-value map)
    /// @param uri     the request URI
    /// @return parsed multipart request or error
    static Result<MultipartRequest> parse(byte[] body, Map<String, List<String>> headers, String uri) {
        var contentType = extractContentType(headers);

        return contentType.map(ct -> parse(body, ct, uri))
                          .or(MultipartError.General.MISSING_CONTENT_TYPE.result());
    }

    private static Result<MultipartRequest> parseFramed(byte[] body,
                                                        MultipartScan.Framing framing,
                                                        String uri,
                                                        int maxParts) {
        var parts = MultipartScan.scan(body, framing.boundary());

        return parts.closed()
               ? Result.lift(throwable -> parseError(throwable, maxParts),
                             () -> doParse(body, framing.normalizedContentType(), uri, maxParts, parts))
               : MultipartError.General.MISSING_CLOSE_DELIMITER.result();
    }

    @SuppressWarnings("JBCT-EX-01")  // Infrastructure resource cleanup: Netty decoder and request must be released
    private static MultipartRequest doParse(byte[] body,
                                            String contentType,
                                            String uri,
                                            int maxParts,
                                            MultipartScan.Parts parts) throws IOException {
        var content = Unpooled.wrappedBuffer(body);
        var nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uri, content);

        nettyRequest.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        nettyRequest.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        var factory = new DefaultHttpDataFactory(DefaultHttpDataFactory.MINSIZE);

        try {
            var decoder = new HttpPostRequestDecoder(factory,
                                                     nettyRequest,
                                                     HttpConstants.DEFAULT_CHARSET,
                                                     maxParts,
                                                     MAX_BUFFERED_BYTES);

            try {
                return extractMultipartData(decoder, parts);
            } finally {
                decoder.destroy();
            }
        } finally {
            factory.cleanAllHttpData();
            nettyRequest.release();
        }
    }

    /// A part whose content cannot be read fails the whole parse: it is never silently left out.
    private static MultipartRequest extractMultipartData(HttpPostRequestDecoder decoder,
                                                         MultipartScan.Parts parts) throws IOException {
        var fields = new LinkedHashMap<String, List<String>>();
        var files = new ArrayList<FileUpload>();

        for (var data : decoder.getBodyHttpDatas()) {
            collectHttpData(data, parts, fields, files);
        }

        return new MultipartRequest(fields, files);
    }

    private static void collectHttpData(InterfaceHttpData data,
                                        MultipartScan.Parts parts,
                                        Map<String, List<String>> fields,
                                        List<FileUpload> files) throws IOException {
        switch (data) {
            case io.netty.handler.codec.http.multipart.FileUpload fileUpload -> files.add(FileUpload.fileUpload(fileUpload.getName(),
                                                                                                                fileUpload.getFilename(),
                                                                                                                fileUpload.getContentType(),
                                                                                                                fileUpload.get()));
            case Attribute attribute -> collectAttribute(attribute, parts, fields, files);
            default -> { /* ignore unknown data types */ }
        }
    }

    /// I13: Netty makes every filename-less part an attribute; one that declared a non-text Content-Type is a file.
    private static void collectAttribute(Attribute attribute,
                                         MultipartScan.Parts parts,
                                         Map<String, List<String>> fields,
                                         List<FileUpload> files) throws IOException {
        var declaredType = parts.nextFieldType(attribute.getName());

        if (declaredType.isPresent()) {
            files.add(FileUpload.fileUpload(attribute.getName(), "", declaredType.unwrap(), attribute.get()));
        } else {
            fields.computeIfAbsent(attribute.getName(), _ -> new ArrayList<>())
                  .add(attribute.getValue());
        }
    }

    /// The decoder's exception never reaches a client: it becomes the origin of a typed error with fixed text. A
    /// failure to READ a part (an I/O error, possibly wrapped by the decoder) is a server fault, not a malformed body.
    private static MultipartError parseError(Throwable throwable, int maxParts) {
        var cause = Causes.fromThrowable(throwable);

        return switch (throwable) {
            case HttpPostRequestDecoder.TooManyFormFieldsException _ -> MultipartError.TooManyParts.FACTORY.apply(maxParts);
            case Throwable t when isIoFailure(t) -> MultipartError.ReadFailed.FACTORY.apply(cause);
            default -> MultipartError.ParseFailed.FACTORY.apply(cause);
        };
    }

    private static boolean isIoFailure(Throwable throwable) {
        return throwable instanceof IOException || throwable.getCause() instanceof IOException;
    }

    private static Option<String> extractContentType(Map<String, List<String>> headers) {
        return Option.option(headers.get("content-type"))
                     .filter(values -> !values.isEmpty())
                     .map(List::getFirst)
                     .orElse(() -> Option.option(headers.get("Content-Type"))
                                         .filter(values -> !values.isEmpty())
                                         .map(List::getFirst));
    }
}
