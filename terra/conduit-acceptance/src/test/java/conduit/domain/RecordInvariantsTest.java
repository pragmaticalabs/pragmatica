package conduit.domain;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;

import conduit.persistence.TagPersistence.ArticleTagRow;
import conduit.persistence.UserPersistence.TakenRow;
import conduit.view.ArticleSummary;
import conduit.view.ArticleView;
import conduit.view.ArticlesResponse;
import conduit.view.CommentView;
import conduit.view.ProfileView;

import static conduit.domain.Expect.failure;
import static conduit.domain.Expect.success;
import static org.assertj.core.api.Assertions.assertThat;


/// Every record with an invariant has a fallible factory; each test pins both directions: the boundary value passes,
/// the first value past it fails with the record's own typed cause.
class RecordInvariantsTest {
    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00.000Z");
    private static final Instant BEFORE = CREATED.minusMillis(1);
    private static final String WIRE_CREATED = "2026-01-01T00:00:00.000Z";
    private static final String WIRE_BEFORE = "2025-12-31T23:59:59.999Z";

    private static final ArticleId ID = success(ArticleId.articleId(1));
    private static final Slug SLUG = success(Slug.slug("s"));
    private static final Title TITLE = success(Title.title(Option.some("t")));
    private static final Description DESCRIPTION = success(Description.description(Option.some("d")));
    private static final ArticleBody BODY = success(ArticleBody.articleBody(Option.some("b")));
    private static final Username USERNAME = success(Username.username(Option.some("u")));
    private static final ProfileView AUTHOR = ProfileView.profileView("u", Option.none(), Option.none(), false);

    private static org.pragmatica.lang.Result<Article> article(Instant updated, long favorites) {
        return Article.article(ID, SLUG, TITLE, DESCRIPTION, BODY, CREATED, updated, USERNAME, Option.none(), Option.none(), favorites, false, false);
    }

    private static org.pragmatica.lang.Result<ArticleListing> listing(Instant updated, long favorites) {
        return ArticleListing.articleListing(ID, SLUG, TITLE, DESCRIPTION, CREATED, updated, USERNAME, Option.none(), Option.none(), favorites, false, false);
    }

    @Test
    void article_favoritesCountAtZeroPasses_belowFails() {
        assertThat(success(article(CREATED, 0)).favoritesCount()).isZero();
        assertThat(failure(article(CREATED, -1))).isSameAs(Article.Invalid.NEGATIVE_FAVORITES);
    }

    @Test
    void article_updatedAtEqualToCreatedPasses_beforeFails() {
        assertThat(success(article(CREATED, 0)).updatedAt()).isEqualTo(CREATED);
        assertThat(failure(article(BEFORE, 0))).isSameAs(Article.Invalid.UPDATED_BEFORE_CREATED);
    }

    @Test
    void articleListing_bothInvariants() {
        assertThat(success(listing(CREATED, 0)).favoritesCount()).isZero();
        assertThat(failure(listing(CREATED, -1))).isSameAs(ArticleListing.Invalid.NEGATIVE_FAVORITES);
        assertThat(failure(listing(BEFORE, 0))).isSameAs(ArticleListing.Invalid.UPDATED_BEFORE_CREATED);
    }

    @Test
    void comment_updatedAtBeforeCreatedFails() {
        var body = success(CommentBody.commentBody(Option.some("c")));
        var id = success(CommentId.commentId(1));

        assertThat(success(Comment.comment(id, body, CREATED, CREATED, USERNAME, Option.none(), Option.none(), false)).id()).isEqualTo(id);
        assertThat(failure(Comment.comment(id, body, CREATED, BEFORE, USERNAME, Option.none(), Option.none(), false))).isSameAs(Comment.Invalid.UPDATED_BEFORE_CREATED);
    }

    @Test
    void tagAndTagRow_blankNameFails() {
        assertThat(success(Tag.tag("x")).name()).isEqualTo("x");
        assertThat(failure(Tag.tag(" "))).isSameAs(Tag.Invalid.BLANK);
        assertThat(failure(ArticleTagRow.articleTagRow(ID, ""))).isSameAs(ArticleTagRow.Invalid.BLANK);
        assertThat(success(ArticleTagRow.articleTagRow(ID, "x")).name()).isEqualTo("x");
    }

    @Test
    void userAndTakenRow_emailMustBeLowercase() {
        var upper = Email.stored("A@B.C").fold(cause -> null, email -> email);
        var lower = success(Email.email(Option.some("a@b.c")));
        var hash = success(PasswordHash.passwordHash("h"));

        assertThat(upper).isNotNull();
        assertThat(success(User.user(success(UserId.userId(1)), USERNAME, lower, hash, Option.none(), Option.none())).email()).isEqualTo(lower);
        assertThat(failure(User.user(success(UserId.userId(1)), USERNAME, upper, hash, Option.none(), Option.none()))).isSameAs(User.Invalid.EMAIL_NOT_LOWERCASE);
        assertThat(success(TakenRow.takenRow(USERNAME, lower)).email()).isEqualTo(lower);
        assertThat(failure(TakenRow.takenRow(USERNAME, upper))).isSameAs(TakenRow.Invalid.EMAIL_NOT_LOWERCASE);
    }

    @Test
    void page_rangeBoundaries_bothReported() {
        assertThat(success(Page.page(1, 0))).isEqualTo(success(Page.page(Option.some("1"), Option.some("0"))));
        assertThat(success(Page.page(100, 5)).limit()).isEqualTo(100);
        assertThat(failure(Page.page(0, 0)).message()).isEqualTo("limit: is invalid");
        assertThat(failure(Page.page(101, 0)).message()).isEqualTo("limit: is invalid");
        assertThat(failure(Page.page(20, -1)).message()).isEqualTo("offset: is invalid");
        assertThat(failure(Page.page(0, -1)).message()).isEqualTo("limit: is invalid, offset: is invalid");
    }

    @Test
    void idsAndHash_minimalChecksOnTheTypeNamedFactory() {
        assertThat(failure(ArticleId.articleId(0))).isSameAs(ArticleId.Invalid.NOT_POSITIVE);
        assertThat(failure(CommentId.commentId(-1))).isSameAs(CommentId.Invalid.NOT_POSITIVE);
        assertThat(failure(UserId.userId(0L))).isSameAs(UserId.Invalid.NOT_POSITIVE);
        assertThat(failure(Slug.slug(" "))).isSameAs(Slug.Invalid.BLANK);
        assertThat(failure(PasswordHash.passwordHash(""))).isSameAs(PasswordHash.Invalid.BLANK);
        assertThat(success(ArticleId.articleId(1)).value()).isEqualTo(1);
    }

    @Test
    void views_countsAndTimestampOrder() {
        assertThat(success(ArticleView.articleView("s", "t", "d", "b", java.util.List.of(), WIRE_CREATED, WIRE_CREATED, false, 0, AUTHOR)).favoritesCount()).isZero();
        assertThat(failure(ArticleView.articleView("s", "t", "d", "b", java.util.List.of(), WIRE_CREATED, WIRE_CREATED, false, -1, AUTHOR))).isSameAs(ArticleView.Invalid.NEGATIVE_FAVORITES);
        assertThat(failure(ArticleView.articleView("s", "t", "d", "b", java.util.List.of(), WIRE_CREATED, WIRE_BEFORE, false, 0, AUTHOR))).isSameAs(ArticleView.Invalid.UPDATED_BEFORE_CREATED);
        assertThat(failure(ArticleSummary.articleSummary("s", "t", "d", java.util.List.of(), WIRE_CREATED, WIRE_CREATED, false, -1, AUTHOR))).isSameAs(ArticleSummary.Invalid.NEGATIVE_FAVORITES);
        assertThat(failure(ArticleSummary.articleSummary("s", "t", "d", java.util.List.of(), WIRE_CREATED, WIRE_BEFORE, false, 0, AUTHOR))).isSameAs(ArticleSummary.Invalid.UPDATED_BEFORE_CREATED);
        assertThat(failure(CommentView.commentView(1, WIRE_CREATED, WIRE_BEFORE, "c", AUTHOR))).isSameAs(CommentView.Invalid.UPDATED_BEFORE_CREATED);
        assertThat(success(CommentView.commentView(1, WIRE_CREATED, WIRE_CREATED, "c", AUTHOR)).id()).isEqualTo(1);
        assertThat(success(ArticlesResponse.articlesResponse(java.util.List.of(), 0)).articlesCount()).isZero();
        assertThat(failure(ArticlesResponse.articlesResponse(java.util.List.of(), -1))).isSameAs(ArticlesResponse.Invalid.NEGATIVE_COUNT);
    }
}
