package conduit.shared;

import org.pragmatica.lang.Promise;

import conduit.adapter.token.TokenIssuer;
import conduit.domain.Clock;
import conduit.domain.User;
import conduit.view.UserResponse;


/// The response of an operation that opens a session: the user with a freshly issued token (B-07).
public sealed interface Sessions {
    static Promise<UserResponse> respond(TokenIssuer tokenIssuer, Clock clock, User user) {
        return tokenIssuer.issue(user.id(),
                                 clock.now())
                          .map(token -> UserResponse.userResponse(Views.user(user, token)))
                          .async();
    }

    record unused() implements Sessions {}
}
