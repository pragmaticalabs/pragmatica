package org.pragmatica.http.routing;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
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
public sealed interface MultipartParser {
    record unused() implements MultipartParser {}

    /// Check whether the given Content-Type header indicates multipart/form-data.
    static boolean isMultipart(Option<String> contentType) {
        return contentType.map(ct -> ct.toLowerCase().startsWith("multipart/form-data"))
                          .or(false);
    }

    /// Check whether the given Content-Type header indicates multipart/form-data.
    static boolean isMultipart(String contentType) {
        return contentType != null && contentType.toLowerCase().startsWith("multipart/form-data");
    }

    /// Parse a multipart request from raw body bytes and Content-Type header.
    ///
    /// @param body        the raw request body
    /// @param contentType the Content-Type header value (must include boundary)
    /// @param uri         the request URI (used by Netty decoder)
    /// @return parsed multipart request or error
    static Result<MultipartRequest> parse(byte[] body, String contentType, String uri) {
        if (contentType == null) {
            return MultipartError.General.MISSING_CONTENT_TYPE.result();
        }
        if (!isMultipart(contentType)) {
            return MultipartError.General.NOT_MULTIPART.result();
        }
        return Result.lift(MultipartParser::liftParseError, () -> doParse(body, contentType, uri));
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

    private static MultipartRequest doParse(byte[] body, String contentType, String uri) {
        var content = Unpooled.wrappedBuffer(body);
        var nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uri, content);
        nettyRequest.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        nettyRequest.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        var factory = new DefaultHttpDataFactory(DefaultHttpDataFactory.MINSIZE);
        var decoder = new HttpPostRequestDecoder(factory, nettyRequest);
        try {
            return extractMultipartData(decoder);
        } finally {
            decoder.destroy();
            nettyRequest.release();
        }
    }

    private static MultipartRequest extractMultipartData(HttpPostRequestDecoder decoder) {
        var fields = new HashMap<String, String>();
        var files = new ArrayList<FileUpload>();

        for (var data : decoder.getBodyHttpDatas()) {
            collectHttpData(data, fields, files);
        }
        return MultipartRequest.multipartRequest(fields, files);
    }

    private static void collectHttpData(InterfaceHttpData data,
                                         Map<String, String> fields,
                                         List<FileUpload> files) {
        switch (data) {
            case io.netty.handler.codec.http.multipart.FileUpload fileUpload -> collectFileUpload(fileUpload, files);
            case Attribute attribute -> collectAttribute(attribute, fields);
            default -> { /* ignore unknown data types */ }
        }
    }

    private static void collectFileUpload(io.netty.handler.codec.http.multipart.FileUpload nettyUpload,
                                           List<FileUpload> files) {
        Result.lift(MultipartParser::liftParseError, nettyUpload::get)
              .onSuccess(content -> files.add(FileUpload.fileUpload(nettyUpload.getName(),
                                                                     nettyUpload.getFilename(),
                                                                     nettyUpload.getContentType(),
                                                                     content)));
    }

    private static void collectAttribute(Attribute attribute, Map<String, String> fields) {
        Result.lift(MultipartParser::liftParseError, attribute::getValue)
              .onSuccess(value -> fields.put(attribute.getName(), value))
              .onFailure(_ -> fields.put(attribute.getName(), ""));
    }

    private static MultipartError.ParseFailed liftParseError(Throwable throwable) {
        return new MultipartError.ParseFailed(throwable.getMessage());
    }

    private static Option<String> extractContentType(Map<String, List<String>> headers) {
        var lowercase = Option.option(headers.get("content-type"))
                              .filter(values -> !values.isEmpty())
                              .map(List::getFirst);

        if (lowercase.isPresent()) {
            return lowercase;
        }

        return Option.option(headers.get("Content-Type"))
                     .filter(values -> !values.isEmpty())
                     .map(List::getFirst);
    }
}
