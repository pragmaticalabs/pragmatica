package org.pragmatica.http.routing;

import java.util.Locale;

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

    /// Names that differ only in case are one field: every value survives, in arrival order.
    @Test
    void trailers_namesDifferingOnlyInCase_mergeEveryValue_inArrivalOrder() {
        var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload", Unpooled.EMPTY_BUFFER);

        request.trailingHeaders()
               .add("x-a", "1")
               .add("X-A", "2")
               .add("x-a", "3");

        var context = RequestContext.RequestContextImpl.requestContext(request, ROUTE, JsonCodecAdapter.defaultCodec(), "req-3");

        assertThat(context.trailers().getAll("x-a")).containsExactly("1", "2", "3");
    }

    /// Under `tr-TR` the default-locale lower case of `AUTHORIZATION` is `authorızatıon`, which the forbidden-field set
    /// does not contain; this wrapper folds names with `Locale.ROOT` like the transports.
    @Test
    void trailers_forbiddenNamesInUpperCase_areDiscarded_underATurkishDefaultLocale() {
        var saved = Locale.getDefault();

        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload", Unpooled.EMPTY_BUFFER);

            request.trailingHeaders()
                   .add("AUTHORIZATION", "Bearer forged")
                   .add("X-OK", "yes");

            var context = RequestContext.RequestContextImpl.requestContext(request,
                                                                            ROUTE,
                                                                            JsonCodecAdapter.defaultCodec(),
                                                                            "req-4");

            assertThat(context.trailers().asMap()).containsOnlyKeys("x-ok");
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    void trailers_ofARequestWithoutATrailerSection_areEmpty() {
        var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload", Unpooled.EMPTY_BUFFER);

        request.headers().add("x-client", "probe");

        var context = RequestContext.RequestContextImpl.requestContext(request, ROUTE, JsonCodecAdapter.defaultCodec(), "req-2");

        assertThat(context.trailers().asMap()).isEmpty();
    }
}
