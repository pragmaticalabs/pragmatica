package org.pragmatica.http.routing;

import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpError;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.QueryParams;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import io.netty.handler.codec.http.DefaultHttpHeadersFactory;
import io.netty.handler.codec.http.HttpHeaders;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// I7/A6 on the http-routing path: a multipart route (`withMultipartBody`) fails a request that is not multipart
/// with a 415 and a malformed one with a 400, as a typed [HttpError] whose client text is fixed and carries no
/// parser internals. The handler is never invoked. Before the fix the failure was a plain cause, rendered 500 with
/// the decoder's exception text.
class MultipartRouteErrorTest {
    private static final String BOUNDARY = "B0undary";

    private final AtomicBoolean invoked = new AtomicBoolean();

    private Route<String> route() {
        return Route.<String> post("/upload")
                    .withMultipartBody()
                    .to(_ -> {
                        invoked.set(true);
                        return Promise.success("stored");
                    })
                    .asText();
    }

    private Cause failureOf(String contentType, String body) {
        var route = route();
        var ctx = new MultipartContext(route, contentType, body.getBytes(StandardCharsets.UTF_8));

        return route.handler()
                    .handle(ctx)
                    .await()
                    .fold(cause -> cause, value -> { throw new AssertionError("unexpected success: " + value); });
    }

    @Test
    void nonMultipartRequest_on_multipartRoute_is415_withFixedText() {
        var cause = failureOf("application/json", "{}");

        assertThat(cause).isInstanceOf(HttpError.class);
        assertThat(((HttpError) cause).status()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(HttpError.clientMessage(cause)).isEqualTo("Unsupported Media Type: Request is not multipart/form-data");
        assertThat(invoked).isFalse();
    }

    @Test
    void truncatedBody_is400_withFixedText() {
        var cause = failureOf("multipart/form-data; boundary=" + BOUNDARY,
                              "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"f\"; filename=\"a\"\r\n\r\nPART");

        assertThat(((HttpError) cause).status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(HttpError.clientMessage(cause)).isEqualTo("Bad Request: Multipart body is incomplete or malformed: no closing boundary delimiter");
        assertThat(invoked).isFalse();
    }

    @Test
    void undecodablePart_is400_withoutExceptionText() {
        var cause = failureOf("multipart/form-data; boundary=" + BOUNDARY,
                              "--" + BOUNDARY + "\r\nContent-Type: text/plain\r\n\r\nx\r\n--" + BOUNDARY + "--\r\n");

        assertThat(((HttpError) cause).status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(HttpError.clientMessage(cause)).isEqualTo("Bad Request: Multipart body could not be parsed")
                                                  .doesNotContain("Exception")
                                                  .doesNotContain("null");
        assertThat(invoked).isFalse();
    }

    private record MultipartContext(Route<?> route, String contentType, byte[] body) implements RequestContext {
        @Override public List<String> pathParams() {return List.of();}
        @Override public String path() {return "/upload";}
        @Override public String requestId() {return "req_multipart";}
        @Override public HttpMethod method() {return HttpMethod.POST;}
        @Override public Headers headers() {return Headers.headers(Map.of("content-type", List.of(contentType)));}
        @Override public QueryParams queryParams() {return QueryParams.queryParams(Map.of());}
        @Override public <T> Result<T> fromJson(TypeToken<T> literal) {return HttpStatus.BAD_REQUEST.with("unsupported").result();}
        @Override public HttpHeaders responseHeaders() {return DefaultHttpHeadersFactory.headersFactory().newHeaders();}
    }
}
