package conduit.usecase.updateuser;

import java.util.stream.Stream;

import org.pragmatica.aether.http.handler.RequestHeaders;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.resource.db.PgSqlConnector;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Patch;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import conduit.adapter.password.HashPassword;
import conduit.domain.Caller.Authenticated;
import conduit.domain.Caller;
import conduit.domain.ConduitError.RequestInvalid;
import conduit.domain.ConduitError.Unauthorized;
import conduit.domain.Email;
import conduit.domain.Password;
import conduit.domain.PasswordHash;
import conduit.domain.Patches;
import conduit.domain.User;
import conduit.domain.UserId;
import conduit.domain.Username;
import conduit.persistence.Transaction;
import conduit.persistence.UserPersistence;
import conduit.persistence.UserPersistenceFactory;
import conduit.shared.Conflicts;
import conduit.shared.Views;
import conduit.view.UserResponse;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


/// `PUT /user`: change any of the caller's fields; the response carries the presented token (B-07).
///
/// Absent means unchanged; `null` is `can't be blank` for username, email and password, and sets `bio`/`image` to
/// null; `""` is stored as null for `bio`/`image` (B-17). No recognised field is `user: can't be empty` (B-18). A new
/// password is hashed before the transaction opens, so bcrypt never holds a connection. The read, the conflict probe
/// (another user's username or email, B-19) and the update share one transaction.
@Slice
public interface UpdateUser {
    record Request(Changes user) {
        public static Request request(Changes user) {
            return new Request(user);
        }

        public record Changes(Patch<String> username,
                              Patch<String> email,
                              Patch<String> password,
                              Patch<String> bio,
                              Patch<String> image) {
            public static Changes changes(Patch<String> username,
                                          Patch<String> email,
                                          Patch<String> password,
                                          Patch<String> bio,
                                          Patch<String> image) {
                return new Changes(username, email, password, bio, image);
            }
        }

        static final Changes NONE = Changes.changes(Patch.absent(),
                                                    Patch.absent(),
                                                    Patch.absent(),
                                                    Patch.absent(),
                                                    Patch.absent());
    }

    /// A nullable text field's new value: `value` empty means set to null.
    record TextChange(Option<String> value) {
        public static TextChange textChange(Option<String> raw) {
            return new TextChange(raw.filter(Verify.Is::notEmpty));
        }
    }

    record ValidChanges(Option<Username> username,
                        Option<Email> email,
                        Option<Password> password,
                        Option<TextChange> bio,
                        Option<TextChange> image) {
        private static final RequestInvalid EMPTY = RequestInvalid.requestInvalid("user", "can't be empty");

        static Result<ValidChanges> validChanges(Request request) {
            var changes = option(request).flatMap(present -> option(present.user())).or(Request.NONE);

            return nonEmpty(changes).flatMap(ValidChanges::parsed);
        }

        private static Result<Request.Changes> nonEmpty(Request.Changes changes) {
            var anyPresent = Stream.of(changes.username(),
                                       changes.email(),
                                       changes.password(),
                                       changes.bio(),
                                       changes.image())
                                   .anyMatch(patch -> !Patches.isAbsent(patch));

            return anyPresent
                   ? success(changes)
                   : EMPTY.result();
        }

        private static Result<ValidChanges> parsed(Request.Changes changes) {
            return Result.all(Patches.parsePresent(changes.username(),
                                                   Username::username),
                              Patches.parsePresent(changes.email(),
                                                   Email::email),
                              Patches.parsePresent(changes.password(),
                                                   Password::password))
                         .map((username, email, password) -> new ValidChanges(username,
                                                                              email,
                                                                              password,
                                                                              Patches.mapPresent(changes.bio(),
                                                                                                 TextChange::textChange),
                                                                              Patches.mapPresent(changes.image(),
                                                                                                 TextChange::textChange)))
                         .mapError(RequestInvalid::merged);
        }

        boolean hasIdentityChange() {
            return username.isPresent() || email.isPresent();
        }
    }

    /// The caller (401 first) and the validated changes (then 422), in B-51 order.
    record Checked(Authenticated caller, ValidChanges changes) {
        public static Checked checked(Authenticated caller, ValidChanges changes) {
            return new Checked(caller, changes);
        }
    }

    /// Everything the transaction needs: the validated changes and, when the password changes, its new hash.
    record Plan(Authenticated caller, ValidChanges changes, Option<PasswordHash> newHash) {
        public static Plan plan(Authenticated caller, ValidChanges changes, Option<PasswordHash> newHash) {
            return new Plan(caller, changes, newHash);
        }
    }

    /// The stored row with the changes applied, and whether username or email moved (then the conflict probe runs).
    record Merge(User user, boolean hasIdentityChange) {
        static Merge merge(User user, boolean hasIdentityChange) {
            return new Merge(user, hasIdentityChange);
        }
    }

    Promise<UserResponse> execute(Request request, SecurityContext security, RequestHeaders headers);

    @FunctionalInterface
    interface ApplyChanges {
        Promise<User> update(Plan plan);

        static ApplyChanges applyChanges(@PgSql PgSqlConnector db) {
            return plan -> Transaction.inTransaction(db, tx -> update(UserPersistenceFactory.userPersistence(tx), plan));
        }

        static Promise<User> update(UserPersistence users, Plan plan) {
            var id = plan.caller().id();

            return users.findById(id)
                        .flatMap(found -> found.async(Unauthorized.TOKEN_INVALID))
                        .flatMap(current -> mergeChecked(users, plan, current))
                        .flatMap(user -> write(users, user))
                        .flatMap(updated -> updated.async(Unauthorized.TOKEN_INVALID));
        }

        private static Promise<User> mergeChecked(UserPersistence users, Plan plan, User current) {
            return merged(current, plan).async()
                         .flatMap(merge -> ensureAvailable(users,
                                                           plan.caller().id(),
                                                           merge));
        }

        /// The write is where a concurrent registration can still win the race after the probe passed: its unique
        /// violation becomes the same 409 `RegisterUser` gives its insert (B-19).
        private static Promise<Option<User>> write(UserPersistence users, User user) {
            return users.update(user.username(),
                                user.email(),
                                user.passwordHash(),
                                user.bio(),
                                user.image(),
                                user.id())
                        .mapError(Conflicts::fromUniqueViolation);
        }

        private static Promise<User> ensureAvailable(UserPersistence users, UserId id, Merge merge) {
            return merge.hasIdentityChange()
                   ? probe(users, id, merge.user())
                   : Promise.success(merge.user());
        }

        private static Promise<User> probe(UserPersistence users, UserId id, User user) {
            return users.findTakenByOthers(user.username(),
                                           user.email(),
                                           id)
                        .flatMap(taken -> Conflicts.verdict(taken,
                                                            user.username(),
                                                            user.email())
                                                   .async())
                        .map(_ -> user);
        }

        private static Result<Merge> merged(User current, Plan plan) {
            var changes = plan.changes();
            var updated = User.user(current.id(),
                                    changes.username().or(current.username()),
                                    changes.email().or(current.email()),
                                    plan.newHash().or(current.passwordHash()),
                                    changes.bio().map(TextChange::value).or(current.bio()),
                                    changes.image().map(TextChange::value).or(current.image()));

            return updated.map(user -> Merge.merge(user, changes.hasIdentityChange()));
        }
    }

    static UpdateUser updateUser(HashPassword hashPassword, ApplyChanges applyChanges) {
        return (request, security, headers) -> Caller.required(security, headers)
                                                     .flatMap(caller -> check(request, caller))
                                                     .async()
                                                     .flatMap(checked -> plan(hashPassword, checked))
                                                     .flatMap(plan -> respond(applyChanges, plan));
    }

    private static Result<Checked> check(Request request, Authenticated caller) {
        return ValidChanges.validChanges(request).map(changes -> Checked.checked(caller, changes));
    }

    private static Promise<UserResponse> respond(ApplyChanges applyChanges, Plan plan) {
        return applyChanges.update(plan)
                           .map(user -> UserResponse.userResponse(Views.user(user,
                                                                             plan.caller().token())));
    }

    private static Promise<Plan> plan(HashPassword hashPassword, Checked checked) {
        return checked.changes()
                      .password()
                      .map(password -> hashPassword.hash(password)
                                                   .map(Option::some))
                      .or(Promise.success(none()))
                      .map(newHash -> Plan.plan(checked.caller(),
                                                checked.changes(),
                                                newHash));
    }
}
