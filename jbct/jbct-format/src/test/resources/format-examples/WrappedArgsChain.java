package format.examples;

import org.pragmatica.lang.Result;


public class WrappedArgsChain {
    Result<Response> returnPosition(Result<User> user, Result<Account> account) {
        return Result.all(user.map(User::id),
                          account.map(Account::status))
                     .flatMap(this::createResponse)
                     .map(this::trim);
    }

    Result<Response> lambdaTailPosition(Result<Seed> input) {
        return input.flatMap(seed -> Result.all(user.map(User::id),
                                                account.map(Account::status))
                                           .flatMap(this::createResponse)
                                           .map(this::trim));
    }

    Result<User> user;
    Result<Account> account;

    Result<Response> createResponse(Object t) {
        return null;
    }

    Response trim(Response r) {
        return r;
    }

    interface Seed {}

    interface User {
        String id();
    }

    interface Account {
        String status();
    }

    interface Response {}
}
