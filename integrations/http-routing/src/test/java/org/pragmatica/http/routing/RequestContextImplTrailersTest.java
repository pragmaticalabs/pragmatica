package org.pragmatica.http.routing;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// #2310: [RequestContext.RequestContextImpl] wraps an aggregated Netty request, so it passes the request's chunked
/// trailer section on as [RequestContext#trailers()], under the shared forbidden-field policy.
class RequestContextImplTrailersTest {
    private static final Route<String> ROUTE = Route.<String> post("/upload")
                                                    .withoutParameters()
                                                    .to(_ -> Promise.success("ok"))
                                                    .asText();

    @Test
    void trailers_areTheAggregatedRequestsTrailingHeaders_withoutForbiddenFields() {
        var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload", Unpooled.EMPTY_BUFFER);

        request.trailingHeaders()
               .add("X-Checksum", "abc123")
               .add("authorization", "Bearer forged");

        var context = RequestContext.RequestContextImpl.requestContext(request, ROUTE, JsonCodecAdapter.defaultCodec(), "req-1");

        assertThat(context.trailers().get("x-checksum")).isEqualTo(Option.some("abc123"));
        assertThat(context.trailers().asMap()).containsOnlyKeys("x-checksum");
        assertThat(context.headers().get("x-checksum").isEmpty()).isTrue();
    }

    @Test
    void trailers_ofARequestWithoutATrailerSection_areEmpty() {
        var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload", Unpooled.EMPTY_BUFFER);

        request.headers().add("x-client", "probe");

        var context = RequestContext.RequestContextImpl.requestContext(request, ROUTE, JsonCodecAdapter.defaultCodec(), "req-2");

        assertThat(context.trailers().asMap()).isEmpty();
    }
}
