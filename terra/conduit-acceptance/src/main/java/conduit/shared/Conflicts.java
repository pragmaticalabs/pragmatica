package conduit.shared;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import conduit.domain.ConduitError.Conflict;
import conduit.domain.ConduitError.FieldError;
import conduit.domain.Email;
import conduit.domain.Username;
import conduit.persistence.UserPersistence.TakenRow;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// B-19: username and email conflicts, every conflicting field reported.
public sealed interface Conflicts {
    String USERNAME_CONSTRAINT = "users_username_key";
    String EMAIL_CONSTRAINT = "users_email_key";

    /// The read-before-write verdict: fields whose value another row already holds.
    static Result<Unit> verdict(List<TakenRow> taken, Username username, Email email) {
        var errors = Stream.of(clash(taken, TakenRow::username, username, Conflict.USERNAME_TAKEN),
                               clash(taken, TakenRow::email, email, Conflict.EMAIL_TAKEN))
                           .flatMap(Option::stream)
                           .toList();

        return errors.isEmpty()
               ? Result.unitResult()
               : Conflict.conflict(errors.getFirst(),
                                   errors.subList(1,
                                                  errors.size()))
                         .result();
    }

    private static <T> Option<FieldError> clash(List<TakenRow> taken,
                                                Fn1<T, TakenRow> column,
                                                T value,
                                                FieldError error) {
        return taken.stream()
                    .anyMatch(row -> column.apply(row)
                                           .equals(value))
               ? some(error)
               : none();
    }

    /// A concurrent insert can still win the race after the read; its unique violation becomes the same 409 for the
    /// constraint's field. pg-async keeps only SQLSTATE and message text (SqlError.ServerResponse), so the constraint
    /// is recognised by name in the message.
    static Cause fromUniqueViolation(Cause cause) {
        return field(cause.message()).<Cause> map(field -> Conflict.conflict(field,
                                                                             List.of()))
                    .or(cause);
    }

    private static Option<FieldError> field(String message) {
        return Option.from(Stream.of(Map.entry(USERNAME_CONSTRAINT, Conflict.USERNAME_TAKEN),
                                     Map.entry(EMAIL_CONSTRAINT, Conflict.EMAIL_TAKEN))
                                 .filter(entry -> message.contains(entry.getKey()))
                                 .map(Map.Entry::getValue)
                                 .findFirst());
    }

    record unused() implements Conflicts {}
}
