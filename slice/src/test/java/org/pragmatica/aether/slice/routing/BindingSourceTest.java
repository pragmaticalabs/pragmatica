package org.pragmatica.aether.slice.routing;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BindingSourceTest {

    @Test
    void parse_succeeds_withBody() {
        BindingSource.parse("body")
                     .onFailureRun(Assertions::fail)
                     .onSuccess(source -> {
                         assertThat(source).isInstanceOf(BindingSource.Body.class);
                         assertThat(source.asString()).isEqualTo("body");
                     });
    }

    @Test
    void parse_succeeds_withValue() {
        BindingSource.parse("value")
                     .onFailureRun(Assertions::fail)
                     .onSuccess(source -> {
                         assertThat(source).isInstanceOf(BindingSource.Value.class);
                         assertThat(source.asString()).isEqualTo("value");
                     });
    }

    @Test
    void parse_succeeds_withKey() {
        BindingSource.parse("key")
                     .onFailureRun(Assertions::fail)
                     .onSuccess(source -> {
                         assertThat(source).isInstanceOf(BindingSource.Key.class);
                         assertThat(source.asString()).isEqualTo("key");
                     });
    }

    @Test
    void parse_succeeds_withPathVar() {
        BindingSource.parse("path.userId")
                     .onFailureRun(Assertions::fail)
                     .onSuccess(source -> {
                         assertThat(source).isInstanceOf(BindingSource.PathVar.class);
                         assertThat(((BindingSource.PathVar) source).name()).isEqualTo("userId");
                         assertThat(source.asString()).isEqualTo("path.userId");
                     });
    }

    @Test
    void parse_succeeds_withQueryVar() {
        BindingSource.parse("query.page")
                     .onFailureRun(Assertions::fail)
                     .onSuccess(source -> {
                         assertThat(source).isInstanceOf(BindingSource.QueryVar.class);
                         assertThat(((BindingSource.QueryVar) source).name()).isEqualTo("page");
                         assertThat(source.asString()).isEqualTo("query.page");
                     });
    }

    @Test
    void parse_succeeds_withHeader() {
        BindingSource.parse("header.Authorization")
                     .onFailureRun(Assertions::fail)
                     .onSuccess(source -> {
                         assertThat(source).isInstanceOf(BindingSource.Header.class);
                         assertThat(((BindingSource.Header) source).name()).isEqualTo("Authorization");
                         assertThat(source.asString()).isEqualTo("header.Authorization");
                     });
    }

    @Test
    void parse_succeeds_withCookie() {
        BindingSource.parse("cookie.sessionId")
                     .onFailureRun(Assertions::fail)
                     .onSuccess(source -> {
                         assertThat(source).isInstanceOf(BindingSource.Cookie.class);
                         assertThat(((BindingSource.Cookie) source).name()).isEqualTo("sessionId");
                         assertThat(source.asString()).isEqualTo("cookie.sessionId");
                     });
    }

    @Test
    void parse_succeeds_withRequestField() {
        BindingSource.parse("request.userId")
                     .onFailureRun(Assertions::fail)
                     .onSuccess(source -> {
                         assertThat(source).isInstanceOf(BindingSource.RequestField.class);
                         assertThat(((BindingSource.RequestField) source).field()).isEqualTo("userId");
                         assertThat(source.asString()).isEqualTo("request.userId");
                     });
    }

    @Test
    void parse_succeeds_withMetadata() {
        BindingSource.parse("metadata.traceId")
                     .onFailureRun(Assertions::fail)
                     .onSuccess(source -> {
                         assertThat(source).isInstanceOf(BindingSource.Metadata.class);
                         assertThat(((BindingSource.Metadata) source).name()).isEqualTo("traceId");
                         assertThat(source.asString()).isEqualTo("metadata.traceId");
                     });
    }

    @Test
    void parse_fails_withInvalidPrefix() {
        BindingSource.parse("unknown.field")
                     .onSuccessRun(Assertions::fail)
                     .onFailure(cause -> assertThat(cause.message()).contains("Invalid binding source format"));
    }

    @Test
    void parse_fails_withMissingValue() {
        BindingSource.parse("path.")
                     .onSuccessRun(Assertions::fail)
                     .onFailure(cause -> assertThat(cause.message()).contains("Invalid binding source format"));
    }

    @Test
    void parse_fails_withNoDot() {
        BindingSource.parse("invalid")
                     .onSuccessRun(Assertions::fail)
                     .onFailure(cause -> assertThat(cause.message()).contains("Invalid binding source format"));
    }
}
