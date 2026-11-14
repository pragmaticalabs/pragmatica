package conduit.usecase.updateuser;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Patch;
import org.pragmatica.lang.Promise;

import conduit.domain.Caller.Authenticated;
import conduit.domain.ConduitError.Conflict;
import conduit.domain.Email;
import conduit.domain.PasswordHash;
import conduit.domain.User;
import conduit.domain.UserId;
import conduit.domain.Username;
import conduit.persistence.UserPersistence;
import conduit.persistence.UserPersistence.TakenRow;

import static conduit.domain.Expect.failure;
import static conduit.domain.Expect.success;
import static org.assertj.core.api.Assertions.assertThat;

/// The write of `PUT /user` can lose a race against a concurrent registration after the conflict probe has passed
/// (B-19): the unique violation must end as the same 409 `RegisterUser` gives its insert, not as a raw persistence error.
class UpdateUserRaceTest {
    private static final User CURRENT = success(User.user(success(UserId.userId(7L)),
                                                          success(Username.username(Option.some("u"))),
                                                          success(Email.email(Option.some("u@x.y"))),
                                                          success(PasswordHash.passwordHash("$2a$10$stub")),
                                                          Option.none(),
                                                          Option.none()));

    /// The probe finds nothing; the write fails with `failure`.
    private static UserPersistence losingTheRace(Cause failure) {
        return new UserPersistence() {
            @Override
            public Promise<Option<User>> findByEmail(Email email) {
                throw new AssertionError("not reached");
            }

            @Override
            public Promise<Option<User>> findById(UserId id) {
                return Promise.success(Option.some(CURRENT));
            }

            @Override
            public Promise<List<TakenRow>> findTaken(Username username, Email email) {
                throw new AssertionError("not reached");
            }

            @Override
            public Promise<List<TakenRow>> findTakenByOthers(Username username, Email email, UserId id) {
                return Promise.success(List.of());
            }

            @Override
            public Promise<User> insert(Username username, Email email, PasswordHash passwordHash) {
                throw new AssertionError("not reached");
            }

            @Override
            public Promise<Option<User>> update(Username username,
                                                Email email,
                                                PasswordHash passwordHash,
                                                Option<String> bio,
                                                Option<String> image,
                                                UserId id) {
                return failure.promise();
            }
        };
    }

    private static UpdateUser.Plan renaming() {
        var request = UpdateUser.Request.request(UpdateUser.Request.Changes.changes(Patch.value("v"),
                                                                                    Patch.absent(),
                                                                                    Patch.absent(),
                                                                                    Patch.absent(),
                                                                                    Patch.absent()));

        return UpdateUser.Plan.plan(Authenticated.authenticated(success(UserId.userId(7L)), "tok"),
                                    success(UpdateUser.ValidChanges.validChanges(request)),
                                    Option.none());
    }

    @Test
    void uniqueViolationOnTheWrite_afterAPassedProbe_is409ForTheConstraintsField() {
        Cause violation = () -> "duplicate key value violates unique constraint \"users_username_key\"";

        var cause = failure(UpdateUser.ApplyChanges.update(losingTheRace(violation), renaming()));

        assertThat(cause).isEqualTo(Conflict.conflict(Conflict.USERNAME_TAKEN, List.of()));
    }

    @Test
    void otherPersistenceFailures_passThroughUnchanged() {
        Cause outage = () -> "connection reset";

        var cause = failure(UpdateUser.ApplyChanges.update(losingTheRace(outage), renaming()));

        assertThat(cause).isSameAs(outage);
    }
}
