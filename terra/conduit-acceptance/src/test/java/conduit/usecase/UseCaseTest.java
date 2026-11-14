package conduit.usecase;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.RequestHeaders;
import org.pragmatica.aether.http.handler.security.Role;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Patch;
import org.pragmatica.lang.Promise;

import conduit.adapter.password.HashPassword;
import conduit.adapter.password.VerifyPassword;
import conduit.adapter.token.TokenIssuer;
import conduit.domain.Clock;
import conduit.domain.ConduitError.Conflict;
import conduit.domain.ConduitError.FieldError;
import conduit.domain.ConduitError.RequestInvalid;
import conduit.domain.ConduitError.Unauthorized;
import conduit.domain.Email;
import conduit.domain.PasswordHash;
import conduit.domain.User;
import conduit.domain.UserId;
import conduit.domain.Username;
import conduit.persistence.UserPersistence.TakenRow;
import conduit.shared.Conflicts;
import conduit.usecase.createarticle.CreateArticle;
import conduit.usecase.feedarticles.FeedArticles;
import conduit.usecase.followuser.FollowUser;
import conduit.usecase.listarticles.ListArticles;
import conduit.usecase.login.Login;
import conduit.usecase.registeruser.RegisterUser;
import conduit.usecase.updatearticle.UpdateArticle;
import conduit.usecase.updateuser.UpdateUser;
import conduit.view.ArticlesResponse;

import static conduit.domain.Expect.failure;
import static conduit.domain.Expect.success;
import static org.assertj.core.api.Assertions.assertThat;



/// Use cases with stubbed steps: validation, B-51 precedence, and which steps run. No database, no bcrypt.
class UseCaseTest {
    private static final Instant NOW = Instant.parse("2026-11-08T12:00:00.123Z");
    private static final Clock CLOCK = () -> NOW;
    private static final TokenIssuer ISSUER = (subject, issuedAt) -> org.pragmatica.lang.Result.success("token-" + subject.value());
    private static final RequestHeaders PRESENTED = RequestHeaders.requestHeaders(Map.of("Authorization",
                                                                                         List.of("Token presented")));
    private static final SecurityContext ANONYMOUS = SecurityContext.securityContext();
    private static final SecurityContext USER_7 = SecurityContext.securityContext("7", Set.of(Role.USER),
                                                                                  Map.of())
                                                                 .unwrap();

    /// Counts calls so a test can assert a step never ran.
    private static final class StubHasher {
        final AtomicInteger hashes = new AtomicInteger();
        final HashPassword hash = password -> {
            hashes.incrementAndGet();
            return PasswordHash.passwordHash("$2a$10$stub").async();
        };
        final VerifyPassword verify = (hash, password) -> password.value()
                                                                  .equals("password123")
                                                         ? Promise.unitPromise()
                                                         : Unauthorized.CREDENTIALS_INVALID.promise();
    }

    private static List<FieldError> errors(Cause cause) {
        assertThat(cause).isInstanceOf(RequestInvalid.class);
        return ((RequestInvalid) cause).errors();
    }

    private static FieldError blank(String field) {
        return FieldError.fieldError(field, "can't be blank");
    }

    private static User row(long id, String username, String email) {
        return success(User.user(success(UserId.userId(id)),
                                 success(Username.username(Option.some(username))),
                                 success(Email.email(Option.some(email))),
                                 success(PasswordHash.passwordHash("$2a$10$stub")),
                                 Option.none(),
                                 Option.none()));
    }

    @Nested
    class Register {
        private final StubHasher hasher = new StubHasher();
        private final List<String> inserted = new ArrayList<>();

        private RegisterUser useCase(List<TakenRow> taken) {
            return RegisterUser.registerUser(hasher.hash,
                                             (username, email, hash) -> Conflicts.verdict(taken, username, email)
                                                                                 .async()
                                                                                 .map(_ -> {
                                                                                     inserted.add(username.value());
                                                                                     return row(5, username.value(), email.value());
                                                                                 }),
                                             ISSUER,
                                             CLOCK);
        }

        private RegisterUser.Request request(String username, String email, String password) {
            return RegisterUser.Request.request(RegisterUser.Request.NewUser.newUser(username, email, password));
        }

        @Test
        void everyInvalidFieldReportedAtOnce_nothingHashedOrStored() {
            var cause = failure(useCase(List.of()).execute(request("", "bad", "short")));

            assertThat(errors(cause)).containsExactly(blank("username"),
                                                      FieldError.fieldError("email", "is invalid"),
                                                      FieldError.fieldError("password",
                                                                            "is too short (minimum is 8 characters)"));
            assertThat(hasher.hashes).hasValue(0);
            assertThat(inserted).isEmpty();
        }

        @Test
        void missingWrapperOrFields_areBlank() {
            assertThat(errors(failure(useCase(List.of()).execute(RegisterUser.Request.request(null))))).containsExactly(blank("username"),
                                                                                                                 blank("email"),
                                                                                                                 blank("password"));
        }

        @Test
        void takenFields_are409_afterHashing_beforeTheInsert() {
            var cause = failure(useCase(List.of(success(TakenRow.takenRow(success(Username.username(Option.some("alice"))), success(Email.email(Option.some("other@x.y"))))),
                                                success(TakenRow.takenRow(success(Username.username(Option.some("bob"))), success(Email.email(Option.some("alice@x.y"))))))).execute(request("alice",
                                                                                                    "Alice@X.Y",
                                                                                                    "password123")));

            assertThat(cause).isEqualTo(Conflict.conflict(Conflict.USERNAME_TAKEN, List.of(Conflict.EMAIL_TAKEN)));
            assertThat(hasher.hashes).hasValue(1);
            assertThat(inserted).isEmpty();
        }

        @Test
        void success_storesLowercasedEmail_andMintsAToken() {
            var user = success(useCase(List.of()).execute(request("Alice", "Alice@X.Y", "password123"))).user();

            assertThat(user.email()).isEqualTo("alice@x.y");
            assertThat(user.username()).isEqualTo("Alice");
            assertThat(user.token()).isEqualTo("token-5");
            assertThat(user.bio()).isEqualTo(Option.none());
        }
    }

    @Nested
    class LoginCase {
        private Login useCase(Option<User> found) {
            return Login.login(email -> Promise.success(found), new StubHasher().verify, ISSUER, CLOCK);
        }

        @Test
        void blankFields_are422_together() {
            assertThat(errors(failure(useCase(Option.none()).execute(Login.Request.request(Login.Request.Credentials.credentials(" ",
                                                                                                                      null)))))).containsExactly(blank("email"),
                                                                                                                                                 blank("password"));
        }

        @Test
        void malformedEmail_is422BeforeTheLookup() {
            var looked = new ArrayList<String>();
            var login = Login.login(email -> {
                                        looked.add(email.value());
                                        return Promise.success(Option.<User>none());
                                    },
                                    new StubHasher().verify,
                                    ISSUER,
                                    CLOCK);
            var result = login.execute(Login.Request.request(Login.Request.Credentials.credentials("a@", "password123")));

            assertThat(errors(failure(result))).containsExactly(FieldError.fieldError("email", "is invalid"));
            assertThat(looked).isEmpty();
        }

        @Test
        void unknownEmailAndWrongPassword_areTheSame401() {
            var credentials = Login.Request.request(Login.Request.Credentials.credentials("a@b.c", "password124"));

            assertThat(failure(useCase(Option.none()).execute(credentials))).isEqualTo(Unauthorized.CREDENTIALS_INVALID);
            assertThat(failure(useCase(Option.some(row(3, "a", "a@b.c"))).execute(credentials))).isEqualTo(Unauthorized.CREDENTIALS_INVALID);
        }

        @Test
        void success_mintsANewToken() {
            var user = success(useCase(Option.some(row(3, "a", "a@b.c"))).execute(Login.Request.request(Login.Request.Credentials.credentials("A@B.C",
                                                                                                                                   "password123")))).user();

            assertThat(user.token()).isEqualTo("token-3");
        }
    }

    @Nested
    class UpdateUserCase {
        private final StubHasher hasher = new StubHasher();
        private final List<UpdateUser.Plan> plans = new ArrayList<>();
        private final UpdateUser useCase = UpdateUser.updateUser(hasher.hash,
                                                                 plan -> {
                                                                     plans.add(plan);
                                                                     return Promise.success(row(7, "u", "u@x.y"));
                                                                 });

        private UpdateUser.Request request(Patch<String> username,
                                           Patch<String> email,
                                           Patch<String> password,
                                           Patch<String> bio,
                                           Patch<String> image) {
            return UpdateUser.Request.request(UpdateUser.Request.Changes.changes(username, email, password, bio, image));
        }

        @Test
        void anonymousWithInvalidBody_is401_first() {
            var cause = failure(useCase.execute(request(Patch.nullValue(),
                                                        Patch.absent(),
                                                        Patch.absent(),
                                                        Patch.absent(),
                                                        Patch.absent()),
                                                ANONYMOUS, PRESENTED));

            assertThat(cause).isEqualTo(Unauthorized.TOKEN_MISSING);
        }

        @Test
        void noRecognisedField_isUserCantBeEmpty() {
            assertThat(errors(failure(useCase.execute(UpdateUser.Request.request(null), USER_7, PRESENTED)))).containsExactly(FieldError.fieldError("user",
                                                                                                                                    "can't be empty"));
        }

        @Test
        void nullUsernameEmailPassword_areBlank_andAllReported() {
            var cause = failure(useCase.execute(request(Patch.nullValue(),
                                                        Patch.nullValue(),
                                                        Patch.value("short"),
                                                        Patch.absent(),
                                                        Patch.absent()),
                                                USER_7, PRESENTED));

            assertThat(errors(cause)).containsExactly(blank("username"),
                                                      blank("email"),
                                                      FieldError.fieldError("password",
                                                                            "is too short (minimum is 8 characters)"));
            assertThat(plans).isEmpty();
        }

        @Test
        void bioEmptyIsStoredAsNull_imageNullIsNull_passwordHashedOnlyWhenSent_tokenEchoed() {
            var user = success(useCase.execute(request(Patch.absent(),
                                                       Patch.absent(),
                                                       Patch.absent(),
                                                       Patch.value(""),
                                                       Patch.nullValue()),
                                               USER_7, PRESENTED)).user();
            var plan = plans.getFirst();

            assertThat(user.token()).isEqualTo("presented");
            assertThat(plan.changes()
                           .bio()).isEqualTo(Option.some(UpdateUser.TextChange.textChange(Option.none())));
            assertThat(plan.changes()
                           .image()).isEqualTo(Option.some(UpdateUser.TextChange.textChange(Option.none())));
            assertThat(plan.changes()
                           .username()).isEqualTo(Option.none());
            assertThat(plan.newHash()).isEqualTo(Option.none());
            assertThat(hasher.hashes).hasValue(0);
        }
    }

    @Nested
    class Articles {
        private final List<CreateArticle.ArticleDraft> created = new ArrayList<>();
        private final CreateArticle create = CreateArticle.createArticle(draft -> {
                                                                             created.add(draft);
                                                                             return Promise.success(null);
                                                                         },
                                                                         CLOCK,
                                                                         new java.util.Random(42)::nextLong);
        private final UpdateArticle update = UpdateArticle.updateArticle(edit -> Promise.success(null), CLOCK);

        private CreateArticle.Request article(String title, Patch<List<String>> tags) {
            return CreateArticle.Request.request(CreateArticle.Request.NewArticle.newArticle(title, "d", "b", tags));
        }

        @Test
        void create_anonymous401_beforeValidation() {
            assertThat(failure(create.execute(article("", Patch.nullValue()), ANONYMOUS, PRESENTED))).isEqualTo(Unauthorized.TOKEN_MISSING);
        }

        @Test
        void create_tagListNullOrBlankTag_is422_absentIsEmpty() {
            assertThat(errors(failure(create.execute(article("T", Patch.nullValue()), USER_7, PRESENTED)))).containsExactly(blank("tagList"));
            assertThat(errors(failure(create.execute(article("T", Patch.value(List.of(""))), USER_7, PRESENTED)))).containsExactly(blank("tagList"));
            success(create.execute(article("T", Patch.absent()), USER_7, PRESENTED));

            var draft = created.getFirst();
            assertThat(draft.article()
                             .tags()
                             .names()).isEmpty();
            assertThat(draft.createdAt()).isEqualTo(NOW);
            assertThat(draft.slug()
                             .value()).matches("^t-[a-z0-9]{8}$");
        }

        @Test
        void update_emptyArticleIsCantBeEmpty_nullTitleIsBlank() {
            var empty = UpdateArticle.Request.request("s",
                                                  UpdateArticle.Request.Changes.changes(Patch.absent(),
                                                                                    Patch.absent(),
                                                                                    Patch.absent(),
                                                                                    Patch.absent()));
            var nullTitle = UpdateArticle.Request.request("s",
                                                      UpdateArticle.Request.Changes.changes(Patch.nullValue(),
                                                                                        Patch.value(" "),
                                                                                        Patch.absent(),
                                                                                        Patch.nullValue()));

            assertThat(errors(failure(update.execute(empty, USER_7, PRESENTED)))).containsExactly(FieldError.fieldError("article",
                                                                                                           "can't be empty"));
            assertThat(errors(failure(update.execute(nullTitle, USER_7, PRESENTED)))).containsExactly(blank("title"),
                                                                                         blank("description"),
                                                                                         blank("tagList"));
        }
    }

    @Nested
    class Lists {
        private final List<ListArticles.Query> queries = new ArrayList<>();
        private final ListArticles list = ListArticles.listArticles(query -> {
                                                                        queries.add(query);
                                                                        return ArticlesResponse.articlesResponse(List.of(), 0).async();
                                                                    });

        @Test
        void invalidPage_is422_andNoQueryRuns() {
            var cause = failure(list.execute(ListArticles.Request.request(Option.none(),
                                                                      Option.none(),
                                                                      Option.none(),
                                                                      Option.some("0"),
                                                                      Option.none()),
                                             ANONYMOUS));

            assertThat(errors(cause)).containsExactly(FieldError.fieldError("limit", "is invalid"));
            assertThat(queries).isEmpty();
        }

        @Test
        void anonymousViewerIsEmpty_authenticatedViewerIsTheCaller() {
            var request = ListArticles.Request.request(Option.none(), Option.none(), Option.none(), Option.none(), Option.none());
            success(list.execute(request, ANONYMOUS));
            success(list.execute(request, USER_7));

            assertThat(queries).extracting(ListArticles.Query::viewer)
                               .containsExactly(Option.none(), Option.some(success(conduit.domain.UserId.userId(7))));
        }

        @Test
        void feed_requiresAuthentication() {
            var feed = FeedArticles.feedArticles(query -> ArticlesResponse.articlesResponse(List.of(), 0).async());

            assertThat(failure(feed.execute(FeedArticles.Request.request(Option.none(), Option.none()), ANONYMOUS, PRESENTED))).isEqualTo(Unauthorized.TOKEN_MISSING);
        }
    }

    @Nested
    class Social {
        @Test
        void follow_requiresAuthentication_beforeAnyLookup() {
            var lookups = new AtomicInteger();
            var follow = FollowUser.followUser(target -> {
                lookups.incrementAndGet();
                return Promise.success(null);
            });

            assertThat(failure(follow.execute(FollowUser.Request.request("x"), ANONYMOUS, PRESENTED))).isEqualTo(Unauthorized.TOKEN_MISSING);
            assertThat(lookups).hasValue(0);
        }
    }
}
