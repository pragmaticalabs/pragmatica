package format.examples;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;


public class ChainAlignment {
    // Even a 2-method chain breaks — Sequencer-as-steps is intentional vertical cost.
    Result<String> shortChain(Result<String> input) {
        return input.map(String::trim)
                    .map(String::toUpperCase);
    }

    Result<String> mediumChain(Result<String> input) {
        return input.map(String::trim)
                    .map(String::toUpperCase)
                    .filter(s -> !s.isEmpty());
    }

    Result<String> chainFromMethodCall(Request request) {
        return ValidRequest.validRequest(request)
                           .map(ValidRequest::email)
                           .map(Email::value);
    }

    Result<String> chainFromStaticMethod(String value) {
        return Result.success(value)
                     .map(String::trim)
                     .filter(s -> !s.isEmpty());
    }

    Promise<Response> sequencerChain(Request request) {
        return ValidRequest.validRequest(request)
                           .async()
                           .flatMap(checkCredentials::apply)
                           .flatMap(checkAccountStatus::apply)
                           .flatMap(generateToken::apply);
    }

    Result<String> mixedChain(Result<String> input) {
        return input.map(String::trim)
                    .flatMap(this::validate)
                    .onSuccess(this::log)
                    .onFailure(this::logError)
                    .map(String::toUpperCase);
    }

    // Args wrap (each on own line aligned to first) but the outer chain stays inline-then-break;
    // .flatMap aligns to first `.` of chain (which is Result.all's `.all`).
    Result<Response> nestedChains(Result<User> user, Result<Account> account) {
        return Result.all(user.map(User::id),
                          account.map(Account::status))
                     .flatMap(this::createResponse);
    }

    Result<ValidRequest> forkJoinChain(Request raw) {
        return Result.all(Email.email(raw.email()),
                          Password.password(raw.password()),
                          ReferralCode.referralCode(raw.referral()))
                     .flatMap(ValidRequest::validRequest);
    }

    Result<String> brokenChain(Result<String> input) {
        return input.map(String::trim)
                    .flatMap(this::expensiveValidation)
                    .map(String::toUpperCase);
    }

    // Nested-lambda chains stay inline — they are not flat chains; each .flatMap is a
    // continuation INSIDE a lambda body, not a sibling step at the outer chain level.
    Result<String> deepNestedChain(Result<A> a, Result<B> b, Result<C> c) {
        return a.flatMap(va -> b.flatMap(vb -> c.map(vc -> combine(va, vb, vc))));
    }

    interface Request {
        String email();
        String password();
        String referral();
    }

    interface Response {}

    interface ValidRequest {
        static Result<ValidRequest> validRequest(Request r) {
            return null;
        }

        Email email();

        static Result<ValidRequest> validRequest(Email e, Password p, Option<ReferralCode> r) {
            return null;
        }
    }

    interface Email {
        String value();

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

    interface CheckCredentials {
        Result<Credentials> apply(ValidRequest vr);
    }

    interface CheckAccountStatus {
        Result<Account> apply(Credentials c);
    }

    interface GenerateToken {
        Promise<Response> apply(Account a);
    }

    interface Credentials {}

    interface A {}

    interface B {}

    interface C {}

    CheckCredentials checkCredentials;
    CheckAccountStatus checkAccountStatus;
    GenerateToken generateToken;

    Result<String> validate(String s) {
        return null;
    }

    void log(String s) {}

    void logError(Object e) {}

    Result<Response> createResponse(Object tuple) {
        return null;
    }

    Result<String> expensiveValidation(String s) {
        return null;
    }

    String combine(A a, B b, C c) {
        return null;
    }
}
