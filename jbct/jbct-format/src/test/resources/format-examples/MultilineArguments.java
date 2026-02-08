package format.examples;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

public class MultilineArguments {
    // Short arguments - fits on one line
    Result<String> shortArgs() {
        return Result.all(first(), second());
    }

    // Arguments aligned to opening paren
    Result<String> alignedArgs() {
        return Result.all(Email.email(raw.email()),
                          Password.password(raw.password()),
                          ReferralCode.referralCode(raw.referral()));
    }

    // Many arguments
    Result<String> manyArgs() {
        return Result.all(first(), second(), third(), fourth(), fifth());
    }

    // Nested method calls as arguments
    Result<String> nestedCallArgs() {
        return Result.all(user.map(User::id)
                              .map(String::trim),
                          account.map(Account::status),
                          profile.flatMap(Profile::validate));
    }

    // Long single argument that wraps
    void longSingleArg() {
        log("This is a very long message that needs to be wrapped because it exceeds the maximum line length");
    }

    // Mixed short and long arguments
    Result<String> mixedLengthArgs() {
        return someMethod(shortArg, "a longer argument that takes more space", anotherShortArg);
    }

    // Constructor with many arguments
    ValidRequest createRequest() {
        return new ValidRequest(email, password, referralCode, timestamp, ipAddress);
    }

    // Static factory with many arguments
    Response createResponse() {
        return Response.response(token, expiresAt, refreshToken, permissions);
    }

    // Chained call with multi-line args
    Result<String> chainedWithArgs() {
        return Result.all(first(),
                          second(),
                          third())
                     .flatMap(this::process)
                     .map(String::trim);
    }

    // Lambda as argument
    Result<String> lambdaArg() {
        return input.map(value -> {
            var trimmed = value.trim();
            return trimmed.toUpperCase();
        });
    }

    // Multiple lambdas as arguments
    Result<String> multipleLambdaArgs() {
        return input.fold(cause -> {
                              logError(cause);
                              return defaultValue;
                          },
                          value -> {
                              log(value);
                              return value.toUpperCase();
                          });
    }

    // Stub types and methods for compilation
    interface Request {
        String email();
        String password();
        String referral();
    }

    interface Email {
        static Result<Email> email(String s) {
            return null;
        }
    }

    interface Password {
        static Result<Password> password(String s) {
            return null;
        }
    }

    interface ReferralCode {
        static Result<Option<ReferralCode>> referralCode(String s) {
            return null;
        }
    }

    interface User {
        String id();
    }

    interface Account {
        String status();
    }

    interface Profile {
        Result<Profile> validate();
    }

    interface ValidRequest {
        ValidRequest(Object... args) {}
    }

    interface Response {
        static Response response(Object... args) {
            return null;
        }
    }

    Request raw;
    Result<User> user;
    Result<Account> account;
    Result<Profile> profile;
    Result<String> input;
    Object email, password, referralCode, timestamp, ipAddress;
    Object token, expiresAt, refreshToken, permissions;
    Object shortArg, anotherShortArg;
    String defaultValue;

    Result<String> first() {
        return null;
    }

    Result<String> second() {
        return null;
    }

    Result<String> third() {
        return null;
    }

    Result<String> fourth() {
        return null;
    }

    Result<String> fifth() {
        return null;
    }

    Result<String> someMethod(Object... args) {
        return null;
    }

    Result<String> process(Object tuple) {
        return null;
    }

    void log(String s) {}

    void logError(Object e) {}
}
