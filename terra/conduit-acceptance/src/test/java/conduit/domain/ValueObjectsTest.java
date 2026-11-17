package conduit.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Patch;

import conduit.domain.ConduitError.FieldError;
import conduit.domain.ConduitError.NotFound;
import conduit.domain.ConduitError.RequestInvalid;
import conduit.domain.ConduitError.Unauthorized;

import static conduit.domain.Expect.failure;
import static conduit.domain.Expect.success;
import static org.assertj.core.api.Assertions.assertThat;



class ValueObjectsTest {
    private static List<FieldError> errors(org.pragmatica.lang.Cause cause) {
        assertThat(cause).isInstanceOf(RequestInvalid.class);
        return ((RequestInvalid) cause).errors();
    }

    @Nested
    class Required {
        @ParameterizedTest
        @ValueSource(strings = {"", " ", "\t\n", " "})
        void blankIsCantBeBlank(String raw) {
            assertThat(errors(failure(Username.username(Option.option(raw))))).containsExactly(FieldError.fieldError("username",
                                                                                                    "can't be blank"));
        }

        @Test
        void nullIsCantBeBlank_andValuesAreNotTrimmed() {
            assertThat(errors(failure(Title.title(Option.option(null))))).containsExactly(FieldError.fieldError("title",
                                                                                               "can't be blank"));
            assertThat(success(Title.title(Option.option(" padded "))).value()).isEqualTo(" padded ");
        }
    }

    @Nested
    class Emails {
        @Test
        void lowercasedWithRootLocale() {
            assertThat(success(Email.email(Option.option("Foo.BAR@Example.COM"))).value()).isEqualTo("foo.bar@example.com");
            assertThat(success(Email.email(Option.option("I@X.TR"))).value()).isEqualTo("i@x.tr");
        }

        @ParameterizedTest
        @ValueSource(strings = {"plain", "@x.com", "a@", "a@@b", "a b@c.d", "a@b@c"})
        void malformedIsInvalid(String raw) {
            assertThat(errors(failure(Email.email(Option.option(raw))))).containsExactly(FieldError.fieldError("email", "is invalid"));
        }
    }

    @Nested
    class Passwords {
        @Test
        void lengthIsCountedInCodePoints_minimumEight() {
            var sevenEmoji = "😀".repeat(7);
            var eightEmoji = "😀".repeat(8);

            assertThat(errors(failure(Password.password(Option.option("1234567"))))).containsExactly(FieldError.fieldError("password",
                                                                                                           "is too short (minimum is 8 characters)"));
            assertThat(errors(failure(Password.password(Option.option(sevenEmoji))))).hasSize(1);
            assertThat(success(Password.password(Option.option(eightEmoji))).value()).isEqualTo(eightEmoji);
            assertThat(success(Password.password(Option.option("12345678"))).value()).isEqualTo("12345678");
        }

        @Test
        void maximumIsSeventyTwoUtf8Bytes() {
            assertThat(success(Password.password(Option.option("a".repeat(72))))).isNotNull();
            assertThat(success(Password.password(Option.option("é".repeat(36))))).isNotNull();
            assertThat(errors(failure(Password.password(Option.option("a".repeat(73)))))).containsExactly(FieldError.fieldError("password",
                                                                                                               "is too long (maximum is 72 bytes)"));
            assertThat(errors(failure(Password.password(Option.option("é".repeat(37)))))).hasSize(1);
            assertThat(errors(failure(Password.password(Option.option("€".repeat(25)))))).hasSize(1);
        }

        @Test
        void loginAcceptsAnyNonBlankValue() {
            assertThat(success(Password.loginPassword(Option.option("x"))).value()).isEqualTo("x");
        }

        @Test
        void neverPrinted() {
            assertThat(success(Password.password(Option.option("secret123"))).toString()).doesNotContain("secret123");
        }
    }

    @Nested
    class Slugs {
        @Test
        void baseFollowsB22() {
            assertThat(Slug.base("Hello, World!")).isEqualTo("hello-world");
            assertThat(Slug.base("  --Already--Dashed--  ")).isEqualTo("already-dashed");
            assertThat(Slug.base("Café au lait")).isEqualTo("caf-au-lait");
            assertThat(Slug.base("!!!")).isEqualTo("article");
            assertThat(Slug.base("")).isEqualTo("article");
            assertThat(Slug.base("I")).isEqualTo("i");
        }

        @Test
        void baseIsCutTo64ThenTrailingDashesRemoved() {
            var title = "a".repeat(63) + " b";

            assertThat(Slug.base(title)).isEqualTo("a".repeat(63));
            assertThat(Slug.base("x".repeat(100))).hasSize(64);
        }

        @Test
        void suffixIsEightCharactersFromTheAlphabet() {
            var title = success(Title.title(Option.option("Duplicate")));
            var random = new Random(42);
            var first = Slug.generate(title, random);
            var second = Slug.generate(title, random);

            assertThat(first.value()).matches("^duplicate-[a-z0-9]{8}$");
            assertThat(second.value()).matches("^duplicate-[a-z0-9]{8}$")
                                      .isNotEqualTo(first.value());
        }

        @Test
        void pathSlugMustNotBeBlank() {
            assertThat(Slug.inPath(" ")).isEqualTo(Option.none());
            assertThat(Slug.inPath("any-thing")).isEqualTo(Option.some(success(Slug.slug("any-thing"))));
        }
    }

    @Nested
    class Tags {
        @Test
        void sortedByByteOrder_duplicatesCollapsed_caseKept() {
            var tags = success(TagList.tagList(Option.option(Arrays.asList("zz", "aa", "mm", "aa", "AA", "é", "😀"))));

            assertThat(tags.names()).containsExactly("AA", "aa", "mm", "zz", "é", "😀");
        }

        /// Through `tagList`, the only path that sorts: U+E000 is three UTF-8 bytes (EE..), U+1F600 four (F0..), so byte order puts U+E000 first,
        /// whereas UTF-16 code-unit order (a surrogate, D83D) puts the supplementary character first. The store orders by `COLLATE "C"`.
        @Test
        void tagListIsSortedInUtf8ByteOrder_notUtf16CodeUnitOrder() {
            var names = success(TagList.tagList(Option.option(List.of("\uD83D\uDE00", "\uE000")))).names();

            assertThat(names).containsExactly("\uE000", "\uD83D\uDE00");
        }

        @Test
        void supplementaryCharactersSortAfterBmpPrivateUse_asUtf8Does() {
            assertThat(TagList.compareUtf8("😀", "�")).isPositive();
            assertThat("😀".compareTo("�")).isNegative();
        }

        @Test
        void nullListOrBlankTagIsCantBeBlank() {
            var blank = List.of(FieldError.fieldError("tagList", "can't be blank"));

            assertThat(errors(failure(TagList.tagList(Option.option(null))))).isEqualTo(blank);
            assertThat(errors(failure(TagList.tagList(Option.option(List.of("ok", " ")))))).isEqualTo(blank);
            assertThat(errors(failure(TagList.tagList(Option.option(Arrays.asList("ok", null)))))).isEqualTo(blank);
        }
    }

    @Nested
    class Pages {
        @Test
        void defaultsAreTwentyAndZero() {
            assertThat(success(Page.page(Option.none(), Option.none()))).isEqualTo(success(Page.page(20, 0)));
        }

        @Test
        void boundsAreChecked_notClamped() {
            assertThat(success(Page.page(Option.some("100"), Option.some("0")))).isEqualTo(success(Page.page(100, 0)));
            assertThat(success(Page.page(Option.some("1"), Option.some("999")))).isEqualTo(success(Page.page(1, 999)));
            for (var bad : List.of("0", "101", "abc", "-1", "1.5", "", " 5")) {
                assertThat(errors(failure(Page.page(Option.some(bad), Option.none())))).as(bad)
                                                                                       .containsExactly(FieldError.fieldError("limit",
                                                                                                                              "is invalid"));
            }
            assertThat(errors(failure(Page.page(Option.none(), Option.some("-1"))))).containsExactly(FieldError.fieldError("offset",
                                                                                                                         "is invalid"));
        }

        @Test
        void bothInvalid_bothReported() {
            assertThat(errors(failure(Page.page(Option.some("0"), Option.some("x"))))).containsExactly(FieldError.fieldError("limit",
                                                                                                                           "is invalid"),
                                                                                                    FieldError.fieldError("offset",
                                                                                                                          "is invalid"));
        }
    }

    @Nested
    class Ids {
        @Test
        void tokenSubjectMustBePositiveDecimal() {
            assertThat(success(UserId.userId("42")).value()).isEqualTo(42L);
            for (var bad : Arrays.asList("0", "-1", "+5", "4.2", "abc", "", "99999999999999999999", null)) {
                assertThat(failure(UserId.userId(bad))).as(String.valueOf(bad))
                                                      .isEqualTo(Unauthorized.TOKEN_INVALID);
            }
        }

        @Test
        void nonIntegerCommentIdIsCommentNotFound() {
            assertThat(CommentId.inPath("7")).isEqualTo(Option.some(success(CommentId.commentId(7L))));
            assertThat(Username.inPath("")).isEqualTo(Option.none());
            assertThat(Username.inPath("Any-Case")).isEqualTo(Option.some(success(Username.username(Option.some("Any-Case")))));
            assertThat(CommentId.inPath("abc")).isEqualTo(Option.none());
            assertThat(CommentId.inPath(null)).isEqualTo(Option.none());
        }
    }

    @Nested
    class Times {
        @Test
        void wireFormatHasExactlyThreeFractionDigits() {
            assertThat(Timestamps.wire(java.time.Instant.parse("2026-11-08T12:34:56Z"))).isEqualTo("2026-11-08T12:34:56.000Z");
            assertThat(Timestamps.wire(java.time.Instant.parse("2026-11-08T12:34:56.789123Z"))).isEqualTo("2026-11-08T12:34:56.789Z");
        }

        @Test
        void nowIsTruncatedToMilliseconds() {
            assertThat(Timestamps.now()
                                 .getNano() % 1_000_000).isZero();
        }
    }

    @Nested
    class Filters {
        @Test
        void eachSingleFilterHasItsOwnShape_twoOrMoreCombine() {
            assertThat(ArticleFilter.articleFilter(Option.none(), Option.none(), Option.none())).isInstanceOf(ArticleFilter.All.class);
            assertThat(ArticleFilter.articleFilter(Option.some("t"), Option.none(), Option.none())).isEqualTo(ArticleFilter.ByTag.byTag("t"));
            assertThat(ArticleFilter.articleFilter(Option.none(), Option.some("a"), Option.none())).isEqualTo(ArticleFilter.ByAuthor.byAuthor("a"));
            assertThat(ArticleFilter.articleFilter(Option.none(), Option.none(), Option.some("f"))).isEqualTo(ArticleFilter.ByFavorited.byFavorited("f"));
            assertThat(ArticleFilter.articleFilter(Option.some("t"), Option.none(), Option.some("f"))).isInstanceOf(ArticleFilter.Combined.class);
        }
    }

    @Nested
    class Errors {
        @Test
        void mergedFlattensValidationFailures_andKeepsAnythingElse() {
            var composite = org.pragmatica.lang.Result.all(Username.username(Option.option("")), Email.email(Option.option("bad")))
                                                      .map((u, e) -> u);
            var merged = RequestInvalid.merged(failure(composite));
            var mixed = org.pragmatica.lang.Result.all(Username.username(Option.option("")), UserId.userId("x"))
                                                  .map((u, e) -> u);

            assertThat(errors(merged)).containsExactly(FieldError.fieldError("username", "can't be blank"),
                                                       FieldError.fieldError("email", "is invalid"));
            assertThat(RequestInvalid.merged(failure(mixed))).isNotInstanceOf(RequestInvalid.class);
            assertThat(merged.message()).isEqualTo("username: can't be blank, email: is invalid");
        }

        @Test
        void messagesCarryTheB50KeysAndTexts() {
            assertThat(Unauthorized.TOKEN_MISSING.message()).isEqualTo("token: is missing");
            assertThat(NotFound.PROFILE.message()).isEqualTo("profile: not found");
            assertThat(ConduitError.Forbidden.COMMENT.message()).isEqualTo("comment: forbidden");
            assertThat(ConduitError.Conflict.conflict(ConduitError.Conflict.USERNAME_TAKEN, List.of())
                                            .message()).isEqualTo("username: has already been taken");
        }
    }

    @Nested
    class PatchReads {
        @Test
        void absentIsNothingToChange_nullAndValueReachTheParser() {
            assertThat(success(Patches.parsePresent(Patch.<String> absent(), Title::title))).isEqualTo(Option.none());
            assertThat(errors(failure(Patches.parsePresent(Patch.<String> nullValue(), Title::title)))).hasSize(1);
            assertThat(success(Patches.parsePresent(Patch.value("T"), Title::title))).isEqualTo(Option.some(success(Title.title(Option.some("T")))));
        }
    }
}
