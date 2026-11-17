package conduit.domain;

import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import static conduit.domain.Expect.failure;
import static conduit.domain.Expect.success;
import static org.assertj.core.api.Assertions.assertThat;



/// `stored` is the read half of every `ValueMapping`: it checks the minimal stable invariant (positive id,
/// non-blank text) and nothing of the registration rules, so a tightened rule cannot lock out stored data.
class StoredValuesTest {
    record IdCase(String name, Function<Long, Result<?>> stored, Cause cause) {
        public static IdCase idCase(String name, Function<Long, Result<?>> stored, Cause cause) {
            return new IdCase(name, stored, cause);
        }
    }

    record TextCase(String name, Function<String, Result<?>> stored, Cause cause) {

        public static TextCase textCase(String name, Function<String, Result<?>> stored, Cause cause) {

            return new TextCase(name, stored, cause);

        }

    }

    static Stream<IdCase> ids() {
        return Stream.of(IdCase.idCase("ArticleId", ArticleId::stored, ArticleId.Invalid.NOT_POSITIVE),
                         IdCase.idCase("CommentId", CommentId::stored, CommentId.Invalid.NOT_POSITIVE),
                         IdCase.idCase("UserId", UserId::stored, UserId.Invalid.NOT_POSITIVE));
    }

    static Stream<TextCase> texts() {
        return Stream.of(TextCase.textCase("Slug", Slug::stored, Slug.Invalid.BLANK),
                         TextCase.textCase("Username", Username::stored, Username.Invalid.BLANK),
                         TextCase.textCase("Email", Email::stored, Email.Invalid.BLANK),
                         TextCase.textCase("PasswordHash", PasswordHash::stored, PasswordHash.Invalid.BLANK),
                         TextCase.textCase("Title", Title::stored, Title.Invalid.BLANK),
                         TextCase.textCase("Description", Description::stored, Description.Invalid.BLANK),
                         TextCase.textCase("ArticleBody", ArticleBody::stored, ArticleBody.Invalid.BLANK),
                         TextCase.textCase("CommentBody", CommentBody::stored, CommentBody.Invalid.BLANK));
    }

    @ParameterizedTest
    @MethodSource("ids")
    void nonPositiveIdFailsWithTheVosCause(IdCase c) {
        assertThat(failure(c.stored().apply(0L))).isSameAs(c.cause());
        assertThat(failure(c.stored().apply(-5L))).isSameAs(c.cause());
        assertThat(failure(c.stored().apply(null))).isSameAs(c.cause());
        assertThat(success(c.stored().apply(1L))).isNotNull();
    }

    @ParameterizedTest
    @MethodSource("texts")
    void blankTextFailsWithTheVosCause(TextCase c) {
        assertThat(failure(c.stored().apply(""))).isSameAs(c.cause());
        assertThat(failure(c.stored().apply("  \t"))).isSameAs(c.cause());
        assertThat(failure(c.stored().apply(null))).isSameAs(c.cause());
        assertThat(success(c.stored().apply(" kept as is "))).isNotNull();
    }

    @Test
    void storedDoesNotApplyTheRegistrationRules() {
        assertThat(success(Email.stored("not-an-email")).value()).isEqualTo("not-an-email");
    }
}
