package org.pragmatica.postgres.message.frontend;

import org.pragmatica.postgres.message.FrontendMessage;
import org.pragmatica.postgres.sasl.SaslPrep;


/// SASL InitialResponse — sends GS2 header + client-first-message-bare to the server.
///
/// GS2 header (RFC 5802 §6) varies by channel-binding state:
/// - `n,,` — client doesn't support channel binding
/// - `y,,` — client supports channel binding but isn't using it (server didn't advertise PLUS)
/// - `p=<cb-name>,,` — client uses channel binding of the named type (e.g. `tls-server-end-point`)
///
/// The server uses this header to detect downgrade attacks: if it advertised PLUS and we
/// reply with `n` or `y`, it rejects the authentication.
public final class SASLInitialResponse implements FrontendMessage {
    private final String saslMechanism;
    private final String channelBindingType;
    private final String userName;
    private final String nonce;
    private final String gs2Header;
    private final String clientFirstMessage;

    private final String clientFirstMessageBare;

    /// @param saslMechanism `SCRAM-SHA-256` or `SCRAM-SHA-256-PLUS`
    /// @param channelBindingType `null` for `n`/`y` headers; otherwise the cb-name (e.g. `tls-server-end-point`)
    /// @param clientSupportsCbind `true` if client supports channel binding; affects `n` vs `y` choice
    /// @param userName user name (Postgres requires empty string here per protocol; see PgProtocolStream)
    /// @param clientNonce client-generated nonce
    public SASLInitialResponse(String saslMechanism,
                               String channelBindingType,
                               boolean clientSupportsCbind,
                               String userName,
                               String clientNonce) {
        this.saslMechanism = saslMechanism;
        this.channelBindingType = channelBindingType;
        this.userName = userName;
        this.nonce = clientNonce;
        String gs2Flag;

        if (channelBindingType != null && !channelBindingType.isBlank()) {
            gs2Flag = "p=" + channelBindingType;
        } else if (clientSupportsCbind) {
            // Client could've used channel binding but server didn't advertise PLUS.
            gs2Flag = "y";
        } else {
            gs2Flag = "n";
        }

        gs2Header = gs2Flag + ",,";
        clientFirstMessageBare = "n=" + SaslPrep.asQueryString(userName) + ",r=" + clientNonce;
        clientFirstMessage = gs2Header + clientFirstMessageBare;
    }

    @SuppressWarnings("unused")
    public String userName() {
        return userName;
    }

    @SuppressWarnings("unused")
    public String channelBindingType() {
        return channelBindingType;
    }

    public String saslMechanism() {
        return saslMechanism;
    }

    @SuppressWarnings("unused")
    public String nonce() {
        return nonce;
    }

    public String gs2Header() {
        return gs2Header;
    }

    public String clientFirstMessageBare() {
        return clientFirstMessageBare;
    }

    public String clientFirstMessage() {
        return clientFirstMessage;
    }
}
